package com.jason.publisher

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.databinding.ActivityTestmapBinding
import com.jason.publisher.model.BusRoute
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
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.jason.publisher.model.BusItem
import com.jason.publisher.model.BusStop
import com.jason.publisher.model.BusStopInfo
import com.jason.publisher.model.BusStopWithTimingPoint
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

class TestMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestmapBinding
    private lateinit var locationManager: LocationManager
    private lateinit var mapController: MapController
    private lateinit var dateTimeHandler: Handler
//    private lateinit var dateTimeRunnable: Runnable

    private var latitude = 0.0
    private var longitude = 0.0
    private var lastLatitude = 0.0
    private var lastLongitude = 0.0
    private var bearing = 0.0F
    private var speed = 30.0F
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
    private val stepHandler = Handler(Looper.getMainLooper())
    private var stepRunnable: Runnable? = null
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

    @SuppressLint("LongLogTag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        binding = ActivityTestmapBinding.inflate(layoutInflater)
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
        scheduleList = intent.getSerializableExtra("FIRST_SCHEDULE_ITEM") as? List<ScheduleItem> ?: emptyList()
        scheduleData = intent.getSerializableExtra("FULL_SCHEDULE_DATA") as? List<ScheduleItem> ?: emptyList()

        Log.d("TestMapActivity onCreate retrieve", "Received aid: $aid")
        Log.d("TestMapActivity onCreate retrieve", "Received config: ${config.toString()}")
        Log.d("TestMapActivity onCreate retrieve", "Received jsonString: $jsonString")
        Log.d("TestMapActivity onCreate retrieve", "Received route: ${route.toString()}")
        Log.d("TestMapActivity onCreate retrieve", "Received stops: ${stops.toString()}")
        Log.d("TestMapActivity onCreate retrieve", "Received durationBetweenStops: ${durationBetweenStops.toString()}")
        Log.d("TestMapActivity onCreate retrieve", "Received busRouteData: ${busRouteData.toString()}")
        Log.d("TestMapActivity onCreate retrieve", "Received scheduleList: ${scheduleList.toString()}")
        Log.d("TestMapActivity onCreate retrieve", "Received scheduleData: ${scheduleData.toString()}")

        extractRedBusStops()

        // Initialize UI components
        initializeUIComponents()

//        aidTextView.text = "AID: $aid"

        // Set up network status UI
        NetworkStatusHelper.setupNetworkStatus(this, binding.connectionStatusTextView, binding.networkStatusIndicator)

        // Initialize the date/time updater
//        startDateTimeUpdater()

//        fetchConfig { success ->
//            if (success) {
//                getAccessToken()
        Log.d("TestMapActivity onCreate Token", token)
//                mqttManager = MqttManager(serverUri = TimeTableActivity.SERVER_URI, clientId = TimeTableActivity.CLIENT_ID, username = token)
        getDefaultConfigValue()
//                requestAdminMessage()
//                connectAndSubscribe()
//                Log.d("TestMapActivity oncreate fetchConfig config", config.toString())
//                Log.d("TestMapActivity oncreate fetchConfig busRoute", route.toString())
//                Log.d("TestMapActivity oncreate fetchConfig busStop", stops.toString())
//            } else {
//                Log.e("TestMapActivity onCreate", "Failed to fetch config, running in offline mode.")
//            }
//        }

        updateBusNameFromConfig()

        // Ensure `locationManager` is properly initialized before use
        locationManager.getCurrentLocation(object : LocationListener {
            override fun onLocationUpdate(location: Location) {
                latitude = location.latitude
                longitude = location.longitude
                Log.d("TestMapActivity onCreate Latitude", latitude.toString())
                Log.d("TestMapActivity onCreate Longitude", longitude.toString())

                // Update UI components with the current location
//                latitudeTextView.text = "Latitude: $latitude"
//                longitudeTextView.text = "Longitude: $longitude"
            }
        })

        // Load offline map first
        openMapFromAssets()

        // Start tracking the location and updating the marker
//        startLocationUpdate()

        binding.startSimulationButton.setOnClickListener {
            startSimulation()
        }
        binding.stopSimulationButton.setOnClickListener {
            stopSimulation()
        }
        binding.backButton.setOnClickListener {
            val intent = Intent(this, ScheduleActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }
        binding.speedUpButton.setOnClickListener {
            speedUp()
        }

        binding.slowDownButton.setOnClickListener {
            slowDown()
        }

        binding.arriveButton.setOnClickListener {
            confirmArrival()
        }
    }

    /**
     * mark a bus stop as "arrived" and prevent duplicate arrivals.
     */
    @SuppressLint("LongLogTag")
    private fun confirmArrival() {
        if (upcomingStop.isNotEmpty()) {
            Toast.makeText(this, "Arrived at $upcomingStop", Toast.LENGTH_SHORT).show()
            Log.d("TestMapActivity confirmArrival", "✅ Arrived at bus stop: $upcomingStop")

            // Move to the next stop
            moveToNextStop()
        } else {
            Toast.makeText(this, "No upcoming stop detected!", Toast.LENGTH_SHORT).show()
            Log.e("TestMapActivity confirmArrival", "❌ Arrival attempted with no upcoming stop set.")
        }
    }

    /**
     * Moves to the next scheduled bus timing stop in the route.
     */
    @SuppressLint("LongLogTag")
    private fun moveToNextStop() {
        if (scheduleList.isEmpty()) {
            Log.e("TestMapActivity moveToNextStop", "❌ No schedule data available.")
            Toast.makeText(this, "No schedule available", Toast.LENGTH_SHORT).show()
            return
        }

        // Find the index of the current upcoming stop
        val currentIndex = scheduleList.first().busStops.indexOfFirst { it.time == upcomingStop }

        if (currentIndex == -1 || currentIndex >= scheduleList.first().busStops.size - 1) {
            Log.d("TestMapActivity moveToNextStop", "✅ Reached the final stop, no further stops available.")
            Toast.makeText(this, "You have reached the final stop.", Toast.LENGTH_SHORT).show()
            return
        }

        // Move to the next stop
        val nextStop = scheduleList.first().busStops[currentIndex + 1]
        upcomingStop = nextStop.time
        runOnUiThread {
            upcomingBusStopTextView.text = "Next Stop: ${nextStop.name} at ${nextStop.time}"
            timingPointValueTextView.text = nextStop.time
        }

        Log.d("TestMapActivity moveToNextStop", "🔹 Moving to next stop: ${nextStop.name} at ${nextStop.time}")
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

            val stops = firstSchedule.busStops.map { it.name }
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
            Log.d("TestMapActivity updateBusNameFromConfig", "✅ Bus name updated: $busname for AID: $aid")
        } else {
            Log.e("TestMapActivity updateBusNameFromConfig", "❌ No matching bus found for AID: $aid")
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
        simulationStartTime = System.currentTimeMillis() // Track simulation time
        simulationHandler = Handler(Looper.getMainLooper())
        currentRouteIndex = 0

        initializeTimingPoint()

        // Start the actual time from the schedule's start time
        startActualTimeUpdater()

        simulationRunnable = object : Runnable {
            @SuppressLint("LongLogTag")
            @RequiresApi(Build.VERSION_CODES.M)
            override fun run() {
                if (currentRouteIndex < route.size - 1) {
                    val start = route[currentRouteIndex]
                    val end = route[currentRouteIndex + 1]

                    val startLat = start.latitude!!
                    val startLon = start.longitude!!
                    val endLat = end.latitude!!
                    val endLon = end.longitude!!

                    val distanceMeters = calculateDistance(startLat, startLon, endLat, endLon)
                    Log.d("startSimulation distanceMeters", "distanceMeters: ${distanceMeters}")
                    // If simulationSpeedFactor is 0, bus is stopped; simply re-post without movement.
                    if (simulationSpeedFactor <= 0) {
                        simulationHandler.postDelayed(this, 1000)
                        return
                    }
                    // Adjust travel time: base travel time divided by the speed factor.
                    val baseSpeedMetersPerSecond = speed / 3.6
                    val travelTimeSeconds = (distanceMeters / baseSpeedMetersPerSecond)
                    val steps = (travelTimeSeconds * 10).toInt() // Update every 100ms

                    // Cancel any previously running simulation steps
                    val handler = Handler(Looper.getMainLooper())
                    handler.removeCallbacksAndMessages(null)

                    simulateMovement(startLat, startLon, endLat, endLon, steps)

                    simulationHandler.postDelayed({
                        currentRouteIndex++
                        simulationHandler.post(this)
                    }, (travelTimeSeconds * 1000).toLong())
                } else {
                    isSimulating = false
                    stopActualTimeUpdater()
                    Toast.makeText(this@TestMapActivity, "Simulation completed", Toast.LENGTH_SHORT).show()
                    showSummaryDialog() // Show the summary dialog after simulation completes.
                }
            }
        }
        simulationHandler.post(simulationRunnable)
        Toast.makeText(this, "Simulation started", Toast.LENGTH_SHORT).show()
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
        speed += 5.0f
        Log.d("SpeedControl", "Speed increased to $speed km/h")
    }

    /**
     * decrease speed factor
     */
    @SuppressLint("LongLogTag")
    private fun slowDown() {
        if (speed > 5.0f) {
            speed -= 5.0f
            Log.d("SpeedControl", "Speed decreased to $speed km/h")
        }
    }

    /**
     * Starts actual time from schedule
     */
    private fun startActualTimeUpdater() {
        if (scheduleList.isNotEmpty()) {
            val startTimeStr = scheduleList.first().startTime  // e.g. "08:00"
            val timeParts = startTimeStr.split(":")
            if (timeParts.size == 2) {
                simulatedStartTime.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
                simulatedStartTime.set(Calendar.MINUTE, timeParts[1].toInt())
                simulatedStartTime.set(Calendar.SECOND, 0)
            }
        }

        actualTimeHandler = Handler(Looper.getMainLooper())
        actualTimeRunnable = object : Runnable {
            override fun run() {
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                actualTimeTextView.text = timeFormat.format(simulatedStartTime.time)

                // Always advance time by 1 second per tick (simulate real clock)
                simulatedStartTime.add(Calendar.SECOND, 1)

                // Update schedule status based on the new simulated time
                checkScheduleStatus()

                actualTimeHandler.postDelayed(this, 1000)
            }
        }
        actualTimeHandler.post(actualTimeRunnable)
    }

    /** Stops actual time */
    private fun stopActualTimeUpdater() {
        actualTimeHandler.removeCallbacks(actualTimeRunnable)
    }

    /**
     * Checks and updates the bus schedule status by comparing the scheduled arrival time
     * with the predicted arrival time.
     *
     * The predicted arrival is computed as:
     *   predictedArrival = (apiTime - baseTime) + actualTime
     *
     * The difference (deltaSec) in seconds between the scheduled arrival and the predicted
     * arrival is compared against a tolerance of 60 seconds.
     * - If |deltaSec| ≤ 60, the status is set to "On Time".
     * - If deltaSec > 60, the bus is considered to be "Ahead by" deltaSec seconds.
     * - If deltaSec < -60, the bus is "Behind by" the absolute value of deltaSec seconds.
     *
     * Instead of displaying a time range, the threshold is now shown as a single value.
     * For example, if the tolerance is 60 seconds, the thresholdRangeValueTextView will display "60 sec".
     *
     * This method logs all of the following values:
     * - Schedule Status (statusText)
     * - Next Timing Point (the text of timingPointValueTextView)
     * - API Time (as shown in ApiTimeValueTextView)
     * - Actual Time (current simulated time)
     * - Threshold Range (the tolerance in seconds)
     */
    @SuppressLint("LongLogTag")
    private fun checkScheduleStatus() {
        if (scheduleList.isEmpty()) return

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        try {
            // 1. Retrieve the scheduled arrival (next timing point) from the UI.
            val scheduledTimeStr = timingPointValueTextView.text.toString()
            val scheduledTime = timeFormat.parse(scheduledTimeStr)

            // 2. Define the base time (schedule start) by appending ":00" for seconds.
            val baseTimeStr = scheduleList.first().startTime + ":00"
            val baseTime = timeFormat.parse(baseTimeStr)

            // 3. Retrieve the API time from its TextView.
            var apiTimeStr = ApiTimeValueTextView.text.toString()
            Log.d("TestMapActivity checkScheduleStatus apiTimeStr", "📦 Retrieved API Time String (initial): $apiTimeStr")
            Log.d("TestMapActivity checkScheduleStatus apiTimeStr", "Upcoming stop address: $upcomingStopName")

            // Override the first api time
            val firstAddress = scheduleList.firstOrNull()?.busStops?.firstOrNull()?.address
            Log.d("TestMapActivity checkScheduleStatus apiTimeStr", "📍 First Bus Stop with Timing Point: $firstAddress")
            Log.d("TestMapActivity checkScheduleStatus apiTimeStr", "📍 Current Stop Index: $currentStopIndex")

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

                val adjustedApiTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(adjustedCalendar.time)

                runOnUiThread {
                    ApiTimeValueTextView.text = adjustedApiTime
                }

                Log.d("TestMapActivity updateApiTime", "🕒 API Time overridden at first timing point: $adjustedApiTime")
            }

            val apiTime = timeFormat.parse(apiTimeStr)

            // 4. Get the actual (simulated) time.
            val actualTimeStr = timeFormat.format(simulatedStartTime.time)
            val actualTime = timeFormat.parse(actualTimeStr)

            // 5. Compute the predicted arrival time.
            val predictedArrivalMillis = (apiTime.time - baseTime.time) + actualTime.time
            val predictedArrival = Date(predictedArrivalMillis)

            // 6. Calculate the difference (delta) in seconds.
            val deltaSec = ((scheduledTime.time - predictedArrival.time) / 1000).toInt()

            // 7. Define a tolerance value (in seconds).
            val tolerance = 0

            val expectedTravelSec = ((scheduledTime.time - baseTime.time) / 1000).toInt()
            val farBehindThreshold = (expectedTravelSec * 0.2).toInt()  // or 0.25, tweak as needed
            // 8. Determine the schedule status text based on deltaSec.
            val statusText = when {
                Math.abs(deltaSec) <= tolerance -> "On Time (${Math.abs(deltaSec)} sec)"
                deltaSec > tolerance -> "Ahead by $deltaSec sec"
                deltaSec < -farBehindThreshold -> "So Far Behind by ${-deltaSec} sec"
                else -> "Behind by ${-deltaSec} sec"
            }

            // 9. Update the UI: set the schedule status and display the tolerance as a single value.
            runOnUiThread {
                scheduleStatusValueTextView.text = statusText
                thresholdRangeValueTextView.text = "$tolerance sec"  // Displaying a single threshold value
                if (Math.abs(deltaSec) <= tolerance) {
                    scheduleStatusValueTextView.setTextColor(Color.GREEN)
                } else {
                    scheduleStatusValueTextView.setTextColor(Color.RED)
                }
            }

            // Log all the desired values.
            Log.d("TestMapActivity checkScheduleStatus", "Schedule Status: $statusText")
            Log.d("checkScheduleStatus", "Next Timing Point: ${timingPointValueTextView.text}")
            Log.d("TestMapActivity checkScheduleStatus", "API Time: $apiTimeStr")
            Log.d("TestMapActivity checkScheduleStatus", "Actual Time: $actualTimeStr")
            Log.d("TestMapActivity checkScheduleStatus", "Threshold Range: $tolerance sec")
            Log.d("TestMapActivity checkScheduleStatus", "Predicted Arrival: ${timeFormat.format(predictedArrival)}")
            Log.d("TestMapActivity checkScheduleStatus", "Delta (sec): $deltaSec")
            Log.d("TestMapActivity checkScheduleStatus", "Far Behind Threshold: $farBehindThreshold sec")
        } catch (e: Exception) {
            Log.e("TestMapActivity checkScheduleStatus", "Error parsing times: ${e.localizedMessage}")
        }
    }

    /**
     * Converts seconds to mm:ss format.
     */
    private fun formatSecondsAsTime(seconds: Int): String {
        val sign = if (seconds < 0) "-" else ""
        val absSeconds = Math.abs(seconds)
        val hours = absSeconds / 3600
        val minutes = (absSeconds % 3600) / 60
        val secs = absSeconds % 60
        return String.format("%s%02d:%02d:%02d", sign, hours, minutes, secs)
    }

    /** Interpolates movement between two points with dynamic bearing and speed updates */
    private fun simulateMovement(startLat: Double, startLon: Double, endLat: Double, endLon: Double, steps: Int) {
        val latStep = (endLat - startLat) / steps
        val lonStep = (endLon - startLon) / steps

        var step = 0

        // Cancel previous one safely
        stepRunnable?.let { stepHandler.removeCallbacks(it) }

        stepRunnable = object : Runnable {
            override fun run() {
                if (step < steps) {
                    val newLat = startLat + (latStep * step)
                    val newLon = startLon + (lonStep * step)

                    checkPassedStops(newLat, newLon)
                    updateTimingPointBasedOnLocation(newLat, newLon)

                    runOnUiThread {
                        speedTextView.text = "Speed: ${"%.2f".format(speed)} km/h"
                    }

                    bearing = calculateBearing(newLat, newLon, endLat, endLon)
                    updateBusMarkerPosition(newLat, newLon, bearing)

                    lastLatitude = newLat
                    lastLongitude = newLon

                    if (step % 3 == 0) {
                        Log.d("simulateMovement", "Lat: $newLat, Lon: $newLon, Bearing: $bearing")
                    }

                    step++
                    stepHandler.postDelayed(this, 100)
                }
            }
        }

        stepHandler.post(stepRunnable!!)
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
    @SuppressLint("LongLogTag")
    private fun updateApiTime() {
        // If locked, simply reuse the locked value — only if upcomingStopName matches the first timing point
        if (apiTimeLocked && lockedApiTime != null) {
            val firstAddress = scheduleList.firstOrNull()?.busStops?.firstOrNull()?.address
            if (upcomingStopName == firstAddress) {
                runOnUiThread { ApiTimeValueTextView.text = lockedApiTime }
                Log.d("TestMapActivity updateApiTime", "API time locked, using last computed value: $lockedApiTime")
            } else {
                Log.d("TestMapActivity updateApiTime", "⏩ Skipped locked API time update because upcomingStopName == firstAddress")
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
        Log.d("TestMapActivity updateApiTime", "Timing list: $timingList")

        val upcomingAddress = upcomingStop
        Log.d("TestMapActivity updateApiTime", "Upcoming stop address: $upcomingAddress")

        // Find the target index.
        val targetIndex = timingList.indexOfFirst {
            it.address?.equals(upcomingAddress, ignoreCase = true) == true
        }
        if (targetIndex == -1) {
            Log.e("TestMapActivity updateApiTime", "Upcoming stop address not found in timing list.")
            return
        }
        Log.d("TestMapActivity updateApiTime", "Found target index: $targetIndex")

        // Compute the total duration.
        val totalDurationMinutes = calculateDurationForUpdate(timingList, scheduleList, targetIndex)
        if (totalDurationMinutes == null) {
            Log.d("TestMapActivity updateApiTime", "Upcoming bus stop not scheduled. Skipping API update.")
            // If we already computed a final value before, do not override.
            return
        }
        Log.d("TestMapActivity updateApiTime", "Total duration in minutes: $totalDurationMinutes")

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
            Log.d("TestMapActivity updateApiTime", "API Time updated to: $updatedApiTime")
        } else {
            Log.d("TestMapActivity updateApiTime", "⏩ Skipped updating API Time because upcomingStopName == firstAddress")
        }

        Log.d("TestMapActivity updateApiTime", "API Time updated to: $updatedApiTime")

        // If the upcoming stop is the final scheduled stop, lock the API time.
        val lastScheduledAddress = getLastScheduledAddress(timingList, scheduleList)
        if (lastScheduledAddress != null &&
            upcomingAddress.equals(lastScheduledAddress, ignoreCase = true)) {
            apiTimeLocked = true
            lockedApiTime = updatedApiTime
            Log.d("TestMapActivity updateApiTime", "Final scheduled bus stop reached. API time locked.")
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
        // Always update if targetIndex == 0 (even if unscheduled)
        if (targetIndex == 0) {
            return timingList.subList(0, 1).sumOf { it.duration }
        }
        // If targetIndex is scheduled...
        if (targetIndex in scheduledIndices) {
            val pos = scheduledIndices.indexOf(targetIndex)
            return if (pos < scheduledIndices.size - 1) {
                val nextScheduledIndex = scheduledIndices[pos + 1]
                timingList.subList(0, nextScheduledIndex).sumOf { it.duration }
            } else {
                // Last scheduled stop: sum entire list.
                timingList.sumOf { it.duration }
            }
        }
        // Not scheduled → return null so that update is skipped.
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
            Log.d("TestMapActivity", "🔹 Initial timing point set to: $upcomingStop")
        }

        // Check which timing point to display
        for ((index, stop) in stopList.withIndex()) {
            val stopLat = stop.latitude
            val stopLon = stop.longitude
            val distance = calculateDistance(currentLat, currentLon, stopLat, stopLon)

            val stopPassThreshold = 25.0 // Within 25 meters

            if (distance <= stopPassThreshold) {
                Log.d(
                    "TestMapActivity updateTimingPointBasedOnLocation",
                    "✅ Passed stop ${stop.name} at ${stop.time} (Distance: ${"%.2f".format(distance)}m)"
                )

                val nextTimingPoint = if (index + 1 < stopList.size) {
                    stopList[index + 1].time + ":00"
                } else {
                    firstSchedule.endTime + ":00" // Last stop reached
                }

                runOnUiThread {
                    timingPointValueTextView.text = nextTimingPoint
                }

                upcomingStop = nextTimingPoint
                Log.d("TestMapActivity updateTimingPointBasedOnLocation", "🔹 Next timing point: $nextTimingPoint")
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
            Log.d("TestMapActivity", "Initial Timing Point: $firstTimingPoint")
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

    @SuppressLint("LongLogTag")
    private fun checkPassedStops(currentLat: Double, currentLon: Double) {
        if (stops.isEmpty()) {
            Log.d("TestMapActivity checkPassedStops", "❌ No bus stops available.")
            return
        }

        if (currentStopIndex >= stops.size) {
            Log.d("TestMapActivity checkPassedStops", "✅ All stops have been passed.")
            return
        }

        val nextStop = stops[currentStopIndex]
        val stopLat = nextStop.latitude ?: return
        val stopLon = nextStop.longitude ?: return
        stopAddress = nextStop.address ?: getUpcomingBusStopName(stopLat, stopLon)
        upcomingStop = stopAddress
        val distance = calculateDistance(currentLat, currentLon, stopLat, stopLon)
        val stopPassThreshold = 25.0 // Normal detection range
        val failSafeThreshold = 100.0 // Fail-safe trigger range

        if (distance <= stopPassThreshold) {
            Log.d(
                "TestMapActivity checkPassedStops",
                "✅ Nearest stop passed: $stopLat, $stopLon (Distance: ${"%.2f".format(distance)} meters) at $stopAddress"
            )

            runOnUiThread {
                upcomingBusStopTextView.text = "$stopAddress"
                upcomingStop = stopAddress
            }

            passedStops.add(nextStop)
            currentStopIndex++

            // Build timing list and update API time only if the stop exists in it
            val timingList = BusStopWithTimingPoint.fromRouteData(busRouteData.first())
            if (timingList.any { it.address?.equals(stopAddress, ignoreCase = true) == true }) {
                updateApiTime()
            } else {
                Log.d("TestMapActivity checkPassedStops", "BusStopWithTimingPoint not available for $stopAddress. Skipping API time update.")
            }

            if (currentStopIndex < stops.size) {
                val upcomingStop = stops[currentStopIndex]
                upcomingStopName = getUpcomingBusStopName(upcomingStop.latitude ?: 0.0, upcomingStop.longitude ?: 0.0)

                Log.d(
                    "TestMapActivity checkPassedStops",
                    "🛑 No stop passed. Nearest stop: ${upcomingStop.latitude}, ${upcomingStop.longitude} is ${
                        "%.2f".format(distance)
                    } meters away at $upcomingStopName."
                )

                runOnUiThread {
                    upcomingBusStopTextView.text = "$upcomingStopName"
                }
            } else if (distance > stopPassThreshold && distance <= failSafeThreshold) {
                Log.w("TestMapActivity checkPassedStops", "⚠️ Warning: No bus stop detected within expected range!")
                runOnUiThread {
                    Toast.makeText(this@TestMapActivity, "⚠️ Warning: No bus stop detected!", Toast.LENGTH_LONG).show()
                }
            } else if (distance > failSafeThreshold) {
                Log.e("TestMapActivity checkPassedStops", "❌ Missed stop! Triggering fail-safe...")
                triggerMissedStopAlert()
            }
        } else {
            val upcomingStopName = getUpcomingBusStopName(stopLat, stopLon)

            Log.d(
                "TestMapActivity checkPassedStops",
                "🛑 No stop passed. Nearest stop: ${nextStop.latitude}, ${nextStop.longitude} is ${
                    "%.2f".format(distance)
                } meters away at $upcomingStopName."
            )

            runOnUiThread {
                upcomingBusStopTextView.text = "$upcomingStopName"
                upcomingStop = upcomingStopName
            }
        }
    }

    /**
     * This function is triggered when the bus misses a stop.
     */
    private fun triggerMissedStopAlert() {
        runOnUiThread {
            // Show the button when a stop is missed
            arriveButtonContainer.visibility = View.VISIBLE

            val alertDialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Missed Stop Alert!")
                .setMessage("You may have missed a bus stop. Please verify your location and route.")
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .setCancelable(false)
                .create()

            alertDialog.show()
        }
    }

    /** Finds the nearest upcoming bus stop */
    @SuppressLint("LongLogTag")
    private fun getUpcomingBusStopName(lat: Double, lon: Double): String {
        try {
            Log.d("TestMapActivity getUpcomingBusStopName", "JSON String: $jsonString")

            // Convert jsonString into a JSONArray
            val jsonArray = JSONArray(jsonString)

            if (jsonArray.length() == 0) {
                Log.e("TestMapActivity getUpcomingBusStopName", "JSON array is empty")
                return "No Upcoming Stop"
            }

            // Get the first object in the array
            val jsonObject = jsonArray.getJSONObject(0)

            // Ensure the key exists
            if (!jsonObject.has("next_points")) {
                Log.e("TestMapActivity getUpcomingBusStopName", "Missing 'next_points' key")
                return "No Upcoming Stop"
            }

            val routeArray = jsonObject.getJSONArray("next_points")

            if (routeArray.length() == 0) {
                Log.e("TestMapActivity getUpcomingBusStopName", "next_points array is empty")
                return "No Upcoming Stop"
            }

            var nearestStop: String? = null
            var minDistance = Double.MAX_VALUE

            for (i in 0 until routeArray.length()) {
                val stop = routeArray.getJSONObject(i)

                if (!stop.has("latitude") || !stop.has("longitude") || !stop.has("address")) {
                    Log.e("TestMapActivity getUpcomingBusStopName", "Missing stop fields at index $i")
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
            Log.e("TestMapActivity getUpcomingBusStopName", "Error: ${e.localizedMessage}", e)
            return "TestMapActivity getUpcomingBusStopName Error Retrieving Stop"
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
        Log.d("TestMapActivity drawPolyline", "Drawing polyline with route: $route")

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

            Log.d("TestMapActivity drawPolyline", "✅ Polyline drawn with ${routePoints.size} points.")
        } else {
            Log.e("TestMapActivity drawPolyline", "❌ No route data available for polyline.")
        }
    }

    /** Move the bus marker dynamically with updated bearing */
    private fun updateBusMarkerPosition(lat: Double, lon: Double, bearing: Float) {
        val newPosition = LatLong(lat, lon)
        val rotatedBitmap = rotateDrawable(bearing)

        if (busMarker == null) {
            busMarker = org.mapsforge.map.layer.overlay.Marker(
                newPosition, rotatedBitmap, 0, 0
            )
            binding.map.layerManager.layers.add(busMarker)
        } else {
            busMarker?.let {
                it.latLong = newPosition
                it.bitmap = rotatedBitmap
                binding.map.invalidate()
            }
        }

        // Apply map rotation
        binding.map.setRotation(-bearing) // Negative to align with compass movement

        // Scale the map to prevent cropping
        binding.map.scaleX = 1.5f  // Adjust scaling factor
        binding.map.scaleY = 1.5f

        // Keep the map centered on the bus location
        binding.map.setCenter(newPosition)
        binding.map.invalidate() // Force redraw
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
     * Initialize UI components and assign them to the corresponding views.
     */
    private fun initializeUIComponents() {
        timingPointandStopsTextView = binding.timingPointandStopsTextView
        tripEndTimeTextView = binding.tripEndTimeTextView
        // Hardcoded values for testing
        if (scheduleList.isNotEmpty()) {
            val scheduleItem = scheduleList.first()

            // Extract stop names and times dynamically
            val stopsInfo = scheduleItem.busStops.joinToString(", ") { "${it.name} - ${it.time}" }

            // Set text views with extracted stop info
            timingPointandStopsTextView.text = stopsInfo
            tripEndTimeTextView.text = scheduleItem.endTime
        }
        actualTimeTextView = binding.actualTimeValueTextView
        timingPointValueTextView = binding.timingPointValueTextView
        ApiTimeValueTextView = binding.ApiTimeValueTextView
        scheduleStatusValueTextView = binding.scheduleStatusValueTextView
        thresholdRangeValueTextView = binding.thresholdRangeValueTextView
        speedTextView = binding.speedTextView
        upcomingBusStopTextView = binding.upcomingBusStopTextView
        arriveButtonContainer = findViewById(R.id.arriveButtonContainer)
    }

    /**
     * Retrieves default configuration values for the activity, such as latitude, longitude, bearing, and more.
     */
    @SuppressLint("LongLogTag")
    private fun getDefaultConfigValue() {
//        busConfig = intent.getStringExtra(Constant.deviceNameKey).toString()
//        Toast.makeText(this, "arrBusDataOnline1: ${arrBusData}", Toast.LENGTH_SHORT).show()
        Log.d("TestMapActivity getDefaultConfigValue busConfig", arrBusData.toString())
        Log.d("TestMapActivity getDefaultConfigValue arrBusDataOnline1", arrBusData.toString())
        Log.d("TestMapActivity getDefaultConfigValue config", config.toString())
        arrBusData = config!!
        arrBusData = arrBusData.filter { it.aid != aid }
//        Toast.makeText(this, "getDefaultConfigValue arrBusDataOnline2: ${arrBusData}", Toast.LENGTH_SHORT).show()
        Log.d("TestMapActivity getDefaultConfigValue arrBusDataOnline2", arrBusData.toString())
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
            Log.d("TestMapActivity getDefaultConfigValue MarkerDrawable", "Bus symbol drawable applied")
        }
    }


    /**
     * Loads the offline map from assets and configures the map.
     * Prevents adding duplicate layers.
     */
    @SuppressLint("LongLogTag")
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
            Log.d("TestMapActivity openMapFromAssets", "✅ Offline map added successfully.")
        } else {
            Log.d("TestMapActivity openMapFromAssets", "⚠️ Offline map layer already exists. Skipping duplicate addition.")
        }

        binding.map.setCenter(LatLong(latitude, longitude)) // Set the default location to center the bus marker
//        binding.map.setCenter(LatLong(-36.855647, 174.765249)) // Airedale
//        binding.map.setCenter(LatLong(-36.8485, 174.7633)) // Auckland, NZ
        binding.map.setZoomLevel(17) // Set default zoom level
//        binding.map.setZoomLevel(11) // Set default zoom level

        // **Initialize the bus marker and ma**
        binding.map.post {
            drawPolyline()  // Draw polyline first
            addBusStopMarkers(stops)
            addBusMarker(latitude, longitude) // 🔁 Move marker here so it is on top
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
            val stopName = when (index) {
                0 -> "Start"
                totalStops - 1 -> "End"
                else -> index.toString() // Numbered stops
            }

            val formattedStopName = stopName.replace("Stop ", "").trim()
            val isRed = redBusStops.any { it.replace("Stop ", "").trim().equals(formattedStopName, ignoreCase = true) }
            Log.d("TestMapActivity addBusStopMarkers", "Checking stop: $stopName, isRed: $isRed (stored stops: $redBusStops)")

            val busStopSymbol = Helper.createBusStopSymbol(applicationContext, index, totalStops, isRed)
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

    /** Stops the date/time updater when the activity is destroyed. */
//    private fun stopDateTimeUpdater() {
//        dateTimeHandler.removeCallbacks(dateTimeRunnable)
//    }

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
        Log.d("TestMapActivity", "🗑️ Removed polyline on destroy.")
    }
}