package com.jason.publisher

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.jason.publisher.databinding.ActivityMainBinding
import com.jason.publisher.model.Bus
import com.jason.publisher.model.BusRoute
import com.jason.publisher.services.MqttManager
import org.osmdroid.config.Configuration
import org.osmdroid.views.MapController
import java.text.SimpleDateFormat
import java.util.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.jason.publisher.model.BusDataCache
import com.jason.publisher.model.BusItem
import com.jason.publisher.model.BusStop
import com.jason.publisher.model.BusStopInfo
import com.jason.publisher.model.RouteData
import com.jason.publisher.services.LocationManager
import com.jason.publisher.utils.BusStopProximityManager
import com.jason.publisher.utils.NetworkStatusHelper
import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import java.io.File
import java.lang.Math.atan2
import java.lang.Math.cos
import java.lang.Math.sin
import java.lang.Math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mqttManagerConfig: MqttManager
    private lateinit var mqttManager: MqttManager
    private lateinit var locationManager: LocationManager
    private lateinit var mapController: MapController
    private lateinit var connectionStatusTextView: TextView
    private lateinit var dateTimeHandler: Handler
    private lateinit var dateTimeRunnable: Runnable
    private val REQUEST_MANAGE_EXTERNAL_STORAGE = 1001
    private val REQUEST_WRITE_PERMISSION = 1002

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
    private var busDataCache = ""
    private var jsonString = ""
    private var config: List<BusItem>? = emptyList()
    private var route: List<BusRoute> = emptyList()
    private var stops: List<BusStop> = emptyList()
    private var durationBetweenStops: List<Double> = emptyList()
    private var busStopInfo: List<BusStopInfo> = emptyList()
    private var arrBusData: List<BusItem> = emptyList()
    private var firstTime = true
    private var upcomingStop: String = "Unknown"

    private lateinit var latitudeTextView: TextView
    private lateinit var longitudeTextView: TextView
    private lateinit var bearingTextView: TextView
    private lateinit var speedTextView: TextView
    private lateinit var upcomingBusStopTextView: TextView

    private var routePolyline: org.mapsforge.map.layer.overlay.Polyline? = null
    private var busMarker: org.mapsforge.map.layer.overlay.Marker? = null

    private lateinit var simulationHandler: Handler
    private lateinit var simulationRunnable: Runnable
    private var currentRouteIndex = 0
    private var isSimulating = false

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

        // Check and request permission
        requestAllFilesAccessPermission()

        // Check internet connection
        if (!NetworkStatusHelper.isNetworkAvailable(this)) {
            // No internet, load cached bus data
            Toast.makeText(this, "You are disconnected from the internet. Loading data from tablet cache.", Toast.LENGTH_LONG).show()
            loadBusDataFromCache()

            Log.d("MainActivity onCreate NetworkStatusHelper", "Loaded cached config: $config")
            Log.d("MainActivity onCreate NetworkStatusHelper", "Loaded cached busRoute: $route")
            Log.d("MainActivity onCreate NetworkStatusHelper", "Loaded cached busStop: $stops")

            // Load bus route information from offline data
            // Note in getBusRoutesOffline() the route is already contain bus stop in it
            jsonString = OfflineData.getBusRoutesOffline().toString()
            Log.d("MainActivity onCreate NetworkStatusHelper jsonString", jsonString)
            val (route, stops, durationBetweenStops) = RouteData.fromJson(jsonString)

            Log.d("MainActivity onCreate NetworkStatusHelper offline", "Updated busRoute: $route")
            Log.d("MainActivity onCreate  NetworkStatusHelperoffline", "Updated busStop: $stops")
            Log.d("MainActivity onCreate  NetworkStatusHelperoffline", "Updated durationBetweenStops: $durationBetweenStops")
        }

//        busDataCache = getOrCreateAid()
        val file = File("/storage/emulated/0/Documents/.vlrshiddenfolder/busDataCache.txt")
        if (file.exists()) {
            Log.d("MainActivity onCreate", "✅ File exists: ${file.absolutePath}")
        } else {
            Log.e("MainActivity onCreate", "❌ File creation failed!")
        }

        // Initialize managers before using them
        initializeManagers()

        // Retrieve AID passed from TimeTableActivity
        aid = intent.getStringExtra("AID") ?: "Unknown"
        Log.d("MainActivity onCreate", "Received AID: $aid")

        updateBusNameFromConfig()

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

        // Connect and subscribe to MQTT
        connectAndSubscribe()

        // Initialize the date/time updater
        startDateTimeUpdater()

        // Ensure `locationManager` is properly initialized before use
        locationManager.getCurrentLocation(object : LocationListener {
            override fun onLocationUpdate(location: Location) {
                latitude = location.latitude
                longitude = location.longitude
                Log.d("MainActivity onCreate Latitude", latitude.toString())
                Log.d("MainActivity onCreate Longitude", longitude.toString())

                // Update UI components with the current location
                latitudeTextView.text = "Latitude: $latitude"
                longitudeTextView.text = "Longitude: $longitude"
            }
        })

        // Load offline map first
        openMapFromAssets()

        // Start tracking the location and updating the marker
//        startLocationUpdate()

        fetchConfig { success ->
            if (success) {
                getAccessToken()
                Log.d("MainActivity onCreate Token", token)
                mqttManager = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID, username = token)
                getDefaultConfigValue()
                requestAdminMessage()
                connectAndSubscribe()
//                Log.d("MainActivity oncreate fetchConfig config", config.toString())
//                Log.d("MainActivity oncreate fetchConfig busRoute", route.toString())
//                Log.d("MainActivity oncreate fetchConfig busStop", stops.toString())
            } else {
                Log.e("MainActivity onCreate", "Failed to fetch config, running in offline mode.")
            }
        }
        binding.startSimulationButton.setOnClickListener {
            startSimulation()
        }
        binding.stopSimulationButton.setOnClickListener {
            stopSimulation()
        }
    }

    /** Updates bus name if AID matches a config entry */
    private fun updateBusNameFromConfig() {
        if (config.isNullOrEmpty()) return // No config available

        val matchingBus = config!!.find { it.aid == aid } // Find matching AID

        if (matchingBus != null) {
            busname = matchingBus.bus
            runOnUiThread { binding.busNameTextView.text = "Bus Name: $busname" }
            Log.d("MainActivity", "✅ Bus name updated: $busname for AID: $aid")
        } else {
            Log.e("MainActivity", "❌ No matching bus found for AID: $aid")
        }
    }

    /** Starts the simulation with realistic speed */
    private fun startSimulation() {
        if (route.isEmpty()) {
            Toast.makeText(this, "No route data available", Toast.LENGTH_SHORT).show()
            return
        }

        if (isSimulating) {
            Toast.makeText(this, "Simulation already running", Toast.LENGTH_SHORT).show()
            return
        }

        isSimulating = true
        simulationHandler = Handler(Looper.getMainLooper())
        currentRouteIndex = 0

        simulationRunnable = object : Runnable {
            override fun run() {
                if (currentRouteIndex < route.size - 1) {
                    val start = route[currentRouteIndex]
                    val end = route[currentRouteIndex + 1]

                    if (start.latitude == null || start.longitude == null ||
                        end.latitude == null || end.longitude == null) return

                    val startLat = start.latitude!!
                    val startLon = start.longitude!!
                    val endLat = end.latitude!!
                    val endLon = end.longitude!!

                    // Calculate distance and estimated travel time at 30 km/h (8.33 m/s)
                    val distanceMeters = calculateDistance(startLat, startLon, endLat, endLon)
                    val travelTimeSeconds = distanceMeters / 8.33  // Time needed at 30 km/h
                    val steps = (travelTimeSeconds * 10).toInt() // Update every 100ms

                    // Interpolate and update position gradually
                    simulateMovement(startLat, startLon, endLat, endLon, steps)

                    // Move to next point after completion
                    simulationHandler.postDelayed({
                        currentRouteIndex++
                        simulationHandler.post(this)
                    }, (travelTimeSeconds * 1000).toLong()) // Wait until movement completes
                } else {
                    isSimulating = false
                    Toast.makeText(this@MainActivity, "Simulation completed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        simulationHandler.post(simulationRunnable)
        Toast.makeText(this, "Simulation started", Toast.LENGTH_SHORT).show()
    }

    /** Interpolates movement between two points with dynamic bearing and speed updates */
    private fun simulateMovement(startLat: Double, startLon: Double, endLat: Double, endLon: Double, steps: Int) {
        val latStep = (endLat - startLat) / steps
        val lonStep = (endLon - startLon) / steps

        var step = 0
        val stepHandler = Handler(Looper.getMainLooper())

        val stepRunnable = object : Runnable {
            override fun run() {
                if (step < steps) {
                    val newLat = startLat + (latStep * step)
                    val newLon = startLon + (lonStep * step)

                    // Check if the bus has passed any stops
                    checkPassedStops(newLat, newLon)

                    // Calculate speed dynamically (meters per second)
                    if (step > 0) {
                        val distance = calculateDistance(lastLatitude, lastLongitude, newLat, newLon)
                        speed = (distance / 0.1).toFloat() // 0.1 seconds per step (100ms)
                    }

                    // Update bearing dynamically at each step
                    if (step > 0) {
                        bearing = calculateBearing(lastLatitude, lastLongitude, newLat, newLon)
                    }

                    // Update UI
                    latitudeTextView.text = "Latitude: $newLat"
                    longitudeTextView.text = "Longitude: $newLon"
                    bearingTextView.text = "Bearing: $bearing°"
                    speedTextView.text = "Speed: ${"%.2f".format(speed)} km/h"

                    // Move the bus marker
                    updateBusMarkerPosition(newLat, newLon, bearing)

                    // Save last location
                    lastLatitude = newLat
                    lastLongitude = newLon

                    step++
                    stepHandler.postDelayed(this, 100) // Update every 100ms
                }
            }
        }
        stepHandler.post(stepRunnable)
    }

    /**
     * Finds and logs the nearest bus stop that has been passed.
     * After passing a stop, it moves to the next stop in the list.
     *
     * @param currentLat The current latitude of the bus.
     * @param currentLon The current longitude of the bus.
     */
    private val passedStops = mutableListOf<BusStop>() // Track stops that have been passed
    private var currentStopIndex = 0 // Keep track of the current stop in order

    private fun checkPassedStops(currentLat: Double, currentLon: Double) {
        if (stops.isEmpty()) {
            Log.d("MainActivity checkPassedStops", "❌ No bus stops available.")
            return
        }

        if (currentStopIndex >= stops.size) {
            Log.d("MainActivity checkPassedStops", "✅ All stops have been passed.")
            return
        }

        val nextStop = stops[currentStopIndex]
        val stopLat = nextStop.latitude ?: return
        val stopLon = nextStop.longitude ?: return
        val stopAddress = nextStop.address ?: "No more upcoming bus stop"
        val distance = calculateDistance(currentLat, currentLon, stopLat, stopLon)

        val stopPassThreshold = 15.0 // 15 meters

        if (distance <= stopPassThreshold) {
            Log.d(
                "MainActivity checkPassedStops",
                "✅ Nearest stop passed: $stopLat, $stopLon (Distance: ${"%.2f".format(distance)} meters) at $stopAddress"
            )

            runOnUiThread {
                upcomingBusStopTextView.text = "$stopAddress"
            }

            passedStops.add(nextStop)
            currentStopIndex++

            if (currentStopIndex < stops.size) {
                val upcomingStop = stops[currentStopIndex]
                val upcomingStopName = getUpcomingBusStopName(upcomingStop.latitude ?: 0.0, upcomingStop.longitude ?: 0.0)

                Log.d(
                    "MainActivity checkPassedStops",
                    "🛑 No stop passed. Nearest stop: ${upcomingStop.latitude}, ${upcomingStop.longitude} is ${
                        "%.2f".format(distance)
                    } meters away at $upcomingStopName."
                )

                runOnUiThread {
                    upcomingBusStopTextView.text = "$upcomingStopName"
                }
            } else {
                Log.d("MainActivity checkPassedStops", "✅ All stops have been passed.")
            }
        } else {
            val upcomingStopName = getUpcomingBusStopName(stopLat, stopLon)

            Log.d(
                "MainActivity checkPassedStops",
                "🛑 No stop passed. Nearest stop: ${nextStop.latitude}, ${nextStop.longitude} is ${
                    "%.2f".format(distance)
                } meters away at $upcomingStopName."
            )

            runOnUiThread {
                upcomingBusStopTextView.text = "$upcomingStopName"
            }
        }
    }

    /** Finds the nearest upcoming bus stop */
    private fun getUpcomingBusStopName(lat: Double, lon: Double): String {
        try {
            Log.d("MainActivity getUpcomingBusStopName", "JSON String: $jsonString")

            // Convert jsonString into a JSONArray
            val jsonArray = JSONArray(jsonString)

            if (jsonArray.length() == 0) {
                Log.e("MainActivity getUpcomingBusStopName", "JSON array is empty")
                return "No Upcoming Stop"
            }

            // Get the first object in the array
            val jsonObject = jsonArray.getJSONObject(0)

            // Ensure the key exists
            if (!jsonObject.has("next_points")) {
                Log.e("MainActivity getUpcomingBusStopName", "Missing 'next_points' key")
                return "No Upcoming Stop"
            }

            val routeArray = jsonObject.getJSONArray("next_points")

            if (routeArray.length() == 0) {
                Log.e("MainActivity getUpcomingBusStopName", "next_points array is empty")
                return "No Upcoming Stop"
            }

            var nearestStop: String? = null
            var minDistance = Double.MAX_VALUE

            for (i in 0 until routeArray.length()) {
                val stop = routeArray.getJSONObject(i)

                if (!stop.has("latitude") || !stop.has("longitude") || !stop.has("address")) {
                    Log.e("MainActivity getUpcomingBusStopName", "Missing stop fields at index $i")
                    continue
                }

                val stopLat = stop.getDouble("latitude")
                val stopLon = stop.getDouble("longitude")
                val stopAddress = stop.getString("address")

                val distance = calculateDistance(lat, lon, stopLat, stopLon)

                if (distance < minDistance) {
                    minDistance = distance
                    nearestStop = stopAddress
                }
            }

            return nearestStop ?: "Unknown Stop"
        } catch (e: Exception) {
            Log.e("MainActivity getUpcomingBusStopName", "Error: ${e.localizedMessage}", e)
            return "MainActivity getUpcomingBusStopName Error Retrieving Stop"
        }
    }

    /** Calculates distance between two lat/lon points in meters */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000 // Radius of Earth in meters
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)

        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLon / 2) * sin(deltaLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c // Distance in meters
    }

    /** Stops the simulation and resets MainActivity */
    private fun stopSimulation() {
        if (::simulationHandler.isInitialized) {
            simulationHandler.removeCallbacks(simulationRunnable)
        }
        isSimulating = false

        // Restart MainActivity
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish() // Close current instance
    }

    /**
     * Draws a polyline on the Mapsforge map using the busRoute data.
     */
    private fun drawPolyline() {
        Log.d("MainActivity drawPolyline", "Drawing polyline with route: $route")

        if (route.isNotEmpty()) {
            val routePoints = route.map { LatLong(it.latitude!!, it.longitude!!) }

            // **Remove existing polyline before adding a new one**
            routePolyline?.let {
                binding.map.layerManager.layers.remove(it)
            }

            // **Ensure Mapsforge factory is initialized**
            AndroidGraphicFactory.createInstance(application)

            // **Set up paint for polyline**
            val polylinePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                color = Color.BLUE  // Change color to RED for visibility
                strokeWidth = 8f  // Increase thickness for better visibility
                setStyle(org.mapsforge.core.graphics.Style.STROKE)
            }

            // **Create polyline with proper style**
            routePolyline = org.mapsforge.map.layer.overlay.Polyline(polylinePaint, AndroidGraphicFactory.INSTANCE).apply {
                addPoints(routePoints)
            }

            // **Ensure polyline is added to the map**
            if (!binding.map.layerManager.layers.contains(routePolyline)) {
                binding.map.layerManager.layers.add(routePolyline)
            }

            // **Force map redraw**
            binding.map.invalidate()

            Log.d("MainActivity drawPolyline", "✅ Polyline drawn with ${routePoints.size} points.")
        } else {
            Log.e("MainActivity drawPolyline", "❌ No route data available for polyline.")
        }
    }

    /**
     * Saves the latest bus data to the cache file.
     */
    private fun saveBusDataToCache() {
        val cacheFile = File(getHiddenFolder(), "busDataCache.txt") // Change extension to .txt
//        Log.d("MainActivity saveBusDataToCache aid", aid.toString())
//        Log.d("MainActivity saveBusDataToCache config", config.toString())
//        Log.d("MainActivity saveBusDataToCache busRoute", routeCache.toString())
//        Log.d("MainActivity saveBusDataToCache busStop", stopsCache.toString())

        try {
            val busData = BusDataCache(
                aid = aid,
                config = config,
                busRoute = route,
                busStop = stops
            )

            val jsonStringBusData = Gson().toJson(busData) // Format data as JSON
            cacheFile.writeText(jsonStringBusData) // Save as a .txt file but in JSON format
            Log.d("MainActivity", "✅ Bus data cache updated successfully in busDataCache.txt.")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error saving bus data cache: ${e.message}")
        }
    }

    /**
     * Gets the hidden folder directory where cached bus data is stored.
     * Creates the folder if it does not exist.
     *
     * @return File representing the hidden directory.
     */
    private fun getHiddenFolder(): File {
        val externalDocumentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val hiddenFolder = File(externalDocumentsDir, ".vlrshiddenfolder")

        if (!hiddenFolder.exists()) {
            hiddenFolder.mkdirs()
        }

        return hiddenFolder
    }

    /**
     * Loads bus data from the cache file if it exists.
     * If no cache is found, returns a default AID.
     *
     * @return AID or an error message.
     */
    private fun loadBusDataFromCache(): String {
        val cacheFile = File(getHiddenFolder(), "busDataCache.txt") // Change extension to .txt

        return if (cacheFile.exists()) {
            try {
                val jsonContent = cacheFile.readText()
                val cachedData = Gson().fromJson(jsonContent, BusDataCache::class.java)

                // Update global variables with cached data
                aid = cachedData.aid
                config = cachedData.config ?: emptyList()
                route = cachedData.busRoute ?: emptyList()
                stops = cachedData.busStop ?: emptyList()

                Log.d("MainActivity loadBusDataFromCache", "✅ Loaded cached bus data from busDataCache.txt.")
                cachedData.aid // Return the AID
            } catch (e: Exception) {
                Log.e("MainActivity loadBusDataFromCache", "❌ Error reading bus data cache: ${e.message}")
                "Error reading cache"
            }
        } else {
            Log.e("MainActivity", "❌ No cached bus data found in busDataCache.txt.")
            "No cache found"
        }
    }

    /**
     * All Files Access Permission (MANAGE_EXTERNAL_STORAGE).
     */
    private fun requestAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
                } catch (e: Exception) {
                    Log.e("MainActivity requestAllFilesAccessPermission", "Error requesting storage permission: ${e.message}")
                }
            }
        }
    }

    /**
     * Retrieves the Android ID (AID) from a hidden JSON file in the app-specific documents directory.
     * If the file or directory does not exist, it creates them and generates a new AID.
     *
     * @return The AID (Android ID) as a String.
     */
    @SuppressLint("HardwareIds")
    private fun getOrCreateAid(): String {
        // Ensure we have the correct storage permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.e("MainActivity getOrCreateAid", "Storage permission not granted.")
                return "Permission Denied"
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e("MainActivity getOrCreateAid", "Storage permission not granted.")
                return "Permission Denied"
            }
        }

        // Use External Storage Public Directory for Documents
        val externalDocumentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val hiddenFolder = File(externalDocumentsDir, ".vlrshiddenfolder")

        if (!hiddenFolder.exists()) {
            val success = hiddenFolder.mkdirs()
            if (!success) {
                Log.e("MainActivity getOrCreateAid", "Failed to create directory: ${hiddenFolder.absolutePath}")
                return "Failed to create directory"
            }
        }

        val aidFile = File(hiddenFolder, "busDataCache.json")
        Log.d("MainActivity getOrCreateAid", "Attempting to create: ${aidFile.absolutePath}")

        if (!aidFile.exists()) {
            val newAid = generateNewAid()
            val jsonObject = JSONObject().apply {
                put("aid", newAid)
            }
            try {
                aidFile.writeText(jsonObject.toString())
                Toast.makeText(this, "AID saved successfully in busDataCache.json", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity getOrCreateAid", "Error writing to file: ${e.message}")
                return "Error writing file"
            }
            return newAid
        }

        return try {
            val jsonContent = JSONObject(aidFile.readText())
            jsonContent.getString("aid").trim()
        } catch (e: Exception) {
            Log.e("MainActivity getOrCreateAid", "Error reading JSON file: ${e.message}")
            "Error reading file"
        }
    }

    /**
     * Generates a new Android ID (AID) using the device's secure Android ID.
     *
     * @return A unique Android ID as a String.
     */
    @SuppressLint("HardwareIds")
    private fun generateNewAid(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    /**
     * onActivityResult to handle permission grant
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.d("MainActivity", "All files access granted.")
                } else {
                    Log.e("MainActivity", "User denied all files access.")
                }
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
                val currentLatitude = location.latitude
                val currentLongitude = location.longitude

                // Calculate bearing and update variables
                if (lastLatitude != 0.0 && lastLongitude != 0.0) {
                    bearing = calculateBearing(
                        lastLatitude,
                        lastLongitude,
                        currentLatitude,
                        currentLongitude
                    )
                    direction = Helper.bearingToDirection(bearing)
                }

                // Update global variables
                latitude = currentLatitude
                longitude = currentLongitude
                speed = (location.speed * 3.6).toFloat()
                lastLatitude = currentLatitude
                lastLongitude = currentLongitude

                // Update UI
                latitudeTextView.text = "Latitude: $latitude"
                longitudeTextView.text = "Longitude: $longitude"
                bearingTextView.text = "Bearing: $bearing°"

                // Update the bus marker position
                updateBusMarkerPosition(latitude, longitude, bearing)
            }
        })
    }

    /** Move the bus marker dynamically with updated bearing */
    private fun updateBusMarkerPosition(lat: Double, lon: Double, bearing: Float) {
        val newPosition = LatLong(lat, lon)

        // Convert Drawable to Bitmap and rotate it
        val rotatedBitmap = rotateDrawable(bearing)

        // Remove old marker if it exists
        busMarker?.let {
            binding.map.layerManager.layers.remove(it)
        }

        // Create a new rotated marker at the updated position
        busMarker = org.mapsforge.map.layer.overlay.Marker(
            newPosition, rotatedBitmap, 0, 0
        )
        binding.map.layerManager.layers.add(busMarker)

        // Keep the map centered on the bus location
        binding.map.setCenter(newPosition)
        binding.map.invalidate()
    }

    /**
     * Calculates the average latitude and longitude of the next 'count' points in the busRoute.
     */
    private fun calculateAverageNextCoordinates(lat: Double, lon: Double, count: Int): Pair<Double, Double>? {
        if (route.isEmpty()) return null

        var totalLat = 0.0
        var totalLon = 0.0
        var validPoints = 0

        // Find the current position in the route
        val currentIndex = route.indexOfFirst { it.latitude == lat && it.longitude == lon }
        if (currentIndex == -1) return null // Current position not found

        // Take the next 'count' points
        for (i in 1..count) {
            val nextIndex = currentIndex + i
            if (nextIndex < route.size) {
                totalLat += route[nextIndex].latitude ?: 0.0
                totalLon += route[nextIndex].longitude ?: 0.0
                validPoints++
            } else {
                break // Stop if we run out of points
            }
        }

        return if (validPoints > 0) {
            Pair(totalLat / validPoints, totalLon / validPoints)
        } else {
            null
        }
    }

    /**
     * Rotates the bus symbol drawable based on the given angle.
     *
     * @param angle The angle in degrees.
     * @return Rotated Bitmap.
     */
    private fun rotateDrawable(angle: Float): org.mapsforge.core.graphics.Bitmap {
        val markerDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_bus_symbol, null)

        if (markerDrawable == null) {
            Log.e("rotateDrawable", "Drawable is null!")
            // Use an alternative way to create a blank Bitmap
            val emptyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            return AndroidBitmap(emptyBitmap)
        }

        // Convert Drawable to Android Bitmap
        val androidBitmap = android.graphics.Bitmap.createBitmap(
            markerDrawable.intrinsicWidth,
            markerDrawable.intrinsicHeight,
            android.graphics.Bitmap.Config.ARGB_8888
        )

        val canvas = android.graphics.Canvas(androidBitmap)
        markerDrawable.setBounds(0, 0, canvas.width, canvas.height)
        markerDrawable.draw(canvas) // Draw the drawable onto the canvas

        // Apply rotation
        val matrix = android.graphics.Matrix()
        matrix.postRotate(angle)

        val rotatedAndroidBitmap = android.graphics.Bitmap.createBitmap(
            androidBitmap, 0, 0, androidBitmap.width, androidBitmap.height, matrix, true
        )

        // Wrap rotated Android Bitmap inside an AndroidBitmap
        return AndroidBitmap(rotatedAndroidBitmap)
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
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLon = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)
        val angleRad = atan2(y, x)
        val angleDeg = (Math.toDegrees(angleRad) + 360) % 360

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
        bearingTextView = binding.bearingTextView
        speedTextView = binding.speedTextView
        upcomingBusStopTextView = binding.upcomingBusStopTextView
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
     * Requests admin messages periodically.
     */
    private fun requestAdminMessage() {
        val jsonObject = JSONObject()
        jsonObject.put("sharedKeys","message,busRoute,busStop,config")
        val jsonStringSharedKeys = jsonObject.toString()
        val handler = Handler(Looper.getMainLooper())
        mqttManager.publish(PUB_MSG_TOPIC, jsonStringSharedKeys)
//        mqttManagerConfig.publish(PUB_MSG_TOPIC, jsonStringSharedKeys)
        handler.post(object : Runnable {
            override fun run() {
                mqttManager.publish(PUB_MSG_TOPIC, jsonStringSharedKeys)
//                mqttManagerConfig.publish(PUB_MSG_TOPIC, jsonStringSharedKeys)
                handler.postDelayed(this, REQUEST_PERIODIC_TIME)
            }
        })
    }

    /**
     * Subscribes to shared data from the server.
     * Validates configuration, AID matching, and updates the route, stops, and proximity manager.
     */
    private fun subscribeSharedData() {
//        Log.d("MainActivity subscribeSharedData", "Enter subscribeSharedData")

        mqttManager.subscribe(SUB_MSG_TOPIC) { message ->
            runOnUiThread {
                try {
//                    Log.d("MainActivity subscribeSharedData", "Received message: $message")

                    // Parse incoming message
                    val gson = Gson()
                    val data = gson.fromJson(message, Bus::class.java)

                    // Debugging received JSON structure
//                    Log.d("MainActivity subscribeSharedData", "Parsed Bus Object: $data")

                    // Update configuration
                    config = data.shared?.config?.busConfig
                    arrBusData = config.orEmpty()

                    Log.d("MainActivity subscribeSharedData Config", "Config: $config")
//                    Log.d("MainActivity subscribeSharedData arrBusData", "arrBusData: $arrBusData")

                    if (config.isNullOrEmpty()) {
//                        Toast.makeText(this, "No bus information available.", Toast.LENGTH_SHORT).show()
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

                    // Save data **only after all values are updated**
                    if (config!!.isNotEmpty() && route.isNotEmpty() && stops.isNotEmpty()) {
                        saveBusDataToCache()
                    }

                    if (route.isNotEmpty()) {
                        // Convert route to BusStopInfo and update ProximityManager
                        val busStopInfoList = convertRouteToBusStopInfo(route)
//                        Log.d("MainActivity subscribeSharedData", "BusStopInfoList: $busStopInfoList")
                        BusStopProximityManager.setBusStopList(busStopInfoList)
//                        Log.d("MainActivity subscribeSharedData", "BusStopProximityManager updated.")

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

    /** Fetches the configuration data and initializes the config variable. */
    private fun fetchConfig(callback: (Boolean) -> Unit) {
        Log.d("MainActivity fetchConfig", "Fetching config...")

        mqttManagerConfig.fetchSharedAttributes(tokenConfigData) { listConfig ->
            runOnUiThread {
                if (listConfig.isNotEmpty()) {
                    config = listConfig
                    Log.d("MainActivity fetchConfig", "✅ Config received: $config")
                    subscribeSharedData()
                    callback(true)
                } else {
                    Log.e("MainActivity fetchConfig", "❌ Failed to initialize config. Running in offline mode.")
//                    Toast.makeText(this@MainActivity, "Running in offline mode. No bus information available.", Toast.LENGTH_SHORT).show()
                    callback(false)
                }
            }
        }
    }

    /**
     * Loads the offline map from assets and configures the map.
     * Prevents adding duplicate layers.
     */
    private fun openMapFromAssets() {
        binding.map.mapScaleBar.isVisible = true
        binding.map.setBuiltInZoomControls(true)

        val cache = AndroidUtil.createTileCache(
            this,
            "mycache",
            binding.map.model.displayModel.tileSize,
            1f,
            binding.map.model.frameBufferModel.overdrawFactor
        )

        val mapFile = copyAssetToFile("new-zealand-2.map")
        val mapStore = MapFile(mapFile)

        val renderLayer = TileRendererLayer(
            cache,
            mapStore,
            binding.map.model.mapViewPosition,
            AndroidGraphicFactory.INSTANCE
        ).apply {
            setXmlRenderTheme(InternalRenderTheme.DEFAULT)
        }

        // **Check if layer already exists before adding**
        if (!binding.map.layerManager.layers.contains(renderLayer)) {
            binding.map.layerManager.layers.add(renderLayer)
            Log.d("MainActivity openMapFromAssets", "✅ Offline map added successfully.")
        } else {
            Log.d("MainActivity openMapFromAssets", "⚠️ Offline map layer already exists. Skipping duplicate addition.")
        }

        binding.map.setCenter(LatLong(latitude, longitude)) // Set the default location to center the bus marker
//        binding.map.setCenter(LatLong(-36.855647, 174.765249)) // Airedale
//        binding.map.setCenter(LatLong(-36.8485, 174.7633)) // Auckland, NZ
        binding.map.setZoomLevel(17) // Set default zoom level
//        binding.map.setZoomLevel(11) // Set default zoom level

        // **Initialize the bus marker**
        addBusMarker(latitude, longitude)

        // **Ensure the map is fully loaded before drawing the polyline**
        binding.map.post {
            Log.d("MainActivity", "Map is fully initialized. Drawing polyline now.")
            drawPolyline()  // Draw polyline only after map is loaded
            addBusStopMarkers(stops)
        }
    }

    /**
     * Place the bus marker at a given latitude and longitude
     */
    private fun addBusMarker(lat: Double, lon: Double) {
        val busPosition = LatLong(lat, lon)

        val markerDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_bus_symbol, null)
        val markerBitmap = AndroidGraphicFactory.convertToBitmap(markerDrawable)

        // Remove previous marker if it exists
        busMarker?.let {
            binding.map.layerManager.layers.remove(it)
        }

        // Create and add a new marker
        busMarker = org.mapsforge.map.layer.overlay.Marker(
            busPosition, markerBitmap, 0, 0
        )
        binding.map.layerManager.layers.add(busMarker)
    }

    /**
     * Adds bus stops to the map using OverlayItem instead of Marker.
     */
    private fun addBusStopMarkers(busStops: List<BusStop>) {
        busStops.forEachIndexed { index, stop ->
            val busStopNumber = index + 1

            // Create a custom bitmap for the marker
            val busStopSymbol = Helper.createBusStopSymbol(applicationContext, busStopNumber, busStops.size)
            val markerBitmap = AndroidGraphicFactory.convertToBitmap(busStopSymbol)

            // Create a Mapsforge Marker
            val marker = org.mapsforge.map.layer.overlay.Marker(
                LatLong(stop.latitude!!, stop.longitude!!), // LatLong position
                markerBitmap, // Marker icon
                0, // Horizontal offset
                0 // Vertical offset
            )

            // Add marker to Mapsforge Layer Manager
            binding.map.layerManager.layers.add(marker)
        }

        // Refresh the map after adding all markers
        binding.map.invalidate()
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
//                Log.d("MainActivity connectAndSubscribe config", config.toString())
//                Log.d("MainActivity connectAndSubscribe busRoute", route.toString())
//                Log.d("MainActivity connectAndSubscribe busStop", stops.toString())
            } else {
                Log.e("MainActivity connectAndSubscribe", "❌ Failed to connect to MQTT broker. Running in offline mode.")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Running in offline mode. No connection to server.", Toast.LENGTH_SHORT).show()
                }
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

        // Remove polyline from Mapsforge map
        routePolyline?.let {
            binding.map.layerManager.layers.remove(it)
            binding.map.invalidate()
        }
        Log.d("MainActivity", "🗑️ Removed polyline on destroy.")
    }
}