package com.jason.publisher

import android.annotation.SuppressLint
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.common.util.AndroidUtilsLight
import com.google.gson.Gson
import com.jason.publisher.databinding.ActivityMainBinding
import com.jason.publisher.model.Bus
import com.jason.publisher.model.BusRoute
import com.jason.publisher.services.MqttManager
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapController
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.*
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.view.View
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import com.jason.publisher.model.BusItem
import com.jason.publisher.model.BusStop
import com.jason.publisher.model.BusStopInfo
import com.jason.publisher.services.LocationManager
import com.jason.publisher.services.NotificationManager
import com.jason.publisher.services.SharedPrefMananger
import com.jason.publisher.services.SoundManager
import com.jason.publisher.utils.BusStopProximityManager
import com.jason.publisher.utils.NetworkStatusHelper
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.OverlayItem
import java.io.File
import java.io.FileInputStream
import java.lang.Math.atan2

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mqttManagerConfig: MqttManager
    private lateinit var mqttManager: MqttManager
    private lateinit var locationManager: LocationManager
    private lateinit var mapController: MapController
    private lateinit var connectionStatusTextView: TextView
    private lateinit var dateTimeHandler: Handler
    private lateinit var dateTimeRunnable: Runnable

    private var token = ""
    private var tokenConfigData = "oRSsbeuqDMSckyckcMyE"
    private var markerBus = HashMap<String, org.mapsforge.map.layer.overlay.Marker>()

    private var latitude = 0.0
    private var longitude = 0.0
    private var lastLatitude = 0.0
    private var lastLongitude = 0.0
    private var bearing = 0.0F
    private var speed = 0.0F
    private var direction = ""
    private var busConfig = ""
    private var busname = ""
    private var aid = ""
    private var config: List<BusItem>? = emptyList()
    private var route: List<BusRoute> = emptyList()
    private var stops: List<BusStop> = emptyList()
    private var busStopInfo: List<BusStopInfo> = emptyList()
    private var arrBusData: List<BusItem> = emptyList()
    private var firstTime = true
    private var upcomingRoadName: String = "Unknown"

    private lateinit var latitudeTextView: TextView
    private lateinit var longitudeTextView: TextView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers before using them
        initializeManagers()

        // Retrieve AID passed from TimeTableActivity
        aid = intent.getStringExtra("AID") ?: "Unknown"
        Log.d("MainActivity", "Received AID: $aid")

        // Initialize UI components
        initializeUIComponents()

        // Initialize mqttManager before using it
        mqttManagerConfig = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID, username = tokenConfigData)

        // Set up network status UI
        NetworkStatusHelper.setupNetworkStatus(this, binding.connectionStatusTextView, binding.networkStatusIndicator)

        // Initialize MQTT manager
        mqttManager = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID)

        // Load configuration
        Configuration.getInstance().load(this, getSharedPreferences(getString(R.string.app_name), MODE_PRIVATE))

        // Automatically open the map from assets
        openMapFromAssets()

        // Connect and subscribe to MQTT
        connectAndSubscribe()

        // Initialize the date/time updater
        startDateTimeUpdater()

        // Ensure `locationManager` is properly initialized before use
        locationManager.getCurrentLocation(object : LocationListener {
            override fun onLocationUpdate(location: Location) {
                latitude = location.latitude
                longitude = location.longitude
                Log.d("MainActivity Latitude", latitude.toString())
                Log.d("MainActivity Longitude", longitude.toString())

                // Update UI components with the current location
                latitudeTextView.text = "Latitude: $latitude"
                longitudeTextView.text = "Longitude: $longitude"
            }
        })

        fetchConfig { success ->
            if (success) {
                getAccessToken()
                Log.d("MainActivity Token Main", token)
                mqttManager = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID, username = token)
                getDefaultConfigValue()
                connectAndSubscribe()
            } else {
                Toast.makeText(this, "Failed to initialize config. No bus information available.", Toast.LENGTH_SHORT).show()
                clearBusData()
            }
        }
    }

    /**
     * Starts location updates, calculates bearing and direction, and identifies the nearest route coordinate.
     * If a nearest coordinate is found, it checks the current road name and finds the upcoming road if it changes.
     */
    private fun startLocationUpdate() {
        locationManager.startLocationUpdates(object : LocationListener {
            override fun onLocationUpdate(location: Location) {
                Log.d("MainActivity startLocationUpdate", "enter startLocationUpdate()")
                val currentLatitude = location.latitude
                val currentLongitude = location.longitude

                // Calculate bearing and update variables as before
                if (lastLatitude != 0.0 && lastLongitude != 0.0) {
                    bearing = calculateBearing(
                        lastLatitude,
                        lastLongitude,
                        currentLatitude,
                        currentLongitude
                    )
                    direction = Helper.bearingToDirection(bearing)
                }

                latitude = currentLatitude
                longitude = currentLongitude
                speed = (location.speed * 3.6).toFloat()
                lastLatitude = currentLatitude
                lastLongitude = currentLongitude
            }
        })
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
     * Initialize various managers used in the application.
     */
    private fun initializeManagers() {
        locationManager = LocationManager(this)
    }

    /**
     * Sets up the map view and initializes markers and polylines with the provided coordinates.
     *
     * @param lat The latitude for the initial map center.
     * @param lon The longitude for the initial map center.
     */
//    private fun mapViewSetup(lat: Double, lon: Double) {
//        val center = GeoPoint(lat, lon)
//
//        val marker = Marker(binding.map)
//        marker.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_bus_symbol, null) // Use custom drawable
//        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
//
//        mapController = binding.map.controller as MapController
//        mapController.setCenter(center)
//        mapController.setZoom(18.0)
//
//        binding.map.apply {
//            setTileSource(TileSourceFactory.MAPNIK)
//            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
//            mapCenter
//            setMultiTouchControls(true)
//            getLocalVisibleRect(Rect())
//        }
//        updateMarkerPosition(marker)
//    }

    /**
     * Updates the position of the marker on the map and publishes telemetry data.
     *
     * @param marker The marker to be updated.
     */
//    private fun updateMarkerPosition(marker: Marker) {
//        val handler = Handler(Looper.getMainLooper())
//        val updateRunnable = object : Runnable {
//            override fun run() {
//                marker.position = GeoPoint(latitude, longitude)
//                marker.rotation = bearing // The bearing now correctly matches the polar coordinate system
//                binding.map.overlays.add(marker)
//                binding.map.invalidate()
//
//                // Update UI elements
//                runOnUiThread {
//                    updateTextViews()
//                }
//
//                handler.postDelayed(this, PUBLISH_POSITION_TIME)
//
//                // To reset the map center position based on the location of the publisher device.
//                val newCenterLocationBasedOnPubDevice = GeoPoint(latitude, longitude)
//                mapController.animateTo(newCenterLocationBasedOnPubDevice)
//            }
//        }
//        handler.post(updateRunnable)
//    }

    /**
     * Initialize UI components and assign them to the corresponding views.
     */
    private fun initializeUIComponents() {
//            bearingTextView = binding.bearingTextView
        latitudeTextView = binding.latitudeTextView
        longitudeTextView = binding.longitudeTextView
//            directionTextView = binding.directionTextView
//            speedTextView = binding.speedTextView
//            busNameTextView = binding.busNameTextView
//            showDepartureTimeTextView = binding.showDepartureTimeTextView
//            departureTimeTextView = binding.departureTimeTextView
//            etaToNextBStopTextView = binding.etaToNextBStopTextView
//            networkStatusIndicator = binding.networkStatusIndicator
//            reconnectProgressBar = binding.reconnectProgressBar
//            attemptingToConnectTextView = binding.attemptingToConnectTextView
//            aidTextView = binding.aidTextView
//            closestBusStopToPubDeviceTextView = binding.closestBusStopToPubDeviceTextView
//            busDirectionTitleTextView = binding.busDirectionTitleTextView
//            busTelemetryTitleTextView = binding.busTelemetryTitleTextView
//            currentRoadTextView = binding.currentRoadTextView
//            upcomingRoadTitleTextView = binding.upcomingRoadTitleTextView
//            upcomingRoadTextView = binding.upcomingRoadTextView
//            busDirectionBearing = binding.busDirectionBearing
//            busDirectionIcon = binding.busDirectionIcon
    }

    /**
     * Updates the other data text view with the current other data telemetry.
     */
    private fun updateTextViews() {
//            bearingTextView.text = "Current Bearing: $bearing degrees"
        latitudeTextView.text = "Latitude: $latitude"
        longitudeTextView.text = "Longitude: $longitude"
//            directionTextView.text = "Direction: $direction"
//            speedTextView.text = "Speed: $speed"
//            busNameTextView.text = "Bus Name: $busname"
//            showDepartureTimeTextView.text = "Show Departure Time: $showDepartureTime"
//            departureTimeTextView.text = "Departure Time: $departureTime"
//            etaToNextBStopTextView.text = "etaToNextBStop: $etaToNextBStop"
//            aidTextView.text = "AID: $aid"
//            closestBusStopToPubDeviceTextView.text = "closestBusStopToPubDevice: $closestBusStopToPubDevice"
//            busDirectionTitleTextView.text = "$busDirectionTitle"
//            busTelemetryTitleTextView.text = "$busTelemetryTitle"
//            upcomingRoadTitleTextView.text = "$bupcomingRoadTitle"
//            upcomingRoadTextView.text = "Upcoming Road: $upcomingRoadText"
//            currentRoadTextView.text = "Current Road: $currentRoadName"
    }

    /**
     * Retrieves default configuration values for the activity, such as latitude, longitude, bearing, and more.
     */
    private fun getDefaultConfigValue() {
//        busConfig = intent.getStringExtra(Constant.deviceNameKey).toString()
//        Toast.makeText(this, "arrBusDataOnline1: ${arrBusData}", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity getDefaultConfigValue busConfig", arrBusData.toString())
        Log.d("MainActivity getDefaultConfigValue arrBusDataOnline1", arrBusData.toString())
        Log.d("MainActivity getDefaultConfigValue config", config.toString())
        arrBusData = config!!
        arrBusData = arrBusData.filter { it.aid != aid }
//        Toast.makeText(this, "getDefaultConfigValue arrBusDataOnline2: ${arrBusData}", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity getDefaultConfigValue arrBusDataOnline2", arrBusData.toString())
        for (bus in arrBusData) {
            val busPosition = LatLong(latitude, longitude)
            val markerDrawable = AndroidGraphicFactory.convertToBitmap(
                ResourcesCompat.getDrawable(resources, R.drawable.ic_bus_symbol2, null)
            )
            // Create a Mapsforge marker
            val marker = org.mapsforge.map.layer.overlay.Marker(
                busPosition, // LatLong position
                markerDrawable, // Marker icon
                0, // Horizontal offset
                0 // Vertical offset
            )
            // Add marker to Mapsforge Layer Manager
            binding.map.layerManager.layers.add(marker)
            // Store it in markerBus HashMap
            markerBus[bus.accessToken] = marker
            Log.d("MainActivity getDefaultConfigValue MarkerDrawable", "Bus symbol drawable applied")
        }
    }

    /**
     * Clears any existing bus data from the map and other UI elements.
     */
    private fun clearBusData() {
        binding.map.layerManager.layers.clear() // Clear all layers
        binding.map.invalidate()
        markerBus.clear()
    }

    /**
     * Retrieves the access token for the current device's Android ID from the configuration list.
     */
    @SuppressLint("HardwareIds")
    private fun getAccessToken() {
        val listConfig = config
        Log.d("MainActivity getAccessToken config", config.toString())
        for (configItem in listConfig.orEmpty()) {
            if (configItem.aid == aid) {
                token = configItem.accessToken
                break
            }
        }
    }

    /**
     * Subscribes to shared data from the server.
     * Validates configuration, AID matching, and updates the route, stops, and proximity manager.
     */
    private fun subscribeSharedData() {
        Log.d("MainActivity subscribeSharedData", "Enter subscribeSharedData")

        mqttManager.subscribe(SUB_MSG_TOPIC) { message ->
            runOnUiThread {
                try {
                    Log.d("MainActivity subscribeSharedData", "Received message: $message")

                    // Parse incoming message
                    val gson = Gson()
                    val data = gson.fromJson(message, Bus::class.java)

                    // Debugging received JSON structure
                    Log.d("MainActivity subscribeSharedData", "Parsed Bus Object: $data")

                    // Update configuration
                    config = data.shared?.config?.busConfig
                    arrBusData = config.orEmpty()

                    Log.d("MainActivity subscribeSharedData Config", "Config: $config")
                    Log.d("MainActivity subscribeSharedData arrBusData", "arrBusData: $arrBusData")

                    if (config.isNullOrEmpty()) {
                        Toast.makeText(this, "No bus information available.", Toast.LENGTH_SHORT).show()
                        clearBusData()
                        return@runOnUiThread
                    }

                    // Check if AID matches any entry in config
                    val matchingAid = config!!.any { it.aid == aid }
                    if (!matchingAid) {
                        Toast.makeText(this, "AID does not match.", Toast.LENGTH_SHORT).show()
                        clearBusData()
                        return@runOnUiThread
                    }

                    // Process route and stops data
                    route = data.shared?.busRoute1 ?: emptyList()
                    stops = data.shared?.busStop1 ?: emptyList()
                    Log.d("MainActivity subscribeSharedData", "Route: $route")
                    Log.d("MainActivity subscribeSharedData", "Stops: $stops")

                    if (route.isNotEmpty()) {
                        // Convert route to BusStopInfo and update ProximityManager
                        val busStopInfoList = convertRouteToBusStopInfo(route)
                        Log.d("MainActivity subscribeSharedData", "BusStopInfoList: $busStopInfoList")
                        BusStopProximityManager.setBusStopList(busStopInfoList)
                        Log.d("MainActivity subscribeSharedData", "BusStopProximityManager updated.")

                        // Generate polyline and markers on first-time initialization
                        if (firstTime) {
                            firstTime = false
                        }
                    } else {
                        Log.d("MainActivity subscribeSharedData", "No route data available.")
                        clearBusData()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity subscribeSharedData", "Error processing shared data: ${e.message}", e)
                    Toast.makeText(this, "Error processing shared data.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Helper function to convert stops to busStopInfo
     */
    private fun updateBusStopProximityManager() {
        if (stops.isNotEmpty()) {
            busStopInfo = stops.map { stop ->
                BusStopInfo(
                    latitude = stop.latitude ?: 0.0,
                    longitude = stop.longitude ?: 0.0,
                    busStopName = "BusStop_${stop.latitude}_${stop.longitude}"
                )
            }
            BusStopProximityManager.setBusStopList(busStopInfo)
            Log.d("MainActivity updateBusStopProximityManager", "BusStopProximityManager updated with ${busStopInfo.size} stops.")
            Log.d("MainActivity updateBusStopProximityManager", "busStopInfo ${busStopInfo.toString()}")
        } else {
            Log.d("MainActivity updateBusStopProximityManager", "No stops available to update BusStopProximityManager.")
        }
    }

    /**
     * function to convert route to the required BusStopInfo format
     */
    private fun convertRouteToBusStopInfo(route: List<BusRoute>): List<BusStopInfo> {
        val result = route.mapIndexed { index, busStop ->
            BusStopInfo(
                latitude = busStop.latitude ?: 0.0,
                longitude = busStop.longitude ?: 0.0,
                busStopName = when (index) {
                    0 -> "S/E"
                    else -> index.toString()
                }
            )
        }
        Log.d("convertRouteToBusStopInfo", "Converted route to BusStopInfo: $result")
        return result
    }

    /**
     * Generates polylines and markers for the bus route and stops.
     *
     * @param busRoute The bus route data in the new format.
     * @param busStops The bus stop data in the new format.
     */
//    private fun generatePolyline(busRoute: List<BusRoute>, busStops: List<BusStop>) {
//        val routes = mutableListOf<LatLong>()
//        for (route in busRoute) {
//            routes.add(LatLong(route.latitude!!, route.longitude!!))
//        }
//        Log.d("Route Polylines", routes.toString())
//        Log.d("Check Length Route", routes.size.toString())
//
//        // Create a Polyline Layer for Mapsforge
//        val polylinePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
//            color = Color.BLUE
//            strokeWidth = 5f
//            setStyle(org.mapsforge.core.graphics.Style.STROKE)
//        }
//
//        val polylineLayer = org.mapsforge.map.layer.overlay.Polyline(polylinePaint, AndroidGraphicFactory.INSTANCE)
//        polylineLayer.addPoints(routes)
//
//        // Add to Mapsforge Layer Manager
//        binding.map.layerManager.layers.add(polylineLayer)
//
//        // Generate Bus Stop Markers
//        val stopLayers = mutableListOf<org.mapsforge.map.layer.overlay.Marker>()
//        busStops.forEachIndexed { index, stop ->
//            val busStopNumber = index + 1
//            val busStopSymbol = Helper.createBusStopSymbol(applicationContext, busStopNumber, busStops.size)
//
//            val markerBitmap = AndroidGraphicFactory.convertToBitmap(busStopSymbol)
//            val markerLayer = org.mapsforge.map.layer.overlay.Marker(
//                LatLong(stop.latitude!!, stop.longitude!!),
//                markerBitmap,
//                0, // x offset
//                0 // y offset
//            )
//
//            stopLayers.add(markerLayer)
//        }
//
//        // Add all bus stop markers to the layer manager
//        stopLayers.forEach { binding.map.layerManager.layers.add(it) }
//
//        // Refresh the map
//        binding.map.invalidate()
//    }

    /**
     * Fetches the configuration data and initializes the config variable.
     * Calls the provided callback with true if successful, false otherwise.
     */
    private fun fetchConfig(callback: (Boolean) -> Unit) {
        Log.d("MainActivity fetchConfig", "Fetching config...")

        mqttManagerConfig.fetchSharedAttributes(tokenConfigData) { listConfig ->
            if (listConfig.isNotEmpty()) {
                config = listConfig
                Log.d("MainActivity fetchConfig", "✅ Config received: $config")

                // Ensure that MQTT subscription happens after config is fetched
                subscribeSharedData()

                callback(true)
            } else {
                config = emptyList()
                Log.e("MainActivity fetchConfig", "❌ Failed to initialize config. No bus information available.")
                callback(false)
            }
        }
    }

    /** Automatically open the map from assets and configure the map. */
    private fun openMapFromAssets() {
        binding.map.mapScaleBar.isVisible = true
        binding.map.setBuiltInZoomControls(true)

        // Create a tile cache for the map renderer
        val cache = AndroidUtil.createTileCache(
            this,
            "mycache",
            binding.map.model.displayModel.tileSize,
            1f,
            binding.map.model.frameBufferModel.overdrawFactor
        )

        // Copy the map file from assets to a temporary file
        val mapFile = copyAssetToFile("new-zealand-2.map")

        // Load the map using the file
        val mapStore = MapFile(mapFile)
        val renderLayer = TileRendererLayer(
            cache,
            mapStore,
            binding.map.model.mapViewPosition,
            AndroidGraphicFactory.INSTANCE
        )
        renderLayer.setXmlRenderTheme(
            InternalRenderTheme.DEFAULT
        )
        binding.map.layerManager.layers.add(renderLayer)
        binding.map.setCenter(LatLong(-36.8485, 174.7633)) // Auckland, New Zealand
        binding.map.setZoomLevel(12) // A moderate zoom level
    }

    /** Copies a file from assets to the device's file system and returns the File object. */
    private fun copyAssetToFile(assetName: String): File {
        val file = File(cacheDir, assetName)
        if (!file.exists()) {
            assets.open(assetName).use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return file
    }

    /** Connects to the MQTT broker and subscribes to the required topics. */
    private fun connectAndSubscribe() {
        mqttManager.connect { isConnected ->
            if (isConnected) {
                Log.d("MainActivity connectAndSubscribe", "✅ Connected to MQTT broker successfully.")
                subscribeSharedData()
            } else {
                Log.e("MainActivity connectAndSubscribe", "❌ Failed to connect to MQTT broker.")
            }
        }
    }

    /** Generates route markers and polylines for the bus route on the map. */
    private fun generateRouteMarkers(busRoute: List<BusRoute>) {
//        val routes = busRoute.map { GeoPoint(it.latitude!!, it.longitude!!) }
//        val polyline = org.osmdroid.views.overlay.Polyline()
//        polyline.setPoints(routes)
//
//        val marker = Marker(binding.map)
//        marker.position = GeoPoint(latitude, longitude)
//        marker.rotation = bearing
//
//        binding.map.overlays.add(polyline)
//        binding.map.overlays.add(marker)
//        binding.map.invalidate()
    }

    /** Updates the upcoming road name in the UI. */
    private fun updateUpcomingRoadName() {
        if (route.isNotEmpty()) {
            upcomingRoadName = "Test1" ?: "Unknown"
            binding.upcomingRoadTextView.text = "Upcoming Road: $upcomingRoadName"
        }
    }

    /** Starts a periodic task to update the current date and time in the UI. */
    @SuppressLint("SimpleDateFormat")
    private fun startDateTimeUpdater() {
        dateTimeHandler = Handler(Looper.getMainLooper())
        dateTimeRunnable = object : Runnable {
            override fun run() {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val currentDateTime = dateFormat.format(Date())
                binding.busDirectionBearing.text = currentDateTime
                dateTimeHandler.postDelayed(this, 1000) // Update every second
            }
        }
        dateTimeHandler.post(dateTimeRunnable)
    }

    /** Stops the date/time updater when the activity is destroyed. */
    private fun stopDateTimeUpdater() {
        dateTimeHandler.removeCallbacks(dateTimeRunnable)
    }

    /** Cleans up resources on activity destruction. */
    override fun onDestroy() {
        mqttManager.disconnect()
        stopDateTimeUpdater()
        super.onDestroy()
    }
}