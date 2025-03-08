package com.jason.publisher

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.jason.publisher.databinding.ActivityScheduleBinding
import com.jason.publisher.model.Bus
import com.jason.publisher.model.BusDataCache
import com.jason.publisher.model.BusItem
import com.jason.publisher.model.BusRoute
import com.jason.publisher.model.BusScheduleInfo
import com.jason.publisher.model.BusStop
import com.jason.publisher.model.RouteData
import com.jason.publisher.model.ScheduleItem
import com.jason.publisher.services.MqttManager
import com.jason.publisher.utils.NetworkStatusHelper
import org.json.JSONObject
import org.osmdroid.config.Configuration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduleBinding
    private lateinit var mqttManagerConfig: MqttManager
    private lateinit var mqttManager: MqttManager
    private lateinit var connectionStatusTextView: TextView
    private val REQUEST_MANAGE_EXTERNAL_STORAGE = 1001
    private val REQUEST_WRITE_PERMISSION = 1002

    private var latitude = 0.0
    private var longitude = 0.0
    private var aid = ""
    private var jsonString = ""
    private var token = ""
    private var tokenConfigData = "oRSsbeuqDMSckyckcMyE"
    private var config: List<BusItem>? = emptyList()
    private var route: List<BusRoute> = emptyList()
    private var stops: List<BusStop> = emptyList()
    private var busRouteData: List<RouteData> = emptyList()
    private var scheduleData: List<ScheduleItem> = emptyList()
    private var durationBetweenStops: List<Double> = emptyList()
    private var arrBusData: List<BusItem> = emptyList()

    private lateinit var dateTimeHandler: Handler
    private lateinit var dateTimeRunnable: Runnable
    private lateinit var multiColorTimelineView: MultiColorTimelineView
    private lateinit var workTable: TableLayout
    private val timelineRange = Pair("08:00", "11:10")
    private lateinit var scheduleTable: TableLayout

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

//    private val dummyScheduleData = listOf(
//        ScheduleItem(
//            "Route 1",
//            "08:00",
//            "08:35",
//            listOf(
//                BusScheduleInfo("Stop 2", "08:05", -36.854209, 174.767755, "25-29 Symonds Street"),
//                BusScheduleInfo("Stop S/E", "08:35", -36.854685, 174.764528, "39 Airedale Street")
//            )
//        ),
//        ScheduleItem(
//            "Route 2",
//            "09:30",
//            "10:00",
//            listOf(
//                BusScheduleInfo("Stop 3", "09:45", -36.855281, 174.767117, "52 Symonds Street"),
//                BusScheduleInfo("Stop S/E", "10:00", -36.854685, 174.764528, "39 Airedale Street")
//            )
//        ),
//        ScheduleItem(
//            "Route 3",
//            "10:20",
//            "11:10",
//            listOf(
//                BusScheduleInfo("Stop 1", "10:25", -36.853677, 174.766063, "27 St Paul Street"),
//                BusScheduleInfo("Stop 4", "10:35", -36.856047, 174.765309, "89 Airedale Street")
//            )
//        ),
//        ScheduleItem(
//            "Route 4",
//            "12:30",
//            "12:10",
//            listOf(
//                BusScheduleInfo("Stop 2", "11:45", -36.854209, 174.767755, "25-29 Symonds Street"),
//                BusScheduleInfo("Stop 3", "12:00", -36.855281, 174.767117, "52 Symonds Street"),
//                BusScheduleInfo("Stop 4", "12:10", -36.856047, 174.765309, "89 Airedale Street")
//            )
//        ),
//        ScheduleItem(
//            "Route 5",
//            "12:30",
//            "13:00",
//            listOf(
//                BusScheduleInfo("Stop 1", "12:40", -36.853677, 174.766063, "27 St Paul Street"),
//                BusScheduleInfo("Stop 4", "12:50", -36.856047, 174.765309, "89 Airedale Street")
//            )
//        )
//    )

    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check and request permission
        requestAllFilesAccessPermission()

        // Fetch AID from the device
        aid = getAndroidId()
        Log.d("TimeTableActivity", "Fetched AID: $aid")

        // Initialize MQTT managers
        mqttManagerConfig = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID, username = tokenConfigData)
        mqttManager = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID)

        // Set up network status UI
        NetworkStatusHelper.setupNetworkStatus(this, binding.connectionStatusTextView, binding.networkStatusIndicator)

        // Load configuration
        Configuration.getInstance().load(this, getSharedPreferences(getString(R.string.app_name), MODE_PRIVATE))

        // Connect and subscribe to MQTT
        connectAndSubscribe()

        // Start updating the date/time
        startDateTimeUpdater()

        scheduleTable = findViewById(R.id.scheduleTable)

//        // Populate only the first 3 schedule items
//        updateScheduleTable(dummyScheduleData.take(3))

        // Initialize Views
        workTable = findViewById(R.id.scheduleTable) // Ensure this matches your XML
        multiColorTimelineView = findViewById(R.id.multiColorTimelineView)

        // Debugging to check if views are properly initialized
        Log.d("ScheduleActivity onCreate", "workTable initialized: ${::workTable.isInitialized}")
        Log.d("ScheduleActivity onCreate", "multiColorTimelineView initialized: ${::multiColorTimelineView.isInitialized}")

        // Ensure updateTimeline() function exists before calling
        if (::workTable.isInitialized && ::multiColorTimelineView.isInitialized) {
            multiColorTimelineView.setTimelineRange(timelineRange.first, timelineRange.second)
            updateTimeline() // Call function to update the timeline dynamically
        } else {
            Log.e("ScheduleActivity onCreate", "❌ WorkTable or MultiColorTimelineView is not initialized!")
        }

        // Check internet connection
        if (!NetworkStatusHelper.isNetworkAvailable(this)) {
            // **Offline Mode: Load cached data**
            Toast.makeText(this, "You are disconnected from the internet. Loading data from tablet cache.", Toast.LENGTH_LONG).show()
            loadBusDataFromCache()
            loadScheduleDataFromCache()

            Log.d("MainActivity onCreate NetworkStatusHelper", "Loaded cached config: $config")
            Log.d("MainActivity onCreate NetworkStatusHelper", "Loaded cached busRoute: $route")
            Log.d("MainActivity onCreate NetworkStatusHelper", "Loaded cached busStop: $stops")
            Log.d("MainActivity onCreate NetworkStatusHelper", "Loaded cached busStop: $stops")

            // Load bus route information from offline data
            // Note in getBusRoutesOffline() the route is already contain bus stop in it
            jsonString = OfflineData.getBusRoutesOffline().toString()
            Log.d("MainActivity onCreate NetworkStatusHelper jsonString", jsonString)
            val (route, stops, durationBetweenStops) = RouteData.fromJson(jsonString)

            Log.d("MainActivity onCreate NetworkStatusHelper offline", "Updated busRoute: $route")
            Log.d("MainActivity onCreate  NetworkStatusHelperoffline", "Updated busStop: $stops")
            Log.d("MainActivity onCreate  NetworkStatusHelperoffline", "Updated durationBetweenStops: $durationBetweenStops")

            // Auto start route if the first schedule time has passed 1.5 minutes
//            startPeriodicScheduleCheck()
        } else {
            // **Online Mode: Fetch data from ThingsBoard**
            Toast.makeText(this, "Online mode: Receiving data from Thingsboard.", Toast.LENGTH_LONG).show()

            fetchConfig { success ->
                if (success) {
                    getAccessToken()
                    Log.d("MainActivity onCreate Token", token)
                    mqttManager = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID, username = token)
//                    getDefaultConfigValue()
                    requestAdminMessage()
                    connectAndSubscribe()
//                Log.d("MainActivity oncreate fetchConfig config", config.toString())
//                Log.d("MainActivity oncreate fetchConfig busRoute", route.toString())
//                Log.d("MainActivity oncreate fetchConfig busStop", stops.toString())
                } else {
                    Log.e("MainActivity onCreate", "Failed to fetch config, running in offline mode.")
                }
            }
        }

//        busDataCache = getOrCreateAid()
        val file = File("/storage/emulated/0/Documents/.vlrshiddenfolder/busDataCache.txt")
        if (file.exists()) {
            Log.d("MainActivity onCreate", "✅ File exists: ${file.absolutePath}")
        } else {
            Log.e("MainActivity onCreate", "❌ File creation failed!")
        }

        // Set up the "Start Route" button
        binding.startRouteButton.setOnClickListener {
            if (scheduleData.isNotEmpty()) {
                val firstScheduleItem = scheduleData.first() // Store first schedule item
                Log.d("ScheduleActivity testStartRouteButton firstScheduleItem", firstScheduleItem.toString())
                Log.d("ScheduleActivity testStartRouteButton before", scheduleData.toString())
//                scheduleData = scheduleData.drop(1) // Remove the first item
                Log.d("ScheduleActivity testStartRouteButton after", scheduleData.toString())
                rewriteOfflineScheduleData()

                val intent = Intent(this, MapActivity::class.java).apply {
                    putExtra("AID", aid)
                    putExtra("CONFIG", ArrayList(config))
                    putExtra("JSON_STRING", jsonString)
                    putExtra("ROUTE", ArrayList(route))
                    putExtra("STOPS", ArrayList(stops))
                    putExtra("DURATION_BETWEEN_BUS_STOP", ArrayList(durationBetweenStops))
                    putExtra("BUS_ROUTE_DATA", ArrayList(busRouteData))
                    putExtra("FIRST_SCHEDULE_ITEM", ArrayList(listOf(firstScheduleItem)))
                    putExtra("FULL_SCHEDULE_DATA", ArrayList(listOf(scheduleData)))
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up the "Start Route" button
        binding.testStartRouteButton.setOnClickListener {
            if (scheduleData.isNotEmpty()) {
                val firstScheduleItem = scheduleData.first() // Store first schedule item
                Log.d("ScheduleActivity testStartRouteButton firstScheduleItem", firstScheduleItem.toString())
                Log.d("ScheduleActivity testStartRouteButton before", scheduleData.toString())
//                scheduleData = scheduleData.drop(1) // Remove the first item
                Log.d("ScheduleActivity testStartRouteButton after", scheduleData.toString())
                rewriteOfflineScheduleData()

                val intent = Intent(this, MapActivity::class.java).apply {
                    putExtra("AID", aid)
                    putExtra("CONFIG", ArrayList(config))
                    putExtra("JSON_STRING", jsonString)
                    putExtra("ROUTE", ArrayList(route))
                    putExtra("STOPS", ArrayList(stops))
                    putExtra("DURATION_BETWEEN_BUS_STOP", ArrayList(durationBetweenStops))
                    putExtra("BUS_ROUTE_DATA", ArrayList(busRouteData))
                    putExtra("FIRST_SCHEDULE_ITEM", ArrayList(listOf(firstScheduleItem)))
                    putExtra("FULL_SCHEDULE_DATA", ArrayList(listOf(scheduleData)))
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Function to continuously check whether the first schedule's start time has passed by 1.5 minutes and automatically start the route when the condition is met
     */
    private val scheduleCheckHandler = Handler(Looper.getMainLooper())
    private lateinit var scheduleCheckRunnable: Runnable

    @SuppressLint("SimpleDateFormat", "LongLogTag")
    private fun startPeriodicScheduleCheck() {
        scheduleCheckRunnable = object : Runnable {
            override fun run() {
                if (scheduleData.isNotEmpty()) {
                    val firstSchedule = scheduleData.first()
                    val startTimeStr = firstSchedule.startTime
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val fullStartTimeStr = "$todayDate $startTimeStr"

                    try {
                        val startTime = dateFormat.parse(fullStartTimeStr) ?: return
                        val currentTime = Date()
                        val diffInMillis = currentTime.time - startTime.time
                        val diffInMinutes = diffInMillis / (1000 * 60)

                        Log.d("ScheduleActivity startPeriodicScheduleCheck",
                            "Current Time: ${dateFormat.format(currentTime)}, " +
                                    "Start Time: ${dateFormat.format(startTime)}, " +
                                    "Diff in minutes: $diffInMinutes")

                        if (diffInMinutes >= 1.5) {
                            Log.d("ScheduleActivity startPeriodicScheduleCheck", "🚀 Auto-starting route as the first schedule has passed 1.5 minutes.")
                            runOnUiThread {
                                binding.startRouteButton.performClick()
                            }
                            // Stop further checking after starting
                            scheduleCheckHandler.removeCallbacks(scheduleCheckRunnable)
                            return
                        }
                    } catch (e: Exception) {
                        Log.e("ScheduleActivity startPeriodicScheduleCheck", "Error parsing start time: ${e.message}")
                    }
                }
                // Re-run check every 1 second
                scheduleCheckHandler.postDelayed(this, 1000)
            }
        }
        // Start periodic checking
        scheduleCheckHandler.post(scheduleCheckRunnable)
    }

    /**
     * Modify the table dynamically to show only the first three schedule items.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateScheduleTable(scheduleItems: List<ScheduleItem>) {
        scheduleTable.removeViews(1, scheduleTable.childCount - 1) // Clear previous rows (except header)

        val limitedSchedule = scheduleItems.take(3) // Show only the first 3 schedule items

        for (item in limitedSchedule) {
            val row = TableRow(this)

            val routeTextView = createTableCell(item.routeNo, 0.5f)
            val stopsInfo = item.busStops.joinToString(", ") { "${it.name} - ${it.time}" } // ✅ Extract stops dynamically
            val stopTextView = createTableCell(stopsInfo, 1f)
            val startTimeTextView = createTableCell(item.startTime, 0.4f)
            val endTimeTextView = createTableCell(item.endTime, 0.4f)

            row.addView(routeTextView)
            row.addView(stopTextView)
            row.addView(startTimeTextView)
            row.addView(endTimeTextView)

            scheduleTable.addView(row)
        }
    }

    /**
     * helper function to create table cell
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun createTableCell(text: String, weight: Float): TextView {
        val params = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight) // Ensure uniform weight
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(8, 8, 8, 8)
            setTextColor(resources.getColor(R.color.white, null))
            setBackgroundResource(R.drawable.table_cell_border)
            layoutParams = params
        }
    }

    /**
     * Extracts work intervals from the schedule table and updates the timeline view.
     */
    @SuppressLint("LongLogTag")
    private fun updateTimeline() {
        if (!::workTable.isInitialized || !::multiColorTimelineView.isInitialized) {
            Log.e("ScheduleActivity updateTimeline", "WorkTable or MultiColorTimelineView is not initialized!")
            return
        }

        val workIntervals = extractWorkIntervals()
        Log.d("ScheduleActivity updateTimeline", "Work intervals extracted: $workIntervals")

        if (workIntervals.isNotEmpty()) {
            val minStartMinutes = workIntervals.minOf { convertToMinutes(it.first) }
            val maxEndMinutes = workIntervals.maxOf { convertToMinutes(it.second) }
            val minStartTime = String.format("%02d:%02d", minStartMinutes / 60, minStartMinutes % 60)
            val maxEndTime = String.format("%02d:%02d", maxEndMinutes / 60, maxEndMinutes % 60)

            multiColorTimelineView.setTimelineRange(minStartTime, maxEndTime)

            // Extract dutyName correctly
            val dutyName = scheduleData.firstOrNull()?.dutyName ?: "Work"

            // Pass dutyName to MultiColorTimelineView
            multiColorTimelineView.setTimeIntervals(workIntervals, minStartTime, maxEndTime, dutyName)
        }
    }

    // Helper function to convert a time string "HH:mm" to minutes since midnight.
    private fun convertToMinutes(time: String): Int {
        val parts = time.split(":").map { it.toInt() }
        return parts[0] * 60 + parts[1]
    }

    /**
     * Extracts work intervals from the table.
     * Reads each row's text, splits it into start and end times, and adds to the list.
     */
    @SuppressLint("LongLogTag")
    private fun extractWorkIntervals(): List<Pair<String, String>> {
        val workIntervals = mutableListOf<Pair<String, String>>()

        for (i in 1 until workTable.childCount) { // Skip header row
            val row = workTable.getChildAt(i) as? TableRow
            val startTimeView = row?.getChildAt(2) as? TextView // Adjust index if needed
            val endTimeView = row?.getChildAt(3) as? TextView // Adjust index if needed

            if (startTimeView != null && endTimeView != null) {
                val startTime = startTimeView.text.toString().trim()
                val endTime = endTimeView.text.toString().trim()
                if (startTime.isNotEmpty() && endTime.isNotEmpty()) {
                    workIntervals.add(Pair(startTime, endTime))
                }
            }
        }
        Log.d("ScheduleActivity extractWorkIntervals", "✅ Extracted Work Intervals: $workIntervals")
        return workIntervals
    }

    /** Starts a periodic task to update the current date and time in the UI. */
    private fun startDateTimeUpdater() {
        dateTimeHandler = Handler(Looper.getMainLooper())
        dateTimeRunnable = object : Runnable {
            override fun run() {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val currentDateTime = dateFormat.format(Date())
                findViewById<TextView>(R.id.currentDateTimeTextView).text = currentDateTime
                dateTimeHandler.postDelayed(this, 1000) // Update every second
            }
        }
        dateTimeHandler.post(dateTimeRunnable)
    }

    /**
     * Retrieves the access token for the current device's Android ID from the configuration list.
     */
    @SuppressLint("HardwareIds", "LongLogTag")
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
        jsonObject.put("sharedKeys","message,busRoute,busStop,config,busRouteData,scheduleData")
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

    /** Fetches the configuration data and initializes the config variable. */
    @SuppressLint("LongLogTag")
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
     * Loads bus data from the cache file if it exists and extracts route-related data.
     */
    @SuppressLint("LongLogTag")
    private fun loadBusDataFromCache() {
        val cacheFile = File(getHiddenFolder(), "busDataCache.txt")

        if (cacheFile.exists()) {
            try {
                val jsonContent = cacheFile.readText()
                val cachedData = Gson().fromJson(jsonContent, BusDataCache::class.java)

                // Ensure all values are updated correctly
                aid = cachedData.aid
                config = cachedData.config ?: emptyList()
                busRouteData = cachedData.busRouteData ?: emptyList()

                Log.d("MainActivity loadBusDataFromCache", "Loaded cached AID: $aid")
                Log.d("MainActivity loadBusDataFromCache", "Loaded cached Config: $config")
                Log.d("MainActivity loadBusDataFromCache", "Loaded cached BusRouteData: $busRouteData")

                // Extract `route`, `stops`, and `durationBetweenStops` from `busRouteData`
                val extractedData = processBusRouteData(busRouteData)
                route = extractedData.first
                stops = extractedData.second
                durationBetweenStops = extractedData.third

                Log.d("MainActivity loadBusDataFromCache", "Extracted Route: $route")
                Log.d("MainActivity loadBusDataFromCache", "Extracted Stops: $stops")
                Log.d("MainActivity loadBusDataFromCache", "Extracted Duration Between Stops: $durationBetweenStops")

            } catch (e: Exception) {
                Log.e("MainActivity loadBusDataFromCache", "Error reading bus data cache: ${e.message}")
            }
        } else {
            Log.e("MainActivity loadBusDataFromCache", "No cached bus data found.")
        }
    }

    /**
     * Loads schedule data from the cache file if it exists and extracts schedule-related data.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    private fun loadScheduleDataFromCache() {
        val cacheFile = File(getHiddenFolder(), "scheduleDataCache.txt")

        if (cacheFile.exists()) {
            try {
                val jsonContent = cacheFile.readText()
                val cachedSchedule = Gson().fromJson(jsonContent, Array<ScheduleItem>::class.java).toList()
                scheduleData = cachedSchedule


                Log.d("ScheduleActivity loadScheduleDataFromCache", "✅ Loaded cached schedule data: $scheduleData")

                // Use the loaded schedule data
                updateScheduleTable(scheduleData.take(3))
                updateTimeline()

            } catch (e: Exception) {
                Log.e("ScheduleActivity loadScheduleDataFromCache", "❌ Error reading schedule data cache: ${e.message}")
            }
        } else {
            Log.e("ScheduleActivity loadScheduleDataFromCache", "❌ No cached schedule data found.")
        }
    }

    /**
     * Processes `busRouteData` and extracts `route`, `stops`, and `durationBetweenStops`.
     *
     * @param busRouteData List of RouteData received from ThingsBoard.
     * @return Triple containing:
     *   - List<BusRoute>: All coordinates forming the route.
     *   - List<BusStop>: All stops along the route.
     *   - List<Double>: Durations between stops.
     */
    private fun processBusRouteData(busRouteData: List<RouteData>): Triple<List<BusRoute>, List<BusStop>, List<Double>> {
        val newRoute = mutableListOf<BusRoute>()
        val newStops = mutableListOf<BusStop>()
        val newDurations = mutableListOf<Double>()

        for (routeData in busRouteData) {
            // Add starting point as the first stop
            newStops.add(BusStop(latitude = routeData.startingPoint.latitude, longitude = routeData.startingPoint.longitude))

            for (nextPoint in routeData.nextPoints) {
                // Add stops
                newStops.add(BusStop(latitude = nextPoint.latitude, longitude = nextPoint.longitude))

                // Add route coordinates
                for (coord in nextPoint.routeCoordinates) {
                    newRoute.add(BusRoute(latitude = coord[1], longitude = coord[0]))
                }

                // Extract duration
                val durationValue = nextPoint.duration.split(" ")[0].toDoubleOrNull() ?: 0.0
                newDurations.add(durationValue)
            }
        }

        return Triple(newRoute, newStops, newDurations)
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

    /** Connects to the MQTT broker and subscribes to the required topics. */
    @SuppressLint("LongLogTag")
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
                    Toast.makeText(this@ScheduleActivity, "Running in offline mode. No connection to server.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Subscribes to shared data from the server.
     * Validates configuration, AID matching, and updates the route, stops, and durationBetweenStops.
     */
    @SuppressLint("LongLogTag")
    private fun subscribeSharedData() {
        mqttManager.subscribe(SUB_MSG_TOPIC) { message ->
            runOnUiThread {
                try {
                    // Parse incoming message
                    val gson = Gson()
                    val data = gson.fromJson(message, Bus::class.java)

                    // Update configuration
                    config = data.shared?.config?.busConfig
                    arrBusData = config.orEmpty()

                    Log.d("MainActivity subscribeSharedData", "Config: $config")

                    if (config.isNullOrEmpty()) {
//                        clearBusData()
                        return@runOnUiThread
                    }

                    // Check if AID matches any entry in config
                    val matchingAid = config!!.any { it.aid == aid }
                    if (!matchingAid) {
                        Toast.makeText(this, "AID does not match.", Toast.LENGTH_SHORT).show()
//                        clearBusData()
                        return@runOnUiThread
                    }

                    // Retrieve `busRouteData` from ThingsBoard
                    busRouteData = data.shared?.busRouteData1 ?: emptyList()
                    Log.d("MainActivity subscribeSharedData", "busRouteData: $busRouteData")

                    // Retrieve `scheduleData` from ThingsBoard
                    scheduleData = data.shared?.scheduleData1 ?: emptyList()
                    Log.d("MainActivity subscribeSharedData", "scheduleData: $scheduleData")

                    // **Rewrite cache when online**
                    if (NetworkStatusHelper.isNetworkAvailable(this@ScheduleActivity)) {
                        // Save data **only after all values are updated**
                        if (config!!.isNotEmpty() && route.isNotEmpty() && stops.isNotEmpty()) {
                            saveBusDataToCache()
                            saveScheduleDataToCache()
                        }
                    }

                    // Extract `route`, `stops`, and `durationBetweenStops` from `busRouteData`
                    val extractedData = processBusRouteData(busRouteData)
                    route = extractedData.first
                    stops = extractedData.second
                    durationBetweenStops = extractedData.third

                    Log.d("MainActivity subscribeSharedData", "Extracted Route: $route")
                    Log.d("MainActivity subscribeSharedData", "Extracted Stops: $stops")
                    Log.d("MainActivity subscribeSharedData", "Extracted Duration Between Stops: $durationBetweenStops")

                    if (route.isNotEmpty()) {
                        // Convert route to BusStopInfo and update ProximityManager
//                        val busStopInfoList = convertRouteToBusStopInfo(route)
//                        BusStopProximityManager.setBusStopList(busStopInfoList)

                        // Generate polyline and markers on first-time initialization
//                        if (firstTime) {
//                            firstTime = false
//                        }
                    } else {
                        Log.d("MainActivity subscribeSharedData", "No route data available.")
//                        clearBusData()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity subscribeSharedData", "Error processing shared data: ${e.message}", e)
                    Toast.makeText(this, "Error processing shared data.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Saves the latest bus data to the cache file.
     */
    private var isBusCacheUpdated = false // Flag to ensure cache is updated only once
    @SuppressLint("LongLogTag")
    private fun saveBusDataToCache() {
        if (!NetworkStatusHelper.isNetworkAvailable(this)) {
            Log.d("MainActivity saveBusDataToCache", "❌ No internet connection. Skipping cache update.")
            return
        }
        // ✅ Check if cache has already been updated
        if (isBusCacheUpdated) {
            Log.d("MainActivity saveBusDataToCache", "🚫 Skipping cache update (already updated).")
            return
        }
        val cacheFile = File(getHiddenFolder(), "busDataCache.txt") // Change extension to .txt
//        Log.d("MainActivity saveBusDataToCache aid", aid.toString())
//        Log.d("MainActivity saveBusDataToCache config", config.toString())
//        Log.d("MainActivity saveBusDataToCache busRoute", routeCache.toString())
//        Log.d("MainActivity saveBusDataToCache busStop", stopsCache.toString())

        try {
            val busData = mapOf(
                "aid" to aid,
                "busRouteData" to busRouteData,
                "config" to config
            )

            val jsonStringBusData = Gson().toJson(busData) // Convert data to JSON
            cacheFile.writeText(jsonStringBusData) // Overwrite cache file
            Log.d("MainActivity saveBusDataToCache", "✅ Bus data cache updated successfully in busDataCache.txt.")
            Toast.makeText(this, "Bus data cache updated successfully in busDataCache.txt.", Toast.LENGTH_SHORT).show()
            // ✅ Set flag to true after successful update
            isBusCacheUpdated = true
        } catch (e: Exception) {
            Log.e("MainActivity saveBusDataToCache", "❌ Error saving bus data cache: ${e.message}")
        }
    }

    /**
     * Saves the latest schedule data to the cache file.
     */
    private var isScheduleCacheUpdated = false // Flag to ensure cache is updated only once
    @SuppressLint("LongLogTag")
    private fun saveScheduleDataToCache() {
        if (!NetworkStatusHelper.isNetworkAvailable(this)) {
            Log.d("ScheduleActivity saveScheduleDataToCache", "❌ No internet connection. Skipping cache update.")
            return
        }

        // ✅ Check if cache has already been updated
        if (isScheduleCacheUpdated) {
            Log.d("MainActivity saveBusDataToCache", "🚫 Skipping cache update (already updated).")
            return
        }

        val cacheFile = File(getHiddenFolder(), "scheduleDataCache.txt")
        Log.d("ScheduleActivity saveScheduleDataToCache before", "scheduleDataCache.txt content: ${cacheFile.readText()}")
        Log.d("ScheduleActivity saveScheduleDataToCache", "Saving scheduleData: $scheduleData")
        try {
            val jsonStringScheduleData = Gson().toJson(scheduleData) // Convert to JSON using the new structure
            cacheFile.writeText(jsonStringScheduleData) // Save the data
            Log.d("ScheduleActivity saveScheduleDataToCache jsonStringScheduleData", "scheduleDataCache.txt content: ${jsonStringScheduleData}")
            Log.d("ScheduleActivity saveScheduleDataToCache after", "scheduleDataCache.txt content: ${cacheFile.readText()}")
            Log.d("ScheduleActivity saveScheduleDataToCache", "✅ Schedule data cache updated successfully.")
            Toast.makeText(this, "Schedule data cache updated successfully.", Toast.LENGTH_SHORT).show()
            isScheduleCacheUpdated = true
        } catch (e: Exception) {
            Log.e("ScheduleActivity saveScheduleDataToCache", "❌ Error saving schedule data cache: ${e.message}")
        }
    }

    /**
     * Saves the latest schedule data to the cache file.
     */
    @SuppressLint("LongLogTag")
    private fun rewriteOfflineScheduleData() {
        if (!NetworkStatusHelper.isNetworkAvailable(this)) {
            Log.d("ScheduleActivity saveScheduleDataToCache", "❌ No internet connection. rewrite data.")
            val cacheFile = File(getHiddenFolder(), "scheduleDataCache.txt")

            try {
                val jsonString = Gson().toJson(scheduleData) // Convert updated data to JSON
                cacheFile.writeText(jsonString) // Overwrite cache file
                Log.d("ScheduleActivity saveScheduleDataToCache", "✅ Schedule data cache updated successfully.")
                Toast.makeText(this, "Schedule data updated.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("ScheduleActivity saveScheduleDataToCache", "❌ Error saving schedule data cache: ${e.message}")
            }
        }
    }

    /**
     * All Files Access Permission (MANAGE_EXTERNAL_STORAGE).
     */
    @SuppressLint("LongLogTag")
    private fun requestAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
                } catch (e: Exception) {
                    Log.e("TimeTableActivity requestAllFilesAccessPermission", "Error requesting storage permission: ${e.message}")
                }
            }
        }
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

    override fun onDestroy() {
        super.onDestroy()
        NetworkStatusHelper.unregisterReceiver(this)
        dateTimeHandler.removeCallbacks(dateTimeRunnable)
    }

    /** Fetches the Android ID (AID) of the device. */
    private fun getAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }
}
