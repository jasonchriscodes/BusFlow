package com.jason.publisher.modules.map.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.main.model.BusRoute
import java.text.SimpleDateFormat
import java.util.*
import android.content.Intent
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.autofill.AutofillManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import com.google.android.gms.location.*
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.LifecycleLogger
import com.jason.publisher.main.model.BusItem
import com.jason.publisher.main.model.BusStop
import com.jason.publisher.main.model.RouteData
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.modules.battery.ui.hookBatteryToasts
import com.jason.publisher.modules.map.models.BusStopWithTimingPoint
import com.jason.publisher.modules.map.services.LocationManager
import com.jason.publisher.modules.map.services.LastLocationStore
import com.jason.publisher.modules.map.utils.BUS_STOP_RADIUS
import com.jason.publisher.modules.map.utils.calculateDistance
import com.jason.publisher.modules.map.utils.calculateDurationForUpdate
import com.jason.publisher.modules.map.utils.formatPanelLabel
import com.jason.publisher.modules.map.utils.getLastScheduledAddress
import com.jason.publisher.modules.map.viewmodels.DetailPanelController
import com.jason.publisher.modules.map.viewmodels.MapViewController
import com.jason.publisher.modules.map.viewmodels.MapViewModel
import com.jason.publisher.modules.map.viewmodels.ScheduleStatusManager
import com.jason.publisher.modules.map.viewmodels.TimeManager
import com.jason.publisher.modules.map.mqtt.helpers.MqttConfigHelper
import com.jason.publisher.modules.map.mqtt.helpers.MqttHelper
import com.jason.publisher.modules.map.mqtt.services.MqttManager
import com.jason.publisher.modules.network.utils.NetworkStatusHelper
import com.jason.publisher.R
import com.jason.publisher.main.utils.convertTimeToMinutes
import com.jason.publisher.main.utils.getNextScheduleStartTime
import com.jason.publisher.main.utils.parseTimeToday
import com.jason.publisher.modules.map.utils.calculateBearing
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.model.common.Observer
import java.util.Locale.getDefault

open class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding

    private var tripEndDialog: AlertDialog? = null

    private lateinit var speedTextView: TextView
    lateinit var upcomingBusStopTextView: TextView
    private lateinit var timingPointandStopsTextView: TextView
    private lateinit var tripEndTimeTextView: TextView

    private lateinit var timingPointValueTextView: TextView
    private lateinit var apiTimeValueTextView: TextView
    lateinit var scheduleStatusValueTextView: TextView

    lateinit var currentTimeTextView: TextView
    lateinit var nextTripCountdownTextView: TextView

    private lateinit var connectionStatusTextView: TextView
    private lateinit var networkStatusIndicator: View

    val viewModel: MapViewModel by viewModels {
        MapViewModel.provideFactory()
    }

    open fun getScheduleStatusNowMillis(): Long = System.currentTimeMillis()
    open fun getDisplayNowMillis(): Long = System.currentTimeMillis()

    private lateinit var locationManager: LocationManager

    private lateinit var mqttHelper: MqttHelper
    val mqttConfigHelper = MqttConfigHelper()
    lateinit var mqttManager: MqttManager
    val timeManager: TimeManager by viewModels {
        TimeManager.provideFactory()
    }
    lateinit var mapController: MapViewController
    lateinit var panelController: DetailPanelController
    lateinit var scheduleStatusManager: ScheduleStatusManager
    val connectivityManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val otherBusLabels = mutableMapOf<String,String>()
    private var autoTapArrivalDone = false

    // Handler untuk periodic UI update
    private var periodicUIUpdateHandler: Handler? = null
    private var periodicUIUpdateRunnable: Runnable? = null

    /** Throttling and debouncing for better performance */
    private var lastUIUpdateTime = 0L
    private val uiUpdateThrottleMs = 500L // Minimum 500ms between UI updates
    private var lastMapInvalidateTime = 0L
    private val mapInvalidateThrottleMs = 1000L // Minimum 1 second between map invalidates

    // Location update throttling
    private var lastLocationUpdateTime = 0L
    private val locationUpdateThrottleMs = 2000L // Minimum 2 seconds between location-based UI updates
    val panelDebounceHandler: Handler = Handler(Looper.getMainLooper())
    var panelDebounceRunnable: Runnable? = null
    private var lastNextRunSafetyToastAt = 0L
    private val nextRunSafetyToastCooldownMs = 5 * 60 * 1000L // 5 minutes

    @RequiresApi(Build.VERSION_CODES.N)
    @SuppressLint("LongLogTag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FileLogger.d("MapActivity", "onCreate")

        // Reset final stop message flag for new trip
        viewModel.hasShownFinalStopMessage = false
        hookBatteryToasts()

        autoTapArrivalDone = savedInstanceState?.getBoolean("autoTapArrivalDone") ?: false

        hideSystemUI()

        // Add logger
        FileLogger.init(this)
        FileLogger.markAppOpened("MapActivity")

        // ── ADD THIS: initialize mqttManager for offline use ──
        mqttManager = MqttManager()

        // Initialize Managers before using it
        scheduleStatusManager = ScheduleStatusManager(this, binding)
        mqttHelper = MqttHelper(this, binding)
        panelController = DetailPanelController(this, binding.detailIconsContainer)
        mapController = MapViewController(this, binding.map, mqttHelper)

        // Retrieve data passed from TimeTableActivity
        viewModel.aid = intent.getStringExtra("AID") ?: "Unknown"
        viewModel.jsonString = intent.getStringExtra("JSON_STRING") ?: ""
        viewModel.durationBetweenStops = intent.getDoubleArrayExtra("DURATION_BETWEEN_BUS_STOP")?.toList() ?: emptyList()
        val timelineLabels = intent.getStringArrayListExtra("TIMELINE_LABELS") ?: emptyList<String>()

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            viewModel.config = intent.getParcelableArrayListExtra("CONFIG", BusItem::class.java) ?: emptyList()
            viewModel.route = intent.getParcelableArrayListExtra("ROUTE", BusRoute::class.java) ?: emptyList()
            viewModel.stops = intent.getParcelableArrayListExtra("STOPS", BusStop::class.java) ?: emptyList()
            viewModel.busRouteData = intent.getParcelableArrayListExtra("BUS_ROUTE_DATA", RouteData::class.java) ?: emptyList()
            viewModel.scheduleList = intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM", ScheduleItem::class.java) ?: emptyList()
            viewModel.scheduleData = intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA", ScheduleItem::class.java) ?: emptyList()
        } else {
            viewModel.config = intent.getParcelableArrayListExtra("CONFIG") ?: emptyList()
            viewModel.route = intent.getParcelableArrayListExtra("ROUTE") ?: emptyList()
            viewModel.stops = intent.getParcelableArrayListExtra("STOPS") ?: emptyList()
            viewModel.busRouteData = intent.getParcelableArrayListExtra("BUS_ROUTE_DATA") ?: emptyList()
            viewModel.scheduleList = intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM") ?: emptyList()
            viewModel.scheduleData = intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA") ?: emptyList()
        }

        val no = intent.getIntExtra("EXTRA_PANEL_DEBUG_NO", -1)
        val idx = intent.getIntExtra("SELECTED_ROUTE_INDEX", -1)

        FileLogger.d(
            "MapActivity INTENT",
            "MapActivity INTENT | no=$no | scheduleList.size=${viewModel.scheduleList.size} | scheduleData.size=${viewModel.scheduleData.size} | busRouteData.size=${viewModel.busRouteData.size}"
        )
        FileLogger.d(
            "MapActivity INTENT",
            "MapActivity INTENT | no=$no | SELECTED_ROUTE_INDEX=$idx | SELECTED_ROUTE_DATA=${viewModel.selectedRouteData}"
        )
        FileLogger.d(
            "MapActivity INTENT",
            "MapActivity INTENT | no=$no | FIRST_SCHEDULE_ITEM=${viewModel.scheduleList.firstOrNull()}"
        )

        Log.d("MapActivity onCreate retrieve", "▶ Received timelineLabels = $timelineLabels")
        viewModel.logReceivedStates()

        // Log MapActivity opened with route info AFTER scheduleList is initialized
        viewModel.logMapActivityOpening()

        // Log detailed activity entry
        viewModel.logActivityEntry()

        val selfLabel = viewModel.scheduleList.firstOrNull()?.let { formatPanelLabel(it) }
        panelController.activeSegment = selfLabel // let the controller draw using this exact text
        selfLabel?.let {
            publishActiveSegment(it)            // tell the other tablet right now
            Handler(Looper.getMainLooper()).postDelayed({ publishActiveSegment(it) }, 1200) // nudge once more
        }
        panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())

        // Prefer SELECTED_ROUTE_DATA from Intent.
// If it is null, fallback to SELECTED_ROUTE_INDEX.
// If index also fails, fallback to first route.
        val selectedIdx = intent.getIntExtra("SELECTED_ROUTE_INDEX", -1)

        val selectedRouteFromIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("SELECTED_ROUTE_DATA", RouteData::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("SELECTED_ROUTE_DATA")
            }

        viewModel.selectedRouteData =
            selectedRouteFromIntent
                ?: viewModel.busRouteData.getOrNull(selectedIdx)
                        ?: viewModel.busRouteData.firstOrNull()

        // If we have a selected route, derive the map vectors from it
        viewModel.deriveMapVectors()
        dumpMapVectors("RepActivity", no)

        viewModel.extractRedBusStops()

        // Initialize UI components
        initializeUIComponents()

        viewModel.nextTimingPoint.observe(this) {
            if (::timingPointValueTextView.isInitialized) {
                timingPointValueTextView.text = it
                viewModel.upcomingStop = it
            }
        }
        viewModel.upcomingStopName.observe(this) {
            if (::upcomingBusStopTextView.isInitialized) {
                upcomingBusStopTextView.text = it
            }
        }
        viewModel.lastSpeedText.observe(this) {
            if (::speedTextView.isInitialized) {
                speedTextView.text = it
            }
        }
        viewModel.lastCurrentTimeText.observe(this) {
            if (::currentTimeTextView.isInitialized) {
                timeManager.currentTime.postValue(it)
            }
        }
        viewModel.lastTripEndTimeText.observe(this) {
            if (::tripEndTimeTextView.isInitialized) {
                tripEndTimeTextView.text = it
            }
        }

        // Start the current time counter
        timeManager.startCurrentTimeUpdater(timeProvider = { getDisplayNowMillis() }) { /* no-op */ }
        timeManager.currentTime.observe(this) { currentTimeTextView.text = it }

        // Start the next trip countdown updater
        val countdownList = ArrayList<ScheduleItem>().apply {
            addAll(viewModel.scheduleList)  // current (REP)
            addAll(viewModel.scheduleData)  // remaining (Run 3, etc)
        }
        timeManager.startNextTripCountdownUpdater(countdownList, timeProvider = { getDisplayNowMillis() })

        timeManager.nextTripCountdown.observe(this) { nextTripCountdownTextView.text = it }

        updateApiTime() // Ensure API time is updated at the start

        // Start periodic UI update to ensure UI stays updated even if location doesn't change
        startPeriodicUIUpdate()

        timingPointValueTextView.text = viewModel.initializeTimingPoint()

        // Ensure locationManager is initialized
        locationManager = LocationManager(this)

        // Set up network status UI
        NetworkStatusHelper.setupNetworkStatus(this, binding.connectionStatusTextView, binding.networkStatusIndicator)

        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // we’re back online → force a one-off refresh, then resume polling
                runOnUiThread {
                    // reuse the same helper to flip your dot + text
                    NetworkStatusHelper.setupNetworkStatus(
                        this@MapActivity,
                        connectionStatusTextView,
                        networkStatusIndicator
                    )

                    // re-fetch config (to repopulate arrBusData & token)
                    mqttConfigHelper.fetchConfig { configList ->
                        val success = configList.isNotEmpty()
                        if (success) {
                            // rebuild your MQTT client with the new token
                            viewModel.config = configList
                            viewModel.token = MqttConfigHelper.getAccessToken(
                                viewModel.aid,
                                viewModel.config.orEmpty()
                            )
                            mqttManager = MqttManager(username = viewModel.token)
                            // tell ThingsBoard to re-send shared data
                            mqttHelper.requestAdminMessage()
                            // (re)subscribe to the shared message topic
                            mqttHelper.connectAndSubscribe()
                            // now poll each bus once, then resume polling loop
                            // only start polling if mqttManager is ready
                            if (::mqttManager.isInitialized) {
                                mqttHelper.refreshAllAttributes()
                                mqttHelper.startAttributePolling()
                            }
                            // and redraw your detail panel
                            mapController.getDefaultConfigValue()
                            panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())
                            // Log immediately
                            if (!panelController.hasDumpedPanelLog) {
                                panelController.logPanelDebugFromDetailPanel()
                                panelController.hasDumpedPanelLog = true
                            }
                            mapController.startActivityMonitor()
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                // went offline → stop polling
                runOnUiThread {
                    NetworkStatusHelper.setupNetworkStatus(
                        this@MapActivity,
                        connectionStatusTextView,
                        networkStatusIndicator
                    )
                    // only stop polling if mqttManager is ready
                    if (::mqttManager.isInitialized) {
                        mqttHelper.stopAttributePolling()
                    }
                }
            }
        })

        // 2) Now fetch the shared config from your server (ThingsBoard)
        // Always switch back to the main thread before touching any views:
        mqttConfigHelper.fetchConfig { configList ->
            val success = configList.isNotEmpty()
            runOnUiThread {
                if (success) {
                    viewModel.config = configList
                    viewModel.token = MqttConfigHelper.getAccessToken(
                        viewModel.aid,
                        viewModel.config.orEmpty()
                    )
                    mqttManager = MqttManager(username  = viewModel.token)

                    // build your markers etc.
                    mapController.getDefaultConfigValue()
                    panelController.activeSegment = selfLabel
                    panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())

                    // Log immediately
                    panelController.logPanelDebugFromDetailPanel()

                    // Log again shortly after MQTT polling begins (gives time for other buses to populate)
                    Handler(Looper.getMainLooper()).postDelayed({
                        panelController.logPanelDebugFromDetailPanel()
                    }, 1200)

                    mqttHelper.requestAdminMessage()
                    mqttHelper.connectAndSubscribe()
                    mqttHelper.startAttributePolling()
                    mapController.startActivityMonitor()
                } else {
                    // --- FAILURE HANDLING ---
                    // ensure mqttManager is assigned even on config-fetch failure
                    mqttManager = MqttManager()
                    Log.e("MapActivity", "Failed to fetch config, entering offline mode.")
                    Toast.makeText(
                        this@MapActivity,
                        "Unable to connect. Falling back to offline map…",
                        Toast.LENGTH_LONG
                    ).show()

                    // load your offline map immediately
                    mapController.openMapFromAssets()

                    // **populate the detail-panel** exactly as in online mode
                    mapController.getDefaultConfigValue()
                    panelController.activeSegment = selfLabel
                    panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())

                    // disable any UI that needs live data
                    binding.startSimulationButton.isEnabled = false
                    binding.stopSimulationButton.isEnabled  = false
                }
            }
        }

        viewModel.updateBusNameFromConfig()

        // Ensure `locationManager` is properly initialized before use
        locationManager.getCurrentLocation(viewModel.locationListener)

        // Load offline map first
        mapController.openMapFromAssets()

        // Start tracking the location and updating the marker
        startLocationUpdate()

        // Mock data to check scheduleStatusValueTextView
        if (viewModel.forceAheadStatus) {
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
            apiTimeValueTextView.text = "22:21:00"         // apiTimeStr

            timeManager.startCurrentTimeUpdater {
                // Update schedule status based on the new simulated time
                scheduleStatusManager.checkScheduleStatus()
            }

            // Trigger visual change to test schedule status UI
            scheduleStatusManager.checkScheduleStatus()
        }

        binding.map.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // ensure we only do this once and the map is actually visible
                if (!autoTapArrivalDone && binding.map.width > 0 && binding.map.height > 0) {
                    autoTapArrivalDone = true
                    binding.map.viewTreeObserver.removeOnGlobalLayoutListener(this)

                    // only auto-tap if it makes sense
                    val canConfirm = viewModel.scheduleList.isNotEmpty() && viewModel.stops.isNotEmpty()
                    if (canConfirm && binding.arriveButton.isShown && !isFinishing) {
                        // small delay so UI settles (optional)
                        binding.arriveButton.postDelayed({
                            // will invoke your existing setOnClickListener { confirmArrival() }
                            binding.arriveButton.performClick()
                        }, 400)
                    }
                }
            }
        })

        binding.map.model.mapViewPosition.addObserver(object : Observer {
            private var lastZoom = -1.0

            override fun onChange() {
                val zoom = binding.map.model.mapViewPosition.zoomLevel.toDouble()
                // ✅ OPTIMIZED: Only log when zoom actually changes (not every observer call)
                val zoomChanged = zoom != lastZoom
                if (zoomChanged) {
                    lastZoom = zoom
                    Log.d("MapActivity", "Zoom changed to $zoom")
                }
                runOnUiThread {
                    panelController.refreshDetailPanelIcons(zoom)
                    // nly log panel debug when zoom changes
                    if (zoomChanged) {
                        panelController.logPanelDebugFromDetailPanel()
                    }
                }
            }
        })

        binding.startSimulationButton.setOnClickListener {
//            startSimulation()
        }
        binding.stopSimulationButton.setOnClickListener {
            resetSimulationState()
            Toast.makeText(this, "Simulation stopped and state reset", Toast.LENGTH_SHORT).show()
        }
        binding.backButton.setOnClickListener {
            if (viewModel.speed > 5.0) {  // Treat speeds above 5 km/h as "moving"
                Toast.makeText(this, "❌ Bus must be moving slower than 5 km/h before ending the trip.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Show number pad confirmation dialog
            val dlgBuilder = AlertDialog.Builder(this)
            val numberPadView = layoutInflater.inflate(R.layout.dialog_number_pad, null)
            val numberPadInput = numberPadView.findViewById<EditText>(R.id.numberPadInput)

            // 1) DO NOT mark it as a "password" input type (that triggers credential suggestions).
            //    Use plain numeric input + manually mask it.
            numberPadInput.inputType = InputType.TYPE_CLASS_NUMBER
            numberPadInput.transformationMethod = PasswordTransformationMethod.getInstance()

            // 2) Turn off saving & autofill on the exact field.
            numberPadInput.isSaveEnabled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                numberPadInput.setAutofillHints(null) // not emptyArray()
                ViewCompat.setImportantForAutofill(numberPadInput, View.IMPORTANT_FOR_AUTOFILL_NO)
            }

            // (optional) keep the keyboard from learning this content
            numberPadInput.imeOptions = numberPadInput.imeOptions or
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

            dlgBuilder.setView(numberPadView)
                .setTitle("Enter Passcode")
                .setPositiveButton("Confirm") { _, _ ->
                    // ensure attribute is cleared (defensive — also called at final stop)
                    clearActiveSegmentAndRefresh()
                    val enteredCode = numberPadInput.text.toString()
                    if (enteredCode == "0000") {
                        finishToScheduleWithRemainingTrips()
                    } else {
                        Toast.makeText(this, "❌ Incorrect code. Please enter 0000.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }

            val dialog = dlgBuilder.create()

            // 3) After the dialog is attached, also block autofill on the dialog window
            dialog.setOnShowListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    dialog.window?.decorView?.importantForAutofill =
                        View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

                    // 4) Cancel any running autofill session to dismiss inline suggestions immediately.
                    getSystemService(AutofillManager::class.java)?.cancel()
                }
            }
            dialog.show()
        }

        binding.speedUpButton.setOnClickListener {
            viewModel.speedUp()
        }

        binding.slowDownButton.setOnClickListener {
            viewModel.slowDown()
        }

        binding.arriveButton.setOnClickListener {
            confirmArrival()
        }
    }

    // ===== DEBUG: log everything used to build bus stop symbols + polyline =====

    private fun dumpMapVectors(tag: String, no: Int) {
        try {
            val rd = viewModel.selectedRouteData
            val sp = rd?.startingPoint
            val np0 = rd?.nextPoints?.firstOrNull()

            FileLogger.d(tag, "MAP_VECTORS | no=$no | aid=${viewModel.aid} | jsonString.len=${viewModel.jsonString.length}")

            // RouteData sources (what should generate vectors)
            FileLogger.d(tag,
                "MAP_VECTORS | no=$no | selectedRouteData=" +
                        "start=(${sp?.latitude},${sp?.longitude}) '${sp?.address}' " +
                        "| next0=(${np0?.latitude},${np0?.longitude}) '${np0?.address}' " +
                        "| nextPoints.size=${rd?.nextPoints?.size ?: 0} " +
                        "| routeCoords0.size=${np0?.routeCoordinates?.size ?: 0}"
            )

            // Entire RouteData pool summary
            FileLogger.d(tag,
                "MAP_VECTORS | no=$no | busRouteData.size=${viewModel.busRouteData.size} " +
                        "| busRouteData.first.start=${viewModel.busRouteData.firstOrNull()?.startingPoint} " +
                        "| busRouteData.last.start=${viewModel.busRouteData.lastOrNull()?.startingPoint}"
            )

            // The actual polyline + stop symbols used for drawing
            FileLogger.d(tag,
                "MAP_VECTORS | no=$no | route(polyline).size=${viewModel.route.size} " +
                        "| route.first=${viewModel.route.firstOrNull()} " +
                        "| route.last=${viewModel.route.lastOrNull()}"
            )
            FileLogger.d(tag,
                "MAP_VECTORS | no=$no | stops(symbols).size=${viewModel.stops.size} " +
                        "| stops.first=${viewModel.stops.firstOrNull()} " +
                        "| stops.last=${viewModel.stops.lastOrNull()}"
            )

            // Runtime state that affects drawing / detection
            FileLogger.d(tag,
                "MAP_STATE | no=$no | currentStopIndex=${viewModel.currentStopIndex} " +
                        "| passedStops.size=${viewModel.passedStops.size} " +
                        "| passedStops.last=${viewModel.passedStops.lastOrNull()}"
            )
            FileLogger.d(tag,
                "MAP_STATE | no=$no | hasPassedFirstStop=${viewModel.hasPassedFirstStop} " +
                        "| nearestRouteIndex=${viewModel.nearestRouteIndex} " +
                        "| lat=${viewModel.latitude} lon=${viewModel.longitude} bearing=${viewModel.bearing}"
            )

            // Optional: sanity check nearest route point lookup
            if (viewModel.route.isNotEmpty() && viewModel.latitude != 0.0 && viewModel.longitude != 0.0) {
                val nearIdx = viewModel.findNearestBusRoutePoint(viewModel.latitude, viewModel.longitude)
                val nearPt = viewModel.route.getOrNull(nearIdx)
                FileLogger.d(tag,
                    "MAP_STATE | no=$no | findNearestBusRoutePoint() -> idx=$nearIdx point=$nearPt"
                )
            }

        } catch (e: Exception) {
            FileLogger.e(tag, "dumpMapVectors failed: ${e.message}")
        }
    }

    /**
     * Enables immersive full-screen mode by hiding the navigation and status bars.
     */
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    /**
     * Mark a bus stop as "arrived" and prevent duplicate arrivals.
     */
    private var isManualMode = false
    @SuppressLint("LongLogTag")
    private fun confirmArrival() {
        Log.d("MapActivity confirmArrival", "🚨 ConfirmArrival Triggered - Starting Process")

        viewModel.selectedRouteData?.startingPoint?.let { sp ->
            BusStop(latitude = sp.latitude, longitude = sp.longitude, address = sp.address)
        }

        if (viewModel.stops.isEmpty() || viewModel.route.isEmpty()) {
            Log.e("MapActivity confirmArrival", "❌ No stops or route data available.")
            return
        }

        Log.d("MapActivity confirmArrival", "🔎 Starting nearest route point search...")

        // 1) find nearest route‑point index
        val nearestIndex = viewModel.findNearestBusRoutePoint(viewModel.latitude, viewModel.longitude)
        Log.d("MapActivity confirmArrival", "✅ Nearest Route Point Found at Index: $nearestIndex")

        // 2) mark every stop up to that point as passed
        var snappedStop: BusStop? = null
        for (stop in viewModel.stops) {
            val routeIdx = viewModel.route.indexOfFirst {
                it.latitude == stop.latitude && it.longitude == stop.longitude
            }
            if (routeIdx != -1 && routeIdx <= nearestIndex) {
                if (!viewModel.passedStops.contains(stop)) viewModel.passedStops.add(stop)
                snappedStop = stop
            } else break
        }

        // 3) clear & redraw all detection zones _once_
        mapController.drawDetectionZones(viewModel.stops, viewModel.passedStops, viewModel.stopStatusByKey)

        // 4) snap UI to the snappedStop (if any)
        snappedStop?.let { stop ->
            viewModel.latitude  = stop.latitude!!
            viewModel.longitude = stop.longitude!!
            viewModel.stopAddress = viewModel.findAddressByCoordinates(viewModel.latitude, viewModel.longitude)
                ?: stop.address.orEmpty()
            upcomingBusStopTextView.text = viewModel.stopAddress
            viewModel.hasPassedFirstStop = true
        }

        // 5) continue with API‑time, schedule status updates, live GPS restart…
        // 🔹 Ensure schedule status updates correctly
        Log.d("MapActivity confirmArrival", "🔄 Updating API Time...")
        val busStopIndex = getBusStopIndex(viewModel.latitude, viewModel.longitude, viewModel.stops)
        viewModel.currentStopIndex = maxOf(0, getBusStopIndex(viewModel.latitude, viewModel.longitude, viewModel.stops))

        val totalDurationUntilArrive =
            getTotalDurationUpToIndex(busStopIndex, viewModel.durationBetweenStops)

        val firstSchedule = viewModel.scheduleList.first()
        val startTimeParts = firstSchedule.startTime.split(":")
        if (startTimeParts.size != 2) return

        val startCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startTimeParts[0].toInt())
            set(Calendar.MINUTE, startTimeParts[1].toInt())
            set(Calendar.SECOND, 0)
        }

        Log.d("MapActivity updateApiTime", "Total duration in minutes: $totalDurationUntilArrive")

        // Add the duration (in seconds) to the start time.
        val additionalSeconds = (totalDurationUntilArrive * 60).toInt()
        startCalendar.add(Calendar.SECOND, additionalSeconds)

        val timeFormat = SimpleDateFormat("HH:mm:ss", getDefault())
        viewModel.lockedApiTime = timeFormat.format(startCalendar.time)
        apiTimeValueTextView.text = viewModel.lockedApiTime
        updateApiTime()

        Log.d("MapActivity confirmArrival", "🔄 Initializing Timing Point...")
        timingPointValueTextView.text = viewModel.initializeTimingPoint()

        viewModel.apiTimeLocked = false    // Unlock the API time to allow updates
        Log.d("MapActivity confirmArrival", "🔓 API Time Unlocked")

        scheduleStatusManager.checkScheduleStatus()   // Immediately refresh the schedule status
        Log.d("MapActivity confirmArrival", "✅ Schedule Status Checked")

        Log.d("MapActivity confirmArrival", "✅ Arrival confirmed at: ${viewModel.stopAddress}")

        startLocationUpdate()   // Continue marker updates
        Log.d("MapActivity confirmArrival", "✅ Tracking resumed after arrival confirmation.")

        Toast.makeText(this, "✅ Tracking resumed after arrival confirmation.", Toast.LENGTH_SHORT).show()
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
     * Call this function when the simulation finishes.
     */
    private fun showSummaryDialog() {
        if (tripEndDialog?.isShowing ?: false) {
            return
        }

        // Flatten scheduleData into a List<ScheduleItem>
        val flatSchedule = (viewModel.scheduleData as? List<Any> ?: emptyList()).flatMap { element ->
            when (element) {
                is ScheduleItem -> listOf(element)
                is List<*> -> element.filterIsInstance<ScheduleItem>()
                else -> emptyList()
            }
        }
        val messageText = if (flatSchedule.size < 2) {
            "You have completed last run of the day."
        } else {
            val nextTrip: ScheduleItem =
                flatSchedule.getOrNull(1)
                    ?: flatSchedule.firstOrNull()
                    ?: run {
                        // defensive fallback (shouldn't happen if list isn't empty)
                        return
                    }

            val nextStartMinutes = nextTrip.startTime.convertTimeToMinutes()
            val currentTime = Calendar.getInstance().apply {
                timeInMillis = getDisplayNowMillis()
            }
            val currentMinutes = currentTime.get(Calendar.HOUR_OF_DAY) * 60 +
                    currentTime.get(Calendar.MINUTE)
            val restTotalMinutes = if (nextStartMinutes > currentMinutes) nextStartMinutes - currentMinutes else 0
            val restHours = restTotalMinutes / 60
            val restMinutes = restTotalMinutes % 60
            "Trip complete! You have $restHours hour(s) and $restMinutes minute(s) rest before your next trip, which starts at ${nextTrip.startTime}:00."
        }
        // late notification: You are $restMinutes minute(s) for your next trip. Please notify operations of late departure.
        // break (lunch break):  You are $restMinutes minute(s) for your break time.
        // end of shift: You have completed last run of the day.
        tripEndDialog = AlertDialog.Builder(this)
            .setTitle("Trip Completed")
            .setMessage(messageText)
            .setPositiveButton("View Next Trip") { dialog, _ ->
                dialog.dismiss()
                finishToScheduleWithRemainingTrips()
            }
            .create()
        tripEndDialog?.show()

        // Customize the dialog's background and text colors
        tripEndDialog?.window?.setBackgroundDrawableResource(R.color.colorMain)
        tripEndDialog?.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(
            ResourcesCompat.getColor(resources, R.color.white, null)
        )
        tripEndDialog?.findViewById<TextView>(android.R.id.message)?.setTextColor(
            ResourcesCompat.getColor(resources, R.color.white, null)
        )
        // Style the positive button similar to your simulation button
        tripEndDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.purple_400, null))
            setTextColor(ResourcesCompat.getColor(resources, R.color.white, null))
        }
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
        if (viewModel.apiTimeLocked && viewModel.lockedApiTime != null) {
            viewModel.loadLockedApiTime()?.let {
                apiTimeValueTextView.text = it
            }
            return
        }

        if (viewModel.busRouteData.isEmpty() || viewModel.scheduleList.isEmpty()) return

        val firstSchedule = viewModel.scheduleList.first()
        val startTimeParts = firstSchedule.startTime.split(":")
        if (startTimeParts.size != 2) return

        val startCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startTimeParts[0].toInt())
            set(Calendar.MINUTE, startTimeParts[1].toInt())
            set(Calendar.SECOND, 0)
        }

        // Build timing list.
        val baseRoute = viewModel.selectedRouteData ?: viewModel.busRouteData.firstOrNull()
        val timingList = baseRoute?.let { BusStopWithTimingPoint.fromRouteData(it) } ?: emptyList()

        val upcomingAddress = viewModel.stopAddress
        if (upcomingAddress.isBlank() || upcomingAddress == "Unknown") {
            return
        }

        // Find the target index.
        val targetIndex = timingList.indexOfFirst { it.address?.equals(upcomingAddress, true) == true }
        if (targetIndex == -1) {
            // Try to find by partial match or use current stop index
            val fallbackIndex = if (viewModel.currentStopIndex < timingList.size) viewModel.currentStopIndex else 0
            if (fallbackIndex >= 0 && fallbackIndex < timingList.size) {
                val totalDurationMinutes = calculateDurationForUpdate(timingList, viewModel.scheduleList, fallbackIndex)
                if (totalDurationMinutes != null) {
                    val additionalSeconds = (totalDurationMinutes * 60).toInt()
                    startCalendar.add(Calendar.SECOND, additionalSeconds)
                    val timeFormat = SimpleDateFormat("HH:mm:ss", getDefault())
                    val updatedApiTime = timeFormat.format(startCalendar.time)
                    // Already on main thread
                    apiTimeValueTextView.text = updatedApiTime
                }
            }
            return
        }

        // Compute the total duration.
        val totalDurationMinutes = calculateDurationForUpdate(timingList, viewModel.scheduleList, targetIndex)
        if (totalDurationMinutes == null) {
            // Try to update with current stop index if available
            if (viewModel.currentStopIndex >= 0 && viewModel.currentStopIndex < timingList.size) {
                val fallbackDuration = calculateDurationForUpdate(timingList, viewModel.scheduleList, viewModel.currentStopIndex)
                if (fallbackDuration != null) {
                    val additionalSeconds = (fallbackDuration * 60).toInt()
                    startCalendar.add(Calendar.SECOND, additionalSeconds)
                    val timeFormat = SimpleDateFormat("HH:mm:ss", getDefault())
                    val updatedApiTime = timeFormat.format(startCalendar.time)
                    // Already on main thread
                    apiTimeValueTextView.text = updatedApiTime
                }
            }
            return
        }

        // Add the duration (in seconds) to the start time.
        val additionalSeconds = (totalDurationMinutes * 60).toInt()
        startCalendar.add(Calendar.SECOND, additionalSeconds)

        val timeFormat = SimpleDateFormat("HH:mm:ss", getDefault())
        val updatedApiTime = timeFormat.format(startCalendar.time)

        // Always update API time (removed condition that was preventing updates)
        // Already on main thread from updateUIElements()
        apiTimeValueTextView.text = updatedApiTime

        // If the upcoming stop is the final scheduled stop, lock the API time.
        val lastScheduledAddress = getLastScheduledAddress(timingList, viewModel.scheduleList)
        if (lastScheduledAddress != null &&
            upcomingAddress.equals(lastScheduledAddress, ignoreCase = true)) {
            viewModel.apiTimeLocked = true
            viewModel.lockedApiTime = updatedApiTime
            Log.d("MapActivity", "✅ API time locked at final stop: $updatedApiTime")
        }
    }

    /** Updates timing point based on current bus location */
    @SuppressLint("LongLogTag")
    private fun updateTimingPointBasedOnLocation() {
        val currentLat = viewModel.latitude
        val currentLon = viewModel.longitude

        if (viewModel.scheduleList.isEmpty()) return

        val firstSchedule = viewModel.scheduleList.first()
        val stopList = firstSchedule.busStops

        if (stopList.isEmpty()) {
            timingPointValueTextView.text = firstSchedule.endTime
            return
        }

        // Ensure the first timing point is set
        if (viewModel.upcomingStop == "Unknown") {
            viewModel.upcomingStop = stopList.first().time
            timingPointValueTextView.text = viewModel.upcomingStop
            Log.d("MapActivity", "🔹 Initial timing point set to: ${viewModel.upcomingStop}")
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

                val nextTimingPoint = if (index + 1 < stopList.size) {
                    stopList[index + 1].time + ":00"
                } else {
                    firstSchedule.endTime + ":00" // Last stop reached
                }
                viewModel.nextTimingPoint.postValue(nextTimingPoint)
                Log.d("MapActivity updateTimingPointBasedOnLocation", "🔹 Next timing point: $nextTimingPoint")
                return
            }
        }
    }

    /**
     * Finds and logs the nearest bus stop that has been passed.
     * After passing a stop, it moves to the next stop in the list.
     */
    @SuppressLint("LongLogTag")
    private fun checkPassedStops() {
        val currentLat = viewModel.latitude
        val currentLon = viewModel.longitude

        if (viewModel.stops.isEmpty()) {
            return
        }

        // Don't check if the coordinate is not ready yet
        if (currentLat == 0.0 && currentLon == 0.0) {
            return
        }

        // Figure out where you are on the polyline
        val nearestRouteIdx = viewModel.findNearestBusRoutePoint(currentLat, currentLon)

        // Auto-pass any stops whose route-index ≤ your position
        // before loop
        val beforeSize = viewModel.passedStops.size

// Auto-pass any stops whose route-index ≤ your position
        viewModel.stops.forEach { stop ->
            val idx = viewModel.route.indexOfFirst {
                it.latitude == stop.latitude && it.longitude == stop.longitude
            }
            if (idx != -1 && idx <= nearestRouteIdx && !viewModel.passedStops.contains(stop)) {
                viewModel.passedStops.add(stop)
            }
        }

        val afterSize = viewModel.passedStops.size
        val newlyPassed = if (afterSize > beforeSize) {
            viewModel.passedStops.takeLast(afterSize - beforeSize)
        } else emptyList()

        if (newlyPassed.isNotEmpty()) {
            // Make sure schedule status is refreshed so lastCategory is accurate “now”
            scheduleStatusManager.checkScheduleStatus()

            applyStatusForNewlyPassedStops(newlyPassed)

            // Redraw detection zones using status colors
            mapController.drawDetectionZones(
                viewModel.stops,
                viewModel.passedStops,
                viewModel.stopStatusByKey
            )

            Log.d("MapActivity", "✅ Auto-passed ${newlyPassed.size} stop(s); last=${newlyPassed.lastOrNull()?.address}")
        }

        // recompute currentStopIndex from passedStops
        viewModel.currentStopIndex = viewModel.stops.indexOfFirst { it !in viewModel.passedStops }
            .takeIf { it >= 0 } ?: viewModel.stops.size

        if (viewModel.currentStopIndex >= viewModel.stops.size) {
            val routeName = viewModel.scheduleList.firstOrNull()?.runName ?: "Unknown"
            LifecycleLogger.logTripComplete(routeName, "AllStopsPassed")

            // update UI to "end of route" and fire the summary dialog:
            upcomingBusStopTextView.text = "End of Route"
            if (!viewModel.hasShownFinalStopMessage) {
                Toast.makeText(this@MapActivity, "✅ You have reached the final stop.", Toast.LENGTH_SHORT).show()
                viewModel.hasShownFinalStopMessage = true
            }
            showSummaryDialog()
            return
        }

        val nextStop = viewModel.stops[viewModel.currentStopIndex]
        val stopLat = nextStop.latitude ?: return
        val stopLon = nextStop.longitude ?: return
        viewModel.stopAddress = nextStop.address ?: viewModel.getUpcomingBusStopName(stopLat, stopLon)
        viewModel.upcomingStop = viewModel.stopAddress
        // Check if the stop is a timing point
        val isTimingPoint = viewModel.redBusStops.contains(viewModel.stopAddress)

        // Only update API time if this timing point stop is different than last one
        if (isTimingPoint && viewModel.stopAddress != viewModel.lastTimingPointStopAddress) {
            // ✅Only log timing point changes (not every check)
            Log.d("MapActivity", "⏱️ Timing point: ${viewModel.stopAddress}")
            viewModel.lastTimingPointStopAddress = viewModel.stopAddress
            updateApiTime()
        }

        val distance = calculateDistance(currentLat, currentLon, stopLat, stopLon)

        if (distance <= BUS_STOP_RADIUS) {
            // Update UI directly (already on main thread from updateUIElements)
            upcomingBusStopTextView.text = viewModel.stopAddress
            viewModel.upcomingStop = viewModel.stopAddress

            // Track the first stop being passed a second time in circular routes
            if (viewModel.isCircularRoute && nextStop == viewModel.stops.first()) {
                if (viewModel.hasPassedFirstStopAgain) {
                    // Trip ends after second pass of the first stop
                    viewModel.upcomingStop = "End of Route"
                    upcomingBusStopTextView.text = "End of Route"
                    // Only show final stop message once
                    if (!viewModel.hasShownFinalStopMessage) {
                        Toast.makeText(this@MapActivity, "✅ You have reached the final stop.", Toast.LENGTH_SHORT).show()
                        viewModel.hasShownFinalStopMessage = true
                    }
                    showSummaryDialog()
                    return
                } else {
                    viewModel.hasPassedFirstStopAgain = true
                    Log.d("MapActivity", "🔄 Passed first stop a second time, preparing to end the trip.")
                }
            }

            // 🔹 Check if the bus is inside the detection area
            if (viewModel.isBusInDetectionArea(currentLat, currentLon, stopLat, stopLon)) {
                // Update UI directly (already on main thread)
                upcomingBusStopTextView.text = viewModel.stopAddress
                viewModel.upcomingStop = viewModel.stopAddress
                FileLogger.d(
                    "MapActivity checkPassedStops",
                    "✅ Arrived at: ${viewModel.stopAddress}"
                )

                // Log detailed bus stop pass with ETA data
                viewModel.logBusStopPass(currentLat, currentLon)

                val beforeSize2 = viewModel.passedStops.size

                if (!viewModel.passedStops.contains(nextStop)) {
                    viewModel.passedStops.add(nextStop)
                }
                viewModel.currentStopIndex++

                val afterSize2 = viewModel.passedStops.size
                val newlyPassed2 = if (afterSize2 > beforeSize2) {
                    viewModel.passedStops.takeLast(afterSize2 - beforeSize2)
                } else emptyList()

                if (newlyPassed2.isNotEmpty()) {
                    scheduleStatusManager.checkScheduleStatus()
                    applyStatusForNewlyPassedStops(newlyPassed2)

                    mapController.drawDetectionZones(
                        viewModel.stops,
                        viewModel.passedStops,
                        viewModel.stopStatusByKey
                    )
                }

                // Ensure the next stop is updated correctly even if a stop is skipped
                while (viewModel.currentStopIndex < viewModel.stops.size && calculateDistance(
                        currentLat, currentLon,
                        viewModel.stops[viewModel.currentStopIndex].latitude ?: 0.0,
                        viewModel.stops[viewModel.currentStopIndex].longitude ?: 0.0
                    ) <= BUS_STOP_RADIUS
                ) {
                    viewModel.passedStops.add(viewModel.stops[viewModel.currentStopIndex])
                    viewModel.currentStopIndex++
                }

                // Automatically detect if this was the final stop
                if (viewModel.currentStopIndex >= viewModel.stops.size) {
                    viewModel.upcomingStop = "End of Route"
                    upcomingBusStopTextView.text = "End of Route"
                    // Only show final stop message once
                    if (!viewModel.hasShownFinalStopMessage) {
                        Toast.makeText(this@MapActivity, "✅ You have reached the final stop.", Toast.LENGTH_SHORT).show()
                        viewModel.hasShownFinalStopMessage = true
                    }

                    // Trigger trip completion dialog
                    showSummaryDialog()
                }
            } else {
                val upcomingStop = viewModel.stops[viewModel.currentStopIndex]
                val upcomingStopName = viewModel.getUpcomingBusStopName(upcomingStop.latitude ?: 0.0, upcomingStop.longitude ?: 0.0)
                upcomingBusStopTextView.text = upcomingStopName
            }

            // Build timing list and update API time only if the stop exists in it
            val baseRoute = viewModel.selectedRouteData ?: viewModel.busRouteData.firstOrNull()
            val timingList = baseRoute?.let { BusStopWithTimingPoint.fromRouteData(it) } ?: emptyList()
            if (timingList.any { it.address?.equals(viewModel.stopAddress, ignoreCase = true) == true }) {
                updateApiTime()
            }

            if (viewModel.currentStopIndex < viewModel.stops.size) {
                val upcomingStop = viewModel.stops[viewModel.currentStopIndex]
                viewModel.upcomingStopName.postValue(
                    viewModel.getUpcomingBusStopName(upcomingStop.latitude ?: 0.0, upcomingStop.longitude ?: 0.0)
                )
            }
        } else {
            viewModel.upcomingStopName.postValue(viewModel.getUpcomingBusStopName(stopLat, stopLon))
            viewModel.upcomingStop = viewModel.upcomingStopName.value ?: "Unknown"
        }
    }

    /** Stops the simulation and resets the state. */
    private fun resetSimulationState() {
        // Reset simulation variables.
        viewModel.simulationSpeedFactor = 1

        // Reset any other state you maintain.
        viewModel.upcomingStop = if (viewModel.scheduleList.isNotEmpty() && viewModel.scheduleList.first().busStops.isNotEmpty())
            viewModel.scheduleList.first().busStops.first().time + ":00" else "Unknown"
        timingPointValueTextView.text = viewModel.upcomingStop

        // If you keep track of passed stops, clear them.
        viewModel.passedStops.clear()
        viewModel.currentStopIndex = 0

        // (Optional) If you want to re-draw the polyline, remove it and then call drawPolyline() again.
        mapController.clearPolyline()
        // If needed, you can re-add the polyline:
        if (viewModel.route.isNotEmpty()) {
            mapController.drawPolyline(viewModel.route)
        }
    }

    /**
     * Starts live location updates using the device's GPS instead of simulation.
     * Updates latitude, longitude, bearing, speed, and UI elements accordingly.
     */
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    @SuppressLint("MissingPermission", "LongLogTag")
    protected open fun startLocationUpdate() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Reduced location update frequency to save battery and improve performance
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000 // 2-second updates to reduce CPU/GPU load
        )
            .setWaitForAccurateLocation(false) // Don't wait for accurate location to prevent blocking
            .setMinUpdateIntervalMillis(1000) // Fastest update in 1 second
            .setMaxUpdateDelayMillis(3000) // Maximum wait time 3 seconds
            .build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // Failsafe to prevent location update when this activity is closed
                if (this@MapActivity.isDestroyed) {
                    return
                }

                locationResult.lastLocation?.let { location ->

                    if (!isManualMode) {
                        viewModel.latitude = location.latitude
                        viewModel.longitude = location.longitude
                        viewModel.updateSpeed(location.speed * 3.6f)
                        viewModel.bearing = location.bearing
                    }

                    // Find the nearest route point
                    val nearestIndex = viewModel.findNearestBusRoutePoint(viewModel.latitude, viewModel.longitude)

                    // Handle First Bus Stop Rule
                    if (!viewModel.hasPassedFirstStop) {
                        if (nearestIndex == 0) {
                            viewModel.hasPassedFirstStop = true // Mark the first bus stop as passed
                            viewModel.nearestRouteIndex = 0
                            Log.d("MapActivity", "✅ First bus stop passed. Rules activated.")
                        } else {
                            Log.d("MapActivity", "⚠️ Waiting for first bus stop to be passed.")
                            // Use live GPS data until the first stop is passed
                            // Always update marker position in real-time
                            runOnUiThread {
                                try {
                                    // Always update marker position first (real-time)
                                    mapController.updateBusMarkerPosition(viewModel.latitude, viewModel.longitude, viewModel.bearing)

                                    // Then update UI with throttling
                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastLocationUpdateTime >= locationUpdateThrottleMs) {
                                        lastLocationUpdateTime = currentTime
                                        updateUIElementsThrottled()
                                    }
                                } catch (e: Exception) {
                                    Log.e("MapActivity", "Error updating before first stop: ${e.message}", e)
                                }
                            }
                            return
                        }
                    }

                    // Allow small backward movement (within 3 points) to handle GPS drift
                    if (nearestIndex < viewModel.nearestRouteIndex - 3) {
                        Log.d("MapActivity", "⚠️ Ignoring large backward movement: $nearestIndex < ${viewModel.nearestRouteIndex - 3}")
                        // Only update marker, not full UI if movement is ignored
                        runOnUiThread {
                            mapController.updateBusMarkerPosition(viewModel.latitude, viewModel.longitude, viewModel.bearing)
                        }
                        return
                    }

                    // Update nearestRouteIndex if we've moved significantly
                    val distanceFromLast = calculateDistance(
                        viewModel.lastValidLatitude, viewModel.lastValidLongitude,
                        viewModel.latitude, viewModel.longitude
                    )
                    if (distanceFromLast > 10.0) { // Update if moved more than 10 meters
                        viewModel.nearestRouteIndex = nearestIndex
                    }

                    // Allow gradual forward movement (don't block if within reasonable range)
                    if (nearestIndex > viewModel.nearestRouteIndex + viewModel.jumpThreshold * 2) {
                        Log.d("MapActivity", "⚠️ Ignoring sudden jump: $nearestIndex > ${viewModel.nearestRouteIndex + viewModel.jumpThreshold * 2}")
                        // Only update marker, not full UI if movement is ignored
                        runOnUiThread {
                            mapController.updateBusMarkerPosition(viewModel.latitude, viewModel.longitude, viewModel.bearing)
                        }
                        return
                    }

                    // Update position - use route point if we're close enough, otherwise use GPS
                    val distanceToRoute = if (nearestIndex < viewModel.route.size) {
                        val routePoint = viewModel.route[nearestIndex]
                        calculateDistance(
                            viewModel.latitude, viewModel.longitude,
                            routePoint.latitude ?: 0.0,
                            routePoint.longitude ?: 0.0
                        )
                    } else {
                        Double.MAX_VALUE
                    }

                    // If GPS is close to route, snap to route; otherwise use GPS directly
                    if (distanceToRoute < 50.0 && nearestIndex < viewModel.route.size) {
                        val nearestRoutePoint = viewModel.route[nearestIndex]
                        viewModel.latitude = nearestRoutePoint.latitude ?: viewModel.latitude
                        viewModel.longitude = nearestRoutePoint.longitude ?: viewModel.longitude
                        viewModel.nearestRouteIndex = nearestIndex
                    } else {
                        // Use GPS directly if far from route
                        viewModel.nearestRouteIndex = nearestIndex
                    }

                    viewModel.lastValidLatitude = viewModel.latitude
                    viewModel.lastValidLongitude = viewModel.longitude

                    // Calculate bearing toward next index
                    val nextIndex = if (viewModel.nearestRouteIndex < viewModel.route.size - 1) viewModel.nearestRouteIndex + 1 else viewModel.nearestRouteIndex
                    if (nextIndex < viewModel.route.size) {
                        val targetPoint = viewModel.route[nextIndex]
                        viewModel.bearing = calculateBearing(
                            viewModel.latitude, viewModel.longitude,
                            targetPoint.latitude ?: 0.0,
                            targetPoint.longitude ?: 0.0
                        )
                    }

                    // Always update marker position in real-time (no throttling)
                    // Marker must move smoothly, but UI updates can be throttled
                    runOnUiThread {
                        try {
                            // Always update marker position first (real-time, no throttling)
                            mapController.updateBusMarkerPosition(viewModel.latitude, viewModel.longitude, viewModel.bearing)

                            // Then update UI elements with throttling
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastLocationUpdateTime >= locationUpdateThrottleMs) {
                                lastLocationUpdateTime = currentTime
                                updateUIElementsThrottled()
                                LastLocationStore.save(this@MapActivity, viewModel.latitude, viewModel.longitude)
                            }
                        } catch (e: Exception) {
                            Log.e("MapActivity", "Error in location update: ${e.message}", e)
                        }
                    }
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
     * Only updates UI if enough time has passed since last update
     * NOTE: Marker position is updated separately in real-time, not here
     */
    protected fun updateUIElementsThrottled() {
        try {
            // Ensure we're on the main thread
            if (Looper.myLooper() != Looper.getMainLooper()) {
                runOnUiThread { updateUIElementsThrottled() }
                return
            }

            val currentTime = System.currentTimeMillis()

            // Throttle UI updates to prevent excessive rendering
            if (currentTime - lastUIUpdateTime < uiUpdateThrottleMs) {
                return // Skip this update, too soon since last update
            }
            lastUIUpdateTime = currentTime

            // Update speed (only if changed)
            if (::speedTextView.isInitialized) {
                viewModel.updateSpeedText()
            }

            // NOTE: Marker position is NOT updated here - it's updated separately in real-time
            // to ensure smooth movement without throttling

            // Update stop detection (this updates upcomingBusStopTextView)
            checkPassedStops()

            // Update timing point
            updateTimingPointBasedOnLocation()

            // Update schedule status (this will update scheduleStatusValueTextView and icon)
            scheduleStatusManager.checkScheduleStatus()
            maybeShowNextRunSafetyWarning()

            // Update API time
            updateApiTime()

            // Explicitly update currentTimeTextView from TimeManager (only if changed)
            if (::currentTimeTextView.isInitialized) {
                viewModel.updateCurrentTimeText(getDisplayNowMillis())
            }

            // Explicitly update nextTripCountdownTextView (only if changed)
            if (::nextTripCountdownTextView.isInitialized) {
                try {
                    val nextTripStartTime = viewModel.scheduleData.getNextScheduleStartTime()
                    viewModel.updateNextTripText(nextTripStartTime)
                } catch (e: Exception) {
                    Log.e("MapActivity", "Error updating nextTripCountdownTextView: ${e.message}", e)
                }
            }

            // Ensure trip end time is set (only if changed)
            if (viewModel.scheduleList.isNotEmpty() && ::tripEndTimeTextView.isInitialized) {
                viewModel.updateTripEndTimeText()
            }

            // Always invalidate map in UI updates to prevent white screen
            // Marker updates already handle real-time invalidate, but we need to ensure
            // map is also invalidated during UI updates to keep it visible
            // Reduced debouncing to ensure map stays visible
            if (currentTime - lastMapInvalidateTime >= mapInvalidateThrottleMs) {
                binding.map.invalidate()
                lastMapInvalidateTime = currentTime
            } else {
                // Even if debounced, ensure map is invalidated at least every 2 seconds
                // This prevents white screen if marker updates are also throttled
                val timeSinceLastInvalidate = currentTime - lastMapInvalidateTime
                if (timeSinceLastInvalidate >= 2000L) {
                    binding.map.invalidate()
                    lastMapInvalidateTime = currentTime
                }
            }

            // Use LifecycleLogger for location updates (throttled to every 10 seconds)
            val upcomingStopName = upcomingBusStopTextView.text?.toString() ?: "Unknown"
            val currentStopName = viewModel.stops.getOrNull(viewModel.currentStopIndex)?.address ?: "Unknown"
            val etaText = apiTimeValueTextView.text?.toString()
            val statusText = scheduleStatusValueTextView.text?.toString() ?: "Unknown"
            LifecycleLogger.logLocationUpdate(
                viewModel.latitude, viewModel.longitude, viewModel.speed,
                upcomingStopName, currentStopName, etaText, statusText
            )

        } catch (e: Exception) {
            Log.e("MapActivity", "Error updating UI elements: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun maybeShowNextRunSafetyWarning() {
        val nextTrip = viewModel.scheduleData.firstOrNull() ?: return
        val nextStartStr = (nextTrip.startTime + ":00")
        val nextStart = nextStartStr.parseTimeToday()

        val deltaSec = ((nextStart.time - getDisplayNowMillis()) / 1000L).toInt()

        // Warn if already late, or if it's imminent and we're in "10+ min late" state
        val shouldWarn = (deltaSec < -60) ||
                (deltaSec in 0..(5 * 60) && scheduleStatusManager.lastCategory == ScheduleStatusManager.StopPassCategory.LATE_10)

        if (!shouldWarn) return

        val now = System.currentTimeMillis()
        if (now - lastNextRunSafetyToastAt < nextRunSafetyToastCooldownMs) return

        lastNextRunSafetyToastAt = now
        Toast.makeText(
            this,
            "⚠️ You may start the next run late. Stay calm — safety first. You can recover time at timing points.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun applyStatusForNewlyPassedStops(newlyPassed: List<BusStop>) {
        newlyPassed.forEach { stop ->
            val key = viewModel.stopKey(stop.latitude, stop.longitude)
            val isTiming = viewModel.isTimingPointStop(stop)

            if (!isTiming) {
                // Non-timing stops become orange pending until next timing point decides them
                viewModel.stopStatusByKey[key] = ScheduleStatusManager.StopVisualStatus(
                    category = ScheduleStatusManager.StopPassCategory.PENDING,
                    isTimingPoint = false
                )
                return@forEach
            }

            // Timing point: use the most recent schedule status result
            val cat = scheduleStatusManager.lastCategory
            viewModel.stopStatusByKey[key] =
                ScheduleStatusManager.StopVisualStatus(category = cat, isTimingPoint = true)

            // Resolve all previous pending stops since last timing point
            resolvePendingStopsUsingTimingPointResult(
                timingPointIndex = viewModel.currentStopIndex,
                timingPointCategory = cat
            )

            viewModel.lastTimingPointPassedIndex = viewModel.currentStopIndex
        }
    }

    private fun resolvePendingStopsUsingTimingPointResult(
        timingPointIndex: Int,
        timingPointCategory: ScheduleStatusManager.StopPassCategory
    ) {
        // Only two outcomes for NON-timing stops:
        // - green if the timing point is NOT early (driver waited/slowed)
        // - red if the timing point is early (driver didn't wait)
        val resolvedCategory =
            if (timingPointCategory == ScheduleStatusManager.StopPassCategory.EARLY)
                ScheduleStatusManager.StopPassCategory.EARLY
            else
                ScheduleStatusManager.StopPassCategory.ON_TIME

        val start = (viewModel.lastTimingPointPassedIndex + 1).coerceAtLeast(0)
        val endExclusive = timingPointIndex.coerceAtMost(viewModel.stops.size)

        for (i in start until endExclusive) {
            val s = viewModel.stops[i]
            val key = viewModel.stopKey(s.latitude, s.longitude)
            val existing = viewModel.stopStatusByKey[key]

            if (existing?.category == ScheduleStatusManager.StopPassCategory.PENDING) {
                viewModel.stopStatusByKey[key] = existing.copy(category = resolvedCategory)
            }
        }
    }

    /**
     * Start periodic UI update with increased interval to reduce CPU load
     */
    private fun startPeriodicUIUpdate() {
        stopPeriodicUIUpdate()
        periodicUIUpdateHandler = Handler(Looper.getMainLooper())
        periodicUIUpdateRunnable = object : Runnable {
            override fun run() {
                try {
                    // Update UI every 3 seconds
                    // This reduces CPU/GPU load while still keeping UI fresh
                    updateUIElementsThrottled()
                    periodicUIUpdateHandler?.postDelayed(this, 3000)
                } catch (e: Exception) {
                    Log.e("MapActivity", "Error in periodic UI update: ${e.message}", e)
                }
            }
        }
        periodicUIUpdateHandler?.post(periodicUIUpdateRunnable!!)
    }

    /**
     * Stop periodic UI update
     */
    private fun stopPeriodicUIUpdate() {
        periodicUIUpdateHandler?.removeCallbacksAndMessages(null)
        periodicUIUpdateRunnable = null
    }

    /**
     * Listener function that is called whenever the bus marker is updated.
     * It checks whether the currently displayed upcoming bus stop (in the upcomingBusStopTextView)
     * has been passed by comparing the current bus position (latitude, longitude) with the bus stop’s coordinates.
     * If the stop is within the defined busStopRadius, then it automatically updates the upcoming stop to the next one.
     * For stops that are timing points (i.e. red bus stops) the API timing is also updated.
     */
    @SuppressLint("LongLogTag")
    fun onBusMarkerUpdated() {
        // Get the stop address currently shown in the upcoming bus stop text view.
        val currentDisplayedStop = upcomingBusStopTextView.text.toString()
        // Try to find this stop in the stops list.
        val currentStopIndexFromDisplay = viewModel.stops.indexOfFirst {
            it.address?.equals(currentDisplayedStop, ignoreCase = true) == true
        }
        if (currentStopIndexFromDisplay != -1) {
            val currentStop = viewModel.stops[currentStopIndexFromDisplay]
            // Compute the distance between the bus marker position and this bus stop.
            val distanceToStop = calculateDistance(
                viewModel.latitude, viewModel.longitude,
                currentStop.latitude ?: 0.0,
                currentStop.longitude ?: 0.0
            )

            // If the bus is close enough—i.e. the stop is considered "passed"
            if (distanceToStop <= BUS_STOP_RADIUS) {
                // Only update if there is a next stop available.
                if (currentStopIndexFromDisplay + 1 < viewModel.stops.size) {
                    val nextStop = viewModel.stops[currentStopIndexFromDisplay + 1]
                    upcomingBusStopTextView.text = nextStop.address ?: "Unknown Stop"
                    viewModel.upcomingStop = nextStop.address ?: "Unknown Stop"
                    // ✅ OPTIMIZED: Only log when stop actually changes
                    Log.d("MapActivity", "✅ Stop passed, next: ${nextStop.address}")
                    // If the new upcoming stop has a timing point (red mark), update its API time.
                    if (viewModel.redBusStops.contains(nextStop.address)) {
                        updateApiTime()
                    }
                } else {
                    // End of route reached.
                    upcomingBusStopTextView.text = "End of Route"
                    viewModel.upcomingStop = "End of Route"
                    Log.d("MapActivity", "✅ Reached end of route")
                }
            }
        }
    }

    /**
     * Initialize UI components and assign them to the corresponding views.
     */
    private fun initializeUIComponents() {
        speedTextView = binding.speedTextView
        timingPointandStopsTextView = binding.timingPointandStopsTextView
        tripEndTimeTextView = binding.tripEndTimeTextView
        // Hardcoded values for testing
        if (viewModel.scheduleList.isNotEmpty()) {
            val scheduleItem = viewModel.scheduleList.first()

            // Extract stop names and times dynamically
            val stopsInfo = scheduleItem.busStops.joinToString(", ") { "${it.name} - ${it.time}" }

            // Set text views with extracted stop info
            timingPointandStopsTextView.text = stopsInfo
            tripEndTimeTextView.text = "${scheduleItem.endTime}:00"
        }
        timingPointValueTextView = binding.timingPointValueTextView
        apiTimeValueTextView = binding.ApiTimeValueTextView
        scheduleStatusValueTextView = binding.scheduleStatusValueTextView
        speedTextView = binding.speedTextView
        upcomingBusStopTextView = binding.upcomingBusStopTextView
        currentTimeTextView = binding.currentTimeTextView
        nextTripCountdownTextView = binding.nextTripCountdownTextView
        connectionStatusTextView   = binding.connectionStatusTextView
        networkStatusIndicator     = binding.networkStatusIndicator
    }

    /**
     * utility to convert dp → px
     */
    fun dpToPx(dp: Int): Int = TypedValue
        .applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() { /* no-op */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH -> true  // swallow Back & Recents
            else                         -> super.onKeyDown(keyCode, event)
        }
    }

    /** Cleans up resources on activity destruction. */
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onDestroy() {
        FileLogger.markAppClosed("MapActivity")
        super.onDestroy()
        panelController.panelDebugEnabled = false

        clearActiveSegmentAndRefresh()

        // Stop location updates
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        // Stop MQTT polling
        if (::mqttHelper.isInitialized) {
            mqttHelper.stopAttributePolling()
        }

        if (::mapController.isInitialized) {
            // Stop activity monitor
            mapController.stopActivityMonitor()

            mapController.currentBusMarker = null

            // Remove polyline from Mapsforge map
            mapController.clearPolyline()
            Log.d("MapActivity", "🗑️ Removed polyline on destroy.")
        }

        // Cleanup time manager
        timeManager.cleanup()

        // Stop periodic UI update
        stopPeriodicUIUpdate()

    }

    override fun onStop() {
        super.onStop()
        panelController.panelDebugEnabled = false
        if (::mqttHelper.isInitialized) mqttHelper.stopAttributePolling()
        // remove any observers/timers you set that could call logPanelDebugFromDetailPanel()
    }

    override fun onResume() {
        super.onResume()
        // Restart polling when activity resumes
        if (::mqttHelper.isInitialized && ::mqttManager.isInitialized) {
            mqttHelper.startAttributePolling()
        }
    }

    /**
     * Clear the shared currentTripLabel on the server and coerce other tablets to refresh.
     * Also refresh the local panel UI & panel log after a small delay to give the server time to process.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun clearActiveSegmentAndRefresh() {
        try {
            // 1) publish empty label to clear server-side client attribute
            try {
                publishActiveSegment("") // re-uses your existing publisher
            } catch (e: Exception) {
                Log.w("MapActivity", "publishActiveSegment(\"\") failed: ${e.message}")
            }

            // 2) ask ThingsBoard to re-broadcast and then force a poll/refresh
            try {
                if (::mqttHelper.isInitialized) {
                    // this triggers any admin broadcast side-effects you use
                    try { mqttHelper.requestAdminMessage() } catch (e: Exception) {
                        Log.w("MapActivity", "requestAdminMessage failed: ${e.message}")
                    }

                    // give the server a moment to process the publish, then refresh attributes
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            if (::mqttHelper.isInitialized) {
                                mqttHelper.refreshAllAttributes()
                            }
                        } catch (e: Exception) {
                            Log.w("MapActivity", "refreshAllAttributes failed: ${e.message}")
                        }

                        // Refresh local UI and log after the attribute refresh attempt
                        panelController.refreshPanelDetailWithLogging(binding.map.model.mapViewPosition.zoomLevel.toDouble())
                    }, 200L)
                    return
                }
            } catch (e: Exception) {
                Log.w("MapActivity", "mqttHelper interaction failed: ${e.message}")
            }

            // If mqttHelper not initialized or above failed, still refresh UI locally
            panelController.refreshPanelDetailWithLogging(binding.map.model.mapViewPosition.zoomLevel.toDouble())

        } catch (e: Exception) {
            Log.w("MapActivity", "clearActiveSegmentAndRefresh failed: ${e.message}")
        }
    }

    private fun publishActiveSegment(label: String) {
        val payload = "{\"currentTripLabel\":\"${label.replace("\"", "\\\"")}\"}"
        if (::mqttManager.isInitialized) {
            mqttManager.publish(MqttHelper.ATTR_TOPIC, payload)
        }

        // Ask server to re-publish shared data and force a quick attribute refresh
        // so other tablets observe the change promptly.
        try {
            // 1) request admin message (your app already supports requestAdminMessage)
            if (::mqttHelper.isInitialized) mqttHelper.requestAdminMessage()
            // 2) also trigger a direct attributes refresh (HTTP) after a short delay
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (::mqttHelper.isInitialized) mqttHelper.refreshAllAttributes()
                } catch (e: Exception) {
                    Log.w("MapActivity","refreshAllAttributes failed: ${e.message}")
                }
            }, 200L)
        } catch (e: Exception) {
            Log.w("MapActivity", "publishActiveSegment follow-up failed: ${e.message}")
        }
    }

    private fun finishToScheduleWithRemainingTrips() {
        val resultIntent = Intent().apply {
            putParcelableArrayListExtra("UPDATED_FULL_SCHEDULE_DATA", getRemainingScheduleFromIntent())
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun getRemainingScheduleFromIntent(): ArrayList<ScheduleItem> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA", ScheduleItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA")
        } ?: arrayListOf()
    }
}
