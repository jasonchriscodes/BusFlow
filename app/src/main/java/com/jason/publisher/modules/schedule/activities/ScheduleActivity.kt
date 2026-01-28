package com.jason.publisher.modules.schedule.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.jason.publisher.BuildConfig
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityScheduleBinding
import com.jason.publisher.modules.`break`.activities.BreakActivity
import com.jason.publisher.main.model.Bus
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.TripLog
import com.jason.publisher.modules.battery.ui.hookBatteryToasts
import com.jason.publisher.modules.map.activities.MapActivity
import com.jason.publisher.modules.rep.activities.RepActivity
import com.jason.publisher.modules.map.utils.formatPanelLabel
import com.jason.publisher.modules.map.utils.safeRunName
import com.jason.publisher.modules.map.mqtt.helpers.MqttConfigHelper
import com.jason.publisher.modules.map.mqtt.helpers.MqttHelper
import com.jason.publisher.modules.map.mqtt.services.MqttManager
import com.jason.publisher.modules.network.utils.NetworkStatusHelper
import com.jason.publisher.modules.schedule.adapters.ScheduleAdapter
import com.jason.publisher.modules.schedule.helpers.OtaCheckResult
import com.jason.publisher.modules.schedule.helpers.OtaInfo
import com.jason.publisher.modules.schedule.helpers.OtaUpdateManager
import com.jason.publisher.modules.schedule.widgets.StyledMultiColorTimeline
import com.jason.publisher.modules.schedule.viewmodels.ScheduleViewModel
import com.jason.publisher.modules.signing.activities.SigningActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.getValue
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

class ScheduleActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScheduleBinding

    val viewModel: ScheduleViewModel by viewModels {
        ScheduleViewModel.provideFactory()
    }

    val mqttConfigHelper = MqttConfigHelper()
    lateinit var mqttManager: MqttManager
    private lateinit var connectionStatusTextView: TextView

    private lateinit var dateTimeHandler: Handler
    private lateinit var dateTimeRunnable: Runnable
    private lateinit var timeline1: StyledMultiColorTimeline
    private lateinit var timeline2: StyledMultiColorTimeline
    private lateinit var timeline3: StyledMultiColorTimeline
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private val loadingBarHandler = Handler(Looper.getMainLooper())
    private lateinit var changeModeButton: Button
    private lateinit var darkModeSwitch: SwitchCompat
    private lateinit var paginationLayout: LinearLayout
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnNext: ImageButton
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var scheduleAdapter: ScheduleAdapter
    private lateinit var scheduleRecycler: RecyclerView
    private lateinit var fetchingLayout: LinearLayout
    private lateinit var fetchingText: TextView
    private val fetchingHandler = Handler(Looper.getMainLooper())
    private lateinit var fetchingIcon: ImageView
    private lateinit var networkStatusIndicator: View
    private lateinit var emptyStateText: TextView
    private var otaCheckedThisLaunch = false

    companion object {
        private const val REQUEST_PERIODIC_TIME = 5000L
        private const val LOCATION_PERMISSION_REQUEST = 1234
        private const val REQUEST_MANAGE_EXTERNAL_STORAGE = 1001
        private const val PANEL_DEBUG_PREF = "panel_debug_pref"
        private const val PANEL_DEBUG_NO_KEY = "panel_debug_no"
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FileLogger.d("ScheduleActivity", "onCreate")
        hookBatteryToasts()

        // initialize them here
        connectionStatusTextView = binding.connectionStatusTextView
        networkStatusIndicator = binding.networkStatusIndicator
        viewModel.fetchRoster = intent.getBooleanExtra("EXTRA_FETCH_ROSTER", false)

        // initialize RecyclerView adapter
        scheduleAdapter = ScheduleAdapter(emptyList(), viewModel.isDarkMode)
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
        val drawable = object : ColorDrawable("#DDDDDD".toColorInt()) {
            override fun getIntrinsicHeight(): Int = oneDp
        }
        divider.setDrawable(drawable)

        scheduleRecycler.addItemDecoration(divider)

        scheduleAdapter = ScheduleAdapter(emptyList(), viewModel.isDarkMode)

        binding.scheduleRecycler.apply {
            layoutManager = LinearLayoutManager(this@ScheduleActivity)
            adapter = scheduleAdapter
            visibility = View.GONE
        }

        // Load dark mode preference BEFORE setting switch listener, but AFTER initializing buttons
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        viewModel.isDarkMode = prefs.getBoolean("dark_mode", false)
        darkModeSwitch.isChecked = viewModel.isDarkMode
        applyThemeMode(viewModel.isDarkMode)

        // Save preference on toggle
        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.isDarkMode = isChecked
            applyThemeMode(viewModel.isDarkMode)
            getSharedPreferences("prefs", MODE_PRIVATE).edit {
                putBoolean("dark_mode", viewModel.isDarkMode)
            }
        }

        // 0) init your MQTT managers *before* you ever call enterOnlineMode()/fetchConfig()
        mqttManager = MqttManager()

        // 1. get connectivity service
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        // 2. define the callback
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (viewModel.fetchRoster) {
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
        viewModel.aid = getAndroidId()
        Log.d("TimeTableActivity", "Fetched AID: ${viewModel.aid}")

        // Set up network status UI
        NetworkStatusHelper.setupNetworkStatus(
            this,
            binding.connectionStatusTextView,
            binding.networkStatusIndicator
        )

        // Load configuration
        Configuration.getInstance()
            .load(this, getSharedPreferences(getString(R.string.app_name), MODE_PRIVATE))

        // Log activity entry with available data
        viewModel.logActivityEntry()

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
        val mapFile = File(viewModel.getHiddenFolder(), "new-zealand.map")
        if (!mapFile.exists()) {
            Log.e("ScheduleActivity", "Map file not found at: ${mapFile.absolutePath}")
//            Toast.makeText(
//                this,
//                "Offline map unavailable. Other features will still work.",
//                Toast.LENGTH_SHORT
//            ).show()
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
                            preloadMap.model.mapViewPosition.zoomLevel = 16
                            preloadMap.model.mapViewPosition.center = LatLong(
                                -36.855647,
                                174.765249
                            )
                            preloadMap.invalidate()
                        }
                    }
                }
            }
        }

        FileLogger.d(
            "ScheduleActivity",
            "STATE_SNAPSHOT | ${Gson().toJson(viewModel.buildExtraDump())}"
        )

        changeModeButton.setOnClickListener {
            if (!viewModel.isTabulatedView) {
                setSchedulePaginationVisibility(View.VISIBLE)
                setTimelineVisibility(View.GONE)
                changeModeButton.text = "Timeline View"
            } else {
                setSchedulePaginationVisibility(View.GONE)
                setTimelineVisibility(View.VISIBLE)
                changeModeButton.text = "Tabulated View"
            }
            viewModel.isTabulatedView = !viewModel.isTabulatedView
        }

        btnPrevious.setOnClickListener {
            if (viewModel.currentPage > 0) {
                viewModel.currentPage--
                updateScheduleTablePaged()
            }
        }

        btnNext.setOnClickListener {
            if (viewModel.currentPage < viewModel.totalPages - 1) {
                viewModel.currentPage++
                updateScheduleTablePaged()
            }
        }

        // Immediately ask for location permission on startup
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
            try {
                if (viewModel.activeScheduleData.isEmpty()) {
                    Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val no = nextPanelDebugNo()
                val first = viewModel.activeScheduleData.first()

                val selectedIdx =
                    if (shouldUseBusRouteData(first)) findRouteIndexForScheduleItem(first) else -1

                FileLogger.d(
                    "ScheduleActivity START_ROUTE",
                    "ScheduleActivity START_ROUTE | no=$no | first.runNo=${first.runNo} first.runName=${first.runName} ${first.startTime}->${first.endTime} | selectedIdx=$selectedIdx | scheduleSize=${viewModel.activeScheduleData.size} | busRouteDataSize=${viewModel.busRouteData.size}"
                )

                FileLogger.d(
                    "ScheduleActivity START_ROUTE",
                    "ScheduleActivity START_ROUTE | no=$no | first.busStops.size=${first.busStops.size} | firstStop=${first.busStops.firstOrNull()} | lastStop=${first.busStops.lastOrNull()}"
                )
                FileLogger.d(
                    "ScheduleActivity START_ROUTE",
                    "ScheduleActivity START_ROUTE | no=$no | selectedRouteData=${viewModel.busRouteData.getOrNull(selectedIdx)}"
                )

                // Log now (works for Trip or Break)
                viewModel.logPanelDebugPreStart(no, first)

                when {
                    first.isBreak()      -> launchBreakActivity(first, no)
                    first.isSigning()    -> launchSigningActivity(first, no)
                    first.isReposition() -> launchRepActivity(first, no)
                    else                 -> launchMapActivity(no)
                }

            } catch (e: Exception) {
                FileLogger.e("ScheduleActivity", "Error in startRouteButton: ${e.message}")
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up the "Test Start Route" button
        binding.testStartRouteButton.setOnClickListener {
            if (viewModel.activeScheduleData.isEmpty()) {
                Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val no = nextPanelDebugNo()
            val first = viewModel.activeScheduleData.first()

            viewModel.logPanelDebugPreStart(no, first)

            when {
                first.isBreak()   -> launchBreakActivity(first, no)
                first.isSigning() -> launchSigningActivity(first, no)
                else              -> launchMapActivity(no)
            }
        }
    }

    private fun buildClientId(): String {
        return "busflow-${viewModel.aid ?: "unknown"}"
    }

    private fun resolveDeviceTokenFromConfig(): String? {
        val aid = viewModel.aid
        val cfg = viewModel.config.orEmpty()

        if (aid.isNullOrBlank() || cfg.isEmpty()) return null

        val match = cfg.firstOrNull { it.aid.equals(aid, ignoreCase = true) }
        if (match == null) return null

        // Change field name if yours is different
        return match.accessToken?.trim().takeIf { !it.isNullOrBlank() }
    }

    private fun shouldUseBusRouteData(item: ScheduleItem): Boolean {
        // Only REP + normal Trip use busRouteData
        return !item.isBreak() && !item.isSigning()
    }

    // ===== RouteData matching (fix for index mismatch when SignOn/Break don't exist in busRouteData) =====

    private fun findRouteIndexForScheduleItem(item: ScheduleItem): Int {
        val routes = viewModel.busRouteData
        if (routes.isEmpty()) return -1

        val (lat, lon) = pickMatchPoint(item) ?: return -1

        // 1) exact-ish match first
        val exactIdx = routes.indexOfFirst { rd ->
            nearlySame(rd.startingPoint.latitude, lat) && nearlySame(rd.startingPoint.longitude, lon)
        }
        if (exactIdx != -1) return exactIdx

        // 2) fallback: nearest startingPoint within a tolerance (meters)
        var bestIdx = -1
        var bestMeters = Double.MAX_VALUE
        routes.forEachIndexed { idx, rd ->
            val d = distanceMeters(lat, lon, rd.startingPoint.latitude, rd.startingPoint.longitude)
            if (d < bestMeters) {
                bestMeters = d
                bestIdx = idx
            }
        }
        return if (bestMeters <= 120.0) bestIdx else -1 // 120m tolerance for GPS/rounding
    }

    private fun pickMatchPoint(item: ScheduleItem): Pair<Double, Double>? {
        if (item.busStops.isEmpty()) return null

        // Prefer Stop S if present, else first stop, else Stop E/last
        val stopS = item.busStops.firstOrNull { it.name.equals("Stop S", true) }
        val stopE = item.busStops.lastOrNull { it.name.equals("Stop E", true) }

        val chosen = stopS ?: item.busStops.firstOrNull() ?: stopE ?: item.busStops.lastOrNull()
        val lat = chosen?.latitude
        val lon = chosen?.longitude
        if (lat == null || lon == null) return null
        return lat to lon
    }

    private fun nearlySame(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) <= 1e-5

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sLat1 = Math.toRadians(lat1)
        val sLat2 = Math.toRadians(lat2)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(sLat1) * kotlin.math.cos(sLat2) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    /**
     * Optional: once we assign a RouteData to a started segment, remove it from the pool
     * so future segments cannot accidentally reuse it.
     */
    private fun consumeRouteDataIfPossible(selectedIdx: Int) {
        if (selectedIdx < 0) return
        if (selectedIdx >= viewModel.busRouteData.size) return
        viewModel.busRouteData = viewModel.busRouteData.toMutableList().apply { removeAt(selectedIdx) }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun launchSigningActivity(firstScheduleItem: ScheduleItem, no: Int) {
        if (viewModel.activeScheduleData.isEmpty()) {
            Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
            return
        }

        val hasActiveTrip = TripLog.hasActive(this)

        val label = formatPanelLabel(firstScheduleItem)
        val action = firstScheduleItem.signingAction()
        viewModel.loadAccessToken()

        val intent = Intent(this, SigningActivity::class.java).apply {
            putExtra("AID", viewModel.aid)
            putExtra("ACCESS_TOKEN", viewModel.token)

            putExtra("SIGNING_LABEL", label)
            putExtra("SIGNING_ACTION", action)
            putExtra("SIGNING_RUN_NAME", firstScheduleItem.runName)

            // ❌ DO NOT PASS BUS_ROUTE_DATA for Sign On/Off
            putExtra("SELECTED_ROUTE_INDEX", -1)

            putExtra("FIRST_SCHEDULE_ITEM", ArrayList(listOf(firstScheduleItem)))
            putExtra("FULL_SCHEDULE_DATA", ArrayList(viewModel.activeScheduleData))
            putExtra("EXTRA_PANEL_DEBUG_NO", no)
        }

        publishActiveSegment(label)

        FileLogger.d(
            "ScheduleActivity → SigningActivity",
            "ScheduleActivity → SigningActivity | no=$no | label=$label action=$action | selectedIdx=-1 (NO ROUTE)"
        )

        // ✅ Consume only the schedule item (NOT busRouteData)
        if (!hasActiveTrip) {
            updateActiveScheduleDataOnLaunch(
                viewModel.activeScheduleData.toMutableList().apply { removeAt(0) }
            )
        } else {
            Log.w("ScheduleActivity", "⚠️ Active trip detected, keeping first schedule in cache")
        }

        startActivity(intent)
    }

    /**
     * Launches BreakActivity for a "Break" item: pops it from the list, persists cache, refreshes UI, and passes current/remaining schedules.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun launchBreakActivity(firstScheduleItem: ScheduleItem, no: Int) {
        if (viewModel.activeScheduleData.isEmpty()) {
            Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
            return
        }

        val hasActiveTrip = TripLog.hasActive(this)

        val breakLabel = formatPanelLabel(firstScheduleItem)
        viewModel.loadAccessToken()

        val intent = Intent(this, BreakActivity::class.java).apply {
            putExtra("AID", viewModel.aid)
            putExtra("ACCESS_TOKEN", viewModel.token)
            putExtra("BREAK_LABEL", breakLabel)

            // ❌ DO NOT PASS BUS_ROUTE_DATA for Break
            putExtra("SELECTED_ROUTE_INDEX", -1)

            putExtra("FIRST_SCHEDULE_ITEM", ArrayList(listOf(firstScheduleItem)))
            putExtra("FULL_SCHEDULE_DATA", ArrayList(viewModel.activeScheduleData))
            putExtra("EXTRA_PANEL_DEBUG_NO", no)
        }

        publishActiveSegment(breakLabel)

        FileLogger.d(
            "ScheduleActivity → BreakActivity",
            "ScheduleActivity → BreakActivity | no=$no | label=$breakLabel | selectedIdx=-1 (NO ROUTE)"
        )

        // ✅ Consume only the schedule item (NOT busRouteData)
        if (!hasActiveTrip) {
            updateActiveScheduleDataOnLaunch(
                viewModel.activeScheduleData.toMutableList().apply { removeAt(0) }
            )
        } else {
            Log.w("ScheduleActivity", "⚠️ Active trip detected, keeping first schedule in cache")
        }

        startActivity(intent)
    }

    // Handle the user’s response to your initial permission request
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
        if (viewModel.activeScheduleData.isEmpty()) {
            Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedIdx = findRouteIndexForScheduleItem(firstScheduleItem)
        val selectedRouteData = viewModel.busRouteData.getOrNull(selectedIdx)

        val hasActiveTrip = TripLog.hasActive(this)
        val scheduleDataToPass = if (hasActiveTrip) {
            Log.w("ScheduleActivity", "⚠️ Active trip detected, keeping first schedule in cache")
            viewModel.activeScheduleData
        } else {
            viewModel.activeScheduleData.toMutableList().apply { removeAt(0) }
        }

        val intent = Intent(this, RepActivity::class.java).apply {
            val labels = scheduleDataToPass.map { item -> formatPanelLabel(item) }
            putStringArrayListExtra("TIMELINE_LABELS", ArrayList(labels))

            putExtra("AID", viewModel.aid)
            putExtra("CONFIG", ArrayList(viewModel.config ?: emptyList()))
            putExtra("JSON_STRING", viewModel.jsonString)

            // ✅ REP MUST USE ROUTE DATA
            putExtra("BUS_ROUTE_DATA", ArrayList(viewModel.busRouteData))
            putExtra("SELECTED_ROUTE_INDEX", selectedIdx)
            selectedRouteData?.let { putExtra("SELECTED_ROUTE_DATA", it) }

            putExtra("FIRST_SCHEDULE_ITEM", ArrayList(listOf(firstScheduleItem)))
            putExtra("FULL_SCHEDULE_DATA", ArrayList(scheduleDataToPass))
            putExtra("EXTRA_PANEL_DEBUG_NO", no)
        }

        FileLogger.d(
            "ScheduleActivity → RepActivity",
            "ScheduleActivity → RepActivity | no=$no | selectedIdx=$selectedIdx | selectedRoute=${selectedRouteData?.startingPoint}"
        )

        if (!hasActiveTrip) {
            updateActiveScheduleDataOnLaunch(scheduleDataToPass)

            // ✅ ONLY REP/TRIP consume route data
            consumeRouteDataIfPossible(selectedIdx)
        }

        startActivity(intent)
    }

    /**
     * Start MapActivity, carrying over any required extras.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    private fun launchMapActivity(no: Int) {
        if (viewModel.activeScheduleData.isEmpty()) {
            Toast.makeText(this, "No schedules available.", Toast.LENGTH_SHORT).show()
            return
        }

        val firstScheduleItem = viewModel.activeScheduleData.first()

        val selectedIdx = findRouteIndexForScheduleItem(firstScheduleItem)
        val selectedRouteData = viewModel.busRouteData.getOrNull(selectedIdx)

        val hasActiveTrip = TripLog.hasActive(this)
        val scheduleDataToPass = if (hasActiveTrip) {
            Log.w("ScheduleActivity", "⚠️ Active trip detected, keeping first schedule in cache")
            viewModel.activeScheduleData
        } else {
            viewModel.activeScheduleData.toMutableList().apply { removeAt(0) }
        }

        val intent = Intent(this, MapActivity::class.java).apply {
            val labels = scheduleDataToPass.map { item -> formatPanelLabel(item) }
            putStringArrayListExtra("TIMELINE_LABELS", ArrayList(labels))

            putExtra("AID", viewModel.aid)
            putExtra("CONFIG", ArrayList(viewModel.config ?: emptyList()))
            putExtra("JSON_STRING", viewModel.jsonString)

            // ✅ TRIP MUST USE ROUTE DATA
            putExtra("BUS_ROUTE_DATA", ArrayList(viewModel.busRouteData))
            putExtra("SELECTED_ROUTE_INDEX", selectedIdx)
            selectedRouteData?.let { putExtra("SELECTED_ROUTE_DATA", it) }

            putExtra("FIRST_SCHEDULE_ITEM", ArrayList(listOf(firstScheduleItem)))
            putExtra("FULL_SCHEDULE_DATA", ArrayList(scheduleDataToPass))
            putExtra("EXTRA_PANEL_DEBUG_NO", no)
        }

        FileLogger.d(
            "ScheduleActivity → MapActivity",
            "ScheduleActivity → MapActivity | no=$no | selectedIdx=$selectedIdx | selectedRoute=${selectedRouteData?.startingPoint}"
        )

        if (!hasActiveTrip) {
            updateActiveScheduleDataOnLaunch(scheduleDataToPass)

            // ✅ ONLY REP/TRIP consume route data
            consumeRouteDataIfPossible(selectedIdx)
        }

        startActivity(intent)
    }

    /** */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateActiveScheduleDataOnLaunch(newScheduleData: List<ScheduleItem>) {
        viewModel.activeScheduleData = newScheduleData
        viewModel.isScheduleCacheUpdated = false
        saveScheduleDataToCache()
        updateScheduleTablePaged()
        updateTimeline()
        rewriteOfflineScheduleData()
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

        // Update text of darkModeSwitch
        val darkModeSwitch = findViewById<SwitchCompat>(R.id.darkModeSwitch)
        darkModeSwitch.text = if (isDark) "Dark Mode" else "Light Mode"

        // Apply dark mode color to currentDateTimeTextView
        val currentDateTimeTextView = findViewById<TextView>(R.id.currentDateTimeTextView)
        val backgroundColorRes = if (isDark) R.color.grey else R.color.purple_500
        currentDateTimeTextView.setBackgroundColor(ContextCompat.getColor(this, backgroundColorRes))

        // Apply same background color as tint to changeModeButton
        changeModeButton.backgroundTintList = ContextCompat.getColorStateList(this, backgroundColorRes)

        // Change background colors of Start/Test buttons
        val startBtn = findViewById<Button>(R.id.startRouteButton)
        val testBtn = findViewById<Button>(R.id.testStartRouteButton)
        val buttonBackgroundTint = if (isDark) R.color.grey else R.color.purple_400

        startBtn.backgroundTintList = ContextCompat.getColorStateList(this, buttonBackgroundTint)
        testBtn.backgroundTintList = ContextCompat.getColorStateList(this, buttonBackgroundTint)

        // Apply dark mode to custom timeline views
        timeline1.setDarkMode(isDark)
        timeline2.setDarkMode(isDark)
        timeline3.setDarkMode(isDark)

        scheduleAdapter.setThemeMode(isDark)

        // Update networkStatusIndicator if currently online
        val networkIndicator = findViewById<View>(R.id.networkStatusIndicator)
        val isConnected = NetworkStatusHelper.isNetworkAvailable(this)

        val drawableRes = when {
            isDark && isConnected -> R.drawable.dark_circle_shape_green
            !isDark && isConnected -> R.drawable.circle_shape_green
            else -> R.drawable.circle_shape_red // you can define other states as needed
        }

        networkIndicator.background = ContextCompat.getDrawable(this, drawableRes)
    }

    private fun setRouteActionButtonsVisibility(visibility: Int) {
        binding.startRouteButton.visibility = visibility
        binding.testStartRouteButton.visibility = visibility
    }

    private fun setSchedulePaginationVisibility(visibility: Int) {
        scheduleRecycler.visibility = visibility
        paginationLayout.visibility = visibility
    }

    private fun setTimelineVisibility(visibility: Int) {
        timeline1.visibility = visibility
        timeline2.visibility = visibility
        timeline3.visibility = visibility
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
        // Stop “Fetching data …” loop and hide the layout
        fetchingHandler.removeCallbacksAndMessages(null)
        fetchingLayout.visibility = View.GONE

        scheduleRecycler.visibility = View.GONE
        setTimelineVisibility(View.VISIBLE)
        viewModel.isTabulatedView = false
        changeModeButton.text = "Today's Overview"

        setRouteActionButtonsVisibility(View.VISIBLE)
        hideTestButton()
        if (viewModel.isTabulatedView) {
            setSchedulePaginationVisibility(View.VISIBLE)
        } else {
            setSchedulePaginationVisibility(View.GONE)
        }

        viewModel.loadBusDataFromCache()
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
        setSchedulePaginationVisibility(View.GONE)
        setRouteActionButtonsVisibility(View.GONE)
        setTimelineVisibility(View.GONE)

        findViewById<ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        startLoadingBar()
        startFetchingAnimation()

        mqttConfigHelper.fetchConfig { configList ->
            val success = configList.isNotEmpty()
            if (success) {
                viewModel.config = configList
                viewModel.loadAccessToken()
                mqttManager = MqttManager(clientId = buildClientId(), username = viewModel.token)
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
                val dots = ".".repeat(viewModel.dotCount)

                // Update the text with the animated dots
                fetchingText.text = "Fetching data $dots"

                // Cycle dotCount: 1 → 2 → 3 → 1 → ...
                viewModel.dotCount = (viewModel.dotCount % 3) + 1

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
        // ✅ Stop “Fetching data …” loop and hide the layout
        fetchingHandler.removeCallbacksAndMessages(null)
        fetchingLayout.visibility = View.GONE

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

                // Stop animation and show complete message/icon
                fetchingHandler.removeCallbacksAndMessages(null)
                fetchingLayout.visibility = View.GONE
                fetchingLayout.visibility = View.INVISIBLE

                // Show main timeline + buttons
                setTimelineVisibility(View.VISIBLE)
                updateScheduleTablePaged()
                updateTimeline()

                setRouteActionButtonsVisibility(View.VISIBLE)
                hideTestButton()
            }
            .show()
    }

    /**
     * Updates the schedule table with paginated rows.
     * Shows only maxRowsPerPage items for the currentPage.
     * Also triggers pagination button rendering.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateScheduleTablePaged() {
        scheduleAdapter.update(viewModel.paginatedActiveScheduleData)

        val totalPages = if (viewModel.activeScheduleData.isEmpty()) 0 else viewModel.totalPages
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

        if (viewModel.isTabulatedView) {
            paginationLayout.visibility = View.VISIBLE
        }

        for (page in 0 until totalPages) {
            val button = Button(this).apply {
                text = (page + 1).toString()
                textSize = 14f
                setPadding(8, 4, 8, 4)
                setOnClickListener {
                    viewModel.currentPage = page
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
            if (viewModel.activeScheduleData.isEmpty()) {
                Log.e("ScheduleActivity updateTimeline", "No scheduleData to draw!")
                updateEmptyState()
                return@runOnUiThread
            }

            setTimelineVisibility(View.VISIBLE)
            if (viewModel.activeScheduleData.isEmpty()) {
                Log.e("ScheduleActivity updateTimeline", "No scheduleData to draw!")
                return@runOnUiThread
            }

            // 1) extract raw data
            val (workIntervals, runNames) = viewModel.extractWorkIntervalsAndRunNames()

            // 2) decide how many per row
            val maxPerLine = 3

            // slice the corresponding ScheduleItem lists
            // slice intervals & names into 3 fixed chunks
            val intervals1 = workIntervals.take(maxPerLine)
            val intervals2 = workIntervals.drop(maxPerLine).take(maxPerLine)
            val intervals3 = workIntervals.drop(2 * maxPerLine).take(maxPerLine)

            val names1 = runNames.take(maxPerLine)
            val names2 = runNames.drop(maxPerLine).take(maxPerLine)
            val names3 = runNames.drop(2 * maxPerLine).take(maxPerLine)

            // 3) slice the corresponding ScheduleItem lists
            val items1 = viewModel.activeScheduleData.take(maxPerLine)
            val items2 = viewModel.activeScheduleData.drop(maxPerLine).take(maxPerLine)
            val items3 = viewModel.activeScheduleData.drop(2 * maxPerLine).take(maxPerLine)

            updateSingleTimeline(intervals1, names1, items1, timeline1)
            updateSingleTimeline(intervals2, names2, items2, timeline2)
            updateSingleTimeline(intervals3, names3, items3, timeline3)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateSingleTimeline(
        intervals: List<Pair<String, String>>,
        names: List<String>,
        items: List<ScheduleItem>,
        timeline: StyledMultiColorTimeline
    ) {
        Log.d(
            "ScheduleActivity",
            "🔹updateTimeline: timeline1 intervals=$intervals, names=$names"
        )

        // 1) extract busStops per line
        val busStops = items.flatMap { it.busStops }

        Log.d("ScheduleActivity", "🔹 timeline-x busStops=$busStops")

        // 2) decide single‐line mode threshold
        val oneLineThreshold = 4
        val single = intervals.size <= oneLineThreshold

        // 3) apply to each StyledMultiColorTimeline
        timeline.apply {
            setScheduleData(items)
            setTimelineData(intervals, names)
            setBusStops(busStops)
            setSingleLineMode(single)
        }
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
     * Requests admin messages periodically.
     */
    private fun requestAdminMessage() {
        val jsonObject = JSONObject()
        jsonObject.put("sharedKeys","message,busRoute,busStop,config,busRouteData,scheduleData")
        val jsonStringSharedKeys = jsonObject.toString()
        val handler = Handler(Looper.getMainLooper())
        mqttManager.publish(MqttHelper.Companion.PUB_MSG_TOPIC, jsonStringSharedKeys)
        handler.post(object : Runnable {
            override fun run() {
                mqttManager.publish(MqttHelper.Companion.PUB_MSG_TOPIC, jsonStringSharedKeys)
                handler.postDelayed(this, REQUEST_PERIODIC_TIME)
            }
        })
    }

    /**
     * Loads schedule data from the cache file if it exists and extracts schedule-related data.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    private fun loadScheduleDataFromCache() {
        ioScope.launch {
            val cacheFile = File(viewModel.getHiddenFolder(), "scheduleDataCache.txt")

            if (cacheFile.exists()) {
                try {
                    viewModel.loadScheduleDataFromCache(cacheFile)

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

    /** Connects to the MQTT broker and subscribes to the required topics. */
    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("LongLogTag")
    private fun connectAndSubscribe() {
        mqttManager.connect { isConnected ->
            if (isConnected) {
                Log.d("ScheduleActivity connectAndSubscribe", "✅ Connected to MQTT broker successfully.")
                subscribeSharedData()
            } else {
                Log.e("ScheduleActivity connectAndSubscribe", "❌ Failed to connect to MQTT broker. Running in offline mode.")
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
    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("LongLogTag")
    private fun subscribeSharedData() {
        Log.d("ScheduleActivity", mqttManager.getUsername())
        mqttManager.subscribe(MqttHelper.Companion.SUB_MSG_TOPIC) { message ->
            runOnUiThread {
                try {
                    // Parse incoming message
                    val gson = Gson()
                    val data = gson.fromJson(message, Bus::class.java)

                    // Update configuration
                    viewModel.config = data.shared?.config?.busConfig
                    viewModel.arrBusData = viewModel.config.orEmpty()

                    FileLogger.d("ScheduleActivity subscribeSharedData", "Config: ${viewModel.config}")

                    if (viewModel.config.isNullOrEmpty()) {
                        return@runOnUiThread
                    }

                    // Check if AID matches any entry in config
                    val matchingAid = viewModel.config!!.any { it.aid == viewModel.aid }
                    if (!matchingAid) {
                        Toast.makeText(this, "AID does not match.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    checkForUpdateOnce()

                    // Retrieve `busRouteData` from ThingsBoard
                    viewModel.busRouteData = data.shared?.busRouteData1 ?: emptyList()
                    FileLogger.d(
                        "ScheduleActivity busRouteData",
                        "ScheduleActivity subscribeSharedData | busRouteData.size=${viewModel.busRouteData.size} | first=${viewModel.busRouteData.firstOrNull()}"
                    )

                    // Retrieve `scheduleData` from ThingsBoard
                    viewModel.scheduleData = (data.shared?.scheduleData1 ?: emptyList()).map { it.copy(runName = safeRunName(it)) }
                    viewModel.activeScheduleData = viewModel.scheduleData.toList() // Shallow copy to sever connection
                    FileLogger.d(
                        "ScheduleActivity scheduleData",
                        "ScheduleActivity subscribeSharedData | scheduleData.size=${viewModel.activeScheduleData.size} | first=${viewModel.activeScheduleData.firstOrNull()}"
                    )

                    if (viewModel.config != null && viewModel.activeScheduleData.isNotEmpty()) {
                        // Save the updated schedule data and bus data immediately
                        saveBusDataToCache()
                        saveScheduleDataToCache()

                        // Mark as updated so offline mode does not reload cache unnecessarily
                        viewModel.isScheduleUpdatedFromServer = true
                    }

                    // **Rewrite cache when online**
                    if (NetworkStatusHelper.isNetworkAvailable(this@ScheduleActivity)) {
                        // Save data **only after all values are updated**
                        if (viewModel.config!!.isNotEmpty() && viewModel.route.isNotEmpty() && viewModel.stops.isNotEmpty()) {
                            saveBusDataToCache()
                            saveScheduleDataToCache()
                        }
                    }

                    // Extract `route`, `stops`, and `durationBetweenStops` from `busRouteData`
                    viewModel.processBusRouteData()

                    if (viewModel.route.isNotEmpty()) {
                        // Convert route to BusStopInfo and update ProximityManager
//                        val busStopInfoList = convertRouteToBusStopInfo(route)
//                        BusStopProximityManager.setBusStopList(busStopInfoList)

                        // Generate polyline and markers on first-time initialization
//                        if (firstTime) {
//                            firstTime = false
//                        }
                    } else {
                        FileLogger.d("ScheduleActivity subscribeSharedData", "No route data available.")
                    }
                } catch (e: Exception) {
                    Log.e("ScheduleActivity subscribeSharedData", "Error processing shared data: ${e.message}", e)
                    Toast.makeText(this, "Error processing shared data.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun checkForUpdateOnce() {
        if (otaCheckedThisLaunch) return
        otaCheckedThisLaunch = true

        val token = resolveDeviceTokenFromConfig()?.trim()
        if (token.isNullOrBlank()) return

        val ota = OtaUpdateManager(
            context = this,
            tbHost = "https://thingsboard.cloud",
            deviceToken = token
        )

        lifecycleScope.launch {
            when (val res = ota.checkForUpdateOnly()) {
                is OtaCheckResult.UpdateAvailable -> {
                    val serverVersion = res.info.version
                    val installed = BuildConfig.VERSION_NAME

                    val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                    val lastPrompted = prefs.getString("last_prompted_sw_version", null)

                    if (isServerVersionNewer(serverVersion, installed) && serverVersion != lastPrompted) {
                        AlertDialog.Builder(this@ScheduleActivity)
                            .setTitle("Update available")
                            .setMessage("New version $serverVersion is available (installed: $installed). Update now?")
                            .setPositiveButton("Update") { _, _ ->
                                lifecycleScope.launch {
                                    val ok = ota.downloadAndInstall(res.info)
                                    if (ok) {
                                        prefs.edit().putString("last_prompted_sw_version", serverVersion).apply()
                                    }
                                }
                            }
                            .setNegativeButton("Not now") { d, _ ->
                                prefs.edit().putString("last_prompted_sw_version", serverVersion).apply()
                                d.dismiss()
                            }
                            .show()
                    }
                }
                else -> {
                    // Do nothing automatically (no “up to date” spam)
                }
            }
        }
    }

    private fun isServerVersionNewer(server: String, installed: String): Boolean {
        val s = parseTwoPartVersion(server) ?: return false
        val i = parseTwoPartVersion(installed) ?: return false
        return (s.first > i.first) || (s.first == i.first && s.second > i.second)
    }

    private fun parseTwoPartVersion(v: String): Pair<Int, Int>? {
        // expects "1.00", "1.01" ...
        val parts = v.trim().split(".")
        if (parts.size != 2) return null
        val a = parts[0].toIntOrNull() ?: return null
        val b = parts[1].toIntOrNull() ?: return null
        return a to b
    }

    /**
     * Saves the latest bus data to the cache file.
     */
    @SuppressLint("LongLogTag")
    private fun saveBusDataToCache() {
        ioScope.launch {
            // 1) Skip if no internet or already updated
            if (!NetworkStatusHelper.isNetworkAvailable(this@ScheduleActivity) || viewModel.isBusCacheUpdated) {
                return@launch
            }
            try {
                // 2) Write JSON to disk and mark flag so we never repeat
                viewModel.saveBusDataToCache()

                // 3) Back to Main to show toast exactly once
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
    @SuppressLint("LongLogTag")
    private fun saveScheduleDataToCache() {
        ioScope.launch {
            // 1) Skip if already updated
            if (viewModel.isScheduleCacheUpdated) {
                return@launch
            }
            try {
                // 2) Ensure file exists, then write JSON and mark flag so we never repeat
                viewModel.saveScheduleDataToCache()

                // 3) Back to Main to show toast exactly once
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
            val cacheFile = File(viewModel.getHiddenFolder(), "scheduleDataCache.txt")

            try {
                val jsonString = Gson().toJson(viewModel.activeScheduleData) // Convert updated data to JSON
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
                    intent.data = "package:$packageName".toUri()
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
                    Log.d("ScheduleActivity", "All files access granted.")
                } else {
                    Log.e("ScheduleActivity", "User denied all files access.")
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
    @SuppressLint("HardwareIds")
    private fun getAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun nextPanelDebugNo(): Int {
        val sp = getSharedPreferences(PANEL_DEBUG_PREF, MODE_PRIVATE)
        val next = sp.getInt(PANEL_DEBUG_NO_KEY, 0) + 1
        sp.edit { putInt(PANEL_DEBUG_NO_KEY, next) }
        return next
    }

    private fun publishActiveSegment(label: String) {
        val payload = "{\"currentTripLabel\":\"${label.replace("\"", "\\\"")}\"}"
        if (::mqttManager.isInitialized) {
            mqttManager.publish(MqttHelper.ATTR_TOPIC, payload)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateEmptyState() {
        val isEmpty = viewModel.activeScheduleData.isEmpty()

        // Empty-state label
        emptyStateText.visibility = if (isEmpty) View.VISIBLE else View.GONE

        // Hide timelines & table/pagination if empty
        if (isEmpty) {
            setTimelineVisibility(View.GONE)
            setSchedulePaginationVisibility(View.GONE)

            // Hide action buttons if nothing to start
            setRouteActionButtonsVisibility(View.GONE)
        } else {
            // Restore based on current mode
            if (viewModel.isTabulatedView) {
                setSchedulePaginationVisibility(View.VISIBLE)
                setTimelineVisibility(View.GONE)
            } else {
                setSchedulePaginationVisibility(View.GONE)
                setTimelineVisibility(View.VISIBLE)
            }
            setRouteActionButtonsVisibility(View.VISIBLE)
            hideTestButton()
        }
    }

    private fun ScheduleItem.isSigning(): Boolean {
        return (runName ?: "").contains("sign", ignoreCase = true)
    }

    private fun ScheduleItem.signingAction(): String {
        val rn = (runName ?: "").lowercase(Locale.getDefault())
        return if (rn.contains("off") || rn.contains("out")) "OFF" else "IN" // "on"/"in" => IN
    }

    override fun onResume() {
        super.onResume()
        // If any trip/break/reposition had been started, landing here means it ended.
        if (TripLog.hasActive(this)) {
            TripLog.end(this, reason = "ScheduleActivityResumed", extraDump = viewModel.buildExtraDump())
        }
    }

    private fun hideTestButton() {
        if (::binding.isInitialized) {
            binding.testStartRouteButton.visibility = View.GONE
        }
    }

}