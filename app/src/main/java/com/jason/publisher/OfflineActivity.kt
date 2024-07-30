package com.jason.publisher

import NetworkReceiver
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.jason.publisher.Helper.createBusStopSymbol
import com.jason.publisher.databinding.ActivityOfflineBinding
import com.jason.publisher.model.AttributesData
import com.jason.publisher.model.Bus
import com.jason.publisher.model.BusItem
import com.jason.publisher.model.Message
import com.jason.publisher.services.ApiService
import com.jason.publisher.services.ApiServiceBuilder
import com.jason.publisher.services.ClientAttributesResponse
import com.jason.publisher.services.MqttManager
import com.jason.publisher.services.NotificationManager
import com.jason.publisher.services.OpenRouteService
import com.jason.publisher.services.SharedPrefMananger
import com.jason.publisher.services.SoundManager
import com.jason.publisher.utils.BusStopProximityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapController
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.OverlayItem
import org.osmdroid.views.overlay.Polyline
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.lang.Math.atan2
import java.lang.Math.cos
import java.lang.Math.sin
import kotlin.random.Random

/**
 * OfflineActivity class responsible for managing the application in offline mode.
 */
class OfflineActivity : AppCompatActivity(), NetworkReceiver.NetworkListener {

    private lateinit var binding: ActivityOfflineBinding
    private lateinit var mqttManager: MqttManager
    private lateinit var sharedPrefMananger: SharedPrefMananger
    private lateinit var notificationManager: NotificationManager
    private lateinit var soundManager: SoundManager
    private lateinit var mapController: MapController
    private lateinit var networkReceiver: NetworkReceiver
    private lateinit var networkStatusIndicator: View
    private lateinit var reconnectProgressBar: ProgressBar
    private lateinit var connectionStatusTextView: TextView
    private lateinit var attemptingToConnectTextView: TextView
    private lateinit var busMarker: Marker
    private val handler = Handler(Looper.getMainLooper())
    private var dotCount = 0

    private lateinit var bearingTextView: TextView
    private lateinit var latitudeTextView: TextView
    private lateinit var longitudeTextView: TextView
    private lateinit var directionTextView: TextView
    private lateinit var speedTextView: TextView
    private lateinit var busNameTextView: TextView
    private lateinit var showDepartureTimeTextView: TextView
    private lateinit var departureTimeTextView: TextView
    private lateinit var etaToNextBStopTextView: TextView
    private lateinit var aidTextView: TextView

    private var lastLatitude = 0.0
    private var lastLongitude = 0.0
    private var latitude = 0.0
    private var longitude = 0.0
    private var bearing = 0.0F
    private var bearingCustomer = 0.0F
    private var speed = 0.0F
    private var direction = "North"
    private var busConfig = ""
    private var busname = ""
    private var listConfig: List<BusItem>? = OfflineData.getConfig()
    private var aid = ""
    private var etaToNextBStop = ""

    private var routeIndex = 0 // Initialize index at the start
    private var busRoute = listOf<GeoPoint>()
    private var busStop = listOf<GeoPoint>()
    private var calculatedBearings = calculateBearings()

    private var lastMessage = ""
    private var totalMessage = 0

    private var token = ""
    private var hoursDeparture = 0
    private var minutesDeparture = 0
    private var showDepartureTime = "Yes"
    private var departureTime = "00:00:00"
    private var isFirstTime = false

    private lateinit var timer: CountDownTimer
    private var apiService = ApiServiceBuilder.buildService(ApiService::class.java)
    private var clientKeys = "latitude,longitude,bearing,bearingCustomer,speed,direction"
    private var arrBusData: List<BusItem> = emptyList()
    private var markerBus = HashMap<String, Marker>()
    private var routeDirection = "forward"

    private var closestBusStopToPubDevice = "none"

    /**
     * Initializes the activity, sets up sensor and service managers, loads configuration, subscribes to admin messages,
     * and initializes UI components.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfflineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // To be implement!!
        // get client attribute
        // filter aid
        // if aid not match, add bus name, accesstoken, aid (automate)
        // post to client attributes
        // use posted data, to connect mqtt manager

        // Initialize UI components
        initializeUIComponents()

        // Load configuration
        Configuration.getInstance().load(this, getSharedPreferences(getString(R.string.app_name), MODE_PRIVATE))

        // Initialize managers
        initializeManagers()

        getAccessToken()
        mqttManager = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID, username = token)
        sharedPrefMananger = SharedPrefMananger(this)
        notificationManager = NotificationManager(this)
        soundManager = SoundManager(this)
        getDefaultConfigValue()

        getMessageCount()
        requestAdminMessage()
        connectAndSubscribe()

        // Initialize busMarker
        busMarker = Marker(binding.map)
        mapController = binding.map.controller as MapController

        // Set up the map initially
        binding.map.apply {
            setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMultiTouchControls(true)
        }
        setupInitialMap()

        binding.chatButton.setOnClickListener {
            showChatDialog()
        }

        // Set up spinner
        val items = arrayOf("Yes", "No")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        // Set click listener for pop-up button
        binding.popUpButton.setOnClickListener {
            showPopUpDialog()
        }

        // Register NetworkReceiver
        networkReceiver = NetworkReceiver(this)
        val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(networkReceiver, intentFilter)
    }

    /**
     * Function to setup initial map position and zoom level
     */
    private fun setupInitialMap() {
        if (busRoute.isNotEmpty()) {
            mapController.setCenter(GeoPoint(busRoute[0].latitude, busRoute[0].longitude))
        } else {
            mapController.setCenter(GeoPoint(-36.78012, 174.99216))
        }
        mapController.setZoom(18.0)
    }

    /**
     * Initialize UI components and assign them to the corresponding views.
     */
    private fun initializeUIComponents() {
        bearingTextView = findViewById(R.id.bearingTextView)
        latitudeTextView = findViewById(R.id.latitudeTextView)
        longitudeTextView = findViewById(R.id.longitudeTextView)
        directionTextView = findViewById(R.id.directionTextView)
        speedTextView = findViewById(R.id.speedTextView)
        busNameTextView = findViewById(R.id.busNameTextView)
        showDepartureTimeTextView = findViewById(R.id.showDepartureTimeTextView)
        departureTimeTextView = findViewById(R.id.departureTimeTextView)
        etaToNextBStopTextView = findViewById(R.id.etaToNextBStopTextView)
        networkStatusIndicator = findViewById(R.id.networkStatusIndicator)
        reconnectProgressBar = findViewById(R.id.reconnectProgressBar)
        connectionStatusTextView = findViewById(R.id.connectionStatusTextView)
        attemptingToConnectTextView = findViewById(R.id.attemptingToConnectTextView)
        aidTextView = findViewById(R.id.aidTextView)
    }

    /**
     * Initialize various managers used in the application.
     */
    private fun initializeManagers() {
        sharedPrefMananger = SharedPrefMananger(this)
        notificationManager = NotificationManager(this)
        soundManager = SoundManager(this)
    }

    /**
     * Called when the network becomes available.
     * Reconnects the MQTT manager and updates the network status indicator.
     */
    override fun onNetworkAvailable() {
        mqttManager.reconnect()
        runOnUiThread {
            networkStatusIndicator.setBackgroundResource(R.drawable.circle_shape_green)
            reconnectProgressBar.visibility = View.GONE
            attemptingToConnectTextView.visibility = View.GONE
            connectionStatusTextView.text = "Connected"
            handler.removeCallbacks(fiveDotRunnable)
        }
    }

    /**
     * Called when the network becomes unavailable.
     * Shows the reconnect spinner and updates the network status indicator.
     */
    override fun onNetworkUnavailable() {
        runOnUiThread {
            networkStatusIndicator.setBackgroundResource(R.drawable.circle_shape_red)
            reconnectProgressBar.visibility = View.VISIBLE
            attemptingToConnectTextView.visibility = View.VISIBLE
            connectionStatusTextView.text = "Disconnected"
            startFiveDotAnimation()
        }
    }

    /**
     * Starts the five dot animation.
     */
    private fun startFiveDotAnimation() {
        dotCount = 0
        handler.post(fiveDotRunnable)
    }

    /**
     * Runnable to animate the five dots in the "Attempting to connect..." text.
     */
    private val fiveDotRunnable = object : Runnable {
        override fun run() {
            dotCount = (dotCount + 1) % 6
            val dots = ".".repeat(dotCount)
            attemptingToConnectTextView.text = "Attempting to connect$dots"
            handler.postDelayed(this, 500)
        }
    }

    /**
     * Retrieves the access token for the current device's Android ID from the configuration list.
     */
    @SuppressLint("HardwareIds")
    private fun getAccessToken() {
//        Toast.makeText(this, "listConfig getAccessToken: ${listConfig}", Toast.LENGTH_SHORT).show()
        aid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        for (config in listConfig!!) {
            if (config.aid == aid) {
                token = config.accessToken
                break
            }
        }
        //if (token.isEmpty())
    }

    /**
     * Shows a dialog for sending a message to the operator.
     */
    private fun showChatDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Send Message to Operator")
        val input = EditText(this)
        builder.setView(input)
        builder.setPositiveButton("Send") { dI, _ ->
            sendMessageToOperator(dI, input.text.toString())
        }
        builder.setNegativeButton("Cancel") { dialogInterface, _ -> dialogInterface.cancel() }
        builder.show()
    }

    /**
     * Sends a message to the operator via API service.
     * @param dI The dialog interface.
     * @param message The message to send.
     */
    private fun sendMessageToOperator(dI: DialogInterface?, message: String) {
        val contentMessage = mapOf("operatorMessage" to message)
        val call = apiService.postAttributes(
            ApiService.BASE_URL + mqttManager.getUsername() + "/attributes",
            "application/json",
            contentMessage
        )
        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Toast.makeText(
                        this@OfflineActivity,
                        "Message has been sent",
                        Toast.LENGTH_SHORT
                    ).show()
                    dI?.cancel()
                } else {
                    Toast.makeText(
                        this@OfflineActivity,
                        "There is something wrong, try again!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Toast.makeText(
                    this@OfflineActivity,
                    "There is something wrong, try again!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    /**
     * Shows a pop-up dialog for setting departure time.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun showPopUpDialog() {
        isFirstTime = true
        val dialogView = layoutInflater.inflate(R.layout.popup_dialog, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerShowTime)
        val hoursPicker = dialogView.findViewById<NumberPicker>(R.id.hoursPicker)
        val minutesPicker = dialogView.findViewById<NumberPicker>(R.id.minutesPicker)

        val items = arrayOf("Yes", "No")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        hoursPicker.minValue = 0
        hoursPicker.maxValue = 1
        minutesPicker.minValue = 0
        minutesPicker.maxValue = 59

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton("OK") { dialog, which ->
                hoursDeparture = hoursPicker.value
                minutesDeparture = minutesPicker.value
                showDepartureTime = spinner.selectedItem.toString()
                mapViewSetup()
                startLocationUpdate()
                publishShowDepartureTime()
                publishDepartureTime()
                publishRouteDirection()
                sendRequestAttributes()
                startCountdown()
            }
            .setNegativeButton("Cancel") { dialog, which ->
                // Handle Cancel button click
            }
            .show()
    }

    /**
     * Requests admin messages periodically.
     */
    private fun requestAdminMessage() {
        val jsonObject = JSONObject()
        jsonObject.put("sharedKeys", "message,busRoute,busStop,config")
        val jsonString = jsonObject.toString()
        val handler = Handler(Looper.getMainLooper())
        mqttManager.publish(PUB_MSG_TOPIC, jsonString)
        handler.post(object : Runnable {
            override fun run() {
                mqttManager.publish(PUB_MSG_TOPIC, jsonString)
                handler.postDelayed(this, REQUEST_PERIODIC_TIME)
            }
        })
    }

    /**
     * Connects to the MQTT broker and subscribes to the shared data topic upon successful connection.
     */
    private fun connectAndSubscribe() {
        mqttManager.connect { isConnected ->
            if (isConnected) {
                Log.d("OfflineActivity", "Connected to MQTT broker")
                subscribeSharedData {
                }
            } else {
                Log.e("OfflineActivity", "Failed to connect to MQTT broker")
                Toast.makeText(this, "Failed to connect to MQTT broker", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Subscribes to shared data from the server.
     */
    private fun subscribeSharedData(onDataFetched: () -> Unit) {
        mqttManager.subscribe(SUB_MSG_TOPIC) { message ->
            runOnUiThread {
                val gson = Gson()
                val data = gson.fromJson(message, Bus::class.java)
                listConfig = data.shared?.config?.busConfig
                arrBusData = listConfig ?:
                return@runOnUiThread

//                Toast.makeText(this, "bus config subscribeSharedData(): ${arrBusData} ", Toast.LENGTH_SHORT).show()

                if (arrBusData.isNullOrEmpty()) {
                    Toast.makeText(this, "No bus information available.", Toast.LENGTH_SHORT).show()
                    clearBusData()
                    return@runOnUiThread
                }

                val tabletAid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                aid = tabletAid
                if (arrBusData.none { it.aid == tabletAid }) {
                    Toast.makeText(this, "AID does not match.", Toast.LENGTH_SHORT).show()
                    clearBusData()
                    finish()
                    System.exit(0)
                }

                if (isFirstTime) {
                    generatePolyline() // Updated to call generatePolyline without arguments
                    isFirstTime = false
                }

                val msg = data.shared?.message
                if (lastMessage != msg && msg != null) {
                    saveNewMessage(msg)
                    showNotification(msg)
                }

                // Initialize busRoute and busStop using data from shared
                busRoute = data.shared?.busRoute1?.mapNotNull {
//                    Toast.makeText(this, "busRoute subscribeSharedData: ${busRoute}", Toast.LENGTH_SHORT).show()
                    it.latitude?.let { lat ->
                        it.longitude?.let { lon ->
                            GeoPoint(lat, lon)
                        }
                    }
                } ?: listOf()
                busStop = data.shared?.busStop1?.mapNotNull {
                    it.latitude?.let { lat ->
                        it.longitude?.let { lon ->
                            GeoPoint(lat, lon)
                        }
                    }
                } ?: listOf()
                calculatedBearings = calculateBearings()

                if (busRoute.isNotEmpty()) {
                    onDataFetched()
                    binding.map.invalidate() // Ensure the map is rendered correctly
                } else {
                    Toast.makeText(this, "No route data available.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Clears any existing bus data from the map and other UI elements.
     */
    private fun clearBusData() {
        binding.map.overlays.clear()
        binding.map.invalidate()
        markerBus.clear()
    }

    /**
     * Displays a notification for a new message from the admin.
     * @param message The message content.
     */
    private fun showNotification(message: String) {
        notificationManager.showNotification(
            channelId = "channel2",
            // use a timestamp as notificationId
            // so that when there is a new notification
            // it creates a new bubble, not overlapping each other
            notificationId = System.currentTimeMillis().toInt(),
            title = "Message from Admin",
            message = message,
            true
        )

        // plays a sound when a new notification comes in
        // to change the sound, please add a new sound to the assets folder
        // and adjust the name in the SOUND_FILE_NAME variable
        soundManager.playSound(SOUND_FILE_NAME)
    }

    /**
     * Saves a new message into shared preferences and updates message count.
     * @param message The message content.
     */
    private fun saveNewMessage(message: String) {
        sharedPrefMananger.saveString(LAST_MSG_KEY, message)

        // assign the last message to lastMessage variable
        // as a comparison when there is a new notification
        lastMessage = sharedPrefMananger.getString(LAST_MSG_KEY, "").toString()

        // check whether there is a message list,
        // if there is, the message list is taken and then added with a new message,
        // because sharedpreference can only store list data type as a single list,
        // it does not have a method of adding one by one
        val messageList = ArrayList<Message>()
        val newMessage = Message(message, false, System.currentTimeMillis())
        val currentMessage = sharedPrefMananger.getMessageList(MSG_KEY)
        if (currentMessage.isNotEmpty()) {
            currentMessage.forEach { msg ->
                messageList.add(msg)
            }
        }
        messageList.add(newMessage)
        sharedPrefMananger.saveMessageList(MSG_KEY, messageList)
        getMessageCount() // calculate total message
    }

    /**
     * Starts updating the location periodically using pre-defined routes from OfflineData.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startLocationUpdate() {
        if (busRoute.isEmpty()) {
            Log.e("OfflineActivity", "Bus route is empty, cannot update location.")
            return
        }

        // Set the initial position to the first point in the busRoute
        latitude = busRoute[0].latitude
        longitude = busRoute[0].longitude
        lastLatitude = latitude
        lastLongitude = longitude

        // Perform route simulation by using pre-defined routes from OfflineData.
        // The location updates and movements are simulated based on these pre-loaded routes.
        generatePolyline()
        generateBusStop()

        val handler = Handler(Looper.getMainLooper())
        val updateRunnable = object : Runnable {
            override fun run() {
                if (routeIndex < busRoute.size) {
                    val currentLatitude = busRoute[routeIndex].latitude
                    val currentLongitude = busRoute[routeIndex].longitude

                    if (lastLatitude != 0.0 && lastLongitude != 0.0) {
                        bearing = calculateBearing(lastLatitude, lastLongitude, currentLatitude, currentLongitude)
                        direction = Helper.bearingToDirection(bearing)
                    }

                    latitude = currentLatitude
                    longitude = currentLongitude
                    if (routeIndex != 0) {
                        speed = Random.nextFloat() * 10f + 50f
                    }

                    lastLatitude = currentLatitude
                    lastLongitude = currentLongitude
                    routeIndex = (routeIndex + 1) % busRoute.size

                    updateMarkerPosition()

                    handler.postDelayed(this, PUBLISH_POSITION_TIME)
                } else {
                    Log.e("OfflineActivity", "routeIndex $routeIndex out of bounds for busRoute size ${busRoute.size}")
                }
            }
        }
        handler.post(updateRunnable)
    }

    /**
     * Calculates the bearing between two geographical points.
     *
     * @param lat1 The latitude of the first point.
     * @param lon1 The longitude of the first point.
     * @param lat2 The latitude of the second point.
     * @param lon2 The longitude of the second point.
     * @return The bearing between the two points in degrees.
     */
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val deltaLon = lon2 - lon1
        val deltaLat = lat2 - lat1

        val angleRad = atan2(deltaLat, deltaLon)
        var angleDeg = Math.toDegrees(angleRad)

        // Adjusting the angle to ensure 0 degrees points to the right
        angleDeg = (angleDeg + 360) % 360

        return angleDeg.toFloat()
    }

    /**
     * Calculates bearings for each consecutive pair of points in the bus route.
     *
     * @return List of Float representing the bearings.
     */
    private fun calculateBearings(): List<Float> {
        val bearings = mutableListOf<Float>()
        if (busRoute.size > 1) {
            for (i in 0 until busRoute.size - 1) {
                val bearing = calculateBearing(
                    busRoute[i].latitude,
                    busRoute[i].longitude,
                    busRoute[i + 1].latitude,
                    busRoute[i + 1].longitude
                )
                bearings.add(bearing)
            }
        }
        return bearings
    }

    /**
     * Generates markers for bus stops on the map.
     */
    private fun generateBusStop() {
        val overlayItems = ArrayList<OverlayItem>()
        busStop.forEachIndexed { index, geoPoint ->
            val busStopNumber = index + 1
            val busStopSymbol = Helper.createBusStopSymbol(applicationContext, busStopNumber, busStop.size)
            val marker = OverlayItem(
                "Bus Stop $busStopNumber",
                "Description",
                geoPoint
            )
            marker.setMarker(busStopSymbol)
            overlayItems.add(marker)
        }
        val overlayItem = ItemizedIconOverlay(
            overlayItems,
            object : ItemizedIconOverlay.OnItemGestureListener<OverlayItem> {
                override fun onItemSingleTapUp(index: Int, item: OverlayItem?): Boolean {
                    return true
                }

                override fun onItemLongPress(index: Int, item: OverlayItem?): Boolean {
                    return false
                }
            },
            applicationContext
        )
        binding.map.overlays.add(overlayItem)
    }

    /**
     * Generates polylines for bus route segments on the map.
     */
    private fun generatePolyline() {
        val routes = mutableListOf<GeoPoint>()
        for (route in busRoute) {
            routes.add(GeoPoint(route.latitude, route.longitude))
        }

        val polyline = Polyline()
        polyline.setPoints(routes)
        polyline.outlinePaint.color = Color.BLUE
        polyline.outlinePaint.strokeWidth = 5f

        binding.map.overlays.add(polyline)
        binding.map.invalidate()
    }

    /**
     * Sets up the map view by removing existing markers, invalidating the map, updating bus marker icon and position,
     * and resetting the route index.
     */
    private fun mapViewSetup() {
        // Clear all overlays first
        binding.map.overlays.clear()
        binding.map.invalidate()

        // Initialize and set up the red arrow for the current device
        busMarker = Marker(binding.map).apply {
            icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_bus_arrow, null)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            // Ensure the marker is always on top
            setPanToView(true)
        }

        // Generate the polyline
        generatePolyline()

        // Add the red arrow for the current device
        if (!binding.map.overlays.contains(busMarker)) {
            binding.map.overlays.add(busMarker)
        }
        updateMarkerPosition()
        routeIndex = 0
    }

    /**
     * Updates the bearing text view with the current bearing.
     * Also updates other telemetry text views with the current data.
     */
    private fun updateTextViews() {
        val bearingString = bearing.toString()
        bearingTextView.text = "Current Bearing: $bearingString degrees"
        latitudeTextView.text = "Latitude: $latitude"
        longitudeTextView.text = "Longitude: $longitude"
        directionTextView.text = "Direction: $direction"
        speedTextView.text = "Speed: $speed"
        busNameTextView.text = "Bus Name: $busname"
        showDepartureTimeTextView.text = "Show Departure Time: $showDepartureTime"
        departureTimeTextView.text = "Departure Time: $departureTime"
        etaToNextBStopTextView.text = "etaToNextBStop: $etaToNextBStop"
        aidTextView.text = "AID: $aid"
    }

    /**
     * Updates the position of the marker on the map and publishes telemetry data.
     */
    private fun updateMarkerPosition() {
        val newLatLng = GeoPoint(latitude, longitude)
        val newBearing = bearing

        if (busMarker.position != newLatLng || busMarker.rotation != newBearing) {
            busMarker.position = newLatLng
            busMarker.rotation = newBearing
            bearingCustomer = newBearing
        }

        if (!binding.map.overlays.contains(busMarker)) {
            binding.map.overlays.add(busMarker)
        }

        binding.map.invalidate()

        // Publish telemetry data only if the latitude and longitude are not initial values
        if (latitude != 0.0 && longitude != 0.0) {
            publishTelemetryData()
        }

        updateClientAttributes()
        updateTextViews()
    }

    /**
     * Updates the client attributes by posting the current location, bearing, speed, and direction data to the server.
     */
    private fun updateClientAttributes() {
        val url = ApiService.BASE_URL + "$token/attributes"
        val attributesData = AttributesData(latitude, longitude, bearing, null,speed, direction)
        val call = apiService.postAttributes(
            url = url,
            "application/json",
            requestBody = attributesData
        )
        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
//                Log.d("Client Attributes", response.message().toString())
//                Log.d("Client Attributes", response.code().toString())
//                Log.d("Client Attributes", response.errorBody().toString())
                if (response.isSuccessful) {
//                    Log.d("Client Attributes", "Successfull")
                } else {
//                    Log.d("Client Attributes", "Fail")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
//                Log.d("Client Attributes", t.message.toString())
            }
        })
    }

    /**
     * Starts a countdown timer for the departure time.
     */
    private fun startCountdown() {
        val totalMinutes = hoursDeparture * 60 + minutesDeparture
        val totalMillis = totalMinutes * 60 * 1000 // Convert total minutes to milliseconds

        timer = object : CountDownTimer(totalMillis.toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val hours = millisUntilFinished / (1000 * 60 * 60)
                val minutes = (millisUntilFinished / (1000 * 60)) % 60
                val seconds = (millisUntilFinished / 1000) % 60
                departureTime = String.format("%02d:%02d:%02d", hours, minutes, seconds)
            }

            override fun onFinish() {
                // This method will be called when the timer finishes
            }
        }
        timer.start()
    }

    /**
     * Publishes telemetry data including latitude, longitude, bearing, speed, direction, and other relevant information.
     */
    private fun publishTelemetryData() {
        val jsonObject = JSONObject()
        busname = findBusNameByAid(aid) ?: ""
        jsonObject.put("latitude", latitude)
        jsonObject.put("longitude", longitude)
        jsonObject.put("bearing", bearing)
        jsonObject.put("direction", direction)
        jsonObject.put("speed", speed)
        jsonObject.put("showDepartureTime", showDepartureTime)
        jsonObject.put("departureTime", departureTime)
        jsonObject.put("bus", busname)
        jsonObject.put("aid", aid)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val nextBusStopInSequence =
                    BusStopProximityManager.getNextBusStopInSequence(closestBusStopToPubDevice)
//                        Toast.makeText(this@OfflineActivity, "nextBusStopInSequence publishTelemetryData: ${nextBusStopInSequence}", Toast.LENGTH_SHORT).show()
                if (nextBusStopInSequence != null) {
                    etaToNextBStop = OpenRouteService.getEstimateTimeFromPointToPoint(
                        latitude, longitude,
                        nextBusStopInSequence.latitude, nextBusStopInSequence.longitude
                    )
                    jsonObject.put("ETAtoNextBStop", etaToNextBStop)
                }

                val jsonString = jsonObject.toString()
                mqttManager.publish(MainActivity.PUB_POS_TOPIC, jsonString, 1)
                notificationManager.showNotification(
                    channelId = "channel1",
                    notificationId = 1,
                    title = "Connected",
                    message = "Lat: $latitude, Long: $longitude, Direction: $direction",
                    false
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Finds the bus name by its associated ID.
     *
     * @param aid the ID of the bus.
     * @return the name of the bus or null if not found.
     */
    fun findBusNameByAid(aid: String?): String? {
//        Toast.makeText(this, "listConfig findBusNameByAid: ${listConfig}", Toast.LENGTH_SHORT).show()
        if (aid == null) {
            Log.e("findBusNameByAid", "AID is null")
            return null
        }
        val busItem = listConfig!!.find { it.aid == aid }

        return busItem?.bus ?: run {
            Log.e("findBusNameByAid", "No bus found with AID: $aid")
            null
        }
    }

    /**
     * Convert polar coordinate to bearing.
     *
     * @param lat1 latitude of the first point.
     * @param lon1 longitude of the first point.
     * @param lat2 latitude of the second point.
     * @param lon2 longitude of the second point.
     * @return the bearing from the first point to the second point.
     */
    private fun PolarCoordinateToBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        // Convert latitude and longitude from degrees to radians
        val lat1Rad = Math.toRadians(lat1)
        val lon1Rad = Math.toRadians(lon1)
        val lat2Rad = Math.toRadians(lat2)
        val lon2Rad = Math.toRadians(lon2)

        // Calculate the differences in longitudes and latitudes
        val deltaLon = lon2Rad - lon1Rad
        val deltaLat = lat2Rad - lat1Rad

        // Calculate the bearing using atan2 function
        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)
        var brng = atan2(y, x)

        // Convert the bearing from radians to degrees
        brng = Math.toDegrees(brng)

        // Normalize the bearing to be in the range [0, 360)
        brng = (brng + 360) % 360

        return brng
    }

    /**
     * Publishes the current status of routeDirection.
     */
    private fun publishRouteDirection() {
        val jsonObject = JSONObject()
        jsonObject.put("routeDirection", routeDirection)
        val jsonString = jsonObject.toString()
        mqttManager.publish(MainActivity.PUB_POS_TOPIC, jsonString, 1)
    }

    /**
     * Publishes the current status of whether to show the departure time.
     */
    private fun publishShowDepartureTime() {
        val jsonObject = JSONObject()
        jsonObject.put("showDepartureTime", showDepartureTime)
        val jsonString = jsonObject.toString()
        mqttManager.publish(MainActivity.PUB_POS_TOPIC, jsonString, 1)
    }

    /**
     * Publishes the current departure time.
     */
    private fun publishDepartureTime() {
        val jsonObject = JSONObject()
        jsonObject.put("departureTime", departureTime)
        val jsonString = jsonObject.toString()
        mqttManager.publish(MainActivity.PUB_POS_TOPIC, jsonString, 1)
    }

    /**
     * Posts attributes data to the API service.
     * @param apiService The API service instance.
     * @param accessToken The access token for authentication.
     * @param attributesData The data to be posted as attributes.
     */
    private fun postAttributes(apiService: ApiService, accessToken: String, attributesData: AttributesData) {
        val call = apiService.postAttributes(
            "${ApiService.BASE_URL}$accessToken/attributes",
            "application/json",
            attributesData
        )
        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
//                    Log.d("Request successful","${response.body()}")
                } else {
//                    Log.d("Request failed","${response.errorBody()}")
                    Toast.makeText(this@OfflineActivity, "Request failed: ${response.errorBody()?.string()}", Toast.LENGTH_SHORT).show()
//                    Log.d("Request failed code","${response.code()}")
//                    Log.d("Request failed message","${response.message()}")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Toast.makeText(this@OfflineActivity, "Request failed: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /**
     * Retrieves default configuration values for the activity, such as latitude, longitude, bearing, and more.
     */
    // because this is offline mode,
    // the default value required is only the new message comparator
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getDefaultConfigValue() {
        latitude = intent.getDoubleExtra("lat", 0.0)
        longitude = intent.getDoubleExtra("lng", 0.0)
        bearing = intent.getFloatExtra("ber", 0.0F)
        bearingCustomer = intent.getFloatExtra("berCus", 0.0F)
        speed = intent.getFloatExtra("spe", 0.0F)
        direction = intent.getStringExtra("dir").toString()
        lastMessage = sharedPrefMananger.getString(LAST_MSG_KEY, "").toString()

        aid = intent.getStringExtra(Constant.aidKey)?.let { it } ?: ""

        busConfig = intent.getStringExtra(Constant.deviceNameKey).toString()

        // Filter out the current device's configuration
        arrBusData = arrBusData.filter { it.aid != aid }
    }

    /**
     * Sends data attributes to the server.
     */
    private fun sendRequestAttributes() {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                for (bus in arrBusData) {
                    getAttributes(apiService, bus.accessToken, clientKeys)
                }
                handler.postDelayed(this, 3000)
            }
        }, 0)
    }

    /**
     * Retrieves attributes data for each bus from the server.
     *
     * @param apiService The API service instance.
     * @param token The access token for authentication.
     * @param clientKeys The keys to request attributes for.
     */
    private fun getAttributes(apiService: ApiService, token: String, clientKeys: String) {
        val call = apiService.getAttributes(
            "${ApiService.BASE_URL}$token/attributes",
            "application/json",
            clientKeys
        )
        call.enqueue(object : Callback<ClientAttributesResponse> {
            override fun onResponse(call: Call<ClientAttributesResponse>, response: Response<ClientAttributesResponse>) {
                if (response.isSuccessful) {
                    val clientAttributes = response.body()?.client
                    if (clientAttributes != null) {
                        val lat = clientAttributes.latitude
                        val lon = clientAttributes.longitude
                        val ber = clientAttributes.bearing

                        if (lat != null && lon != null && ber != null) {
                            if (token != this@OfflineActivity.token) {  // Ensure this is not the current device
                                var marker = markerBus[token]
                                if (marker == null) {
                                    marker = Marker(binding.map).apply {
                                        icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_bus_arrow2, null)
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    }
                                    markerBus[token] = marker
                                }
                                marker.position = GeoPoint(lat, lon)
                                marker.rotation = ber
                                if (!binding.map.overlays.contains(marker)) {
                                    binding.map.overlays.add(marker)
                                }
                                binding.map.invalidate()
                            }
                        } else {
                            Log.e("OfflineActivity", "Received null values for lat, lon, or bearing")
                        }
                    } else {
                        Log.e("OfflineActivity", "Client attributes are null")
                    }
                } else {
                    Log.e("OfflineActivity", "Failed to retrieve attributes: ${response.message()}")
                }
            }

            override fun onFailure(call: Call<ClientAttributesResponse>, t: Throwable) {
                Log.e("OfflineActivity", "Error fetching attributes: ${t.message}")
            }
        })
    }

    /**
     * Retrieves the total message count from shared preferences.
     */
    private fun getMessageCount() {
        // sets the value of the textview
        // based on the length of the stored arraylist
        totalMessage = sharedPrefMananger.getMessageList(MSG_KEY).size
    }

    /**
     * Stops sound, disconnects MQTT manager, and cleans up resources when the activity is destroyed.
     */
    override fun onDestroy() {
        soundManager.stopSound()
        mqttManager.disconnect()
        unregisterReceiver(networkReceiver)
        handler.removeCallbacks(fiveDotRunnable)
        super.onDestroy()
    }

    /**
     * Companion object holding constant values used throughout the activity.
     * Includes server URI, client ID, MQTT topics, and other constants.
     */
    companion object {
        const val SERVER_URI = "tcp://43.226.218.97:1883"
        const val CLIENT_ID = "jasonAndroidClientId"
        const val PUB_POS_TOPIC = "v1/devices/me/telemetry"
        private const val SUB_MSG_TOPIC = "v1/devices/me/attributes/response/+"
        private const val PUB_MSG_TOPIC = "v1/devices/me/attributes/request/1"
        private const val REQUEST_PERIODIC_TIME = 5000L
        private const val PUBLISH_POSITION_TIME = 5000L
        private const val LAST_MSG_KEY = "lastMessageKey"
        private const val MSG_KEY = "messageKey"
        private const val SOUND_FILE_NAME = "notif.wav"
    }
}
