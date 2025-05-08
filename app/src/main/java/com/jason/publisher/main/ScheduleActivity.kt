package com.jason.publisher.main

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityScheduleBinding
import com.jason.publisher.main.model.Bus
import com.jason.publisher.main.model.BusDataCache
import com.jason.publisher.main.model.BusItem
import com.jason.publisher.main.model.BusRoute
import com.jason.publisher.main.model.BusScheduleInfo
import com.jason.publisher.main.model.BusStop
import com.jason.publisher.main.model.RouteData
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.services.MqttManager
import com.jason.publisher.main.utils.NetworkStatusHelper
import org.json.JSONObject
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.osmdroid.config.Configuration
import java.io.File
import java.io.IOException
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
    private lateinit var multiColorTimelineView2: MultiColorTimelineView
    private lateinit var workTable: TableLayout
    private val timelineRange = Pair("08:00", "11:10")
    private lateinit var scheduleTable: TableLayout
    private lateinit var networkStatusHelper: NetworkStatusHelper
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private val loadingBarHandler = Handler(Looper.getMainLooper())

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

        // Initialize all views up front:
        scheduleTable            = findViewById(R.id.scheduleTable)
        workTable                = findViewById(R.id.scheduleTable)
        multiColorTimelineView   = findViewById(R.id.multiColorTimelineView)
        multiColorTimelineView2 = findViewById(R.id.multiColorTimelineView2)

        // 0) init your MQTT managers *before* you ever call enterOnlineMode()/fetchConfig()
        mqttManagerConfig = MqttManager(
            serverUri = SERVER_URI,
            clientId  = CLIENT_ID,
            username  = tokenConfigData
        )
        mqttManager = MqttManager(
            serverUri = SERVER_URI,
            clientId  = CLIENT_ID
        )

        // 1. get connectivity service
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

// 2. define the callback
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // when internet is back
                runOnUiThread { enterOnlineMode() }
            }
            override fun onLost(network: Network) {
                // when internet is gone
                runOnUiThread { enterOfflineMode() }
            }
        }

// 3. register it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            // on API 21–23
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }

// 4. do an initial-mode check
        if (NetworkStatusHelper.isNetworkAvailable(this)) {
            enterOnlineMode()
        } else {
            enterOfflineMode()
        }

        // Check and request permission
        requestAllFilesAccessPermission()

        // Fetch AID from the device
        aid = getAndroidId()
        Log.d("TimeTableActivity", "Fetched AID: $aid")

        // Set up network status UI
        NetworkStatusHelper.setupNetworkStatus(this, binding.connectionStatusTextView, binding.networkStatusIndicator)

        // Load configuration
        Configuration.getInstance().load(this, getSharedPreferences(getString(R.string.app_name), MODE_PRIVATE))

        // Connect and subscribe to MQTT
        connectAndSubscribe()

        // Start updating the date/time
        startDateTimeUpdater()

        NetworkStatusHelper.setupNetworkStatus(
            this,
            findViewById(R.id.connectionStatusTextView),
            findViewById(R.id.networkStatusIndicator)
        )

        // 1. Initialize Mapsforge
        AndroidGraphicFactory.createInstance(application)

        // 2. Find the hidden MapView
        val preloadMap: MapView = findViewById(R.id.preloadMapView)

        // 3. Create tile cache
        val cache = AndroidUtil.createTileCache(
            this,
            "preloadCache",
            preloadMap.model.displayModel.tileSize,
            1f,
            preloadMap.model.frameBufferModel.overdrawFactor
        )

        // 4. Open the .map file
        val expectedMapFileSize = 363837457L
        val mapFile = File(getHiddenFolder(), "new-zealand-2.map")

        if (!mapFile.exists() || mapFile.length() != expectedMapFileSize) {
//            Toast.makeText(this, "Map file missing or corrupted. Please re-download or connect to internet.", Toast.LENGTH_LONG).show()
//            Log.e("ScheduleActivity", "Invalid or missing map file. Exists: ${mapFile.exists()}, Size: ${mapFile.length()}")
            return
        }

        // Now safe to use
        val mapStore = MapFile(mapFile)


        // 5. Create renderer and add to the hidden MapView
        val renderer = TileRendererLayer(
            cache,
            mapStore,
            preloadMap.model.mapViewPosition,
            AndroidGraphicFactory.INSTANCE
        ).apply {
            setXmlRenderTheme(InternalRenderTheme.DEFAULT)
        }

        preloadMap.layerManager.layers.add(renderer)

        // 6. Force one render cycle:
        preloadMap.post {
            preloadMap.model.mapViewPosition.setZoomLevel(16)
            preloadMap.model.mapViewPosition.setCenter(LatLong(-36.855647, 174.765249))
            preloadMap.invalidate()
        }

        // Set up the "Start Route" button
        binding.startRouteButton.setOnClickListener {
            if (scheduleData.isNotEmpty()) {
                val firstScheduleItem = scheduleData.first() // ✅ Store first schedule item
                Log.d("ScheduleActivity startRouteButton firstScheduleItem", firstScheduleItem.toString())
                Log.d("ScheduleActivity startRouteButton before", scheduleData.toString())

                // ✅ Actually remove the first item
                scheduleData = scheduleData.toMutableList().apply { removeAt(0) }
                updateScheduleTable(scheduleData.take(3))
                updateTimeline()
                rewriteOfflineScheduleData()

                Log.d("ScheduleActivity startRouteButton after", scheduleData.toString())

                val intent = Intent(this, MapActivity::class.java).apply {
                    putExtra("AID", aid)
                    putExtra("CONFIG", ArrayList(config))
                    putExtra("JSON_STRING", jsonString)
                    putExtra("ROUTE", ArrayList(route))
                    putExtra("STOPS", ArrayList(stops))
                    putExtra("DURATION_BETWEEN_BUS_STOP", ArrayList(durationBetweenStops))
                    putExtra("BUS_ROUTE_DATA", ArrayList(busRouteData))
                    putExtra("FIRST_SCHEDULE_ITEM", ArrayList(listOf(firstScheduleItem)))
                    putExtra("FULL_SCHEDULE_DATA", ArrayList(scheduleData))
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
            }
        }

// Set up the "Test Start Route" button
        binding.testStartRouteButton.setOnClickListener {
            if (scheduleData.isNotEmpty()) {
                val firstScheduleItem = scheduleData.first()
                Log.d("ScheduleActivity testStartRouteButton firstScheduleItem", firstScheduleItem.toString())
                Log.d("ScheduleActivity testStartRouteButton before", scheduleData.toString())

                // ✅ Actually remove the first item
                scheduleData = scheduleData.toMutableList().apply { removeAt(0) }
                updateScheduleTable(scheduleData.take(3))
                updateTimeline()
                rewriteOfflineScheduleData()

                Log.d("ScheduleActivity testStartRouteButton after", scheduleData.toString())

                val intent = Intent(this, TestMapActivity::class.java).apply {
                    putExtra("AID", aid)
                    putExtra("CONFIG", ArrayList(config))
                    putExtra("JSON_STRING", jsonString)
                    putExtra("ROUTE", ArrayList(route))
                    putExtra("STOPS", ArrayList(stops))
                    putExtra("DURATION_BETWEEN_BUS_STOP", ArrayList(durationBetweenStops))
                    putExtra("BUS_ROUTE_DATA", ArrayList(busRouteData))
                    putExtra("FIRST_SCHEDULE_ITEM", ArrayList(listOf(firstScheduleItem)))
                    putExtra("FULL_SCHEDULE_DATA", ArrayList(scheduleData))
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
            }
        }
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

    /**
     * Switches the activity into offline mode:
     * - Notifies the user that cached data will be used
     * - Reveals the schedule table, pagination controls, and route buttons
     * - Loads bus and schedule data from the local cache
     * - Rebuilds pagination and shows the first page
     * - Resets and redraws the timeline view based on the loaded schedule
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun enterOfflineMode() {
        Toast.makeText(this, "Offline: loading from cache…", Toast.LENGTH_LONG).show()

        // hide loading bar
        findViewById<ProgressBar>(R.id.progressBar).apply {
            visibility = View.GONE
            progress = 0
        }
        // if you used a Handler in startLoadingBar(), cancel it:
        loadingBarHandler.removeCallbacksAndMessages(null)

        binding.scheduleTable.visibility = View.VISIBLE
        binding.startRouteButton.visibility = View.VISIBLE
        binding.testStartRouteButton.visibility = View.VISIBLE
        binding.multiColorTimelineView.visibility = View.VISIBLE
        binding.multiColorTimelineView2.visibility = View.VISIBLE
        workTable.visibility             = View.VISIBLE

        loadBusDataFromCache()
        loadScheduleDataFromCache()

        multiColorTimelineView.setTimelineRange(timelineRange.first, timelineRange.second)
        updateTimeline()
    }

    /**
     * Switches the activity into online mode:
     * - Notifies the user that fresh data is being fetched from ThingsBoard
     * - Hides the offline UI elements while loading
     * - Starts the loading progress bar
     * - Fetches the configuration; on success:
     *     • Retrieves the device token
     *     • Initializes MQTT with the new token
     *     • Subscribes for admin messages and telemetry
     *   On failure, falls back to offline mode
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun enterOnlineMode() {
        Toast.makeText(this, "Online: fetching from ThingsBoard…", Toast.LENGTH_LONG).show()
        binding.scheduleTable.visibility = View.GONE
        binding.startRouteButton.visibility = View.GONE
        binding.testStartRouteButton.visibility = View.GONE
        binding.multiColorTimelineView.visibility = View.GONE
        binding.multiColorTimelineView2.visibility = View.GONE
        workTable.visibility             = View.GONE

        findViewById<ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        startLoadingBar()

        fetchConfig { success ->
            if (success) {
                getAccessToken()
                mqttManager = MqttManager(
                    serverUri = SERVER_URI,
                    clientId = CLIENT_ID,
                    username = token
                )
                requestAdminMessage()
                connectAndSubscribe()
            } else {
                enterOfflineMode()
            }
        }
    }

    /**
     * function to start loading bar from 0% to 100% with color transitioning from red to green
     */
    private fun startLoadingBar() {
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        progressBar.visibility = View.VISIBLE
        progressBar.progress   = 0

        // cancel any leftover callbacks
        loadingBarHandler.removeCallbacksAndMessages(null)

        var progress = 0
        @RequiresApi(Build.VERSION_CODES.M)
        fun updateProgress(increment: Int, delay: Long) {
            loadingBarHandler.postDelayed({
                progress += increment
                progressBar.progress = progress
                progressBar.progressTintList =
                    ContextCompat.getColorStateList(this, R.color.green)
                if (progress == 100) {
                    Toast.makeText(this, "All data successfully received!", Toast.LENGTH_SHORT).show()
                    showCacheCompleteDialog()
                }
            }, delay)
        }

        // Step 1: 33.33% Green
        updateProgress(33, 2000)

        // Step 2: 66.66% Green
        updateProgress(33, 7000)

        // Step 3: 100% Green
        updateProgress(34, 9500)
    }

    /**
     * a pop-up dialog that appears after the progress reaches 100%
     */
    private fun showCacheCompleteDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cache Complete")
            .setMessage("All data has been cached successfully. Please turn off your Wi-Fi to switch to online mode.")
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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
        scheduleTable.removeViews(2, scheduleTable.childCount - 2) // Keep header + separator

        for (item in scheduleItems) {
            val row = TableRow(this).apply {
                layoutParams = TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT,
                    TableLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val routeTextView = createStyledCell(item.routeNo, Gravity.START)
            val startTimeView = createStyledCell(item.startTime, Gravity.CENTER)
            val endTimeView = createStyledCell(item.endTime, Gravity.END)

            row.addView(routeTextView)
            row.addView(startTimeView)
            row.addView(endTimeView)

            scheduleTable.addView(row)

            // Add separator line after each row
            val separator = View(this).apply {
                layoutParams = TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(Color.BLACK)
            }
            scheduleTable.addView(separator)
        }
    }

    /**
     * function to style the table cell
     */
    private fun createStyledCell(text: String, gravity: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f // smaller text
            setTextColor(Color.BLACK)
            setPadding(4, 4, 4, 4) // smaller padding
            this.gravity = gravity
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
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

        val (workIntervals, dutyNames) = extractWorkIntervalsAndDutyNames()
        Log.d("ScheduleActivity updateTimeline", "Work intervals extracted: $workIntervals")

        if (workIntervals.isNotEmpty()) {
            val halfSize = workIntervals.size / 2

            val firstHalfIntervals = workIntervals.take(halfSize)
            val secondHalfIntervals = workIntervals.drop(halfSize)

            val firstHalfDutyNames = dutyNames.take(halfSize)
            val secondHalfDutyNames = dutyNames.drop(halfSize)

            val minStartMinutesFirst = firstHalfIntervals.minOf { convertToMinutes(it.first) }
            val maxEndMinutesFirst = firstHalfIntervals.maxOf { convertToMinutes(it.second) }
            val minStartTimeFirst = String.format("%02d:%02d", minStartMinutesFirst / 60, minStartMinutesFirst % 60)
            val maxEndTimeFirst = String.format("%02d:%02d", maxEndMinutesFirst / 60, maxEndMinutesFirst % 60)

            val minStartMinutesSecond = maxEndMinutesFirst
            val maxEndMinutesSecond = secondHalfIntervals.maxOf { convertToMinutes(it.second) }
            val minStartTimeSecond = String.format("%02d:%02d", minStartMinutesSecond / 60, minStartMinutesSecond % 60)
            val maxEndTimeSecond = String.format("%02d:%02d", maxEndMinutesSecond / 60, maxEndMinutesSecond % 60)

            multiColorTimelineView.setTimelineRange(minStartTimeFirst, maxEndTimeFirst)
            multiColorTimelineView.setTimeIntervals(firstHalfIntervals, minStartTimeFirst, maxEndTimeFirst, firstHalfDutyNames, true)
            multiColorTimelineView.setBusStops(extractBusStops()) // ✅ ADDED

            multiColorTimelineView2.setTimelineRange(minStartTimeSecond, maxEndTimeSecond)
            multiColorTimelineView2.setTimeIntervals(secondHalfIntervals, minStartTimeSecond, maxEndTimeSecond, secondHalfDutyNames, false)
            multiColorTimelineView2.setBusStops(extractBusStops()) // ✅ ADDED
        }
    }

    /**
     * a helper to grab the busStops list from your scheduleData
     */
    private fun extractBusStops(): List<BusScheduleInfo> {
        val busStopsList = mutableListOf<BusScheduleInfo>()
        for (item in scheduleData) {
            busStopsList.addAll(item.busStops)
        }
        return busStopsList
    }


    /**
     * Extracts work intervals and duty names from the schedule data dynamically.
     */
    @SuppressLint("LongLogTag")
    private fun extractWorkIntervalsAndDutyNames(): Pair<List<Pair<String, String>>, List<String>> {
        val workIntervals = mutableListOf<Pair<String, String>>()
        val dutyNames = mutableListOf<String>()

        for (item in scheduleData) { // ✅ Limit to first 3 entries directly
            val startTime = item.startTime
            val endTime = item.endTime
            val dutyName = item.dutyName

            if (startTime.isNotEmpty() && endTime.isNotEmpty()) {
                workIntervals.add(Pair(startTime, endTime))
                dutyNames.add(dutyName)
            }
        }

        Log.d("ScheduleActivity extractWorkIntervalsAndDutyNames", "✅ Extracted Work Intervals: $workIntervals")
        Log.d("ScheduleActivity extractWorkIntervalsAndDutyNames", "✅ Extracted Duty Names: $dutyNames")
        return Pair(workIntervals, dutyNames)
    }

    /**
     * Helper function to convert a time string "HH:mm" to minutes since midnight.
     */
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
                    callback(true)
                } else {
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
            Log.d("ScheduleActivity saveScheduleDataToCache", "🚫 Skipping cache update (already updated).")
            return
        }

        val cacheFile = File(getHiddenFolder(), "scheduleDataCache.txt")

        // ✅ Ensure the file exists before reading or writing
        try {
            if (!cacheFile.exists()) {
                cacheFile.createNewFile()
                Log.d("ScheduleActivity saveScheduleDataToCache", "✅ File created successfully: ${cacheFile.absolutePath}")
            } else {
                Log.d("ScheduleActivity saveScheduleDataToCache", "✅ File already exists.")
            }

            Log.d("ScheduleActivity saveScheduleDataToCache before", "scheduleDataCache.txt content before saving: ${cacheFile.readText()}")

            // ✅ Save schedule data
            val jsonStringScheduleData = Gson().toJson(scheduleData)
            cacheFile.writeText(jsonStringScheduleData)

            Log.d("ScheduleActivity saveScheduleDataToCache after", "scheduleDataCache.txt content after saving: ${cacheFile.readText()}")
            Log.d("ScheduleActivity saveScheduleDataToCache", "✅ Schedule data cache updated successfully.")

            Toast.makeText(this, "Schedule data cache updated successfully.", Toast.LENGTH_SHORT).show()
            isScheduleCacheUpdated = true

        } catch (e: IOException) {
            Log.e("ScheduleActivity saveScheduleDataToCache", "❌ Error creating or accessing schedule data cache: ${e.message}")
        } catch (e: Exception) {
            Log.e("ScheduleActivity saveScheduleDataToCache", "❌ Unexpected error: ${e.message}")
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
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    /** Fetches the Android ID (AID) of the device. */
    private fun getAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }
}
