package com.jason.publisher

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.model.BusRoute
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
import com.jason.publisher.model.BusItem
import com.jason.publisher.model.BusStop
import com.jason.publisher.model.BusStopInfo
import com.jason.publisher.model.RouteData
import com.jason.publisher.model.ScheduleItem
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

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var locationManager: LocationManager
    private lateinit var mapController: MapController
    private lateinit var dateTimeHandler: Handler
    private lateinit var dateTimeRunnable: Runnable

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
    private var token = ""
    private var config: List<BusItem>? = emptyList()
    private var route: List<BusRoute> = emptyList()
    private var stops: List<BusStop> = emptyList()
    private var busRouteData: List<RouteData> = emptyList()
    private var durationBetweenStops: List<Double> = emptyList()
    private var busStopInfo: List<BusStopInfo> = emptyList()
    private var arrBusData: List<BusItem> = emptyList()
    private var firstTime = true
    private var upcomingStop: String = "Unknown"

//    private lateinit var aidTextView: TextView
//    private lateinit var latitudeTextView: TextView
//    private lateinit var longitudeTextView: TextView
//    private lateinit var bearingTextView: TextView
//    private lateinit var speedTextView: TextView
    private lateinit var upcomingBusStopTextView: TextView

    private var routePolyline: org.mapsforge.map.layer.overlay.Polyline? = null
    private var busMarker: org.mapsforge.map.layer.overlay.Marker? = null
    private var markerBus = HashMap<String, org.mapsforge.map.layer.overlay.Marker>()

    private lateinit var simulationHandler: Handler
    private lateinit var simulationRunnable: Runnable
    private var currentRouteIndex = 0
    private var isSimulating = false
    private var simulationStartTime: Long = 0L
    private lateinit var scheduleList: List<ScheduleItem>
    private val redBusStops = mutableSetOf<String>()

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
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers before using them
        initializeManagers()

        // Retrieve data passed from TimeTableActivity
        aid = intent.getStringExtra("AID") ?: "Unknown"
        config = intent.getSerializableExtra("CONFIG") as? List<BusItem> ?: emptyList()
        jsonString = intent.getStringExtra("JSON_STRING") ?: ""
        route = intent.getSerializableExtra("ROUTE") as? List<BusRoute> ?: emptyList()
        stops = intent.getSerializableExtra("STOPS") as? List<BusStop> ?: emptyList()
        durationBetweenStops = intent.getSerializableExtra("DURATION_BETWEEN_BUS_STOP") as? List<Double> ?: emptyList()
        busRouteData = intent.getSerializableExtra("BUS_ROUTE_DATA") as? List<RouteData> ?: emptyList()
        scheduleList = intent.getSerializableExtra("SCHEDULE_DATA") as? List<ScheduleItem> ?: emptyList()

        Log.d("MainActivity onCreate retrieve", "Received aid: $aid")
        Log.d("MainActivity onCreate retrieve", "Received config: ${config.toString()}")
        Log.d("MainActivity onCreate retrieve", "Received jsonString: $jsonString")
        Log.d("MainActivity onCreate retrieve", "Received route: ${route.toString()}")
        Log.d("MainActivity onCreate retrieve", "Received stops: ${stops.toString()}")
        Log.d("MainActivity onCreate retrieve", "Received durationBetweenStops: ${durationBetweenStops.toString()}")
        Log.d("MainActivity onCreate retrieve", "Received busRouteData: ${busRouteData.toString()}")
        Log.d("MainActivity onCreate retrieve", "Received scheduleList: ${scheduleList.toString()}")

        // Initialize UI components
        initializeUIComponents()

//        aidTextView.text = "AID: $aid"

        // Set up network status UI
        NetworkStatusHelper.setupNetworkStatus(this, binding.connectionStatusTextView, binding.networkStatusIndicator)

        // Initialize the date/time updater
        startDateTimeUpdater()

//        fetchConfig { success ->
//            if (success) {
//                getAccessToken()
                Log.d("MainActivity onCreate Token", token)
//                mqttManager = MqttManager(serverUri = TimeTableActivity.SERVER_URI, clientId = TimeTableActivity.CLIENT_ID, username = token)
                getDefaultConfigValue()
//                requestAdminMessage()
//                connectAndSubscribe()
//                Log.d("MainActivity oncreate fetchConfig config", config.toString())
//                Log.d("MainActivity oncreate fetchConfig busRoute", route.toString())
//                Log.d("MainActivity oncreate fetchConfig busStop", stops.toString())
//            } else {
//                Log.e("MainActivity onCreate", "Failed to fetch config, running in offline mode.")
//            }
//        }

        updateBusNameFromConfig()

        // Ensure `locationManager` is properly initialized before use
        locationManager.getCurrentLocation(object : LocationListener {
            override fun onLocationUpdate(location: Location) {
                latitude = location.latitude
                longitude = location.longitude
                Log.d("MainActivity onCreate Latitude", latitude.toString())
                Log.d("MainActivity onCreate Longitude", longitude.toString())

                // Update UI components with the current location
//                latitudeTextView.text = "Latitude: $latitude"
//                longitudeTextView.text = "Longitude: $longitude"
            }
        })

        // Load offline map first
        openMapFromAssets()

        // Start tracking the location and updating the marker
//        startLocationUpdate()

//        binding.startSimulationButton.setOnClickListener {
//            startSimulation()
//        }
//        binding.stopSimulationButton.setOnClickListener {
//            stopSimulation()
//        }
    }

    /** Updates bus name if AID matches a config entry */
    private fun updateBusNameFromConfig() {
        if (config.isNullOrEmpty()) return // No config available

        val matchingBus = config!!.find { it.aid == aid } // Find matching AID

        if (matchingBus != null) {
            busname = matchingBus.bus
            runOnUiThread {
                binding.busNameTextView.text = "$busname"
            }
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
        simulationStartTime = System.currentTimeMillis() // Initialize simulation time
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
                    Toast.makeText(this@MapActivity, "Simulation completed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        simulationHandler.post(simulationRunnable)

        // ✅ Run schedule check every 1 second during simulation
        simulationHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isSimulating) return
                checkScheduleStatus() // Check schedule
                simulationHandler.postDelayed(this, 1000) // Repeat every 1 sec
            }
        }, 1000)

        Toast.makeText(this, "Simulation started", Toast.LENGTH_SHORT).show()
    }

    /**
     * Logs actual timestamp, expected arrival time, and schedule status.
     * Resets actual time when a stop is passed.
     */
    private fun checkScheduleStatus() {
        if (!isSimulating || durationBetweenStops.isEmpty() || stops.isEmpty()) return

        Log.d("=========== 🚀 SCHEDULE CHECK START ===========", "Checking schedule status...")

        // Adjust index to prevent out-of-bounds access
        val adjustedIndex = currentStopIndex - 1

        // Ensure the adjusted index is within valid bounds
        if (adjustedIndex < 0 || adjustedIndex >= stops.size || adjustedIndex >= durationBetweenStops.size) {
            Log.e(
                "MainActivity checkScheduleStatus",
                "❌ Index out of bounds!"
            )
//            resetSchedule() // 🔄 Reset schedule
            return
        }

        val nextStop = stops[adjustedIndex]
        val stopLat = nextStop.latitude ?: return
        val stopLon = nextStop.longitude ?: return

        // Set expected time to the corresponding value in durationBetweenStops
        val expectedTimeSeconds = (durationBetweenStops[adjustedIndex] * 60).toInt()

        // Get actual elapsed time
        val elapsedTimeMillis = System.currentTimeMillis() - simulationStartTime
        val actualTimeSeconds = (elapsedTimeMillis / 1000).toInt()

        // Define threshold range (± threshold seconds)
        val threshold = 25
        val lowerThresholdSeconds = expectedTimeSeconds - threshold
        val upperThresholdSeconds = expectedTimeSeconds + threshold

        // Convert seconds to mm:ss format
        fun formatTime(seconds: Int): String {
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            return String.format("%02d min %02d sec", minutes, remainingSeconds)
        }

        Log.d(
            "MainActivity checkScheduleStatus",
            """
        🕒 Actual Time: ${formatTime(actualTimeSeconds)}
        ⏳ Expected Time: ${formatTime(expectedTimeSeconds)}
        ⏲️ Duration Between Stops: ${durationBetweenStops[adjustedIndex]} min
        Duration Between Stops list: $durationBetweenStops
        Upcoming Bus Stop: ${upcomingBusStopTextView.text}
        ⏲️ Threshold: ±${threshold} sec (${formatTime(lowerThresholdSeconds)} - ${formatTime(upperThresholdSeconds)})
        """.trimIndent()
        )

        val statusTextView = findViewById<TextView>(R.id.scheduleStatusValueTextView)
//        val expectedTimeTextView = findViewById<TextView>(R.id.expectedTimeValueTextView)
        val actualTimeTextView = findViewById<TextView>(R.id.actualTimeValueTextView)
        val thresholdRangeTextView = findViewById<TextView>(R.id.thresholdRangeValueTextView)

//        expectedTimeTextView.text = formatTime(expectedTimeSeconds)
        actualTimeTextView.text = formatTime(actualTimeSeconds)
        thresholdRangeTextView.text = "${threshold} sec: ${formatTime(lowerThresholdSeconds)} - ${formatTime(upperThresholdSeconds)}"

        // Check if the bus has passed the stop
        val distanceToStop = calculateDistance(latitude, longitude, stopLat, stopLon)
        val stopPassThreshold = 25.0  // Pass threshold (meters)

        if (distanceToStop <= stopPassThreshold) {
            Log.d("MainActivity checkScheduleStatus ✅ Stop Passed", "Resetting actual time for the next stop.")
            resetActualTime() // ✅ Reset actual time to 0 sec
            currentStopIndex++ // ✅ Move to next stop
            return  // ✅ Skip remaining checks to avoid errors
        }

        // Check if the bus is ahead, behind, or on time
        when {
            actualTimeSeconds < lowerThresholdSeconds -> {
                Log.w("MainActivity checkScheduleStatus", "🚀 Ahead by ${lowerThresholdSeconds - actualTimeSeconds} sec")
                statusTextView.text = "🚀 Ahead by ${lowerThresholdSeconds - actualTimeSeconds} sec"
                statusTextView.setTextColor(Color.RED)
            }
            actualTimeSeconds > upperThresholdSeconds -> {
                Log.w("MainActivity checkScheduleStatus", "🐢 Behind by ${actualTimeSeconds - upperThresholdSeconds} sec")
                statusTextView.text = "🐢 Behind by ${actualTimeSeconds - upperThresholdSeconds} sec"
                statusTextView.setTextColor(Color.RED)
            }
            else -> {
                Log.i("MainActivity checkScheduleStatus", "✅ On Time")
                statusTextView.text = "✅ On Time"
                statusTextView.setTextColor(Color.GREEN)
            }
        }
    }

//    /**
//     * Resets the schedule when an index out of bounds error occurs.
//     * - Resets `currentStopIndex` to 0.
//     * - Resets `simulationStartTime`.
//     * - Ensures `expectedTimeSeconds` is set to the first durationBetweenStops value.
//     */
//    private fun resetSchedule() {
//        Log.d("MainActivity resetSchedule", "🔄 Resetting schedule and actual time.")
//
//        currentStopIndex = 0 // ✅ Reset stop index
//        simulationStartTime = System.currentTimeMillis() // ✅ Reset actual time
//
//        // ✅ Ensure expected time is set to the first value from durationBetweenStops
//        val expectedTimeSeconds = if (durationBetweenStops.isNotEmpty()) {
//            (durationBetweenStops.first() * 60).toInt()
//        } else {
//            0 // Default to 0 if durationBetweenStops is empty
//        }
//
//        findViewById<TextView>(R.id.expectedTimeValueTextView).text = formatTime(expectedTimeSeconds)
//
//        Log.d("MainActivity resetSchedule", "✅ Schedule reset complete. Expected time: ${formatTime(expectedTimeSeconds)}. Starting from Stop 0.")
//    }

    /**
     * Converts seconds to mm:ss format.
     */
    private fun formatTime(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d min %02d sec", minutes, remainingSeconds)
    }


    /** 🔹 Reset actual time when the bus reaches a stop or upcoming stop changes */
    private fun resetActualTime() {
        simulationStartTime = System.currentTimeMillis()
        Log.d("MainActivity", "✅ Actual time reset to current time.")
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
//                    latitudeTextView.text = "Latitude: $newLat"
//                    longitudeTextView.text = "Longitude: $newLon"
//                    bearingTextView.text = "Bearing: $bearing°"
//                    speedTextView.text = "Speed: ${"%.2f".format(speed)} km/h"

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

        val stopPassThreshold = 25.0

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

            // **🔹 Reset actual time when a stop is passed**
            resetActualTime()

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
        val intent = Intent(this, MapActivity::class.java)
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
//                latitudeTextView.text = "Latitude:\n$latitude"
//                longitudeTextView.text = "Longitude:\n$longitude"
//                bearingTextView.text = "Bearing: $bearing°"

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
//        latitudeTextView = binding.latitudeTextView
//        longitudeTextView = binding.longitudeTextView
//        bearingTextView = binding.bearingTextView
//        speedTextView = binding.speedTextView
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
     * Retrieves default configuration values for the activity, such as latitude, longitude, bearing, and more.
     */
    @SuppressLint("LongLogTag")
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

//    /**
//     * Clears any existing bus data from the map and other UI elements.
//     */
//    private fun clearBusData() {
//        binding.map.layerManager.layers.clear() // Clear all layers
//        binding.map.invalidate()
//        markerBus.clear()
//    }

    /** Fetches the configuration data and initializes the config variable. */
//    private fun fetchConfig(callback: (Boolean) -> Unit) {
//        Log.d("MainActivity fetchConfig", "Fetching config...")
//
//        mqttManagerConfig.fetchSharedAttributes(tokenConfigData) { listConfig ->
//            runOnUiThread {
//                if (listConfig.isNotEmpty()) {
//                    config = listConfig
//                    Log.d("MainActivity fetchConfig", "✅ Config received: $config")
//                    subscribeSharedData()
//                    callback(true)
//                } else {
//                    Log.e("MainActivity fetchConfig", "❌ Failed to initialize config. Running in offline mode.")
////                    Toast.makeText(this@MainActivity, "Running in offline mode. No bus information available.", Toast.LENGTH_SHORT).show()
//                    callback(false)
//                }
//            }
//        }
//    }

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

//    /**
//     * function to convert route to the required BusStopInfo format
//     */
//    private fun convertRouteToBusStopInfo(route: List<BusRoute>): List<BusStopInfo> {
//        val result = route.mapIndexed { index, busStop ->
//            BusStopInfo(
//                latitude = busStop.latitude ?: 0.0,
//                longitude = busStop.longitude ?: 0.0,
//                busStopName = when (index) {
//                    0 -> "S/E"
//                    else -> index.toString()
//                }
//            )
//        }
//        Log.d("convertRouteToBusStopInfo", "Converted route to BusStopInfo: $result")
//        return result
//    }

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
            val stopName = if (busStopNumber == 1) "S/E" else "Stop $busStopNumber"

            val isRed = redBusStops.contains(stopName) // Check if the stop should be red
            val busStopSymbol = Helper.createBusStopSymbol(applicationContext, busStopNumber, busStops.size, isRed)

            val markerBitmap = AndroidGraphicFactory.convertToBitmap(busStopSymbol)

            val marker = org.mapsforge.map.layer.overlay.Marker(
                LatLong(stop.latitude!!, stop.longitude!!),
                markerBitmap,
                0,
                0
            )

            binding.map.layerManager.layers.add(marker)
        }
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

    /** Starts a periodic task to update the current date and time in the UI. */
    @SuppressLint("SimpleDateFormat")
    private fun startDateTimeUpdater() {
        dateTimeHandler = Handler(Looper.getMainLooper())
        dateTimeRunnable = object : Runnable {
            override fun run() {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val currentDateTime = dateFormat.format(Date())
                binding.currentTimeTextView.text = currentDateTime
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
//        mqttManager.disconnect()
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