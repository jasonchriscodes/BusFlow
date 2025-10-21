package com.jason.publisher.main.activity

import ScheduleAdapter
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityScheduleBinding
import com.jason.publisher.main.ui.StyledMultiColorTimeline
import com.jason.publisher.main.model.Bus
import com.jason.publisher.main.model.BusDataCache
import com.jason.publisher.main.model.BusItem
import com.jason.publisher.main.model.BusRoute
import com.jason.publisher.main.model.BusScheduleInfo
import com.jason.publisher.main.model.BusStop
import com.jason.publisher.main.model.RouteData
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.services.MqttManager
import com.jason.publisher.main.utils.FileLogger
import com.jason.publisher.main.utils.NetworkStatusHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduleBinding
    lateinit var mqttManagerConfig: MqttManager
    lateinit var mqttManager: MqttManager
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
    private lateinit var timeline1: StyledMultiColorTimeline
    private lateinit var timeline2: StyledMultiColorTimeline
    private lateinit var timeline3: StyledMultiColorTimeline
    private val timelineRange = Pair("08:00", "11:10")
    private lateinit var networkStatusHelper: NetworkStatusHelper
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private val loadingBarHandler = Handler(Looper.getMainLooper())
    private var isTabulatedView: Boolean = false
    private lateinit var changeModeButton: Button
    private lateinit var darkModeSwitch: Switch
    private var isDarkMode = false
    private lateinit var paginationLayout: LinearLayout
    private var currentPage = 0
    private val maxRowsPerPage = 5
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnNext: ImageButton
    private var isScheduleUpdatedFromServer = false
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var scheduleAdapter: ScheduleAdapter
    private lateinit var scheduleRecycler: RecyclerView
    private lateinit var fetchingLayout: LinearLayout
    private lateinit var fetchingText: TextView
    private val fetchingHandler = Handler(Looper.getMainLooper())
    private var dotCount = 1
    private lateinit var fetchingIcon: ImageView
    private lateinit var networkStatusIndicator: View
    private var fetchRoster = false
    private val PANEL_DEBUG_PREF = "panel_debug_pref"
    private val PANEL_DEBUG_NO_KEY = "panel_debug_no"
    private var lastPreStartDump: String? = null
    private lateinit var emptyStateText: TextView

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
        private const val LOCATION_PERMISSION_REQUEST = 1234
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
        FileLogger.d("ScheduleActivity", "onCreate")

        // initialize them here
        connectionStatusTextView = binding.connectionStatusTextView
        networkStatusIndicator = binding.networkStatusIndicator
        fetchRoster = intent.getBooleanExtra("EXTRA_FETCH_ROSTER", false)

        // initialize RecyclerView adapter
        scheduleAdapter = ScheduleAdapter(emptyList(), isDarkMode)
        scheduleRecycler = binding.scheduleRecycler
        scheduleRecycler.apply {
            layoutManager = LinearLayoutManager(this@ScheduleActivity)
            adapter = scheduleAdapter
            visibility = View.GONE
        }

        // Initialize all views up front:
        scheduleRecycler = binding.scheduleRecycler
        scheduleRecycler.visibility = View.GONE
        timeline1 = findViewById(R.id.timelinePart1)
        timeline2 = findViewById(R.id.timelinePart2)
        timeline3 = findViewById(R.id.timelinePart3)
        changeModeButton = findViewById(R.id.changeModeButton)
        darkModeSwitch = findViewById(R.id.darkModeSwitch)
        paginationLayout = findViewById(R.id.paginationLayout)
        paginationLayout.visibility = View.GONE
        btnPrevious = findViewById(R.id.btnPrevious)
        btnNext = findViewById(R.id.btnNext)
        fetchingLayout = findViewById(R.id.fetchingLayout)
        fetchingText = findViewById(R.id.fetchingText)
        fetchingIcon = findViewById(R.id.fetchingIcon)
        emptyStateText = findViewById(R.id.emptyStateText)

        // add a light gray 1dp divider without any XML
        val divider = DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        val oneDp = (resources.displayMetrics.density).toInt()

// subclass ColorDrawable so we can override intrinsicHeight
        val drawable = object : ColorDrawable(Color.parseColor("#DDDDDD")) {
            override fun getIntrinsicHeight(): Int = oneDp
        }
        divider.setDrawable(drawable)

        scheduleRecycler.addItemDecoration(divider)

        scheduleAdapter = ScheduleAdapter(emptyList(), isDarkMode)

        binding.scheduleRecycler.apply {
            layoutManager = LinearLayoutManager(this@ScheduleActivity)
            adapter = scheduleAdapter
            visibility = View.GONE
        }

        // Load dark mode preference BEFORE setting switch listener, but AFTER initializing buttons
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("dark_mode", false)
        darkModeSwitch.isChecked = isDarkMode
        applyThemeMode(isDarkMode)

        // Save preference on toggle
        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            isDarkMode = isChecked
            applyThemeMode(isDarkMode)
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("dark_mode", isDarkMode)
                .apply()
        }

        // 0) init your MQTT managers *before* you ever call enterOnlineMode()/fetchConfig()
        mqttManagerConfig = MqttManager(
            serverUri = SERVER_URI,
            clientId = CLIENT_ID,
            username = tokenConfigData
        )
        mqttManager = MqttManager(
            serverUri = SERVER_URI,
            clientId = CLIENT_ID
        )

        // 1. get connectivity service
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

// 2. define the callback
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (fetchRoster) {
                    // only fetch when the user actually asked for fresh data
                    runOnUiThread { enterOnlineMode() }
                } else {
                    // just update your status indicator
                    runOnUiThread {
                        connectionStatusTextView.text = "Connected (cache only)"
                        networkStatusIndicator.setBackgroundResource(R.drawable.circle_shape_green)
                    }
                }
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

        // read the user’s choice from the Splash
        val fetchRoster = intent.getBooleanExtra("EXTRA_FETCH_ROSTER", false)
// if they tapped “Fetch Roster” *and* we have internet, do a one-time fetch
        if (fetchRoster && NetworkStatusHelper.isNetworkAvailable(this)) {
            enterOnlineMode()
        } else {
            enterOfflineMode()
        }

        // still register the network-status indicator, but *do not* auto-fetch on reconnect
        NetworkStatusHelper.setupNetworkStatus(
            this,
            binding.connectionStatusTextView,
            binding.networkStatusIndicator
        )

        // Check and request permission
        requestAllFilesAccessPermission()

        // Fetch AID from the device
        aid = getAndroidId()
        Log.d("TimeTableActivity", "Fetched AID: $aid")

        // Set up network status UI
        NetworkStatusHelper.setupNetworkStatus(
            this,
            binding.connectionStatusTextView,
            binding.networkStatusIndicator
        )

        // Load configuration
        Configuration.getInstance()
            .load(this, getSharedPreferences(getString(R.string.app_name), MODE_PRIVATE))

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
        preloadMap.isClickable = false
        preloadMap.isFocusable = false

        // 3. Create tile cache
        val cache = AndroidUtil.createTileCache(
            this,
            "preloadCache",
            preloadMap.model.displayModel.tileSize,
            1f,
            preloadMap.model.frameBufferModel.overdrawFactor
        )

        // 4. Open the .map file off the main thread
        val mapFile = File(getHiddenFolder(), "new-zealand.map")
        if (!mapFile.exists()) {
            Log.e("ScheduleActivity", "Map file not found at: ${mapFile.absolutePath}")
            Toast.makeText(
                this,
                "Offline map unavailable. Other features will still work.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            ioScope.launch {
                // Open the .map file on IO
                val mapStore = try {
                    MapFile(mapFile)
                } catch (e: Exception) {
                    Log.e("ScheduleActivity", "Failed to open MapFile: ${e.message}")
                    null
                }
                if (mapStore != null) {
                    // Create the renderer off the main thread
                    val renderer = TileRendererLayer(
                        cache,
                        mapStore,
                        preloadMap.model.mapViewPosition,
                        AndroidGraphicFactory.INSTANCE
                    ).apply {
                        setXmlRenderTheme(InternalRenderTheme.DEFAULT)
                    }

                    // Switch back to UI thread to add the layer and center/map invalidate
                    withContext(Dispatchers.Main) {
                        preloadMap.layerManager.layers.add(renderer)
                        preloadMap.post {
                            preloadMap.model.mapViewPosition.setZoomLevel(16)
                            preloadMap.model.mapViewPosition.setCenter(
                                LatLong(
                                    -36.855647,
                                    174.765249
                                )
                            )
                            preloadMap.invalidate()
                        }
                    }
                }
            }
        }

        changeModeButton.setOnClickListener {
//            Log.d("ChangeModeButton", "Clicked")
//            Toast.makeText(this, "Clicked!", Toast.LENGTH_SHORT).show()
            if (!isTabulatedView) {
                scheduleRecycler.visibility = View.VISIBLE
                paginationLayout.visibility = View.VISIBLE
                timeline1.visibility = View.GONE
                timeline2.visibility = View.GONE
                timeline3.visibility = View.GONE
                changeModeButton.text = "Timeline View"
            } else {
                scheduleRecycler.visibility = View.GONE
                paginationLayout.visibility = View.GONE
                timeline1.visibility = View.VISIBLE
                timeline2.visibility = View.VISIBLE
                timeline3.visibility = View.VISIBLE
                changeModeButton.text = "Tabulated View"
            }
            isTabulatedView = !isTabulatedView
        }

        btnPrevious.setOnClickListener {
            if (currentPage > 0) {
                currentPage--
                updateScheduleTablePaged()
            }
        }

        btnNext.setOnClickListener {
            val totalPages = (scheduleData.size + maxRowsPerPage - 1) / maxRowsPerPage
            if (currentPage < totalPages - 1) {
                currentPage++
                updateScheduleTablePaged()
            }
        }

        // ➊ Immediately ask for location permission on startup
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }

        // Set up the "Start Route" button
        binding.startRouteButton.setOnClickListener {
            if (scheduleData.isEmpty()) {
                Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val no = nextPanelDebugNo()
            val first = scheduleData.first()

            // Log now (works for Trip or Break)
            logPanelDebugPreStart(no, first)

            when {
                isBreak(first)      -> launchBreakActivity(first, no)
                isReposition(first) -> launchRepActivity(first, no)   // 👈 NEW
                else                -> launchMapActivity(no)
            }
        }

// Set up the "Test Start Route" button
        binding.testStartRouteButton.setOnClickListener {
            if (scheduleData.isEmpty()) {
                Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val no = nextPanelDebugNo()
            val first = scheduleData.first()

            logPanelDebugPreStart(no, first)

            if (isBreak(first)) {
                launchBreakActivity(first,no)
            } else {
                launchMapActivity(no)
            }
        }
    }

    /**
     * Launches BreakActivity for a "Break" item: pops it from the list, persists cache, refreshes UI, and passes current/remaining schedules.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun launchBreakActivity(firstScheduleItem: ScheduleItem, no: Int) {
        // remove first item, persist, refresh (your existing code) ...
        scheduleData = scheduleData.toMutableList().apply { removeAt(0) }
        isScheduleCacheUpdated = false
        saveScheduleDataToCache()
        updateScheduleTablePaged()
        updateTimeline()
        rewriteOfflineScheduleData()

        val breakLabel = formatPanelLabel(firstScheduleItem) // e.g. "09:00 Break BCS → BCS"
        getAccessToken()

        val intent = Intent(this, BreakActivity::class.java).apply {
            putExtra("AID", aid)
            putExtra("ACCESS_TOKEN", token)
            putExtra("BREAK_LABEL", breakLabel)
            putExtra("FIRST_SCHEDULE_ITEM", ArrayList(listOf(firstScheduleItem)))
            putExtra("FULL_SCHEDULE_DATA", ArrayList(scheduleData))
            putExtra("EXTRA_PANEL_DEBUG_NO", no)   // <-- same counter
        }
        publishActiveSegment(breakLabel)
        startActivity(intent)
    }

    // ➌ Handle the user’s response to your initial permission request
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Location permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Location permission is required to show the map", Toast.LENGTH_LONG).show()
            }
        }
    }


    /** Launches RepActivity to handle a single-stop reposition trip. */
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    private fun launchRepActivity(firstScheduleItem: ScheduleItem, no: Int) {
        // We expect exactly one stop for REP; use the first if more are present
        val repStop = firstScheduleItem.busStops.firstOrNull()
        if (repStop == null) {
            Toast.makeText(this, "No reposition stop found.", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedIdx = routeIndexFromRouteNo(firstScheduleItem.runNo)

        val intent = Intent(this, RepActivity::class.java).apply {
            putExtra("AID", aid)
            putExtra("CONFIG", ArrayList(config))
            putExtra("JSON_STRING", jsonString)

            putExtra("BUS_ROUTE_DATA", ArrayList(busRouteData))
            putExtra("FIRST_SCHEDULE_ITEM", ArrayList(listOf(firstScheduleItem)))
            putExtra("FULL_SCHEDULE_DATA", ArrayList(scheduleData))

            putExtra("SELECTED_ROUTE_INDEX", selectedIdx ?: -1)
            selectedIdx?.let { idx ->
                putExtra("SELECTED_ROUTE_DATA", busRouteData[idx])
            }

            putExtra("EXTRA_PANEL_DEBUG_NO", no)

            // REP stop payload
            putExtra("REP_STOP_LAT", repStop.latitude ?: 0.0)
            putExtra("REP_STOP_LON", repStop.longitude ?: 0.0)
            putExtra("REP_STOP_NAME", repStop.name ?: repStop.abbreviation ?: "Reposition Stop")
            putExtra("REP_STOP_ADDR", repStop.address ?: repStop.name ?: "Reposition Stop")
        }

        // pop the first schedule like other flows
        scheduleData = scheduleData.toMutableList().apply { removeAt(0) }
        isScheduleCacheUpdated = false
        saveScheduleDataToCache()
        updateScheduleTablePaged()
        updateTimeline()
        rewriteOfflineScheduleData()

        startActivity(intent)
    }

    /**
     * Start MapActivity, carrying over any required extras.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    private fun launchMapActivity(no: Int) {
        if (scheduleData.isNotEmpty()) {
            // Store the first schedule item for the Map
            val firstScheduleItem = scheduleData.first()
            val selectedIdx = routeIndexFromRouteNo(firstScheduleItem.runNo)
            Log.d("ScheduleActivity startRouteButton firstScheduleItem", firstScheduleItem.toString())
            Log.d("ScheduleActivity startRouteButton before", scheduleData.toString())

            // We will still pass the full list (for future trips), AND pass the selected one explicitly.
            val intent = Intent(this, MapActivity::class.java).apply {
                // timeline labels (unchanged)
                val (workIntervals, runNames) = extractWorkIntervalsAndrunNames()
                val labels = scheduleData.map { item ->
                    val from = item.busStops.firstOrNull()?.abbreviation ?: "?"
                    val to   = item.busStops.lastOrNull()?.abbreviation  ?: "?"
                    "${item.startTime} ${saferunName(item)} $from → $to"
                }
                putStringArrayListExtra("TIMELINE_LABELS", ArrayList(labels))

                // essentials
                putExtra("AID", aid)
                putExtra("CONFIG", ArrayList(config))
                putExtra("JSON_STRING", jsonString)

                // keep sending the *full* sets as before
                putExtra("BUS_ROUTE_DATA", ArrayList(busRouteData))
                putExtra("FIRST_SCHEDULE_ITEM", ArrayList(listOf(firstScheduleItem)))

                // NEW: tell MapActivity which one to use for THIS trip
                putExtra("SELECTED_ROUTE_INDEX", selectedIdx ?: -1)
                selectedIdx?.let { idx ->
                    putExtra("SELECTED_ROUTE_DATA", busRouteData[idx])  // RouteData must be Serializable/Parcelable (you already pass list)
                }
                putExtra("EXTRA_PANEL_DEBUG_NO", no)
            }

            // remove first schedule & persist
            scheduleData = scheduleData.toMutableList().apply { removeAt(0) }
            isScheduleCacheUpdated = false
            saveScheduleDataToCache()
            updateScheduleTablePaged()
            updateTimeline()
            rewriteOfflineScheduleData()

            // And hand over the remaining full schedule
            intent.putExtra("FULL_SCHEDULE_DATA", ArrayList(scheduleData))

            startActivity(intent)
        } else {
            Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Map "1" or "Route 1" → 0-based index into busRouteData */
    private fun routeIndexFromRouteNo(runNo: String): Int? {
        // Accepts "1" or "Route 1" etc.
        val cleaned = runNo.trim().lowercase(Locale.ROOT)
        val digits  = cleaned.removePrefix("route").trim()
        val idx     = digits.toIntOrNull()?.minus(1) ?: return null
        return if (idx in busRouteData.indices) idx else null
    }

    /**
     * Function toggles the dark/light UI:
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun applyThemeMode(isDark: Boolean) {
        val rootLayout = findViewById<View>(R.id.rootLayout)
        if (isDark) {
            rootLayout.setBackgroundColor(Color.BLACK)
        } else {
            rootLayout.background = ContextCompat.getDrawable(this, R.drawable.gradient_background)
        }

        // ✅ Update text of darkModeSwitch
        val darkModeSwitch = findViewById<Switch>(R.id.darkModeSwitch)
        darkModeSwitch.text = if (isDark) "Dark Mode" else "Light Mode"

        // ✅ Apply dark mode color to currentDateTimeTextView
        val currentDateTimeTextView = findViewById<TextView>(R.id.currentDateTimeTextView)
        val backgroundColorRes = if (isDark) R.color.grey else R.color.purple_500
        currentDateTimeTextView.setBackgroundColor(ContextCompat.getColor(this, backgroundColorRes))

// Apply same background color as tint to changeModeButton
        changeModeButton.backgroundTintList = ContextCompat.getColorStateList(this, backgroundColorRes)

        // ✅ Change background colors of Start/Test buttons
        val startBtn = findViewById<Button>(R.id.startRouteButton)
        val testBtn = findViewById<Button>(R.id.testStartRouteButton)
        val buttonBackgroundTint = if (isDark) R.color.grey else R.color.purple_400

        startBtn.setBackgroundTintList(ContextCompat.getColorStateList(this, buttonBackgroundTint))
        testBtn.setBackgroundTintList(ContextCompat.getColorStateList(this, buttonBackgroundTint))

        // ✅ Apply dark mode to custom timeline views
        timeline1.setDarkMode(isDark)
        timeline2.setDarkMode(isDark)
        timeline3.setDarkMode(isDark)

        scheduleAdapter.setThemeMode(isDark)

        // ✅ Update networkStatusIndicator if currently online
        val networkIndicator = findViewById<View>(R.id.networkStatusIndicator)
        val isConnected = NetworkStatusHelper.isNetworkAvailable(this)

        val drawableRes = when {
            isDark && isConnected -> R.drawable.dark_circle_shape_green
            !isDark && isConnected -> R.drawable.circle_shape_green
            else -> R.drawable.circle_shape_red // you can define other states as needed
        }

        networkIndicator.background = ContextCompat.getDrawable(this, drawableRes)
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

        scheduleRecycler.visibility = View.GONE
        timeline1.visibility = View.VISIBLE
        timeline2.visibility = View.VISIBLE
        timeline3.visibility = View.VISIBLE
        isTabulatedView = false
        changeModeButton.text = "Today's Overview"

        binding.startRouteButton.visibility = View.VISIBLE
        binding.testStartRouteButton.visibility = View.VISIBLE
        if (isTabulatedView) {
            scheduleRecycler.visibility = View.VISIBLE
            paginationLayout.visibility  = View.VISIBLE
        } else {
            scheduleRecycler.visibility = View.GONE
            paginationLayout.visibility  = View.GONE
        }

        loadBusDataFromCache()
        loadScheduleDataFromCache()
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
        scheduleRecycler.visibility = View.GONE
        paginationLayout.visibility  = View.GONE
        binding.startRouteButton.visibility = View.GONE
        binding.testStartRouteButton.visibility = View.GONE
        timeline1.visibility = View.GONE
        timeline2.visibility = View.GONE
        timeline3.visibility = View.GONE
        binding.startRouteButton.visibility = View.GONE
        binding.testStartRouteButton.visibility = View.GONE
        paginationLayout.visibility = View.GONE

        findViewById<ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        startLoadingBar()
        startFetchingAnimation()

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
     * Starts a looping animation for the "Fetching data" text with dots (., .., ...) every 500ms.
     * Also makes the spinner + text layout visible.
     */
    private fun startFetchingAnimation() {
        // Make the spinner and text visible
        fetchingLayout.visibility = View.VISIBLE

        // Start a repeating task using Handler
        fetchingHandler.post(object : Runnable {
            override fun run() {
                // Build the animated dots: ".", "..", or "..."
                val dots = ".".repeat(dotCount)

                // Update the text with the animated dots
                fetchingText.text = "Fetching data $dots"

                // Cycle dotCount: 1 → 2 → 3 → 1 → ...
                dotCount = (dotCount % 3) + 1

                // Re-post this Runnable after 500ms
                fetchingHandler.postDelayed(this, 500)
            }
        })
    }

    /**
     * Stops the fetching text animation and hides the spinner layout.
     */
    private fun stopFetchingAnimation() {
        fetchingHandler.removeCallbacksAndMessages(null)
        fetchingText.text = "Fetching data Complete!"
        fetchingIcon.setImageResource(R.drawable.ic_check_green) // your finish icon
    }

    /**
     * function to start loading bar from 0% to 100% with color transitioning from red to green
     */
    @RequiresApi(Build.VERSION_CODES.M)
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
                    stopFetchingAnimation()
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
     * a pop-up dialog that appears after the progress reaches 100% and triggers final UI updates.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun showCacheCompleteDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cache Complete")
            .setMessage("All data has been cached successfully. Please click OK to view the schedule.")
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()

                // ✅ Hide progress bar
                findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE

                // ✅ Stop animation and show complete message/icon
                fetchingHandler.removeCallbacksAndMessages(null)
                fetchingLayout.visibility = View.GONE
                fetchingLayout.visibility = View.INVISIBLE

                // ✅ Show main timeline + buttons
                timeline1.visibility = View.VISIBLE
                timeline2.visibility = View.VISIBLE
                timeline3.visibility = View.VISIBLE
                updateScheduleTablePaged()
                updateTimeline()

                binding.startRouteButton.visibility = View.VISIBLE
                binding.testStartRouteButton.visibility = View.VISIBLE
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
     * Updates the schedule table with paginated rows.
     * Shows only maxRowsPerPage items for the currentPage.
     * Also triggers pagination button rendering.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateScheduleTablePaged() {
        val start = currentPage * maxRowsPerPage
        val end   = minOf(scheduleData.size, start + maxRowsPerPage)
        scheduleAdapter.update(if (scheduleData.isEmpty()) emptyList() else scheduleData.subList(start, end))

        val totalPages = if (scheduleData.isEmpty()) 0 else (scheduleData.size + maxRowsPerPage - 1) / maxRowsPerPage
        if (totalPages == 0) {
            paginationLayout.visibility = View.GONE
        } else {
            renderPagination(totalPages)
        }

        updateEmptyState()
    }

    /**
     * Dynamically renders pagination buttons below the table.
     * Clicking a button updates currentPage and redraws the table.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun renderPagination(totalPages: Int) {
        // Only remove dynamically added page buttons, not btnPrevious/btnNext
        // Keep children 0 (btnPrevious) and last (btnNext)
        val childCount = paginationLayout.childCount
        if (childCount > 2) {
            paginationLayout.removeViews(1, childCount - 2)
        }

        if (isTabulatedView) {
            paginationLayout.visibility = View.VISIBLE
        }

        for (page in 0 until totalPages) {
            val button = Button(this).apply {
                text = (page + 1).toString()
                textSize = 14f
                setPadding(8, 4, 8, 4)
                setOnClickListener {
                    currentPage = page
                    updateScheduleTablePaged()
                }
            }
            paginationLayout.addView(button, paginationLayout.childCount - 1) // insert before btnNext
        }
    }

    /**
     * Extracts work intervals from the schedule table and updates the timeline view.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    private fun updateTimeline() {

        runOnUiThread {
            if (scheduleData.isEmpty()) {
                Log.e("ScheduleActivity updateTimeline", "No scheduleData to draw!")
                updateEmptyState()
                return@runOnUiThread
            }

            timeline1.visibility = View.VISIBLE
            timeline2.visibility = View.VISIBLE
            timeline3.visibility = View.VISIBLE
            if (scheduleData.isEmpty()) {
                Log.e("ScheduleActivity updateTimeline", "No scheduleData to draw!")
                return@runOnUiThread
            }

            // 1) extract raw data
            val (workIntervals, runNames) = extractWorkIntervalsAndrunNames()
            Log.d("ScheduleActivity updateTimeline", "🔄 workIntervals=$workIntervals")
            Log.d("ScheduleActivity updateTimeline", "🔄 runNames    =$runNames")

            // 2) decide how many per row
            val maxPerLine = 3

            // slice intervals & names into 3 fixed chunks
            val intervals1 = workIntervals.take(maxPerLine)
            val intervals2 = workIntervals.drop(maxPerLine).take(maxPerLine)
            val intervals3 = workIntervals.drop(2 * maxPerLine).take(maxPerLine)

            val names1 = runNames.take(maxPerLine)
            val names2 = runNames.drop(maxPerLine).take(maxPerLine)
            val names3 = runNames.drop(2 * maxPerLine).take(maxPerLine)

            Log.d(
                "ScheduleActivity updateTimeline",
                "🔹 timeline1 intervals=$intervals1, names=$names1"
            )
            Log.d(
                "ScheduleActivity updateTimeline",
                "🔹 timeline2 intervals=$intervals2, names=$names2"
            )
            Log.d(
                "ScheduleActivity updateTimeline",
                "🔹 timeline3 intervals=$intervals3, names=$names3"
            )

            // 3) slice the corresponding ScheduleItem lists
            val items1 = scheduleData.take(maxPerLine)
            val items2 = scheduleData.drop(maxPerLine).take(maxPerLine)
            val items3 = scheduleData.drop(2 * maxPerLine).take(maxPerLine)

            // 4) extract busStops per line
            val busStops1 = items1.flatMap { it.busStops }
            val busStops2 = items2.flatMap { it.busStops }
            val busStops3 = items3.flatMap { it.busStops }

            Log.d("ScheduleActivity updateTimeline", "🔹 timeline1 busStops=$busStops1")
            Log.d("ScheduleActivity updateTimeline", "🔹 timeline2 busStops=$busStops2")
            Log.d("ScheduleActivity updateTimeline", "🔹 timeline3 busStops=$busStops3")

            // 5) decide single‐line mode threshold
            val oneLineThreshold = 4
            val single1 = intervals1.size <= oneLineThreshold
            val single2 = intervals2.size <= oneLineThreshold
            val single3 = intervals3.size <= oneLineThreshold

            // 6) apply to each StyledMultiColorTimeline
            timeline1.apply {
                setScheduleData(items1)
                setTimelineData(intervals1, names1)
                setBusStops(busStops1)
                setSingleLineMode(single1)
            }
            timeline2.apply {
                setScheduleData(items2)
                setTimelineData(intervals2, names2)
                setBusStops(busStops2)
                setSingleLineMode(single2)
            }
            timeline3.apply {
                setScheduleData(items3)
                setTimelineData(intervals3, names3)
                setBusStops(busStops3)
                setSingleLineMode(single3)
            }
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
    private fun extractWorkIntervalsAndrunNames(): Pair<List<Pair<String, String>>, List<String>> {
        val workIntervals = mutableListOf<Pair<String, String>>()
        val runNames = mutableListOf<String>()

        for (item in scheduleData) { // ✅ Limit to first 3 entries directly
            val startTime = item.startTime
            val endTime = item.endTime
            val runName = item.runName

            if (startTime.isNotEmpty() && endTime.isNotEmpty()) {
                workIntervals.add(Pair(startTime, endTime))
                runNames.add(runName)
            }
        }

        Log.d("ScheduleActivity extractWorkIntervalsAndrunNames", "✅ Extracted Work Intervals: $workIntervals")
        Log.d("ScheduleActivity extractWorkIntervalsAndrunNames", "✅ Extracted Duty Names: $runNames")
        return Pair(workIntervals, runNames)
    }

    /**
     * Helper function to convert a time string "HH:mm" to minutes since midnight.
     */
    private fun convertToMinutes(time: String): Int {
        val parts = time.split(":").map { it.toInt() }
        return parts[0] * 60 + parts[1]
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
        ioScope.launch {
            val cacheFile = File(getHiddenFolder(), "scheduleDataCache.txt")

            if (cacheFile.exists()) {
                try {
                    val jsonContent = cacheFile.readText()
                    val cachedSchedule =
                        Gson().fromJson(jsonContent, Array<ScheduleItem>::class.java).toList()
                    scheduleData = cachedSchedule.map { it.copy(runName = saferunName(it)) }

                    Log.d(
                        "ScheduleActivity loadScheduleDataFromCache",
                        "✅ Loaded cached schedule data: $scheduleData"
                    )

                    // Use the loaded schedule data
                    withContext(Dispatchers.Main) {
                        updateScheduleTablePaged()
                        updateTimeline()
                    }

                } catch (e: Exception) {
                    Log.e(
                        "ScheduleActivity loadScheduleDataFromCache",
                        "❌ Error reading schedule data cache: ${e.message}"
                    )
                }
            } else {
                Log.e(
                    "ScheduleActivity loadScheduleDataFromCache",
                    "❌ No cached schedule data found."
                )
            }
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
                var last: Pair<Double,Double>? = null
                for (coord in nextPoint.routeCoordinates) {
                    val lat = coord[1]
                    val lon = coord[0]
                    if (last == lat to lon) continue      // skip duplicate
                    newRoute.add(BusRoute(latitude = lat, longitude = lon))
                    last = lat to lon
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
        Log.d("storage",
            "api=${Build.VERSION.SDK_INT}, " +
                    "target=${applicationInfo.targetSdkVersion}, " +
                    "allFiles=${if (Build.VERSION.SDK_INT>=30) Environment.isExternalStorageManager() else "n/a"}"
        )

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
                    scheduleData = (data.shared?.scheduleData1 ?: emptyList()).map { it.copy(runName = saferunName(it)) }
                    Log.d("MainActivity subscribeSharedData", "scheduleData: $scheduleData")

                    if (config != null && scheduleData.isNotEmpty()) {
                        // Save the updated schedule data and bus data immediately
                        saveBusDataToCache()
                        saveScheduleDataToCache()

                        // ✅ Mark as updated so offline mode does not reload cache unnecessarily
                        isScheduleUpdatedFromServer = true
                    }

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
        ioScope.launch {
            // 1) Skip if no internet or already updated
            if (!NetworkStatusHelper.isNetworkAvailable(this@ScheduleActivity) || isBusCacheUpdated) {
                return@launch
            }
            try {
                // 2) Write JSON to disk
                val cacheFile = File(getHiddenFolder(), "busDataCache.txt")
                val busData = mapOf(
                    "aid"          to aid,
                    "busRouteData" to busRouteData,
                    "config"       to config
                )
                cacheFile.writeText(Gson().toJson(busData))

                // 3) Mark flag so we never repeat
                isBusCacheUpdated = true

                // 4) Back to Main to show toast exactly once
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ScheduleActivity,
                        "Bus data cache updated successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("ScheduleActivity saveBusDataToCache", "Error saving bus data cache: ${e.message}")
            }
        }
    }

    /**
     * Saves the latest schedule data to the cache file.
     */
    private var isScheduleCacheUpdated = false // Flag to ensure cache is updated only once
    @SuppressLint("LongLogTag")
    private fun saveScheduleDataToCache() {
        ioScope.launch {
            // 1) Skip if already updated
            if (isScheduleCacheUpdated) {
                return@launch
            }
            try {
                // 2) Ensure file exists, then write JSON
                val cacheFile = File(getHiddenFolder(), "scheduleDataCache.txt")
                if (!cacheFile.exists()) {
                    cacheFile.createNewFile()
                }
                cacheFile.writeText(Gson().toJson(scheduleData))

                // 3) Mark flag so we never repeat
                isScheduleCacheUpdated = true

                // 4) Back to Main to show toast exactly once
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ScheduleActivity,
                        "Schedule data cache updated successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("ScheduleActivity saveScheduleDataToCache", "Error saving schedule data cache: ${e.message}")
            }
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

    /**
     * Returns true if this schedule item represents a break (case-insensitive).
     */
    private fun isBreak(item: com.jason.publisher.main.model.ScheduleItem): Boolean {
        // Accept "Break" or "break"
        return item.runName.equals("break", ignoreCase = true)
    }

    /** Returns true if the schedule item represents a Reposition (REP). */
    private fun isReposition(item: ScheduleItem): Boolean {
        return item.runName.equals("REP", true)
                || item.runName.contains("reposition", true)
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

    private fun nextPanelDebugNo(): Int {
        val sp = getSharedPreferences(PANEL_DEBUG_PREF, MODE_PRIVATE)
        val next = sp.getInt(PANEL_DEBUG_NO_KEY, 0) + 1
        sp.edit().putInt(PANEL_DEBUG_NO_KEY, next).apply()
        return next
    }

    // Keep it simple: build the same label your panel shows
    private fun formatPanelLabel(item: ScheduleItem): String {
        val from = item.busStops.firstOrNull()?.abbreviation
            ?: item.busStops.firstOrNull()?.name ?: "?"
        val to = item.busStops.lastOrNull()?.abbreviation
            ?: item.busStops.lastOrNull()?.name ?: "?"
        return "${item.startTime} ${item.runName} $from → $to"
    }

    private fun logPanelDebugPreStart(no: Int, first: ScheduleItem) {
        val current = "ic_bus_symbol ${formatPanelLabel(first)}"
        val dump = buildString {
            appendLine("no: $no")
            appendLine("runName: ${first.runName}")
            append("currentDetailPanel: $current")
        }
        if (dump == lastPreStartDump) return
        lastPreStartDump = dump
        Log.d("PanelDebug", dump)
    }

    private fun isTokenLike(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        val t = s.trim()
        return t.length in 20..40 && t.all { it.isLetterOrDigit() }
    }

    private fun saferunName(item: ScheduleItem): String {
        if (!isTokenLike(item.runName)) return item.runName
        val from = item.busStops.firstOrNull()?.abbreviation ?: item.busStops.firstOrNull()?.name ?: "?"
        val to   = item.busStops.lastOrNull()?.abbreviation  ?: item.busStops.lastOrNull()?.name  ?: "?"
        return "${item.runNo} $from → $to"
    }

    private fun publishActiveSegment(label: String) {
        val topic = "v1/devices/me/attributes"
        // CHANGE activeSegment → currentTripLabel
        val payload = "{\"currentTripLabel\":\"${label.replace("\"", "\\\"")}\"}"
        if (::mqttManager.isInitialized) {
            mqttManager.publish(topic, payload)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateEmptyState() {
        val isEmpty = scheduleData.isEmpty()

        // Empty-state label
        emptyStateText.visibility = if (isEmpty) View.VISIBLE else View.GONE

        // Hide timelines & table/pagination if empty
        if (isEmpty) {
            timeline1.visibility = View.GONE
            timeline2.visibility = View.GONE
            timeline3.visibility = View.GONE
            scheduleRecycler.visibility = View.GONE
            paginationLayout.visibility = View.GONE

            // Hide action buttons if nothing to start
            binding.startRouteButton.visibility = View.GONE
            binding.testStartRouteButton.visibility = View.GONE
        } else {
            // Restore based on current mode
            if (isTabulatedView) {
                scheduleRecycler.visibility = View.VISIBLE
                paginationLayout.visibility = View.VISIBLE
                timeline1.visibility = View.GONE
                timeline2.visibility = View.GONE
                timeline3.visibility = View.GONE
            } else {
                scheduleRecycler.visibility = View.GONE
                paginationLayout.visibility = View.GONE
                timeline1.visibility = View.VISIBLE
                timeline2.visibility = View.VISIBLE
                timeline3.visibility = View.VISIBLE
            }
            binding.startRouteButton.visibility = View.VISIBLE
            binding.testStartRouteButton.visibility = View.VISIBLE
        }
    }
}