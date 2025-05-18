package com.jason.publisher.main

import FileLogger
import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.main.model.BusRoute
import org.osmdroid.views.MapController
import java.text.SimpleDateFormat
import java.util.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.jason.publisher.main.model.BusItem
import com.jason.publisher.main.model.BusStop
import com.jason.publisher.main.model.BusStopInfo
import com.jason.publisher.main.model.BusStopWithTimingPoint
import com.jason.publisher.main.model.RouteData
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.services.LocationManager
import com.jason.publisher.main.utils.BusStopProximityManager
import com.jason.publisher.main.utils.NetworkStatusHelper
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
import com.google.android.gms.location.*
import com.jason.publisher.LocationListener
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.utils.TimeBasedMovingAverageFilterDouble
import java.lang.Math.abs

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var locationManager: LocationManager
    private lateinit var mapController: MapController
    private lateinit var dateTimeHandler: Handler
//    private lateinit var dateTimeRunnable: Runnable

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
    private var stopAddress: String = "Unknown"
    private var upcomingStopName: String = "Unknown"

    private lateinit var aidTextView: TextView
    private lateinit var latitudeTextView: TextView
    private lateinit var longitudeTextView: TextView
    private lateinit var bearingTextView: TextView
    private lateinit var speedTextView: TextView
    private lateinit var upcomingBusStopTextView: TextView
    private lateinit var scheduleStatusIcon: ImageView
    private lateinit var scheduleStatusText: TextView
    private lateinit var timingPointandStopsTextView: TextView
    private lateinit var tripEndTimeTextView: TextView

    private var routePolyline: org.mapsforge.map.layer.overlay.Polyline? = null
    private var busMarker: org.mapsforge.map.layer.overlay.Marker? = null
    private var markerBus = HashMap<String, org.mapsforge.map.layer.overlay.Marker>()

    private lateinit var simulationHandler: Handler
    private lateinit var simulationRunnable: Runnable
    private var currentRouteIndex = 0
    private var isSimulating = false
    private var simulationStartTime: Long = 0L
    private lateinit var scheduleList: List<ScheduleItem>
    private lateinit var scheduleData: List<ScheduleItem>
    private val redBusStops = mutableSetOf<String>()

    private lateinit var actualTimeHandler: Handler
    private lateinit var actualTimeRunnable: Runnable
    private lateinit var actualTimeTextView: TextView
    private lateinit var timingPointValueTextView: TextView
    private lateinit var ApiTimeValueTextView: TextView
    private lateinit var scheduleStatusValueTextView: TextView
    private lateinit var thresholdRangeValueTextView: TextView
    private var simulatedStartTime: Calendar = Calendar.getInstance()

    private var apiTimeLocked = false
    private var lockedApiTime: String? = null
    private var simulationSpeedFactor: Int = 1
    private lateinit var arriveButtonContainer: LinearLayout
    private var nextTimingPoint: String = "Unknown"
    private lateinit var currentTimeTextView: TextView
    private lateinit var currentTimeHandler: Handler
    private lateinit var currentTimeRunnable: Runnable
    private lateinit var nextTripCountdownTextView: TextView
    private var busStopRadius: Double = 50.0
    private val forceAheadStatus = false
    private var statusText  = "Please wait..."
    private var baseTimeStr  = "00:00:00"
    private var customTime  = "00:00:00"
    private var lastTimingPointStopAddress: String? = null
    // Class-level variable (initialize it to zero or a default simulation value)
    private var smoothedSpeed: Float = 0f
    // Choose an alpha value between 0 and 1: smaller alpha means slower adjustment (more smoothing)
    private val smoothingAlpha = 0.2f

    companion object {
        const val SERVER_URI = "tcp://43.226.218.97:1883"
        const val CLIENT_ID = "jasonAndroidClientId"
        const val PUB_POS_TOPIC = "v1/devices/me/telemetry"
        private const val SUB_MSG_TOPIC = "v1/devices/me/attributes/response/+"
        private const val PUB_MSG_TOPIC = "v1/devices/me/attributes/request/1"
        private const val REQUEST_PERIODIC_TIME = 1000L
        private const val PUBLISH_POSITION_TIME = 1000L
        private const val LAST_MSG_KEY = "lastMessageKey"
        private const val MSG_KEY = "messageKey"
        private const val SOUND_FILE_NAME = "notif.wav"
    }

    @SuppressLint("LongLogTag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Add logger
        FileLogger.init(this)

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
        scheduleList = intent.getSerializableExtra("FIRST_SCHEDULE_ITEM") as? List<ScheduleItem> ?: emptyList()
        scheduleData = intent.getSerializableExtra("FULL_SCHEDULE_DATA") as? List<ScheduleItem> ?: emptyList()

        Log.d("MapActivity onCreate retrieve", "Received aid: $aid")
        Log.d("MapActivity onCreate retrieve", "Received config: ${config.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received jsonString: $jsonString")
        Log.d("MapActivity onCreate retrieve", "Received route: ${route.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received stops: ${stops.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received durationBetweenStops: ${durationBetweenStops.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received busRouteData: ${busRouteData.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received scheduleList: ${scheduleList.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received scheduleData: ${scheduleData.toString()}")

        FileLogger.d("MapActivity onCreate retrieve", "Received aid: $aid")
        FileLogger.d("MapActivity onCreate retrieve", "Received config: ${config.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received jsonString: $jsonString")
        FileLogger.d("MapActivity onCreate retrieve", "Received route: ${route.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received stops: ${stops.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received durationBetweenStops: ${durationBetweenStops.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received busRouteData: ${busRouteData.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received scheduleList: ${scheduleList.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received scheduleData: ${scheduleData.toString()}")

        extractRedBusStops()

        // Initialize UI components
        initializeUIComponents()

        // Start the current time counter
//        startCurrentTimeUpdater()
        startStartTime()

        // Start the next trip countdown updater
        startNextTripCountdownUpdater()

        updateApiTime() // Ensure API time is updated at the start

        initializeTimingPoint()

        // ✅ Ensure locationManager is initialized
        locationManager = LocationManager(this)

//        aidTextView.text = "AID: $aid"

        // Set up network status UI
        NetworkStatusHelper.setupNetworkStatus(this, binding.connectionStatusTextView, binding.networkStatusIndicator)

        // Initialize the date/time updater
//        startDateTimeUpdater()

//        fetchConfig { success ->
//            if (success) {
//                getAccessToken()
        Log.d("MapActivity onCreate Token", token)
//                mqttManager = MqttManager(serverUri = TimeTableActivity.SERVER_URI, clientId = TimeTableActivity.CLIENT_ID, username = token)
        getDefaultConfigValue()
//                requestAdminMessage()
//                connectAndSubscribe()
//                Log.d("MapActivity oncreate fetchConfig config", config.toString())
//                Log.d("MapActivity oncreate fetchConfig busRoute", route.toString())
//                Log.d("MapActivity oncreate fetchConfig busStop", stops.toString())
//            } else {
//                Log.e("MapActivity onCreate", "Failed to fetch config, running in offline mode.")
//            }
//        }

        updateBusNameFromConfig()

        // Ensure `locationManager` is properly initialized before use
        locationManager.getCurrentLocation(object : LocationListener {
            override fun onLocationUpdate(location: Location) {
                latitude = location.latitude
                longitude = location.longitude
                Log.d("MapActivity onCreate Latitude", latitude.toString())
                Log.d("MapActivity onCreate Longitude", longitude.toString())

                // Update UI components with the current location
//                latitudeTextView.text = "Latitude: $latitude"
//                longitudeTextView.text = "Longitude: $longitude"
            }
        })

        // Load offline map first
        openMapFromAssets()

        // Start tracking the location and updating the marker
        startLocationUpdate()

        // Mock data to check scheduleStatusValueTextView
        if (forceAheadStatus == true) {
            Log.d("ForceAheadDebug", "Inside forceAheadStatus at 08:00:00")

            //Example
//            scheduledTimeStr: 09:10:00
//            apiTimeStr: 09:09:30
//            actualTimeStr: 09:00:00
//            predictedArrivalMillis: (09:09:30)
//            deltaSec: 30
//            statusText: Ahead by 30 sec
//
//            scheduledTimeStr: 09:10:00
//            apiTimeStr: 09:10:00
//            actualTimeStr: 09:00:00
//            predictedArrivalMillis: (09:10:00)
//            deltaSec: 0
//            statusText: On Time (0 sec)
//
//            scheduledTimeStr: 09:10:00
//            apiTimeStr: 09:10:45
//            actualTimeStr: 09:00:00
//            predictedArrivalMillis: (09:10:45)
//            deltaSec: -45
//            statusText: Behind by 45 sec


            // Manually set dummy values to simulate schedule status in advance
            timingPointValueTextView.text = "22:21:00"     // scheduledTimeStr
            ApiTimeValueTextView.text = "22:21:00"         // apiTimeStr
            customTime = "22:15:00"                    // actualTimeStr
            // result

            stopCurrentTime()
            startCustomTime(customTime)
//            startActualTimeUpdater()

            // Trigger visual change to test schedule status UI
            scheduleStatusValueTextView.text = "Calculating..."
            checkScheduleStatus()
        }

        binding.startSimulationButton.setOnClickListener {
//            startSimulation()
        }
        binding.stopSimulationButton.setOnClickListener {
            stopSimulation()
        }
        binding.backButton.setOnClickListener {
            if (speed > 5.0) {  // Treat speeds above 5 km/h as "moving"
                Toast.makeText(this, "❌ Bus must be moving slower than 5 km/h before ending the trip.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Show number pad confirmation dialog
            val numberPadDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            val numberPadView = layoutInflater.inflate(R.layout.dialog_number_pad, null)
            val numberPadInput = numberPadView.findViewById<EditText>(R.id.numberPadInput)

            numberPadDialog.setView(numberPadView)
                .setTitle("Enter Passcode")
                .setPositiveButton("Confirm") { _, _ ->
                    // Verify passcode
                    val enteredCode = numberPadInput.text.toString()
                    if (enteredCode == "0000") {
                        val intent = Intent(this, ScheduleActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this, "❌ Incorrect code. Please enter 0000.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        binding.speedUpButton.setOnClickListener {
            speedUp()
        }

        binding.slowDownButton.setOnClickListener {
            slowDown()
        }

//        binding.arriveButton.visibility = View.GONE
        binding.arriveButton.setOnClickListener {
            confirmArrival()
        }
    }

    /**
     * Starts a custom time from a hardcoded string and counts up from there.
     * Example: startCustomTime("08:11:00")
     */
    private fun startCustomTime(customTime: String) {
        val timeParts = customTime.split(":")
        if (timeParts.size != 3) {
            Log.e("MapActivity startCustomTime", "❌ Invalid time format: $customTime. Expected HH:mm:ss")
            return
        }

        // Initialize simulatedStartTime from the custom string
        simulatedStartTime.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
        simulatedStartTime.set(Calendar.MINUTE, timeParts[1].toInt())
        simulatedStartTime.set(Calendar.SECOND, timeParts[2].toInt())

        currentTimeHandler = Handler(Looper.getMainLooper())
        currentTimeRunnable = object : Runnable {
            override fun run() {
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                currentTimeTextView.text = timeFormat.format(simulatedStartTime.time)
                Log.d("MapActivity startCustomTime", "currentTimeTextView.text: ${currentTimeTextView.text}")

                // Advance time by 1 second per tick
                simulatedStartTime.add(Calendar.SECOND, 1)

                // Update schedule status based on the new simulated time
                scheduleStatusValueTextView.text = "Calculating..."
                checkScheduleStatus()

                currentTimeHandler.postDelayed(this, 1000) // Update every second
            }
        }

        currentTimeHandler.post(currentTimeRunnable)
    }

//    /**
//     * setter to adjust current time
//     */
//    private fun setSimulatedCurrentTime(hour: Int, minute: Int, second: Int) {
//        simulatedStartTime.set(Calendar.HOUR_OF_DAY, hour)
//        simulatedStartTime.set(Calendar.MINUTE, minute)
//        simulatedStartTime.set(Calendar.SECOND, second)
//    }

    /**
     * Starts the simulated clock using the startTime of the first ScheduleItem in scheduleList.
     */
    private fun startStartTime() {
        if (scheduleList.isEmpty()) {
            Log.e("MapActivity", "❌ scheduleList is empty. Cannot start start time updater.")
            return
        }

        // Extract the first schedule start time (e.g., "11:15")
        val startTimeStr = scheduleList.first().startTime
        val timeParts = startTimeStr.split(":")
        if (timeParts.size != 2) {
            Log.e("MapActivity", "❌ Invalid start time format in scheduleList: $startTimeStr")
            return
        }

        // Initialize simulatedStartTime to the scheduled start time
        simulatedStartTime.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
        simulatedStartTime.set(Calendar.MINUTE, timeParts[1].toInt())
        simulatedStartTime.set(Calendar.SECOND, 0)

        currentTimeHandler = Handler(Looper.getMainLooper())
        currentTimeRunnable = object : Runnable {
            override fun run() {
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                currentTimeTextView.text = timeFormat.format(simulatedStartTime.time)
                Log.d("MapActivity startStartTime", "Current simulated time: ${currentTimeTextView.text}")

                // Advance the simulated time by 1 second per tick
                simulatedStartTime.add(Calendar.SECOND, 1)

                // Schedule the next update after 1 second
                currentTimeHandler.postDelayed(this, 1000)
            }
        }

        currentTimeHandler.post(currentTimeRunnable)
    }

    /**
     * function to calculate and display the remaining time until the next scheduled run
     */
    private fun startNextTripCountdownUpdater() {
        Log.d("MapActivity startNextTripCountdownUpdater", "scheduleList: $scheduleList")
        Log.d("MapActivity startNextTripCountdownUpdater", "scheduleData: $scheduleData")
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                // Use the simulated clock (instead of the real system time)
                val currentTime = simulatedStartTime.clone() as Calendar
                Log.d("MapActivity startNextTripCountdownUpdater", "Current simulated time: ${currentTime.time}")

                // Retrieve the next schedule start time using your helper function.
                val nextTripStartTime = getNextScheduleStartTime()

                if (nextTripStartTime != null) {
                    // Parse the nextTripStartTime (e.g., "12:00") into a Calendar object.
                    val timeParts = nextTripStartTime.split(":").map { it.toInt() }
                    val nextTripCalendar = Calendar.getInstance().apply {
                        // Set date to match the current simulation time
                        set(Calendar.YEAR, currentTime.get(Calendar.YEAR))
                        set(Calendar.MONTH, currentTime.get(Calendar.MONTH))
                        set(Calendar.DAY_OF_MONTH, currentTime.get(Calendar.DAY_OF_MONTH))
                        // Set the schedule start time from the string
                        set(Calendar.HOUR_OF_DAY, timeParts[0])
                        set(Calendar.MINUTE, timeParts[1])
                        set(Calendar.SECOND, 0)
                        // If the scheduled time has already passed for this day, assume the next trip is tomorrow:
                        if (timeInMillis <= currentTime.timeInMillis) {
                            add(Calendar.DATE, 1)
                        }
                    }

                    val timeDiffMillis = nextTripCalendar.timeInMillis - currentTime.timeInMillis
                    Log.d("MapActivity startNextTripCountdownUpdater", "timeDiffMillis: $timeDiffMillis")
                    if (timeDiffMillis > 0) {
                        val minutesRemaining = (timeDiffMillis / 1000 / 60).toInt()
                        val secondsRemaining = ((timeDiffMillis / 1000) % 60).toInt()
                        runOnUiThread {
                            nextTripCountdownTextView.text = "Next run in: $minutesRemaining mins $secondsRemaining seconds"
                        }
                    } else {
                        runOnUiThread {
                            nextTripCountdownTextView.text = "You are late for the next run"
                        }
                    }
                } else {
                    runOnUiThread {
                        nextTripCountdownTextView.text = "No more scheduled trips for today"
                    }
                }
                handler.postDelayed(this, 1000) // Update every second
            }
        }
        handler.post(runnable)
    }

    /**
     * function to update the currentTimeTextView
     */
    private fun startCurrentTimeUpdater() {
        currentTimeHandler = Handler(Looper.getMainLooper())
        currentTimeRunnable = object : Runnable {
            override fun run() {
                val currentTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val currentTimeString = currentTimeFormat.format(Date())  // Display system time directly

                currentTimeTextView.text = currentTimeString
                currentTimeHandler.postDelayed(this, 1000) // Update every second
            }
        }
        currentTimeHandler.post(currentTimeRunnable)
    }

    /**
     * Function to remove current time call back
     */
    fun stopCurrentTime() {
        currentTimeRunnable?.let { currentTimeHandler.removeCallbacks(it) }
    }

    /**
     * Mark a bus stop as "arrived" and prevent duplicate arrivals.
     */
    private var isManualMode = false
    @SuppressLint("LongLogTag")
    private fun confirmArrival() {
        Log.d("MapActivity confirmArrival", "🚨 ConfirmArrival Triggered - Starting Process")

        val startingPoint = busRouteData?.first()?.startingPoint?.let { sp ->
            BusStop(latitude = sp.latitude, longitude = sp.longitude, address = sp.address)
        }

        if (stops.isEmpty() || route.isEmpty()) {
            Log.e("MapActivity confirmArrival", "❌ No stops or route data available.")
            return
        }

        Log.d("MapActivity confirmArrival", "🔎 Starting nearest route point search...")
        var nearestIndex = findNearestBusRoutePoint(latitude, longitude)
        Log.d("MapActivity confirmArrival", "✅ Nearest Route Point Found at Index: $nearestIndex")

        var nearestStop: BusStop? = null

        for (stopIndex in stops.indices) {
            val stop = stops[stopIndex]

            val routePointIndex = route.indexOfFirst {
                it.latitude == stop.latitude && it.longitude == stop.longitude
            }

            Log.d("MapActivity confirmArrival", "🔎 Checking Stop: ${stop.address}")
            Log.d("MapActivity confirmArrival", "   Route Point Index: $routePointIndex | Nearest Index: $nearestIndex")

            if (routePointIndex != -1 && routePointIndex <= nearestIndex) {
                for (i in 0..stopIndex) {
                    if (!passedStops.contains(stops[i])) {
                        passedStops.add(stops[i])
                        Log.d("MapActivity confirmArrival", "✅ Passed Stop Added: ${stops[i].address}")
                    }
                }

                if (nearestStop == null || routePointIndex < nearestIndex) {
                    nearestStop = stop
                    Log.d("MapActivity confirmArrival", "➡️ Nearest Stop Updated to: ${nearestStop.address}")
                }

                if (passedStops.contains(stop)) continue
            } else {
                Log.d("MapActivity confirmArrival", "❌ Stop Skipped: ${stop.address}")
                break
            }
        }

        if (nearestStop != null) {
            latitude = nearestStop.latitude ?: latitude
            longitude = nearestStop.longitude ?: longitude
            nearestStop.address = findAddressByCoordinates(latitude, longitude)
            hasPassedFirstStop = true

            if (nearestStop.address != null) {
                stopAddress = nearestStop.address.toString()
                upcomingBusStopTextView.text = "$stopAddress"
                Log.d("MapActivity confirmArrival", "✅ Updated Address: $stopAddress")
            } else {
                Log.w("MapActivity confirmArrival", "⚠️ Address Not Found for Nearest Stop")
            }
        } else {
            Log.w("MapActivity confirmArrival", "⚠️ No Nearest Stop Found")
        }

        drawDetectionZones(stops)

        // 🔹 Ensure schedule status updates correctly
        Log.d("MapActivity confirmArrival", "🔄 Updating API Time...")
        var busStopIndex = getBusStopIndex(latitude, longitude, stops)
        currentStopIndex = busStopIndex

        var totalDurationUntilArrive =
            busStopIndex?.let { getTotalDurationUpToIndex(it, durationBetweenStops) }

        val firstSchedule = scheduleList.first()
        val startTimeParts = firstSchedule.startTime.split(":")
        if (startTimeParts.size != 2) return

        val startCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startTimeParts[0].toInt())
            set(Calendar.MINUTE, startTimeParts[1].toInt())
            set(Calendar.SECOND, 0)
        }

        if (totalDurationUntilArrive == null) {
            Log.d("MapActivity updateApiTime", "Upcoming bus stop not scheduled. Skipping API update.")
            // If we already computed a final value before, do not override.
            return
        }
        Log.d("MapActivity updateApiTime", "Total duration in minutes: $totalDurationUntilArrive")

        // Add the duration (in seconds) to the start time.
        val additionalSeconds = (totalDurationUntilArrive * 60).toInt()
        startCalendar.add(Calendar.SECOND, additionalSeconds)

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        lockedApiTime = timeFormat.format(startCalendar.time)
        ApiTimeValueTextView.text = lockedApiTime
        updateApiTime()

        Log.d("MapActivity confirmArrival", "🔄 Initializing Timing Point...")
        initializeTimingPoint()

        apiTimeLocked = false    // Unlock the API time to allow updates
        Log.d("MapActivity confirmArrival", "🔓 API Time Unlocked")

        scheduleStatusValueTextView.text = "Calculating..."
        checkScheduleStatus()    // Immediately refresh the schedule status
        Log.d("MapActivity confirmArrival", "✅ Schedule Status Checked")

//        showCustomToast("Confirm Arrival at Latitude: ${latitude}, Longitude: ${longitude}, Address: ${stopAddress}")
        Log.d("MapActivity confirmArrival", "✅ Arrival confirmed at: ${stopAddress}")

        startLocationUpdate()   // ✅ Continue marker updates
        Log.d("MapActivity confirmArrival", "✅ Tracking resumed after arrival confirmation.")

        Toast.makeText(this@MapActivity, "✅ Tracking resumed after arrival confirmation.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Finds the index of the bus stop that exactly matches the given latitude and longitude.
     *
     * @param latitude The current latitude.
     * @param longitude The current longitude.
     * @param stops The list of bus stops (each with a latitude and longitude).
     * @return The index of the matching bus stop or -1 if no match is found.
     */
    fun getBusStopIndex(latitude: Double, longitude: Double, stops: List<BusStop>): Int {
        for ((index, stop) in stops.withIndex()) {
            if (latitude == stop.latitude && longitude == stop.longitude) {
                return index
            }
        }
        return -1 // Return -1 if no match is found
    }

    /**
     * Calculates the total duration from index 0 to the given index (inclusive).
     */
    fun getTotalDurationUpToIndex(index: Int, durationList: List<Double>): Double {
        if (index < 0 || index >= durationList.size) return 0.0
        return durationList.take(index + 1).sum()
    }

    /**
     * Finds the address for a given latitude and longitude from the busRouteData.
     */
    fun findAddressByCoordinates(latitude: Double, longitude: Double): String? {
        // Check starting point first
        busRouteData?.forEach { routeData ->
            if (abs(routeData.startingPoint.latitude - latitude) < 0.0001 &&
                abs(routeData.startingPoint.longitude - longitude) < 0.0001
            ) {
                return routeData.startingPoint.address
            }

            // Iterate through next points
            routeData.nextPoints.forEach { nextPoint ->
                if (abs(nextPoint.latitude - latitude) < 0.0001 &&
                    abs(nextPoint.longitude - longitude) < 0.0001
                ) {
                    return nextPoint.address
                }

                // Iterate through routeCoordinates inside each nextPoint
                nextPoint.routeCoordinates.forEach { coordinates ->
                    if (abs(coordinates[1] - latitude) < 0.0001 &&
                        abs(coordinates[0] - longitude) < 0.0001
                    ) {
                        return nextPoint.address
                    }
                }
            }
        }
        return null // Address not found
    }

    /**
     * extract first schedule item of bus stop to be marked red
     */
    @SuppressLint("LongLogTag")
    private fun extractRedBusStops() {
        redBusStops.clear()
        if (scheduleList.isNotEmpty()) {
            val firstSchedule = scheduleList.first()
            Log.d("TestMapActivity extractRedBusStops firstSchedule", "$firstSchedule")

            val stops = firstSchedule.busStops.mapNotNull { it.address }
            redBusStops.addAll(stops)
            Log.d("TestMapActivity extractRedBusStops stops", "$stops")
        }
        Log.d("TestMapActivity extractRedBusStops", "Updated Red bus stops: $redBusStops")
    }

    /** Updates bus name if AID matches a config entry */
    @SuppressLint("LongLogTag")
    private fun updateBusNameFromConfig() {
        if (config.isNullOrEmpty()) return // No config available

        val matchingBus = config!!.find { it.aid == aid } // Find matching AID

        if (matchingBus != null) {
            busname = matchingBus.bus
            runOnUiThread {
//                binding.busNameTextView.text = "Bus Name: $busname"
            }
            Log.d("MapActivity updateBusNameFromConfig", "✅ Bus name updated: $busname for AID: $aid")
        } else {
            Log.e("MapActivity updateBusNameFromConfig", "❌ No matching bus found for AID: $aid")
        }
    }

    /**
     * Add this helper function to convert a time string (e.g. "08:11") to minutes since midnight.
     */
    private fun convertTimeToMinutes(time: String): Int {
        val parts = time.split(":").map { it.toInt() }
        return parts[0] * 60 + parts[1]
    }

    /**
     * Call this function when the simulation finishes.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun showSummaryDialog() {
        // Flatten scheduleData into a List<ScheduleItem>
        val flatSchedule = (scheduleData as? List<Any> ?: emptyList()).flatMap { element ->
            when (element) {
                is ScheduleItem -> listOf(element)
                is List<*> -> element.filterIsInstance<ScheduleItem>()
                else -> emptyList()
            }
        }
        val messageText = if (flatSchedule.size < 2) {
            "You have completed last run of the day."
        } else {
            val nextTrip = flatSchedule[1]
            val nextStartMinutes = convertTimeToMinutes(nextTrip.startTime)
            val currentMinutes = simulatedStartTime.get(Calendar.HOUR_OF_DAY) * 60 +
                    simulatedStartTime.get(Calendar.MINUTE)
            val restTotalMinutes = if (nextStartMinutes > currentMinutes) nextStartMinutes - currentMinutes else 0
            val restHours = restTotalMinutes / 60
            val restMinutes = restTotalMinutes % 60
            "Trip complete! You have $restHours hour(s) and $restMinutes minute(s) rest before your next trip, which starts at ${nextTrip.startTime}:00."
        }
        // late notification: You are $restMinutes minute(s) for your next trip. Please notify operations of late departure.
        // break (lunch break):  You are $restMinutes minute(s) for your break time.
        // end of shift: You have completed last run of the day.
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Trip Completed")
            .setMessage(messageText)
            .setPositiveButton("View Next Trip") { dialog, _ ->
                dialog.dismiss()
                startActivity(Intent(this, ScheduleActivity::class.java))
            }
            .create()
        dialog.show()

        // Customize the dialog's background and text colors
        dialog.window?.setBackgroundDrawableResource(R.color.colorMain)
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(
            resources.getColor(R.color.white, null)
        )
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(
            resources.getColor(R.color.white, null)
        )
        // Style the positive button similar to your simulation button
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            setBackgroundTintList(ColorStateList.valueOf(resources.getColor(R.color.purple_400, null)))
            setTextColor(resources.getColor(R.color.white, null))
        }
    }

    /**
     * increase speed factor
     */
    @SuppressLint("LongLogTag")
    private fun speedUp() {
        simulationSpeedFactor++ // Increase by 1 second per tick
        Log.d("MapActivity SpeedControl", "Speed Up: simulationSpeedFactor is now $simulationSpeedFactor")
    }

    /**
     * decrease speed factor
     */
    @SuppressLint("LongLogTag")
    private fun slowDown() {
        // Ensure never go below 0.
        if (simulationSpeedFactor > 0) {
            simulationSpeedFactor--
        }
        Log.d("MapActivity SpeedControl", "Slow Down: simulationSpeedFactor is now $simulationSpeedFactor")
    }

//    /**
//     * Starts actual time from schedule
//     */
//    private fun startActualTimeUpdater() {
//        if (scheduleList.isNotEmpty()) {
//            val startTimeStr = scheduleList.first().startTime  // e.g. "08:00"
//            val timeParts = startTimeStr.split(":")
//            if (timeParts.size == 2) {
//                simulatedStartTime.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
//                simulatedStartTime.set(Calendar.MINUTE, timeParts[1].toInt())
//                simulatedStartTime.set(Calendar.SECOND, 0)
//            }
//        }
//
//        actualTimeHandler = Handler(Looper.getMainLooper())
//        actualTimeRunnable = object : Runnable {
//            override fun run() {
//                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
//                actualTimeTextView.text = timeFormat.format(simulatedStartTime.time)
//
//                // Always advance time by 1 second per tick (simulate real clock)
//                simulatedStartTime.add(Calendar.SECOND, 1)
//
//                // Update schedule status based on the new simulated time
//                scheduleStatusValueTextView.text = "Calculating..."
//                checkScheduleStatus()
//
//                actualTimeHandler.postDelayed(this, 1000)
//            }
//        }
//        actualTimeHandler.post(actualTimeRunnable)
//    }

//    /** Stops actual time */
//    private fun stopActualTimeUpdater() {
//        actualTimeHandler.removeCallbacks(actualTimeRunnable)
//    }

    /**
     * Checks and updates the bus schedule status for the upcoming red timing point.
     *
     * The function calculates the predicted arrival time based on the current simulation speed
     * and the distance from the bus’s current location to the next red timing point, then compares
     * this value against the expected timing point arrival time shown in the UI.
     *
     * Key Steps:
     * 1. **Initial Validation and Base Time Determination:**
     *    - If in force-ahead mode and the first stop has not yet been passed, displays "Please wait...".
     *    - Sets a base time:
     *         - If forceAheadStatus is true, uses a custom time.
     *         - Otherwise, uses the start time from the first schedule.
     *
     * 2. **Extracting and Adjusting Timing Values:**
     *    - Retrieves the scheduled timing point from the UI (via `timingPointValueTextView`).
     *    - Extracts the API-based scheduled arrival time from `ApiTimeValueTextView` and, when
     *      necessary (i.e. if the upcoming stop is the first bus stop and additional duration applies),
     *      adjusts it to account for the initial stop duration.
     *
     * 3. **Determining the Next Red Timing Point:**
     *    - Searches for the next bus stop designated as a “red” timing point (used as a key reference).
     *    - If none is found after the current stop, falls back to using the final bus stop.
     *
     * 4. **Distance and Time Calculations:**
     *    - **d1:** Computes the distance (in meters) from the current location to the identified red timing point.
     *    - **d2:** Sums the distances along the route from the start to the red timing point.
     *         - If d2 equals zero, the function logs an error and exits.
     *    - **t2:** Calculates the total scheduled travel time (in seconds) from the base time until the red timing point,
     *         using the API-provided schedule.
     *
     * 5. **Predicted Arrival Estimation:**
     *    - Determines the effective speed (in m/s) from the current smoothed speed, applying a minimum threshold
     *      (to avoid division by zero).
     *    - **t1:** Estimates the remaining travel time to the red timing point as `t1 = d1 / effectiveSpeed`.
     *    - Computes the predicted arrival time by adding t1 (in seconds) to the simulation start time.
     *
     * 6. **Status Comparison and UI Update:**
     *    - **deltaSec:** Calculates the time difference (in seconds) between the timing point’s displayed time
     *      (parsed from `timingPointValueTextView`) and the predicted arrival time.
     *    - Determines the schedule status based on deltaSec:
     *         - ≥120 sec → "Very Ahead"
     *         - 1 to 119 sec → "Slightly Ahead"
     *         - -179 to 0 sec → "On Time"
     *         - -299 to -180 sec → "Slightly Behind"
     *         - ≤ -300 sec → "Very Behind"
     *    - Updates the UI:
     *         - Sets the appropriate status text.
     *         - Changes text color and icon based on how early or late the bus is predicted to be.
     *
     * **Notes:**
     * - The API-based scheduled arrival time (apiTime) is used to calculate the overall journey time (t2),
     *   while the timing point value from the UI is used for the final status comparison.
     * - Lower speeds (or a speed near zero) extend t1, making the bus appear behind schedule; higher speeds reduce t1,
     *   showing the bus as ahead.
     * - If the upcoming stop is the first bus stop and additional duration applies, the API time is adjusted accordingly.
     */
    @SuppressLint("LongLogTag")
    private fun checkScheduleStatus() {
        // If using mock data and first stop hasn't been passed, show "Please wait..."
        if (forceAheadStatus && !hasPassedFirstStop) {
            runOnUiThread {
                scheduleStatusValueTextView.text = "Please wait..."
            }
            return
        }

        if (scheduleList.isEmpty()) return

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        if (latitude == 0.0 && longitude == 0.0) {
            Log.w("checkScheduleStatus", "Skipping status check: Invalid location (0.0, 0.0)")
            return
        }

        try {
            val scheduledTimeStr = timingPointValueTextView.text.toString()
            val timingPointTime = parseTimeToday(scheduledTimeStr)

            if (forceAheadStatus) {
                baseTimeStr = customTime
                Log.d("MapActivity checkScheduleStatus", "baseTimeStr: ${baseTimeStr}")
            } else {
                baseTimeStr = scheduleList.first().startTime + ":00"
                Log.d("MapActivity checkScheduleStatus", "baseTimeStr: ${baseTimeStr}")
            }
            val baseTime = parseTimeToday(baseTimeStr)

            var apiTimeStr = ApiTimeValueTextView.text.toString()
            val firstAddress = scheduleList.firstOrNull()?.busStops?.firstOrNull()?.address

            if (upcomingStopName == firstAddress && currentStopIndex > 0 && currentStopIndex - 1 < durationBetweenStops.size) {
                val durationToFirstTimingPoint = durationBetweenStops[currentStopIndex - 1]
                val firstSchedule = scheduleList.first()
                val startTimeParts = firstSchedule.startTime.split(":")
                if (startTimeParts.size != 2) return
                val adjustedCalendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, startTimeParts[0].toInt())
                    set(Calendar.MINUTE, startTimeParts[1].toInt())
                    set(Calendar.SECOND, 0)
                    add(Calendar.SECOND, (durationToFirstTimingPoint * 60).toInt())
                }

                val adjustedApiTime = timeFormat.format(adjustedCalendar.time)
                runOnUiThread {
                    ApiTimeValueTextView.text = adjustedApiTime
                }
                apiTimeStr = adjustedApiTime
            }

            val apiTime = parseTimeToday(apiTimeStr)
            val actualTimeStr = timeFormat.format(simulatedStartTime.time)
            val actualTime = simulatedStartTime.time

            // ✅ Find next stop that is a red timing point
            var redStopIndex = stops.indexOfFirst { stop ->
                redBusStops.contains(stop.address ?: "") &&
                        stops.indexOf(stop) >= currentStopIndex
            }

// 🔁 Fallback: If no red timing point found, use last stop instead
            if (redStopIndex == -1) {
                Log.d("checkScheduleStatus", "⚠️ No red timing point found after index $currentStopIndex, using final stop as fallback.")
                redStopIndex = stops.lastIndex
            }

            val redStop = stops[redStopIndex]
            val stopLat = redStop.latitude!!
            val stopLon = redStop.longitude!!

            // --- 1. Distance from current location to red timing point (d1) ---
            val d1 = calculateDistance(latitude, longitude, stopLat, stopLon)

            // --- 2. Total distance from route start to this red timing point (d2) ---
            val upcomingIndex = route.indexOfLast {
                calculateDistance(it.latitude!!, it.longitude!!, stopLat, stopLon) < 30.0
            }.coerceAtLeast(1)

            val d2 = (0 until upcomingIndex).sumOf { i ->
                val p1 = route[i]
                val p2 = route[i + 1]
                calculateDistance(p1.latitude!!, p1.longitude!!, p2.latitude!!, p2.longitude!!)
            }

            if (d2 == 0.0) {
                Log.e("checkScheduleStatus", "❌ d2 (total distance) is 0. Cannot compute estimated time.")
                return
            }

            // --- 3. Total time from start to red timing point in seconds (t2) ---
            val t2 = ((apiTime.time - baseTime.time) / 1000).toDouble()

            // --- 4. Estimate time to arrival from current position (t1) ---
            val minSpeedMps = 0.1  // Avoid division by zero
            val effectiveSpeed = if (smoothedSpeed / 3.6 < minSpeedMps) minSpeedMps else smoothedSpeed / 3.6
            val t1 = d1 / effectiveSpeed  // d1 is the distance to the red stop in meters

            val predictedArrival = Calendar.getInstance().apply {
                time = simulatedStartTime.time
                add(Calendar.SECOND, t1.toInt())
            }

            val predictedArrivalStr = timeFormat.format(predictedArrival.time)

            // --- 5. Compare predicted arrival with Timing Point ---
            val deltaSec = ((timingPointTime.time - predictedArrival.time.time) / 1000).toInt()

            val statusText = when {
                deltaSec >= 120 -> "Very Ahead (~${deltaSec}s early)"
                deltaSec in 1..119 -> "Slightly Ahead (~${deltaSec}s early)"
                deltaSec in -179..0 -> "On Time (~${-deltaSec}s on time)"
                deltaSec in -299..-180 -> "Slightly Behind (~${-deltaSec}s late)"
                deltaSec <= -300 -> "Very Behind (~${-deltaSec}s late)"
                else -> "Unknown"
            }

            val symbolRes = when {
                deltaSec >= 120 -> R.drawable.ic_schedule_very_ahead
                deltaSec in 1..119 -> R.drawable.ic_schedule_slightly_ahead
                deltaSec in -179..0 -> R.drawable.ic_schedule_on_time
                deltaSec in -299..-180 -> R.drawable.ic_schedule_slightly_behind
                deltaSec <= -300 -> R.drawable.ic_schedule_very_behind
                else -> R.drawable.ic_schedule_on_time
            }

            val colorRes = when {
                deltaSec >= 120 -> R.color.blind_red            // Very Ahead
                deltaSec in 1..119 -> R.color.blind_light_orange     // Slightly Ahead
                deltaSec in -179..0 -> R.color.blind_cyan       // On Time
                deltaSec in -299..-180 -> R.color.blind_orange   // Slightly Behind
                deltaSec <= -300 -> R.color.blind_orange          // Very Behind
                else -> R.color.blind_cyan
            }

            runOnUiThread {
                scheduleStatusValueTextView.text = statusText
                scheduleStatusValueTextView.setTextColor(ContextCompat.getColor(this@MapActivity, colorRes))
                findViewById<ImageView>(R.id.scheduleAheadIcon).setImageResource(symbolRes)
            }

            FileLogger.d("MapActivity checkScheduleStatus", "======= Schedule Status Debug =======")
            FileLogger.d("MapActivity checkScheduleStatus", "Current Lat: $latitude, Lng: $longitude")
            FileLogger.d("MapActivity checkScheduleStatus", "Upcoming Stop: $stopAddress")
            Log.d("MapActivity checkScheduleStatus", "Upcoming Stop UI Text: ${upcomingBusStopTextView.text}")
            if (currentStopIndex in stops.indices) {
                FileLogger.d("MapActivity checkScheduleStatus", "Current Stop (index $currentStopIndex): ${stops[currentStopIndex].address}")
            } else {
                FileLogger.d("MapActivity checkScheduleStatus", "Current Stop not available; currentStopIndex: $currentStopIndex, stops count: ${stops.size}")
            }
            FileLogger.d("MapActivity checkScheduleStatus", "Red Stop Index: $redStopIndex")
            FileLogger.d("MapActivity checkScheduleStatus", "Red Stop Name: ${redStop.address}")
            FileLogger.d("MapActivity checkScheduleStatus", "effectiveSpeed (km/h): $effectiveSpeed, effectiveSpeed (m/s): ${effectiveSpeed / 3.6}")
            FileLogger.d("MapActivity checkScheduleStatus", "Distance to Red Stop (d1): $d1 meters")
            FileLogger.d("MapActivity checkScheduleStatus", "Total Distance (d2): $d2 meters")
            Log.d("MapActivity checkScheduleStatus", "Total Time (t2): $t2 seconds")
            FileLogger.d("MapActivity checkScheduleStatus", "Estimated Time Remaining (t1 = d1 / effectiveSpeed): $t1 seconds")
            FileLogger.d("MapActivity checkScheduleStatus", "Predicted Arrival: $predictedArrivalStr")
            FileLogger.d("MapActivity checkScheduleStatus", "API Time: $apiTimeStr")
            FileLogger.d("MapActivity checkScheduleStatus", "Actual Time: $actualTimeStr")
            FileLogger.d("MapActivity checkScheduleStatus", "Delta to Timing Point: $deltaSec seconds")
            FileLogger.d("MapActivity checkScheduleStatus", "Status: $statusText")
//            showCustomToastBottom("Latitude: ${latitude}, Longitude: ${longitude}, effectiveSpeed: ${effectiveSpeed}, Bearing: ${bearing}, Distance to Red Stop (d1): $d1 meters, Total Distance (d2): $d2 meters, Total Time (t2): $t2 seconds, Estimated Time Remaining (t1 = d1 / effectiveSpeed): $t1 seconds, Predicted Arrival: $predictedArrivalStr, API Time: $apiTimeStr, Actual Time: $actualTimeStr, Delta to Timing Point: $deltaSec seconds")
            overrideLateStatusForNextSchedule()
        } catch (e: Exception) {
            Log.e("MapActivity checkScheduleStatus", "Error: ${e.localizedMessage}")
        }
    }

    /**
     * Override the schedule status text with a late-for-next-run message if the difference between
     * the next schedule's start time and the predicted arrival time at the final bus stop (predictedArrivalLastStop)
     * is within the range [-86400, 300] seconds.
     *
     * Calculation details:
     * - First, the predicted arrival at the final bus stop is computed (using variable names ending with LastStop).
     * - Then, the next schedule start time (converted to full HH:mm:ss) is parsed.
     * - The difference deltaNextSec = nextScheduleStartTime - predictedArrival is obtained in seconds.
     * - If deltaNextSec is negative, then the override value is computed as (-deltaNextSec) + 300.
     * - The status text is then overridden with:
     *      "Late for next run by <overrideValue>s"
     */
    @SuppressLint("LongLogTag")
    private fun overrideLateStatusForNextSchedule() {
        val logTag = "TestMapActivity checkScheduleStatus"
        // Set up time format.
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        // Retrieve the scheduled final stop time from the current schedule's endTime.
        val scheduledTimeForFinalStopStr = scheduleList.first().endTime + ":00"
        val finalStopScheduledTime = parseTimeToday(scheduledTimeForFinalStopStr)
        Log.d(logTag, "Final stop scheduled time: $scheduledTimeForFinalStopStr")

        // Base time is the schedule's start time.
        val baseTimeStr = scheduleList.first().startTime + ":00"
        val baseTime = parseTimeToday(baseTimeStr)
        Log.d(logTag, "Base time: $baseTimeStr")

        // Use the final bus stop (last element in the stops list).
        val finalStop = stops.last()
        val stopLat = finalStop.latitude!!
        val stopLon = finalStop.longitude!!
        Log.d(logTag, "Final stop coordinates: lat=$stopLat, lon=$stopLon")

        // --- Compute distance from current position to final stop (d1) ---
        val d1 = calculateDistance(latitude, longitude, stopLat, stopLon)
        Log.d(logTag, "d1 (distance current to final stop): $d1 meters")

        // --- Calculate the total distance (d2) along the route to near the final stop ---
        val finalStopRouteIndex = route.indexOfLast {
            calculateDistance(it.latitude!!, it.longitude!!, stopLat, stopLon) < 30.0
        }.coerceAtLeast(1)
        val d2 = (0 until finalStopRouteIndex).sumOf { i ->
            val p1 = route[i]
            val p2 = route[i + 1]
            calculateDistance(p1.latitude!!, p1.longitude!!, p2.latitude!!, p2.longitude!!)
        }
        Log.d(logTag, "d2 (total route distance to final stop): $d2 meters")
        if (d2 == 0.0) {
            Log.e(logTag, "Total route distance is zero; cannot compute predicted arrival.")
            return
        }

        // --- Compute total scheduled time in seconds (t2) from start to final stop ---
        val t2 = ((finalStopScheduledTime.time - baseTime.time) / 1000).toDouble()
        Log.d(logTag, "t2 (total scheduled time to final stop): $t2 seconds")

        // --- Estimate time to final stop from current position (t1) ---
        val speedMetersPerSec = speed / 3.6
        val t1 = d1 / speedMetersPerSec
        Log.d(logTag, "t1 (estimated time remaining): $t1 seconds")

        // Compute predicted arrival at final stop.
        val predictedArrival = Calendar.getInstance().apply {
            time = simulatedStartTime.time
            add(Calendar.SECOND, t1.toInt())
        }
        val predictedArrivalLastStop = timeFormat.format(predictedArrival.time)
        Log.d(logTag, "Predicted arrival at final stop: $predictedArrivalLastStop")

        // Retrieve the next schedule start time.
        val nextScheduleStartRaw = getNextScheduleStartTime()
        if (nextScheduleStartRaw == null) {
            Log.d(logTag, "No next schedule start time available; skipping override.")
            return
        }
        // Append ":00" for seconds.
        val nextScheduleStartStr = nextScheduleStartRaw + ":00"
        val nextScheduleStartTime = parseTimeToday(nextScheduleStartStr)
        Log.d(logTag, "Next schedule start time: $nextScheduleStartStr")

        // Compute delta in seconds: (next schedule start time - predicted arrival time).
        val deltaNextSec = ((nextScheduleStartTime.time - predictedArrival.time.time) / 1000).toInt()
        Log.d(logTag, "Delta (next schedule - predicted arrival): $deltaNextSec seconds")

        // Check if delta falls within the override range [-86400, 300].
        if (deltaNextSec in -86400..300) {
            // If the delta is negative, compute override value as (-delta) + 300.
            val overrideValue = if (deltaNextSec < 0) (-deltaNextSec) + 300 else deltaNextSec
            val overrideStatusText = "Late for next run by ${overrideValue}s"
            runOnUiThread {
                // Set status text.
                scheduleStatusValueTextView.text = overrideStatusText
                // Set text color to blind_red.
                scheduleStatusValueTextView.setTextColor(ContextCompat.getColor(this@MapActivity,
                    R.color.blind_red
                ))
                // Set the symbol to ic_schedule_late.
                findViewById<ImageView>(R.id.scheduleAheadIcon).setImageResource(R.drawable.ic_schedule_late)
            }
            Log.d(logTag, "Overridden status text: \"$overrideStatusText\" (overrideValue: $overrideValue)")
        } else {
            Log.d(logTag, "Delta not within override range; no status override applied.")
        }
    }

    /**
     * Returns the start time for the next schedule.
     * Assumes that the scheduleData list is sorted chronologically.
     */
    @SuppressLint("LongLogTag")
    private fun getNextScheduleStartTime(): String? {
        // Flatten scheduleData in case it's a nested list.
        val flatSchedule = (scheduleData as? List<Any> ?: emptyList()).flatMap { element ->
            when (element) {
                is ScheduleItem -> listOf(element)
                is List<*> -> element.filterIsInstance<ScheduleItem>()
                else -> emptyList()
            }
        }
        // Now check if we have a second schedule item.
        val nextStartTime = if (flatSchedule.size > 1) flatSchedule[1].startTime else null
        Log.d("TestMapActivity getNextScheduleStartTime", "Next schedule start time: ${nextStartTime ?: "None"}")
        return nextStartTime
    }

    /**
     * Set the same base date for all Date
     */
    private fun parseTimeToday(timeStr: String): Date {
        val parts = timeStr.split(":")
        if (parts.size != 3) return Date()

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, parts[0].toInt())
            set(Calendar.MINUTE, parts[1].toInt())
            set(Calendar.SECOND, parts[2].toInt())
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.time
    }

//    /**
//     * Function to normalize all times to the same date
//     */
//    fun normalizeTimeToToday(original: Date): Calendar {
//        val now = Calendar.getInstance()
//        val cal = Calendar.getInstance()
//        cal.time = original
//        cal.set(Calendar.YEAR, now.get(Calendar.YEAR))
//        cal.set(Calendar.MONTH, now.get(Calendar.MONTH))
//        cal.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
//        return cal
//    }

//    /**
//     * Converts seconds to mm:ss format.
//     */
//    private fun formatSecondsAsTime(seconds: Int): String {
//        val sign = if (seconds < 0) "-" else ""
//        val absSeconds = Math.abs(seconds)
//        val hours = absSeconds / 3600
//        val minutes = (absSeconds % 3600) / 60
//        val secs = absSeconds % 60
//        return String.format("%s%02d:%02d:%02d", sign, hours, minutes, secs)
//    }

    /** 🔹 Reset actual time when the bus reaches a stop or upcoming stop changes */
    private fun resetActualTime() {
        simulationStartTime = System.currentTimeMillis()
        Log.d("MapActivity", "✅ Actual time reset to current time.")
    }

    /**
     * Updates the API Time TextView based on the schedule start time and the cumulative durations
     * from the BusStopWithTimingPoint list.
     *
     * If the API time has already been locked (final value computed), it simply reuses that value.
     * Otherwise, it computes the update as follows:
     * - Finds the target index in the timing list based on the upcomingStop address.
     * - Uses calculateDurationForUpdate() to determine the total duration.
     *   • If that returns null, then the upcoming stop isn’t scheduled – no update.
     *   • If non-null, it updates the API time.
     * - If the upcoming stop equals the last scheduled bus stop, then we lock the API time.
     */
    private fun updateApiTime() {
        // If locked, simply reuse the locked value — only if upcomingStopName matches the first timing point
        if (apiTimeLocked && lockedApiTime != null) {
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            // Parse the locked time into a Calendar object
            val lockedCal = Calendar.getInstance().apply {
                time = timeFormat.parse(lockedApiTime!!) ?: return
            }

            // Get timing list and last red timing point
            val timingList = BusStopWithTimingPoint.fromRouteData(busRouteData.first())
            val lastScheduledAddress = getLastScheduledAddress(timingList, scheduleList)
            val lastTimingPointIndex = timingList.indexOfFirst { it.address == lastScheduledAddress }
            val finalStopIndex = timingList.lastIndex

            if (lastTimingPointIndex != -1 && lastTimingPointIndex < finalStopIndex) {
                // Calculate remaining duration from last timing point to final stop
                val remainingDurationMinutes = timingList.subList(lastTimingPointIndex + 1, timingList.size)
                    .sumOf { it.duration }
                val additionalSeconds = (remainingDurationMinutes * 60).toInt()
                lockedCal.add(Calendar.SECOND, additionalSeconds)

                val updatedFinalApiTime = timeFormat.format(lockedCal.time)
                runOnUiThread {
                    ApiTimeValueTextView.text = updatedFinalApiTime
                }
                Log.d("MapActivity updateApiTime", "⏩ Updated API time after last timing point to: $updatedFinalApiTime")
            } else {
                runOnUiThread { ApiTimeValueTextView.text = lockedApiTime }
                Log.d("MapActivity updateApiTime", "API time locked, using last computed value: $lockedApiTime")
            }
            return
        }

        if (busRouteData.isEmpty() || scheduleList.isEmpty()) return

        val firstSchedule = scheduleList.first()
        val startTimeParts = firstSchedule.startTime.split(":")
        if (startTimeParts.size != 2) return

        val startCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startTimeParts[0].toInt())
            set(Calendar.MINUTE, startTimeParts[1].toInt())
            set(Calendar.SECOND, 0)
        }

        // Build timing list.
        val timingList = BusStopWithTimingPoint.fromRouteData(busRouteData.first())
        Log.d("MapActivity updateApiTime", "Timing list: $timingList")

        val upcomingAddress = upcomingStop
        Log.d("MapActivity updateApiTime", "Upcoming stop address: $upcomingAddress")

        // Find the target index.
        val targetIndex = timingList.indexOfFirst {
            it.address?.equals(upcomingAddress, ignoreCase = true) == true
        }
        if (targetIndex == -1) {
            Log.e("MapActivity updateApiTime", "Upcoming stop address not found in timing list.")
            return
        }
        Log.d("MapActivity updateApiTime", "Found target index: $targetIndex")

        // Compute the total duration.
        val totalDurationMinutes = calculateDurationForUpdate(timingList, scheduleList, targetIndex)
        if (totalDurationMinutes == null) {
            Log.d("MapActivity updateApiTime", "Upcoming bus stop not scheduled. Skipping API update.")
            // If we already computed a final value before, do not override.
            return
        }
        Log.d("MapActivity updateApiTime", "Total duration in minutes: $totalDurationMinutes")

        // Add the duration (in seconds) to the start time.
        val additionalSeconds = (totalDurationMinutes * 60).toInt()
        startCalendar.add(Calendar.SECOND, additionalSeconds)

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val updatedApiTime = timeFormat.format(startCalendar.time)

        val firstAddress = scheduleList.firstOrNull()?.busStops?.firstOrNull()?.address
        if (upcomingStopName == firstAddress) {
            runOnUiThread {
                ApiTimeValueTextView.text = updatedApiTime
            }
            Log.d("MapActivity updateApiTime", "API Time updated to: $updatedApiTime")
        } else {
            Log.d("MapActivity updateApiTime", "⏩ Skipped updating API Time because upcomingStopName == firstAddress")
        }

        Log.d("MapActivity updateApiTime", "API Time updated to: $updatedApiTime")

        // If the upcoming stop is the final scheduled stop, lock the API time.
        val lastScheduledAddress = getLastScheduledAddress(timingList, scheduleList)
        if (lastScheduledAddress != null &&
            upcomingAddress.equals(lastScheduledAddress, ignoreCase = true)) {
            apiTimeLocked = true
            lockedApiTime = updatedApiTime
            Log.d("MapActivity updateApiTime", "Final scheduled bus stop reached. API time locked.")
        }
    }

    /**
     * Returns a sorted list of indices in [timingList] whose addresses appear in the schedule.
     */
    private fun getScheduledIndices(
        timingList: List<BusStopWithTimingPoint>,
        scheduleList: List<ScheduleItem>
    ): List<Int> {
        if (scheduleList.isEmpty() || timingList.isEmpty()) return emptyList()
        val scheduledAddresses = scheduleList.first().busStops.map { it.address?.toLowerCase() }
        return timingList.withIndex()
            .filter { it.value.address?.toLowerCase() in scheduledAddresses }
            .map { it.index }
            .sorted()
    }

    /**
     * Returns the address of the final scheduled bus stop from [timingList].
     */
    private fun getLastScheduledAddress(
        timingList: List<BusStopWithTimingPoint>,
        scheduleList: List<ScheduleItem>
    ): String? {
        val scheduledIndices = getScheduledIndices(timingList, scheduleList)
        return if (scheduledIndices.isNotEmpty()) timingList[scheduledIndices.last()].address else null
    }

    /**
     * Calculates the total duration (in minutes) for the update.
     *
     * If targetIndex is 0, returns the duration at index 0.
     * If the upcoming stop (at targetIndex) is scheduled:
     *   • If it is not the last scheduled stop, sum durations from index 0 up to (but not including) the next scheduled stop.
     *   • If it is the last scheduled stop, sum the entire timing list.
     * Otherwise (unscheduled and not index 0) returns null.
     */
    private fun calculateDurationForUpdate(
        timingList: List<BusStopWithTimingPoint>,
        scheduleList: List<ScheduleItem>,
        targetIndex: Int
    ): Double? {
        val scheduledIndices = getScheduledIndices(timingList, scheduleList)

        if (targetIndex == 0) {
            return timingList.subList(0, 1).sumOf { it.duration }
        }

        if (targetIndex in scheduledIndices) {
            val pos = scheduledIndices.indexOf(targetIndex)
            return if (pos < scheduledIndices.size - 1) {
                val nextScheduledIndex = scheduledIndices[pos + 1]
                timingList.subList(0, nextScheduledIndex).sumOf { it.duration }
            } else {
                // 🟢 NEW LOGIC: Last timing point → return full duration to end
                return timingList.subList(0, targetIndex + 1).sumOf { it.duration }
            }
        }

        // If not scheduled, check if it's after the last scheduled index
        if (scheduledIndices.isNotEmpty() && targetIndex >= scheduledIndices.last()) {
            // Add all remaining durations from this point onward
            return timingList.subList(0, targetIndex + 1).sumOf { it.duration }
        }
        return null
    }

    /**
     * Checks whether the given bus stop address appears in the scheduleList.
     */
    private fun isBusStopInScheduleList(address: String?, scheduleList: List<ScheduleItem>): Boolean {
        if (address == null || scheduleList.isEmpty()) return false
        val busStops = scheduleList.first().busStops
        return busStops.any { it.address.equals(address, ignoreCase = true) }
    }

    /**
     * From the timingList, returns the smallest index greater than [currentIndex]
     * whose bus stop address is found in the scheduleList.
     * Returns null if none exists.
     */
    private fun nextBusStopIndexInScheduleList(
        timingList: List<BusStopWithTimingPoint>,
        scheduleList: List<ScheduleItem>,
        currentIndex: Int
    ): Int? {
        if (scheduleList.isEmpty()) return null
        val busStops = scheduleList.first().busStops.map { it.address }
        // Get all indices in timingList that are scheduled (i.e. address in busStops)
        val scheduledIndices = timingList.withIndex()
            .filter { entry ->
                entry.value.address?.let { addr ->
                    busStops.any { it.equals(addr, ignoreCase = true) }
                } ?: false
            }
            .map { it.index }
        // Find the first scheduled index greater than currentIndex
        return scheduledIndices.firstOrNull { it > currentIndex }
    }

//    /**
//     * Calculates the total duration to be used in API time update.
//     * If the bus stop at [currentIndex] is in the schedule list,
//     * then the total duration is the sum of durations from index 0 up to and including
//     * the next scheduled bus stop (if one exists). Otherwise, it simply sums up
//     * durations from index 0 to [currentIndex].
//     */
//    private fun calculateDurationBetweenBusStopWithTimingPoint(
//        timingList: List<BusStopWithTimingPoint>,
//        scheduleList: List<ScheduleItem>,
//        currentIndex: Int
//    ): Double {
//        return if (isBusStopInScheduleList(timingList[currentIndex].address, scheduleList)) {
//            // Find the next scheduled bus stop index in timingList
//            val nextScheduledIndex = nextBusStopIndexInScheduleList(timingList, scheduleList, currentIndex)
//            if (nextScheduledIndex != null) {
//                // Sum durations from index 0 to nextScheduledIndex (inclusive)
//                timingList.subList(0, nextScheduledIndex + 1).sumOf { it.duration }
//            } else {
//                // Fallback: sum durations from index 0 to currentIndex if no next scheduled stop found
//                timingList.subList(0, currentIndex + 1).sumOf { it.duration }
//            }
//        } else {
//            // Not a scheduled bus stop; sum durations normally from index 0 to currentIndex
//            timingList.subList(0, currentIndex + 1).sumOf { it.duration }
//        }
//    }

    /** Updates timing point based on current bus location */
    @SuppressLint("LongLogTag")
    private fun updateTimingPointBasedOnLocation(currentLat: Double, currentLon: Double) {
        if (scheduleList.isEmpty()) return

        val firstSchedule = scheduleList.first()
        val stopList = firstSchedule.busStops

        if (stopList.isEmpty()) {
            timingPointValueTextView.text = firstSchedule.endTime
            return
        }

        // Ensure the first timing point is set
        if (upcomingStop == "Unknown") {
            upcomingStop = stopList.first().time
            runOnUiThread {
                timingPointValueTextView.text = upcomingStop
            }
            Log.d("MapActivity", "🔹 Initial timing point set to: $upcomingStop")
        }

        // Check which timing point to display
        for ((index, stop) in stopList.withIndex()) {
            val stopLat = stop.latitude
            val stopLon = stop.longitude
            val distance = calculateDistance(currentLat, currentLon, stopLat, stopLon)

            val stopPassThreshold = 25.0 // Within 25 meters

            if (distance <= stopPassThreshold) {
                Log.d(
                    "MapActivity updateTimingPointBasedOnLocation",
                    "✅ Passed stop ${stop.name} at ${stop.time} (Distance: ${"%.2f".format(distance)}m)"
                )

                nextTimingPoint = if (index + 1 < stopList.size) {
                    stopList[index + 1].time + ":00"
                } else {
                    firstSchedule.endTime + ":00" // Last stop reached
                }

                runOnUiThread {
                    timingPointValueTextView.text = nextTimingPoint
                }

                upcomingStop = nextTimingPoint
                Log.d("MapActivity updateTimingPointBasedOnLocation", "🔹 Next timing point: $nextTimingPoint")
                return
            }
        }
    }

    /**
     * initialize first timing point
     */
    private fun initializeTimingPoint() {
        if (scheduleList.isNotEmpty()) {
            val firstSchedule = scheduleList.first()
            val firstTimingPoint = firstSchedule.busStops.firstOrNull()?.time + ":00" ?: "Unknown"

            timingPointValueTextView.text = firstTimingPoint
            upcomingStop = firstTimingPoint // Set the upcoming stop initially
            Log.d("MapActivity initializeTimingPoint", "Initial Timing Point: $firstTimingPoint")
        } else {
            timingPointValueTextView.text = "No Schedule Available"
        }
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
    private var hasPassedFirstStopAgain = false
    private val isCircularRoute: Boolean
        get() = stops.isNotEmpty() && stops.first().address == stops.last().address


    @SuppressLint("LongLogTag")
    private fun checkPassedStops(currentLat: Double, currentLon: Double) {
        if (stops.isEmpty()) {
            Log.d("MapActivity checkPassedStops", "❌ No bus stops available.")
            return
        }

        if (currentStopIndex >= stops.size) {
            Log.d("MapActivity checkPassedStops", "✅ All stops have been passed.")
            if (isManualMode) {
                isManualMode = false
                startLocationUpdate() // 🔹 Resume GPS updates
            }
            return
        }

        val nextStop = stops[currentStopIndex]
        val stopLat = nextStop.latitude ?: return
        val stopLon = nextStop.longitude ?: return
        stopAddress = nextStop.address ?: getUpcomingBusStopName(stopLat, stopLon)
        upcomingStop = stopAddress
        // 🔁 Check if the stop is a timing point
        val isTimingPoint = redBusStops.contains(stopAddress)

        // 🔁 Only update API time if this timing point stop is different than last one
        if (isTimingPoint && stopAddress != lastTimingPointStopAddress) {
            Log.d("checkPassedStops", "⏱️ Timing point changed to: $stopAddress")
            lastTimingPointStopAddress = stopAddress
            updateApiTime()
        }

        val distance = calculateDistance(currentLat, currentLon, stopLat, stopLon)

        if (distance <= busStopRadius) {

            runOnUiThread {
                upcomingBusStopTextView.text = "$stopAddress"
                upcomingStop = stopAddress
                Log.d(
                    "MapActivity checkPassedStops",
                    "✅ Nearest stop passed: $stopLat, $stopLon (Distance: ${"%.2f".format(distance)} meters) at $stopAddress"
                )
                FileLogger.d(
                    "MapActivity checkPassedStops",
                    "✅ Nearest stop passed: $stopLat, $stopLon (Distance: ${"%.2f".format(distance)} meters) at $stopAddress"
                )
                Toast.makeText(
                    this@MapActivity,
                    "✅At ${latitude} ${longitude} nearest stop passed: $stopLat, $stopLon (Distance: ${"%.2f".format(distance)} meters) at $stopAddress",
                    Toast.LENGTH_LONG
                ).show()
            }

            // Track the first stop being passed a second time in circular routes
            if (isCircularRoute && nextStop == stops.first()) {
                if (hasPassedFirstStopAgain) {
                    // Trip ends after second pass of the first stop
                    upcomingStop = "End of Route"
                    runOnUiThread {
                        upcomingBusStopTextView.text = "End of Route"
                        Toast.makeText(this@MapActivity, "✅ You have reached the final stop.", Toast.LENGTH_SHORT).show()
                    }
                    showSummaryDialog()
                    return
                } else {
                    hasPassedFirstStopAgain = true
                    Log.d("MapActivity", "🔄 Passed first stop a second time, preparing to end the trip.")
                }
            }

            // 🔹 Check if the bus is inside the detection area
            if (isBusInDetectionArea(currentLat, currentLon, stopLat, stopLon)) {
                runOnUiThread {
                    upcomingBusStopTextView.text = "$stopAddress"
                    upcomingStop = stopAddress
                    Toast.makeText(
                        this@MapActivity,
                        "✅ Arrived at: $stopAddress",
                        Toast.LENGTH_LONG
                    ).show()
                    FileLogger.d(
                        "MapActivity checkPassedStops",
                        "✅ Arrived at: $stopAddress"
                    )
                }

                // ✅ Add this block to track and update detection zones
                if (!passedStops.contains(nextStop)) {
                    passedStops.add(nextStop)
                    drawDetectionZones(stops) // Redraw zones to reflect changes
                }

                passedStops.add(nextStop)
                currentStopIndex++

                // Ensure the next stop is updated correctly even if a stop is skipped
                while (currentStopIndex < stops.size && calculateDistance(
                        currentLat, currentLon,
                        stops[currentStopIndex].latitude ?: 0.0,
                        stops[currentStopIndex].longitude ?: 0.0
                    ) <= busStopRadius
                ) {
                    passedStops.add(stops[currentStopIndex])
                    currentStopIndex++
                }

                // Automatically detect if this was the final stop
                if (currentStopIndex >= stops.size) {
                    upcomingStop = "End of Route"
                    runOnUiThread {
                        upcomingBusStopTextView.text = "End of Route"
                        Toast.makeText(this@MapActivity, "✅ You have reached the final stop.", Toast.LENGTH_SHORT).show()
                    }

                    // ✅ Trigger trip completion dialog
                    showSummaryDialog()
                }
            } else {
                val upcomingStop = stops[currentStopIndex]
                val upcomingStopName = getUpcomingBusStopName(upcomingStop.latitude ?: 0.0, upcomingStop.longitude ?: 0.0)
                runOnUiThread {
                    upcomingBusStopTextView.text = "$upcomingStopName"
                }
            }

            // Build timing list and update API time only if the stop exists in it
            val timingList = BusStopWithTimingPoint.fromRouteData(busRouteData.first())
            if (timingList.any { it.address?.equals(stopAddress, ignoreCase = true) == true }) {
                updateApiTime()
            } else {
                Log.d("MapActivity checkPassedStops", "BusStopWithTimingPoint not available for $stopAddress. Skipping API time update.")
            }

            if (currentStopIndex < stops.size) {
                val upcomingStop = stops[currentStopIndex]
                upcomingStopName = getUpcomingBusStopName(upcomingStop.latitude ?: 0.0, upcomingStop.longitude ?: 0.0)

                runOnUiThread {
                    upcomingBusStopTextView.text = "$upcomingStopName"
                }
            } else if (distance > busStopRadius) {
                Log.w("MapActivity checkPassedStops", "⚠️ Warning: No bus stop detected within expected range!")
                runOnUiThread {
                    Toast.makeText(this@MapActivity, "⚠️ Warning: No bus stop detected!", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            upcomingStopName = getUpcomingBusStopName(stopLat, stopLon)

            runOnUiThread {
                upcomingBusStopTextView.text = "$upcomingStopName"
                upcomingStop = upcomingStopName
                Log.d(
                    "MapActivity checkPassedStops",
                    "🛑 No stop passed. Nearest stop: ${nextStop.latitude}, ${nextStop.longitude} is ${
                        "%.2f".format(distance)
                    } meters away at $upcomingStopName."
                )
            }
        }
    }

    /**
     * Geofence Detection Method
     */
    private fun isBusInDetectionArea(currentLat: Double, currentLon: Double, stopLat: Double, stopLon: Double, radius: Double = busStopRadius): Boolean {
        val distance = calculateDistance(currentLat, currentLon, stopLat, stopLon)
        return distance <= radius
    }

    /**
     * Function to add circular markers to represent the detection area for each stop with 25% opacity.
     */
    private fun drawDetectionZones(busStops: List<BusStop>, radiusMeters: Double = busStopRadius) {
        busStops.forEach { stop ->
            val isPassed = passedStops.any { it.latitude == stop.latitude && it.longitude == stop.longitude }

            val fillColor = if (isPassed) Color.argb(64, 0, 255, 0) // Green with 25% opacity
            else Color.argb(64, 255, 0, 0) // Red with 25% opacity

            val circleLayer = org.mapsforge.map.layer.overlay.Circle(
                LatLong(stop.latitude!!, stop.longitude!!),
                radiusMeters.toFloat(),
                AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    color = fillColor
                    setStyle(org.mapsforge.core.graphics.Style.FILL)
                },
                AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    color = if (isPassed) Color.GREEN else Color.RED
                    strokeWidth = 2f
                    setStyle(org.mapsforge.core.graphics.Style.STROKE)
                }
            )

            binding.map.layerManager.layers.add(circleLayer)
        }

        binding.map.invalidate() // Refresh map view
    }

    /** Finds the nearest upcoming bus stop */
    @SuppressLint("LongLogTag")
    private fun getUpcomingBusStopName(lat: Double, lon: Double): String {
        try {
            Log.d("MapActivity getUpcomingBusStopName", "JSON String: $jsonString")

            // Convert jsonString into a JSONArray
            val jsonArray = JSONArray(jsonString)

            if (jsonArray.length() == 0) {
                Log.e("MapActivity getUpcomingBusStopName", "JSON array is empty")
                return "No Upcoming Stop"
            }

            // Get the first object in the array
            val jsonObject = jsonArray.getJSONObject(0)

            // Ensure the key exists
            if (!jsonObject.has("next_points")) {
                Log.e("MapActivity getUpcomingBusStopName", "Missing 'next_points' key")
                return "No Upcoming Stop"
            }

            val routeArray = jsonObject.getJSONArray("next_points")

            if (routeArray.length() == 0) {
                Log.e("MapActivity getUpcomingBusStopName", "next_points array is empty")
                return "No Upcoming Stop"
            }

            var nearestStop: String? = null
            var minDistance = Double.MAX_VALUE

            for (i in 0 until routeArray.length()) {
                val stop = routeArray.getJSONObject(i)

                if (!stop.has("latitude") || !stop.has("longitude") || !stop.has("address")) {
                    Log.e("MapActivity getUpcomingBusStopName", "Missing stop fields at index $i")
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
            Log.e("MapActivity getUpcomingBusStopName", "Error: ${e.localizedMessage}", e)
            return "MapActivity getUpcomingBusStopName Error Retrieving Stop"
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

    /** Stops the simulation and resets the state. */
    private fun stopSimulation() {
        resetSimulationState()
        Toast.makeText(this, "Simulation stopped and state reset", Toast.LENGTH_SHORT).show()
    }

    /** Stops the simulation and resets the state. */
    private fun resetSimulationState() {
        // Stop any pending simulation callbacks.
        if (::simulationHandler.isInitialized) {
            simulationHandler.removeCallbacks(simulationRunnable)
        }
        isSimulating = false

        // Reset simulation variables.
        currentRouteIndex = 0
        simulationSpeedFactor = 1
        simulationStartTime = System.currentTimeMillis()

        // Reset the simulated clock to the schedule's start time (if available)
        if (scheduleList.isNotEmpty()) {
            val startTimeStr = scheduleList.first().startTime  // e.g. "08:00"
            val timeParts = startTimeStr.split(":")
            if (timeParts.size == 2) {
                simulatedStartTime.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
                simulatedStartTime.set(Calendar.MINUTE, timeParts[1].toInt())
                simulatedStartTime.set(Calendar.SECOND, 0)
            }
        }

        // Reset any other state you maintain.
        upcomingStop = if (scheduleList.isNotEmpty() && scheduleList.first().busStops.isNotEmpty())
            scheduleList.first().busStops.first().time + ":00" else "Unknown"
        timingPointValueTextView.text = upcomingStop

        // If you keep track of passed stops, clear them.
        passedStops.clear()
        currentStopIndex = 0

        // (Optional) If you want to re-draw the polyline, remove it and then call drawPolyline() again.
        routePolyline?.let {
            binding.map.layerManager.layers.remove(it)
            binding.map.invalidate()
        }
        // If needed, you can re-add the polyline:
        if (route.isNotEmpty()) {
            drawPolyline()
        }
    }

    /**
     * Draws a polyline on the Mapsforge map using the busRoute data.
     */
    @SuppressLint("LongLogTag")
    private fun drawPolyline() {
        Log.d("MapActivity drawPolyline", "Drawing polyline with route: $route")

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

            Log.d("MapActivity drawPolyline", "✅ Polyline drawn with ${routePoints.size} points.")
        } else {
            Log.e("MapActivity drawPolyline", "❌ No route data available for polyline.")
        }
    }

//    /**
//     * Retrieves the Android ID (AID) from a hidden JSON file in the app-specific documents directory.
//     * If the file or directory does not exist, it creates them and generates a new AID.
//     *
//     * @return The AID (Android ID) as a String.
//     */
//    @SuppressLint("HardwareIds", "LongLogTag")
//    private fun getOrCreateAid(): String {
//        // Ensure we have the correct storage permission
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            if (!Environment.isExternalStorageManager()) {
//                Log.e("MapActivity getOrCreateAid", "Storage permission not granted.")
//                return "Permission Denied"
//            }
//        } else {
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
//                != PackageManager.PERMISSION_GRANTED) {
//                Log.e("MapActivity getOrCreateAid", "Storage permission not granted.")
//                return "Permission Denied"
//            }
//        }
//
//        // Use External Storage Public Directory for Documents
//        val externalDocumentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
//        val hiddenFolder = File(externalDocumentsDir, ".vlrshiddenfolder")
//
//        if (!hiddenFolder.exists()) {
//            val success = hiddenFolder.mkdirs()
//            if (!success) {
//                Log.e("MapActivity getOrCreateAid", "Failed to create directory: ${hiddenFolder.absolutePath}")
//                return "Failed to create directory"
//            }
//        }
//
//        val aidFile = File(hiddenFolder, "busDataCache.json")
//        Log.d("MapActivity getOrCreateAid", "Attempting to create: ${aidFile.absolutePath}")
//
//        if (!aidFile.exists()) {
//            val newAid = generateNewAid()
//            val jsonObject = JSONObject().apply {
//                put("aid", newAid)
//            }
//            try {
//                aidFile.writeText(jsonObject.toString())
//                Toast.makeText(this, "AID saved successfully in busDataCache.json", Toast.LENGTH_SHORT).show()
//            } catch (e: Exception) {
//                Log.e("MapActivity getOrCreateAid", "Error writing to file: ${e.message}")
//                return "Error writing file"
//            }
//            return newAid
//        }
//
//        return try {
//            val jsonContent = JSONObject(aidFile.readText())
//            jsonContent.getString("aid").trim()
//        } catch (e: Exception) {
//            Log.e("MapActivity getOrCreateAid", "Error reading JSON file: ${e.message}")
//            "Error reading file"
//        }
//    }

//    /**
//     * Generates a new Android ID (AID) using the device's secure Android ID.
//     *
//     * @return A unique Android ID as a String.
//     */
//    @SuppressLint("HardwareIds")
//    private fun generateNewAid(): String {
//        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
//    }

    /**
     * Starts live location updates using the device's GPS instead of simulation.
     * Updates latitude, longitude, bearing, speed, and UI elements accordingly.
     */
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    // New variables for tracking logic
    private var nearestRouteIndex = 0
    private var lastValidLatitude = 0.0
    private var lastValidLongitude = 0.0
    private var hasPassedFirstStop = false
    private val jumpThreshold = 3 // Prevents sudden jumps
    private val detectionZoneRadius = 200.0 // 200m detection zone


    @SuppressLint("MissingPermission", "LongLogTag")
    private fun startLocationUpdate() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.create().apply {
            interval = 1000 // 1-second updates
            fastestInterval = 500 // Fastest update in 500ms
            priority = Priority.PRIORITY_HIGH_ACCURACY
            setWaitForAccurateLocation(true)  // Ensures precise GPS fix
        }
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->

//                    Log.d("GPS_DEBUG", "Latitude: ${location.latitude}, Longitude: ${location.longitude}, Accuracy: ${location.accuracy}")
//                    Log.d("GPS_DEBUG", "Speed: ${location.speed}, Bearing: ${location.bearing}")
//
//                    showCustomToast("Latitude: ${latitude}, Longitude: ${longitude}, LocAccuracy: ${location.accuracy}, Speed: ${speed}, Bearing: ${bearing}, BearAccuracy: ${location.bearingAccuracyDegrees}")
//                    Toast.makeText(this@MapActivity, "Lat: ${location.latitude}, Lon: ${location.longitude}, LocAcc: ${location.accuracy}, Speed: ${location.speed}, Bear: ${location.bearing}, BearAcc: ${location.bearingAccuracyDegrees}", Toast.LENGTH_LONG).show()
//                    Toast.makeText(this@MapActivity, "Speed: ${location.speed}, Bearing: ${location.bearing}", Toast.LENGTH_LONG).show()

                    if (!isManualMode) {
                        latitude = location.latitude
                        longitude = location.longitude
                        updateSpeed(location.speed * 3.6f)
                        bearing = location.bearing
                    }

                    // Find the nearest route point
                    val nearestIndex = findNearestBusRoutePoint(latitude, longitude)

                    // Handle First Bus Stop Rule
                    if (!hasPassedFirstStop) {
                        if (nearestIndex == 0) {
                            hasPassedFirstStop = true // Mark the first bus stop as passed
                            Log.d("MapActivity", "✅ First bus stop passed. Rules activated.")
                        } else {
                            Log.d("MapActivity", "⚠️ Waiting for first bus stop to be passed.")
                            // Use live GPS data until the first stop is passed
                            runOnUiThread {
                                updateBusMarkerPosition(latitude, longitude, bearing)
                                binding.map.invalidate()
                            }
                            return
                        }
                    }

                    // Ignore backward movement
                    if (nearestIndex < nearestRouteIndex) return

                    // Unlock detection zone logic
                    val distance = calculateDistance(lastLatitude, lastLongitude, latitude, longitude)
                    if (distance > detectionZoneRadius * 2) {
                        nearestRouteIndex = findNearestBusRoutePoint(latitude, longitude)
                    }

                    // Ignore sudden jumps (skip unexpected spikes)
                    if (nearestIndex > nearestRouteIndex + jumpThreshold) return

                    // Smooth marker animation for consecutive points
                    if (nearestIndex >= nearestRouteIndex) {
                        animateMarkerThroughPoints(nearestRouteIndex, nearestIndex)
                    }

                    // Update the valid position
                    nearestRouteIndex = nearestIndex
                    val nearestRoutePoint = route[nearestIndex]

                    latitude = nearestRoutePoint.latitude ?: latitude
                    longitude = nearestRoutePoint.longitude ?: longitude
                    lastValidLatitude = latitude
                    lastValidLongitude = longitude

                    // Calculate bearing toward next index
                    val nextIndex = if (nearestIndex < route.size - 1) nearestIndex + 1 else nearestIndex
                    val targetPoint = route[nextIndex]

                    bearing = calculateBearing(
                        latitude, longitude,
                        targetPoint.latitude ?: 0.0,
                        targetPoint.longitude ?: 0.0
                    )

                    // Assuming 'route' is your List<BusRoute> already populated
//                    if (route.isNotEmpty()) {
//                        busRouteDetectionZone(route, nearestDistance)
//                    }

                    Log.d("GPS_DEBUG", "Latitude: ${location.latitude}, Longitude: ${location.longitude}, Accuracy: ${location.accuracy}")
                    Log.d("GPS_DEBUG", "Speed: ${location.speed}, Bearing: ${location.bearing}")

//                    showCustomToast("Latitude: ${location.latitude}, Longitude: ${location.longitude}, LocAccuracy: ${location.accuracy}, Speed: ${location.speed}, Bearing: ${location.bearing}, BearAccuracy: ${location.bearingAccuracyDegrees}")
//                    Toast.makeText(this@MapActivity, "Lat: ${location.latitude}, Lon: ${location.longitude}, LocAcc: ${location.accuracy}, Speed: ${location.speed}, Bear: ${location.bearing}, BearAcc: ${location.bearingAccuracyDegrees}", Toast.LENGTH_LONG).show()
//                    Toast.makeText(this@MapActivity, "Speed: ${location.speed}, Bearing: ${location.bearing}", Toast.LENGTH_LONG).show()

                    runOnUiThread {
                        speedTextView.text = "Speed: ${"%.2f".format(speed)} km/h"
                        updateBusMarkerPosition(latitude, longitude, bearing)
                        updateBusMarkerPosition(latitude, longitude, bearing)
                        checkPassedStops(latitude, longitude)
                        updateTimingPointBasedOnLocation(latitude, longitude)
                        scheduleStatusValueTextView.text = "Calculating..."
                        checkScheduleStatus()
                        updateApiTime()

                        binding.map.invalidate() // Refresh map view
                    }

                    if (firstTime && !forceAheadStatus) {
                        firstTime = false
//                        startActualTimeUpdater()
                    }

                    lastLatitude = latitude
                    lastLongitude = longitude
                }
            }
        }

        // Check GPS settings and request updates if successful
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        LocationServices.getSettingsClient(this)
            .checkLocationSettings(builder.build())
            .addOnSuccessListener {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            }
            .addOnFailureListener { e ->
                Log.e("MapActivity", "❌ GPS settings error: ${e.localizedMessage}")
                Toast.makeText(this, "Please enable GPS for accurate tracking.", Toast.LENGTH_LONG).show()
            }

        Toast.makeText(this, "Live location updates started", Toast.LENGTH_SHORT).show()
    }

    /**
     * Smoothly updates the speed using an time‑based filter moving average.
     */
    private val speedFilter = TimeBasedMovingAverageFilterDouble(windowMillis = 15000)
    // For example, a window of 15000 ms (15 seconds):
    fun updateSpeed(newSpeed: Float) {
        // The filter expects a Double value; newSpeed is given in km/h.
        // You can directly pass newSpeed.toDouble() to the filter.
        smoothedSpeed = speedFilter.add(newSpeed.toDouble()).toFloat()
        Log.d("MapActivity", "Smoothed speed (time-based moving average): $smoothedSpeed km/h")
    }

    /**
     * function to smoothly animate the marker's movement instead of jumping suddenly.
     */
    private fun animateMarkerThroughPoints(startIndex: Int, endIndex: Int) {
        val handler = Handler(Looper.getMainLooper())
        val pointsToAnimate = route.subList(startIndex, endIndex + 1)

        var currentStep = 0
        val totalSteps = pointsToAnimate.size

        handler.post(object : Runnable {
            override fun run() {
                if (currentStep < totalSteps) {
                    val point = pointsToAnimate[currentStep]
                    updateBusMarkerPosition(point.latitude ?: 0.0, point.longitude ?: 0.0, bearing)
                    currentStep++
                    handler.postDelayed(this, 500) // Smooth animation every 500ms
                }
            }
        })
    }

//    /**
//     * Function to add circular markers to represent the detection area for each stop with 25% opacity.
//     */
//    private fun busRouteDetectionZone(busRoute: List<BusRoute>, radiusMeters: Double) {
//        busRoute.forEach { point ->
//            val circleLayer = org.mapsforge.map.layer.overlay.Circle(
//                LatLong(point.latitude!!, point.longitude!!),
//                radiusMeters.toFloat(),
//                AndroidGraphicFactory.INSTANCE.createPaint().apply {
//                    color = Color.argb(8, 128, 0, 128) // Purple with 25% opacity
//                    setStyle(org.mapsforge.core.graphics.Style.FILL)
//                },
//                AndroidGraphicFactory.INSTANCE.createPaint().apply {
//                    color = Color.rgb(128, 0, 128) // Solid Purple border
//                    strokeWidth = 2f
//                    setStyle(org.mapsforge.core.graphics.Style.STROKE)
//                }
//            )
//
//            binding.map.layerManager.layers.add(circleLayer)
//        }
//
//        binding.map.invalidate() // Refresh map view
//    }
    /**
     * function to calculate the nearest coordinate index in the busRoute
     */
    private fun findNearestBusRoutePoint(currentLat: Double, currentLon: Double): Int {
        var nearestIndex = 0
        var minDistance = Double.MAX_VALUE

        for (i in route.indices) {
            val routePoint = route[i]
            val distance = calculateDistance(
                currentLat ?: 0.0,
                currentLon ?: 0.0,
                routePoint.latitude ?: 0.0,
                routePoint.longitude ?: 0.0
            )

            if (distance < minDistance) {
                minDistance = distance
                nearestIndex = i
            }
        }

        return nearestIndex
    }

//    /**
//     * function to smooth the bearing to reduce sudden direction flips.
//     */
//    private var previousBearing: Float? = null
//
//    private fun smoothBearing(newBearing: Float, alpha: Float = 0.2f): Float {
//        return if (previousBearing == null) {
//            previousBearing = newBearing
//            newBearing
//        } else {
//            val smoothedBearing = (alpha * newBearing) + ((1 - alpha) * previousBearing!!)
//            previousBearing = smoothedBearing
//            smoothedBearing
//        }
//    }

//    /**
//     * normalize the bearing to ensure it’s within the valid range.
//     */
//    private fun normalizeBearing(bearing: Float): Float {
//        return (bearing + 360) % 360
//    }

//    /**
//     * custom toast align center
//     */
//    fun showCustomToast(message: String) {
//        val toast = Toast.makeText(this@MapActivity, message, Toast.LENGTH_LONG)
//
//        // Position the toast at the top-center
//        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 100)
//
//        // Custom view with a TextView for longer text
//        val textView = TextView(this)
//        textView.text = message
//        textView.setTextColor(Color.WHITE)
//        textView.setBackgroundColor(Color.BLACK) // Custom background for visibility
//        textView.setPadding(20, 20, 20, 20)
//
//        toast.view = textView
//        toast.show()
//    }

//    /**
//     * custom toast align bottom
//     */
//    fun showCustomToastBottom(message: String) {
//        val toast = Toast.makeText(this@MapActivity, message, Toast.LENGTH_LONG)
//
//        // Position the toast at the top-center
//        toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 140)
//
//        // Custom view with a TextView for longer text
//        val textView = TextView(this)
//        textView.text = message
//        textView.setTextColor(Color.WHITE)
//        textView.setBackgroundColor(Color.BLACK) // Custom background for visibility
//        textView.setPadding(20, 20, 20, 20)
//
//        toast.view = textView
//        toast.show()
//    }

//    /**
//     * ✅ Function to validate time format (HH:mm:ss)
//     */
//    private fun isValidTime(time: String?): Boolean {
//        if (time.isNullOrEmpty() || time == "Unknown") {
//            Log.e("MapActivity isValidTime", "❌ Invalid time detected: '$time'")
//            return false
//        }
//        return try {
//            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).parse(time)
//            true
//        } catch (e: Exception) {
//            Log.e("MapActivity isValidTime", "❌ Failed to parse time: '$time' - ${e.localizedMessage}")
//            false
//        }
//    }

    private var firstTimeCentering = true  // Add this flag to track the initial centering

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

        // Apply map rotation
        binding.map.setRotation(-bearing) // Negative to align with compass movement

        // Scale the map to prevent cropping
        binding.map.scaleX = 1f  // Adjust scaling factor
        binding.map.scaleY = 1f

        // Keep the map centered on the bus location
        binding.map.setCenter(newPosition)
        binding.map.invalidate() // Force redraw

        // Call our new listener function to check/update upcoming bus stop details
        onBusMarkerUpdated()
    }

    /**
     * Listener function that is called whenever the bus marker is updated.
     * It checks whether the currently displayed upcoming bus stop (in the upcomingBusStopTextView)
     * has been passed by comparing the current bus position (latitude, longitude) with the bus stop’s coordinates.
     * If the stop is within the defined busStopRadius, then it automatically updates the upcoming stop to the next one.
     * For stops that are timing points (i.e. red bus stops) the API timing is also updated.
     */
    private fun onBusMarkerUpdated() {
        // Get the stop address currently shown in the upcoming bus stop text view.
        val currentDisplayedStop = upcomingBusStopTextView.text.toString()
        // Try to find this stop in the stops list.
        val currentStopIndexFromDisplay = stops.indexOfFirst {
            it.address?.equals(currentDisplayedStop, ignoreCase = true) == true
        }
        if (currentStopIndexFromDisplay != -1) {
            val currentStop = stops[currentStopIndexFromDisplay]
            // Compute the distance between the bus marker position and this bus stop.
            val distanceToStop = calculateDistance(
                latitude, longitude,
                currentStop.latitude ?: 0.0,
                currentStop.longitude ?: 0.0
            )
            Log.d("MapActivity onBusMarkerUpdated", "Distance to current stop '${currentStop.address}': ${"%.2f".format(distanceToStop)} m")
            // If the bus is close enough—i.e. the stop is considered "passed"
            if (distanceToStop <= busStopRadius) {
                // Only update if there is a next stop available.
                if (currentStopIndexFromDisplay + 1 < stops.size) {
                    val nextStop = stops[currentStopIndexFromDisplay + 1]
                    upcomingBusStopTextView.text = nextStop.address ?: "Unknown Stop"
                    upcomingStop = nextStop.address ?: "Unknown Stop"
                    Log.d("MapActivity onBusMarkerUpdated", "Updated upcoming bus stop to: ${nextStop.address}")
                    // If the new upcoming stop has a timing point (red mark), update its API time.
                    if (redBusStops.contains(nextStop.address)) {
                        updateApiTime()
                        Log.d("MapActivity onBusMarkerUpdated", "Upcoming stop is a timing point. API time updated.")
                    }
                } else {
                    // End of route reached.
                    upcomingBusStopTextView.text = "End of Route"
                    upcomingStop = "End of Route"
                    Log.d("MapActivity onBusMarkerUpdated", "Reached end of route.")
                }
            }
        } else {
            Log.d("MapActivity onBusMarkerUpdated", "The displayed upcoming stop '$currentDisplayedStop' is not found in the stops list.")
        }
    }

//    /**
//     * Calculates the average latitude and longitude of the next 'count' points in the busRoute.
//     */
//    private fun calculateAverageNextCoordinates(lat: Double, lon: Double, count: Int): Pair<Double, Double>? {
//        if (route.isEmpty()) return null
//
//        var totalLat = 0.0
//        var totalLon = 0.0
//        var validPoints = 0
//
//        // Find the current position in the route
//        val currentIndex = route.indexOfFirst { it.latitude == lat && it.longitude == lon }
//        if (currentIndex == -1) return null // Current position not found
//
//        // Take the next 'count' points
//        for (i in 1..count) {
//            val nextIndex = currentIndex + i
//            if (nextIndex < route.size) {
//                totalLat += route[nextIndex].latitude ?: 0.0
//                totalLon += route[nextIndex].longitude ?: 0.0
//                validPoints++
//            } else {
//                break // Stop if we run out of points
//            }
//        }
//
//        return if (validPoints > 0) {
//            Pair(totalLat / validPoints, totalLon / validPoints)
//        } else {
//            null
//        }
//    }

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
     * Initialize UI components and assign them to the corresponding views.
     */
    private fun initializeUIComponents() {
        speedTextView = binding.speedTextView
        timingPointandStopsTextView = binding.timingPointandStopsTextView
        tripEndTimeTextView = binding.tripEndTimeTextView
        // Hardcoded values for testing
        if (scheduleList.isNotEmpty()) {
            val scheduleItem = scheduleList.first()

            // Extract stop names and times dynamically
            val stopsInfo = scheduleItem.busStops.joinToString(", ") { "${it.name} - ${it.time}" }

            // Set text views with extracted stop info
            timingPointandStopsTextView.text = stopsInfo
            tripEndTimeTextView.text = scheduleItem.endTime + ":00"
        }
        actualTimeTextView = binding.actualTimeValueTextView
        timingPointValueTextView = binding.timingPointValueTextView
        ApiTimeValueTextView = binding.ApiTimeValueTextView
        scheduleStatusValueTextView = binding.scheduleStatusValueTextView
        thresholdRangeValueTextView = binding.thresholdRangeValueTextView
        speedTextView = binding.speedTextView
        upcomingBusStopTextView = binding.upcomingBusStopTextView
        arriveButtonContainer = findViewById(R.id.arriveButtonContainer)
        currentTimeTextView = binding.currentTimeTextView
        nextTripCountdownTextView = binding.nextTripCountdownTextView
    }

    /**
     * Retrieves default configuration values for the activity, such as latitude, longitude, bearing, and more.
     */
    @SuppressLint("LongLogTag")
    private fun getDefaultConfigValue() {
//        busConfig = intent.getStringExtra(Constant.deviceNameKey).toString()
//        Toast.makeText(this, "arrBusDataOnline1: ${arrBusData}", Toast.LENGTH_SHORT).show()
        Log.d("MapActivity getDefaultConfigValue busConfig", arrBusData.toString())
        Log.d("MapActivity getDefaultConfigValue arrBusDataOnline1", arrBusData.toString())
        Log.d("MapActivity getDefaultConfigValue config", config.toString())
        arrBusData = config!!
        arrBusData = arrBusData.filter { it.aid != aid }
//        Toast.makeText(this, "getDefaultConfigValue arrBusDataOnline2: ${arrBusData}", Toast.LENGTH_SHORT).show()
        Log.d("MapActivity getDefaultConfigValue arrBusDataOnline2", arrBusData.toString())
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
            Log.d("MapActivity getDefaultConfigValue MarkerDrawable", "Bus symbol drawable applied")
        }
    }

//    /**
//     * Helper function to convert stops to busStopInfo
//     */
//    @SuppressLint("LongLogTag")
//    private fun updateBusStopProximityManager() {
//        if (stops.isNotEmpty()) {
//            busStopInfo = stops.map { stop ->
//                BusStopInfo(
//                    latitude = stop.latitude ?: 0.0,
//                    longitude = stop.longitude ?: 0.0,
//                    busStopName = "BusStop_${stop.latitude}_${stop.longitude}"
//                )
//            }
//            BusStopProximityManager.setBusStopList(busStopInfo)
//            Log.d("MapActivity updateBusStopProximityManager", "BusStopProximityManager updated with ${busStopInfo.size} stops.")
//            Log.d("MapActivity updateBusStopProximityManager", "busStopInfo ${busStopInfo.toString()}")
//        } else {
//            Log.d("MapActivity updateBusStopProximityManager", "No stops available to update BusStopProximityManager.")
//        }
//    }

    /**
     * Loads the offline map from assets and configures the map.
     * Prevents adding duplicate layers.
     */
    @SuppressLint("LongLogTag")
    private fun openMapFromAssets() {
        binding.map.mapScaleBar.isVisible = true
        binding.map.setBuiltInZoomControls(true)

        // Instead of creating “mycache”, open the existing “preloadCache”:
        val cache = AndroidUtil.createTileCache(
            this,
            "preloadCache",                    // same name!
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
            Log.d("MapActivity openMapFromAssets", "✅ Offline map added successfully.")
        } else {
            Log.d("MapActivity openMapFromAssets", "⚠️ Offline map layer already exists. Skipping duplicate addition.")
        }

        binding.map.setCenter(LatLong(latitude, longitude)) // Set the default location to center the bus marker
//        binding.map.setCenter(LatLong(-36.855647, 174.765249)) // Airedale
//        binding.map.setCenter(LatLong(-36.8485, 174.7633)) // Auckland, NZ
        binding.map.setZoomLevel(16) // Set default zoom level
//        binding.map.setZoomLevel(11) // Set default zoom level

        // **Ensure the map is fully loaded before drawing the polyline**
        binding.map.post {
            Log.d("MapActivity", "Map is fully initialized. Drawing polyline and markers now.")
            drawDetectionZones(stops)   // Draw detection zones first
            drawPolyline()              // Then draw Polyline on top
            addBusStopMarkers(stops)    // Bus stop markers as the final element
            addBusMarker(latitude, longitude)
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
    @SuppressLint("LongLogTag")
    private fun addBusStopMarkers(busStops: List<BusStop>) {
        val totalStops = busStops.size

        busStops.forEachIndexed { index, stop ->
            val stopAddress = stop.address ?: ""
            val isRed = redBusStops.any { it.equals(stopAddress, ignoreCase = true) }
            Log.d("MapActivity addBusStopMarkers", "Checking stop address: $stopAddress, isRed: $isRed")

            val busStopSymbol =
                Helper.createBusStopSymbol(applicationContext, index, totalStops, isRed)
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

    /** Cleans up resources on activity destruction. */
    override fun onDestroy() {
//        mqttManager.disconnect()
//        stopDateTimeUpdater()
        super.onDestroy()

        // Remove polyline from Mapsforge map
        routePolyline?.let {
            binding.map.layerManager.layers.remove(it)
            binding.map.invalidate()
        }
        Log.d("MapActivity", "🗑️ Removed polyline on destroy.")
        currentTimeHandler.removeCallbacks(currentTimeRunnable)
    }
}