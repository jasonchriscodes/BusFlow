package com.jason.publisher

import NetworkReceiver
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.jason.publisher.databinding.ActivityMainBinding
import com.jason.publisher.model.AttributesData
import com.jason.publisher.model.Bus
import com.jason.publisher.model.BusItem
import com.jason.publisher.model.BusRoute
import com.jason.publisher.model.BusStop
import com.jason.publisher.model.Message
import com.jason.publisher.services.ApiService
import com.jason.publisher.services.ApiServiceBuilder
import com.jason.publisher.services.ClientAttributesResponse
import com.jason.publisher.services.LocationManager
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
import java.lang.Math.atan2
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import com.jason.publisher.model.Coordinate
import com.jason.publisher.model.findNearestCoordinate
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mqttManagerConfig: MqttManager
    private lateinit var mqttManager: MqttManager
    private lateinit var locationManager: LocationManager
    private lateinit var sharedPrefMananger: SharedPrefMananger
    private lateinit var notificationManager: NotificationManager
    private lateinit var soundManager: SoundManager
    private lateinit var mapController: MapController
    private lateinit var networkReceiver: NetworkReceiver
    private lateinit var networkStatusIndicator: View
    private lateinit var reconnectProgressBar: ProgressBar
    private lateinit var connectionStatusTextView: TextView
    private lateinit var attemptingToConnectTextView: TextView
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
    private lateinit var closestBusStopToPubDeviceTextView: TextView
//    private lateinit var busDirectionTitleTextView: TextView
    private lateinit var busDirectionIcon: ImageView
    private lateinit var busTelemetryTitleTextView: TextView
//    private lateinit var upcomingRoadTitleTextView: TextView
    private lateinit var upcomingRoadTextView: TextView
    private lateinit var currentRoadTextView: TextView

    private var lastLatitude = 0.0
    private var lastLongitude = 0.0
    private var latitude = 0.0
    private var longitude = 0.0
    private var bearing = 0.0F
    private var speed = 0.0F
    private var direction = "North"
    private var busConfig = ""
    private var busname = ""
    private var config: List<BusItem>? = emptyList()
    private var aid = ""
    private var etaToNextBStop = ""
    private var closestBusStopToPubDevice = "none"
    private var busDirectionTitle = "Bus Direction"
    private var busTelemetryTitle = "Bus Telemetry Data"
//    private var bupcomingRoadTitle = "Upcoming Road"
    private var upcomingRoadText = ""
    private var route: List<BusRoute> = emptyList()
    private var currentRoadName: String? = null
    private var passesRoadName: String? = null
    private var tempRoadName: String? = null
    private var upcomingRoadName: String? = null
    private var previousRoadName: String? = null // Add this variable to track previous road name
    private var isUpcomingRoadLocked = false // Flag to lock upcoming road updates
    private var liveRouteName: String? = null

    private var lastMessage = ""
    private var totalMessage = 0

    private var token = ""
    private var tokenConfigData = "oRSsbeuqDMSckyckcMyE"
    private var apiService = ApiServiceBuilder.buildService(ApiService::class.java)
    private var markerBus = HashMap<String, Marker>()
    private var arrBusData: List<BusItem> = emptyList()
    private var clientKeys = "latitude,longitude,bearing,speed,direction"

    private var hoursDeparture = 0
    private var minutesDeparture = 0
    private var showDepartureTime = "Yes"
    private var departureTime = "00:00:00"
    private var isFirstTime = false
    private lateinit var timer: CountDownTimer
    private var firstTime = true
    private val client = OkHttpClient()

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Retrieve AID from intent
        aid = intent.getStringExtra("AID") ?: "Unknown"
        Log.d("AID in MainActivity", aid)

        // Initialize mqttManager before using it
        mqttManagerConfig = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID, username = tokenConfigData)

        // Fetch the latest version from the server and update the versionTextView
        fetchLatestVersion()

        // Initialize UI components
        initializeUIComponents()

        // Load configuration
        Configuration.getInstance().load(this, getSharedPreferences(getString(R.string.app_name), MODE_PRIVATE))

        // Initialize managers
        initializeManagers()

        /**
         * Initialize current location and setup map.
         * The map setup occurs only after the current location is fetched.
         */
        locationManager.getCurrentLocation(object : LocationListener {
            override fun onLocationUpdate(location: Location) {
                latitude = location.latitude
                longitude = location.longitude
                Log.d("Latitude", latitude.toString())
                Log.d("Longitude", longitude.toString())
                // Update UI components with the current location
                latitudeTextView.text = "Latitude: $latitude"
                longitudeTextView.text = "Longitude: $longitude"

                // Set up the map view with the current location
                mapViewSetup(latitude, longitude)
            }
        })

        // Fetch and initialize config
        fetchConfig { success ->
            if (success) {
                // Get access token for MQTT connection
                getAccessToken()
                Log.d("Token Main", token)
                mqttManager = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID, username = token)
                getDefaultConfigValue()
                requestAdminMessage()

                // Connect and subscribe to MQTT after config is fetched
                connectAndSubscribe()
                networkReceiver = NetworkReceiver(object : NetworkReceiver.NetworkListener {
                    /**
                     * Called when the network becomes available.
                     * Reconnects the MQTT manager and updates the network status indicator.
                     */
                    override fun onNetworkAvailable() {
                        mqttManager.reconnect()
//                        mqttManagerConfig.reconnect()
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
                })
                val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
                registerReceiver(networkReceiver, intentFilter)
            } else {
                // Handle config initialization failure
                Toast.makeText(this, "Failed to initialize config. No bus information available.", Toast.LENGTH_SHORT).show()
                clearBusData() // Clear any existing bus data
            }
        }

        Log.d("Oncreate config", config.toString())
        getMessageCount()
        startLocationUpdate()
        sendRequestAttributes()

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
//        networkReceiver = NetworkReceiver(this)
        val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
//        registerReceiver(networkReceiver, intentFilter)

        // Set up feedback button
        binding.feedbackButton.setOnClickListener {
            val intent = Intent(this, FeedbackActivity::class.java)
            intent.putExtra("TOKEN", token)  // Pass the token to FeedbackActivity
            startActivity(intent)
        }

        // Set click listerner for logout button
        binding.logoutButton.setOnClickListener {
            handleLogout()
        }
    }

    /**
     * Handles the logout action
     */
    private fun handleLogout(){
        val intent = Intent(this, SplashScreen::class.java)
        startActivity(intent)
        finish()
    }

    /**
     * Initialize UI components and assign them to the corresponding views.
     */
    private fun initializeUIComponents() {
        bearingTextView = binding.bearingTextView
        latitudeTextView = binding.latitudeTextView
        longitudeTextView = binding.longitudeTextView
        directionTextView = binding.directionTextView
        speedTextView = binding.speedTextView
        busNameTextView = binding.busNameTextView
        showDepartureTimeTextView = binding.showDepartureTimeTextView
        departureTimeTextView = binding.departureTimeTextView
        etaToNextBStopTextView = binding.etaToNextBStopTextView
        networkStatusIndicator = binding.networkStatusIndicator
        reconnectProgressBar = binding.reconnectProgressBar
        connectionStatusTextView = binding.connectionStatusTextView
        attemptingToConnectTextView = binding.attemptingToConnectTextView
        aidTextView = binding.aidTextView
        closestBusStopToPubDeviceTextView = binding.closestBusStopToPubDeviceTextView
//        busDirectionTitleTextView = binding.busDirectionTitleTextView
        busTelemetryTitleTextView = binding.busTelemetryTitleTextView
        currentRoadTextView = binding.currentRoadTextView
//        upcomingRoadTitleTextView = binding.upcomingRoadTitleTextView
        upcomingRoadTextView = binding.upcomingRoadTextView
        busDirectionIcon = binding.busDirectionIcon
    }

    /**
     * Initialize various managers used in the application.
     */
    private fun initializeManagers() {
        locationManager = LocationManager(this)
        sharedPrefMananger = SharedPrefMananger(this)
        notificationManager = NotificationManager(this)
        soundManager = SoundManager(this)
    }

    /**
     * Fetches the latest version of the app from the server and updates the versionTextView
     * in the MainActivity with the retrieved version.
     */
    private fun fetchLatestVersion() {

        val requestCurrent = Request.Builder()
            .url("http://43.226.218.98:5000/api/current-version/$aid")
            .build()

        // Request latest version available on the server
        val requestLatest = Request.Builder()
            .url("http://43.226.218.98:5000/api/latest-version")
            .build()

        Log.d("aidMain", aid)

        client.newCall(requestCurrent).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("MainActivity", "Failed to fetch current version", e)
                runOnUiThread {
                    findViewById<TextView>(R.id.versionTextView).text = "Version unknown"
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    val json = JSONObject(responseData!!)
                    val currentVersion = json.getString("version")

                    // After fetching the current version, request the latest version
                    client.newCall(requestLatest).enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: IOException) {
                            Log.e("MainActivity", "Failed to fetch latest version", e)
                            runOnUiThread {
                                findViewById<TextView>(R.id.versionTextView).text = "Version unknown"
                            }
                        }

                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            if (response.isSuccessful) {
                                val responseData = response.body?.string()
                                val json = JSONObject(responseData!!)
                                val latestVersion = json.getString("version")

                                Log.d("currentVersionMain", currentVersion)
                                Log.d("latestVersionMain", latestVersion)

                                runOnUiThread {
                                    if (currentVersion == latestVersion) {
                                        findViewById<TextView>(R.id.versionTextView).text = "Version $currentVersion (Up to date)"
                                    } else {
                                        findViewById<TextView>(R.id.versionTextView).text = "Version $currentVersion (Update available: $latestVersion)"
                                    }
                                }
                            } else {
                                runOnUiThread {
                                    findViewById<TextView>(R.id.versionTextView).text = "Version unknown"
                                }
                            }
                        }
                    })
                } else {
                    runOnUiThread {
                        findViewById<TextView>(R.id.versionTextView).text = "Version unknown"
                    }
                }
            }
        })
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
     * Runnable to animate the dots in the "Attempting to connect..." text.
     * This Runnable updates the text with an increasing number of dots every 500 milliseconds,
     * looping through 1 to 4 dots.
     */
    private val dotRunnable = object : Runnable {
        override fun run() {
            dotCount = (dotCount + 1) % 4
            val dots = ".".repeat(dotCount)
            attemptingToConnectTextView.text = "Attempting to connect$dots"
            handler.postDelayed(this, 500)
        }
    }

    /**
     * Fetches the configuration data and initializes the config variable.
     * Calls the provided callback with true if successful, false otherwise.
     */
    private fun fetchConfig(callback: (Boolean) -> Unit) {
        Log.d("MainActivity fetchConfig", "Fetching config...")
        mqttManagerConfig.fetchSharedAttributes(tokenConfigData) { listConfig ->
            if (listConfig.isNotEmpty()) {
                config = listConfig
                Log.d("MainActivity fetchConfig", "Config received: $config")
//                runOnUiThread {
//                    Toast.makeText(this, "Config initialized successfully", Toast.LENGTH_SHORT).show()
//                }
                callback(true)
            } else {
                config = emptyList()
                Log.e("MainActivity fetchConfig", "Failed to initialize config. No bus information available.")
//                runOnUiThread {
//                    Toast.makeText(this, "Failed to initialize config. No bus information available.", Toast.LENGTH_SHORT).show()
//                }
                callback(false)
            }
        }
    }

    /**
     * Retrieves the access token for the current device's Android ID from the configuration list.
     */
    @SuppressLint("HardwareIds")
    private fun getAccessToken() {
        val listConfig = config
        Log.d("getAccessToken config", config.toString())
        for (configItem in listConfig.orEmpty()) {
            if (configItem.aid == aid) {
                token = configItem.accessToken
                break
            }
        }
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

        // Options for the Spinner
        val items = arrayOf("Yes", "No")

        // ArrayAdapter for the Spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        // Set ArrayAdapter to the Spinner
        spinner.adapter = adapter

        // Initialize the NumberPickers
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
//                Log.d("departureTimeDialog", showDepartureTime)
                publishShowDepartureTime()
                publishDepartureTime()
                // Start the countdown timer
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
        jsonObject.put("sharedKeys","message,busRoute,busStop,config")
        val jsonString = jsonObject.toString()
        val handler = Handler(Looper.getMainLooper())
        mqttManager.publish(PUB_MSG_TOPIC, jsonString)
//        mqttManagerConfig.publish(PUB_MSG_TOPIC, jsonString)
        handler.post(object : Runnable {
            override fun run() {
                mqttManager.publish(PUB_MSG_TOPIC, jsonString)
//                mqttManagerConfig.publish(PUB_MSG_TOPIC, jsonString)
                handler.postDelayed(this, REQUEST_PERIODIC_TIME)
            }
        })
    }

    /**
     * Subscribes to shared data from the server.
     * Checks if the configuration is null or empty, and if the `aid` from ThingsBoard matches the tablet's `aid`.
     * If either check fails, it returns and runs on the UI thread.
     */
    private fun subscribeSharedData() {
        mqttManager.subscribe(SUB_MSG_TOPIC) { message ->
            runOnUiThread {
                val gson = Gson()
                val data = gson.fromJson(message, Bus::class.java)

                config = data.shared?.config?.busConfig
                arrBusData = config.orEmpty() // Ensure arrBusData is assigned
                Log.d("subscribeSharedData config", config.toString())
                Log.d("subscribeSharedData arrBusData", arrBusData.toString())

                if (config.isNullOrEmpty()) {
                    Toast.makeText(this, "No bus information available.", Toast.LENGTH_SHORT).show()
                    clearBusData()
                    return@runOnUiThread
                }

                val matchingAid = config!!.any { it.aid == aid }

                if (!matchingAid) {
                    Toast.makeText(this, "AID does not match.", Toast.LENGTH_SHORT).show()
                    clearBusData()
                    return@runOnUiThread
                }

                if (firstTime) {
                    route = data.shared?.busRoute1 ?: emptyList()  // Initialize the route variable
                    Log.d("routeMainActivity", route.toString())
                    val stops = data.shared?.busStop1
                    if (route.isNotEmpty() && stops != null) {
                        generatePolyline(route, stops)
                        firstTime = false
                    }
                }

                val msg = data.shared?.message
                if (lastMessage != msg && msg != null) {
                    saveNewMessage(msg)
                    showNotification(msg)
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
     * Connects to the MQTT broker and subscribes to the shared data topic upon successful connection.
     */
    private fun connectAndSubscribe() {
        mqttManager.connect { isConnected ->
            if (isConnected) {
                Log.d("MainActivity", "Connected to MQTT broker")
                subscribeSharedData()
            } else {
                Log.e("MainActivity", "Failed to connect to MQTT broker")
//                Toast.makeText(this, "Failed to connect to MQTT broker", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Displays a notification for a new message from the admin.
     * @param message The message content.
     */
    private fun showNotification(message: String) {
        notificationManager.showNotification(
            channelId = "channel2",
            notificationId = System.currentTimeMillis().toInt(),
            title = "Message from Admin",
            message = message,
            true
        )
        soundManager.playSound(SOUND_FILE_NAME)
    }

    /**
     * Saves a new message into shared preferences and updates message count.
     * @param message The message content.
     */
    private fun saveNewMessage(message: String) {
        sharedPrefMananger.saveString(LAST_MSG_KEY, message)
        lastMessage = sharedPrefMananger.getString(LAST_MSG_KEY, "").toString()

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
        getMessageCount()
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
            sendMessageToOperator(dI,input.text.toString())
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
            ApiService.BASE_URL+mqttManager.getUsername()+"/attributes",
            "application/json",
            contentMessage
        )
        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Toast.makeText(
                        this@MainActivity,
                        "Message has been sent",
                        Toast.LENGTH_SHORT
                    ).show()
                    dI?.cancel()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "There is something wrong, try again!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            override fun onFailure(call: Call<Void>, t: Throwable) {
                Toast.makeText(
                    this@MainActivity,
                    "There is something wrong, try again!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    /**
     * Starts location updates, calculates bearing and direction, and identifies the nearest route coordinate.
     * If a nearest coordinate is found, it checks the current road name and finds the upcoming road if it changes.
     */
    private fun startLocationUpdate() {
        locationManager.startLocationUpdates(object : LocationListener {
            override fun onLocationUpdate(location: Location) {
                val currentLatitude = location.latitude
                val currentLongitude = location.longitude

                // Calculate bearing and update variables as before
                if (lastLatitude != 0.0 && lastLongitude != 0.0) {
                    bearing = calculateBearing(lastLatitude, lastLongitude, currentLatitude, currentLongitude)
                    direction = Helper.bearingToDirection(bearing)
                }

                latitude = currentLatitude
                longitude = currentLongitude
                speed = (location.speed * 3.6).toFloat()
                lastLatitude = currentLatitude
                lastLongitude = currentLongitude

                val currentLocation = Coordinate(latitude, longitude)
                val nearestCoordinate = findNearestCoordinate(currentLocation, route.map { Coordinate(it.latitude!!, it.longitude!!) })

                runOnUiThread {
                    if (nearestCoordinate != null) {
                        Log.d("startLocationUpdate", "nearest coordinate: $nearestCoordinate")
                        val nearestIndex = route.indexOfFirst { it.latitude == nearestCoordinate.latitude && it.longitude == nearestCoordinate.longitude }

                        if (nearestIndex != -1 && nearestIndex < route.size - 1) {
                            val nextCoordinate = route[nearestIndex + 1]
                            val nextCoordinateAsCoordinate = Coordinate(nextCoordinate.latitude!!, nextCoordinate.longitude!!)

                            // Use a coroutine to fetch the road name and find the upcoming road
                            CoroutineScope(Dispatchers.IO).launch {
                                val fetchedCurrentRoadName = checkRouteName(nearestCoordinate, nextCoordinateAsCoordinate)
                                withContext(Dispatchers.Main) {
                                    currentRoadName = fetchedCurrentRoadName // Update current road name
                                    currentRoadTextView.text = "Current Road: $currentRoadName" // Update UI with current road name
                                    findUpcomingRoad(nearestCoordinate, fetchedCurrentRoadName)
                                }
                            }
                        }
                    } else {
                        Log.d("startLocationUpdate", "No nearest coordinate found")
                    }
                }
            }
        })
    }

    /**
     * Finds the upcoming road by checking for a change in road name along the route, starting
     * from the nearest coordinate to the current position.
     *
     * @param nearestCoordinate The coordinate closest to the current position to start the search.
     * @param currentRoadName The name of the current road to compare against.
     */
    private suspend fun findUpcomingRoad(nearestCoordinate: Coordinate, currentRoadName: String) {
        // Find the index of the nearest coordinate in the route
        val nearestIndex = route.indexOfFirst {
            it.latitude == nearestCoordinate.latitude && it.longitude == nearestCoordinate.longitude
        }

        // If the nearest coordinate is not found or is the last in the route, log an error and exit
        if (nearestIndex == -1 || nearestIndex >= route.size - 1) {
            Log.e("findUpcomingRoad", "Nearest coordinate not found or is the last in the route")
            return
        }

        // Get the next coordinate on the route to determine the road segment for API checking
        val endCoordinate = route.getOrNull(nearestIndex + 1)?.let {
            Coordinate(it.latitude!!, it.longitude!!)
        } ?: nearestCoordinate

        // Set liveRouteName only if the currentRoadName is valid and not "-"
        if (currentRoadName != "-") {
            liveRouteName = currentRoadName
            Log.d("findUpcomingRoad", "Live route name set to: $liveRouteName")
        } else {
            Log.d("findUpcomingRoad", "Skipping update of liveRouteName due to invalid road name")
        }

        // Unlock if current road has reached the upcoming road
        if (liveRouteName == upcomingRoadName && isUpcomingRoadLocked) {
            isUpcomingRoadLocked = false
            Log.d("findUpcomingRoad", "Unlocked upcoming road update as bus reached $liveRouteName")
        }

        // If road updates are still locked, prevent further updates
        if (isUpcomingRoadLocked) {
            Log.d("findUpcomingRoad", "Upcoming road update is locked to: $upcomingRoadText")
            return
        }

        // Check if we need to update the upcoming road based on tempRoadName and current road name
        if (upcomingRoadName != tempRoadName || (upcomingRoadName == null && tempRoadName == null)) {
            CoroutineScope(Dispatchers.Default).launch {
                Log.d("findUpcomingRoad", "Checking upcoming road starting from index: $nearestIndex")

                for (i in nearestIndex until route.size - 1) {
                    val start = Coordinate(route[i].latitude!!, route[i].longitude!!)
                    val end = Coordinate(route[i + 1].latitude!!, route[i + 1].longitude!!)
                    tempRoadName = getRoadNameFromApi(start, end)

                    Log.d("findUpcomingRoad", "Checking segment $i: Start = $start, End = $end, Temp Road Name = $tempRoadName")

                    // Update the upcoming road if a valid name is found that differs from the current road
                    if (tempRoadName != null && tempRoadName != currentRoadName &&
                        tempRoadName != upcomingRoadText && tempRoadName != "-" && tempRoadName!!.isNotEmpty()) {

                        withContext(Dispatchers.Main) {
                            if (upcomingRoadName != tempRoadName) {
                                upcomingRoadName = tempRoadName
                                upcomingRoadText = upcomingRoadName!!
                                upcomingRoadTextView.text = "Upcoming Road: $upcomingRoadText"
                                Log.d("findUpcomingRoad", "Updated upcoming road to: $upcomingRoadText")

                                // Lock updates to upcoming road once it’s set to a new road
                                isUpcomingRoadLocked = true
                                Log.d("findUpcomingRoad", "Locked upcoming road update to: $upcomingRoadText")
                            }
                        }
                        break
                    }
                }
            }
        } else {
            // If current road changes, reset upcoming and temporary road names
            if (previousRoadName != currentRoadName) {
                Log.d("findUpcomingRoad", "Detected change in currentRoadName from $previousRoadName to $currentRoadName")
                previousRoadName = currentRoadName
            }

            synchronized(this@MainActivity) {
                upcomingRoadName = null
                tempRoadName = null
            }
            Log.d("findUpcomingRoad", "Reset upcomingRoadName and tempRoadName due to currentRoadName change")
        }
    }

    private suspend fun getRoadNameFromApi(start: Coordinate, end: Coordinate): String? {
        val url = "http://43.226.218.99:8080/ors/v2/directions/driving-car?start=${start.longitude},${start.latitude}&end=${end.longitude},${end.latitude}&format=geojson"
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseData = response.body?.string()
                if (responseData != null) {
                    val jsonResponse = JSONObject(responseData)
                    return extractRoadName(jsonResponse)
                }
            } else {
                Log.e("getRoadNameFromApi", "Failed to fetch road name: ${response.message}")
            }
            null
        } catch (e: Exception) {
            Log.e("getRoadNameFromApi", "Error fetching road name: ${e.message}")
            null
        }
    }

    /**
     * Fetches the road name between two coordinates via API call and returns it as a string.
     *
     * @param start Starting coordinate.
     * @param end Ending coordinate.
     * @return The name of the current road, or "-" if unavailable.
     */
    private suspend fun checkRouteName(start: Coordinate, end: Coordinate): String {
        val url = "http://43.226.218.99:8080/ors/v2/directions/driving-car?start=${start.longitude},${start.latitude}&end=${end.longitude},${end.latitude}&format=geojson"

        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseData = response.body?.string()
                if (responseData != null) {
                    val jsonResponse = JSONObject(responseData)
                    currentRoadName = extractRoadName(jsonResponse) ?: "-"
                    passesRoadName = currentRoadName

                    Log.d("checkRouteName", "Current road name: $currentRoadName")
                    currentRoadName!!
                } else {
                    "-"
                }
            } else {
                Log.e("checkRouteName", "Failed to fetch route name: ${response.message}")
                "-"
            }
        } catch (e: Exception) {
            Log.e("checkRouteName", "Error fetching route name: ${e.message}")
            "-"
        }
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
     * Updates the other data text view with the current other data telemetry.
     */
    private fun updateTextViews() {
        bearingTextView.text = "Current Bearing: $bearing degrees"
        latitudeTextView.text = "Latitude: $latitude"
        longitudeTextView.text = "Longitude: $longitude"
        directionTextView.text = "Direction: $direction"
        speedTextView.text = "Speed: $speed"
        busNameTextView.text = "Bus Name: $busname"
        showDepartureTimeTextView.text = "Show Departure Time: $showDepartureTime"
        departureTimeTextView.text = "Departure Time: $departureTime"
        etaToNextBStopTextView.text = "etaToNextBStop: $etaToNextBStop"
        aidTextView.text = "AID: $aid"
        closestBusStopToPubDeviceTextView.text = "closestBusStopToPubDevice: $closestBusStopToPubDevice"
//        busDirectionTitleTextView.text = "$busDirectionTitle"
        busTelemetryTitleTextView.text = "$busTelemetryTitle"
//        upcomingRoadTitleTextView.text = "$bupcomingRoadTitle"
        upcomingRoadTextView.text = "Upcoming Road: $upcomingRoadText"
        currentRoadTextView.text = "Current Road: $currentRoadName"
    }

    /**
     * Extracts the road name from the API JSON response. Returns "-" if no name is found.
     *
     * @param json GeoJSON data.
     * @return Road name or "-".
     */
    private fun extractRoadName(json: JSONObject): String {
        try {
            Log.d("extractRoadName", "Extracting road name from JSON response.")

            val features = json.optJSONArray("features")
            if (features != null && features.length() > 0) {
                val properties = features.getJSONObject(0).optJSONObject("properties")
                val segments = properties?.optJSONArray("segments")
                if (segments != null && segments.length() > 0) {
                    val steps = segments.getJSONObject(0).optJSONArray("steps")
                    if (steps != null && steps.length() > 0) {
                        val name = steps.getJSONObject(0).optString("name", "-")
                        Log.d("extractRoadName", "Road name found: $name")
                        return name
                    } else {
                        Log.d("extractRoadName", "No steps found in segments.")
                    }
                } else {
                    Log.d("extractRoadName", "No segments found in properties.")
                }
            } else {
                Log.d("extractRoadName", "No features found in JSON.")
            }
        } catch (e: Exception) {
            Log.e("extractRoadName", "Error extracting road name: ${e.message}")
        }
        return "-"
    }

    /**
     * Sets up the map view and initializes markers and polylines with the provided coordinates.
     *
     * @param lat The latitude for the initial map center.
     * @param lon The longitude for the initial map center.
     */
    private fun mapViewSetup(lat: Double, lon: Double) {
        val center = GeoPoint(lat, lon)

        val marker = Marker(binding.map)
        marker.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_bus_symbol, null) // Use custom drawable
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        mapController = binding.map.controller as MapController
        mapController.setCenter(center)
        mapController.setZoom(18.0)

        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            mapCenter
            setMultiTouchControls(true)
            getLocalVisibleRect(Rect())
        }
        updateMarkerPosition(marker)
    }

    /**
     * Generates polylines and markers for the bus route and stops.
     *
     * @param busRoute The bus route data in the new format.
     * @param busStops The bus stop data in the new format.
     */
    private fun generatePolyline(busRoute: List<BusRoute>, busStops: List<BusStop>) {
        val routes = mutableListOf<GeoPoint>()
        for (route in busRoute) {
            routes.add(GeoPoint(route.latitude!!, route.longitude!!))
        }
        Log.d("Route Polylines",routes.toString())
        Log.d("Check Length Route",routes.size.toString())

        val overlayItems = ArrayList<OverlayItem>()
        busStops.forEachIndexed { index, geoPoint ->
            val busStopNumber = index + 1
            val busStopSymbol = Helper.createBusStopSymbol(applicationContext, busStopNumber, busStops.size)
            val marker = OverlayItem(
                "Bus Stop $busStopNumber",
                "Description",
                GeoPoint(geoPoint.latitude!!, geoPoint.longitude!!)
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

        val polyline = Polyline()
        polyline.setPoints(routes)
        polyline.outlinePaint.color = Color.BLUE
        polyline.outlinePaint.strokeWidth = 5f

        binding.map.overlays.add(polyline)
        binding.map.invalidate()
    }

    /**
     * Updates the position of the marker on the map and publishes telemetry data.
     *
     * @param marker The marker to be updated.
     */
    private fun updateMarkerPosition(marker: Marker) {
        val handler = Handler(Looper.getMainLooper())
        val updateRunnable = object : Runnable {
            override fun run() {
                marker.position = GeoPoint(latitude, longitude)
                marker.rotation = bearing // The bearing now correctly matches the polar coordinate system
                binding.map.overlays.add(marker)
                binding.map.invalidate()
                publishTelemetryData()
                updateClientAttributes()

                // Update UI elements
                runOnUiThread {
                    updateTextViews()
                }

                handler.postDelayed(this, PUBLISH_POSITION_TIME)

                // To reset the map center position based on the location of the publisher device.
                val newCenterLocationBasedOnPubDevice = GeoPoint(latitude, longitude)
                mapController.animateTo(newCenterLocationBasedOnPubDevice)
            }
        }
        handler.post(updateRunnable)
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
                // This method will be called every second
                val hours = millisUntilFinished / (1000 * 60 * 60)
                val minutes = (millisUntilFinished / (1000 * 60)) % 60
                val seconds = (millisUntilFinished / 1000) % 60
                departureTime = String.format("%02d:%02d:%02d", hours, minutes, seconds)
//                Log.d("departureTime", departureTime)
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
        busname = Utils.findBusNameByAid(aid) ?: ""
        jsonObject.put("latitude", latitude)
        jsonObject.put("longitude", longitude)
        jsonObject.put("bearing", bearing)
        jsonObject.put("direction", direction)
        jsonObject.put("speed", speed)
        jsonObject.put("showDepartureTime", showDepartureTime)
        jsonObject.put("departureTime", departureTime)
        jsonObject.put("bus", busname)
        jsonObject.put("aid", aid)
        Log.d("publishTelemetryData latitude", latitude.toString())
        Log.d("publishTelemetryData longitude", longitude.toString())
//        Log.d("BusConfig", busConfig)
//        Log.d("aid", aid)

        // To publish the closest bus stop to the publisher device.
        closestBusStopToPubDevice = BusStopProximityManager.getTheClosestBusStopToPubDevice(
            latitude,
            longitude,
            closestBusStopToPubDevice
        );
        jsonObject.put("closestBusStopToPubDevice", closestBusStopToPubDevice)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val nextBusStopInSequence =
                    BusStopProximityManager.getNextBusStopInSequence(closestBusStopToPubDevice)
                if (nextBusStopInSequence != null) {

                    // Note: uncomment below lines of code to use TomTom API.
//                    val etaToNextBStop = TomTomService.getEstimateTimeFromPointToPoint(
//                        latitude, longitude,
//                        nextBusStopInSequence.latitude, nextBusStopInSequence.longitude
//                    )

                    etaToNextBStop = OpenRouteService.getEstimateTimeFromPointToPoint(
                        latitude, longitude,
                        nextBusStopInSequence.latitude, nextBusStopInSequence.longitude
                    )

                    jsonObject.put("ETAtoNextBStop", etaToNextBStop)
                }

                val jsonString = jsonObject.toString()
                mqttManager.publish(MainActivity.PUB_POS_TOPIC, jsonString, 1)
//                mqttManagerConfig.publish(MainActivity.PUB_POS_TOPIC, jsonString, 1)
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
        if (aid == null) {
            Log.e("findBusNameByAid", "AID is null")
            return null
        }

        val configList = OfflineData.getConfig()
        val busItem = configList.find { it.aid == aid }

        return busItem?.bus ?: run {
            Log.e("findBusNameByAid", "No bus found with AID: $aid")
            null
        }
    }

    /**
     * Publishes the current status of whether to show the departure time.
     */
    private fun publishShowDepartureTime(){
        val jsonObject = JSONObject()
        jsonObject.put("showDepartureTime", showDepartureTime)
//        Log.d("ShowDepartureTime", showDepartureTime)
        val jsonString = jsonObject.toString()
        mqttManager.publish(MainActivity.PUB_POS_TOPIC, jsonString, 1)
//        mqttManagerConfig.publish(MainActivity.PUB_POS_TOPIC, jsonString, 1)
    }

    /**
     * Publishes the current departure time.
     */
    private fun publishDepartureTime(){
        val jsonObject = JSONObject()
        jsonObject.put("departureTime", departureTime)
//        Log.d("ShowDepartureTime", showDepartureTime)
        val jsonString = jsonObject.toString()
        mqttManager.publish(MainActivity.PUB_POS_TOPIC, jsonString, 1)
//        mqttManagerConfig.publish(MainActivity.PUB_POS_TOPIC, jsonString, 1)
    }

    /**
     * Retrieves default configuration values for the activity, such as latitude, longitude, bearing, and more.
     */
    private fun getDefaultConfigValue() {
        latitude = intent.getDoubleExtra("lat", latitude)
        longitude = intent.getDoubleExtra("lng", longitude)
        bearing = intent.getFloatExtra("ber", 0.0F)
        speed = intent.getFloatExtra("spe", 0.0F)
        direction = intent.getStringExtra("dir").toString()
        lastMessage = sharedPrefMananger.getString(LAST_MSG_KEY, "").toString()

//        busConfig = intent.getStringExtra(Constant.deviceNameKey).toString()
//        Toast.makeText(this, "arrBusDataOnline1: ${arrBusData}", Toast.LENGTH_SHORT).show()
        Log.d("getDefaultConfigValue busConfig", arrBusData.toString())
        Log.d("getDefaultConfigValue arrBusDataOnline1", arrBusData.toString())
        Log.d("getDefaultConfigValue config", config.toString())
        arrBusData = config!!
        arrBusData = arrBusData.filter { it.aid != aid }
//        Toast.makeText(this, "getDefaultConfigValue arrBusDataOnline2: ${arrBusData}", Toast.LENGTH_SHORT).show()
        Log.d("getDefaultConfigValue arrBusDataOnline2", arrBusData.toString())
        for (bus in arrBusData) {
            markerBus[bus.accessToken] = Marker(binding.map)
            Log.d("getDefaultConfigValue MarkerDrawable", "Bus symbol drawable applied")
            markerBus[bus.accessToken]!!.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_bus_symbol2, null)
            markerBus[bus.accessToken]!!.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
    }

    /**
     * Sends data attributes to the server.
     */
    private fun sendRequestAttributes(){
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
                        val berCus = clientAttributes.bearingCustomer

                        // Ensure lat, lon, and other attributes are not null before using them
                        if (lat != null && lon != null && ber != null) {
                            for (bus in arrBusData) {
                                if (token == bus.accessToken) {
                                    val marker = markerBus[token]
                                    Log.d("MC", markerBus.toString())
                                    if (marker != null) {
                                        marker.position = GeoPoint(lat, lon)
                                        marker.rotation = ber
                                        binding.map.overlays.add(marker)
                                        binding.map.invalidate()
                                    } else {
                                        Log.e("MainActivity", "Marker for token $token is null")
                                    }
                                }
                            }
                        } else {
                            Log.e("MainActivity", "Received null values for lat, lon, or bearing")
                        }
                    } else {
                        Log.e("MainActivity", "Client attributes are null")
                    }
                } else {
                    Log.e("MainActivity", "Failed to retrieve attributes: ${response.message()}")
                }
            }

            override fun onFailure(call: Call<ClientAttributesResponse>, t: Throwable) {
                Log.e("MainActivity", "Error fetching attributes: ${t.message}")
            }
        })
    }


    /**
     * Retrieves the total message count from shared preferences.
     */
    private fun getMessageCount() {
        totalMessage = sharedPrefMananger.getMessageList(MSG_KEY).size
//        binding.notificationBadge.text = totalMessage.toString()
    }

    /**
     * Resumes sensor listeners when the activity is resumed.
     */
    override fun onResume() {
        super.onResume()
    }

    /**
     * Pauses sensor listeners when the activity is paused.
     */
    override fun onPause() {
        super.onPause()
    }

    /**
     * Stops sound, disconnects MQTT manager, and cleans up resources when the activity is destroyed.
     */
    override fun onDestroy() {
        soundManager.stopSound()
        mqttManager.disconnect()
        mqttManagerConfig.disconnect()
        unregisterReceiver(networkReceiver)
        handler.removeCallbacks(dotRunnable)
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