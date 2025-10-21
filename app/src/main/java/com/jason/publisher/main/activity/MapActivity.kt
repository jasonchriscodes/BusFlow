package com.jason.publisher.main.activity

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
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
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.autofill.AutofillManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import com.jason.publisher.main.model.BusItem
import com.jason.publisher.main.model.BusStop
import com.jason.publisher.main.model.BusStopInfo
import com.jason.publisher.main.model.BusStopWithTimingPoint
import com.jason.publisher.main.model.RouteData
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.services.LocationManager
import com.jason.publisher.main.utils.NetworkStatusHelper
import org.json.JSONArray
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
import com.google.gson.Gson
import com.jason.publisher.LocationListener
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.helpers.MapViewController
import com.jason.publisher.main.helpers.MqttHelper
import com.jason.publisher.main.helpers.ScheduleStatusManager
import com.jason.publisher.main.helpers.TimeManager
import com.jason.publisher.main.utils.Helper
import com.jason.publisher.main.model.AttributesData
import com.jason.publisher.main.services.ApiServiceBuilder
import com.jason.publisher.main.services.MqttManager
import com.jason.publisher.main.utils.FileLogger
import com.jason.publisher.main.utils.TimeBasedMovingAverageFilterDouble
import com.jason.publisher.main.utils.TripLog
import com.jason.publisher.services.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.map.model.MapViewPosition
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.lang.Math.abs
import org.mapsforge.map.model.common.Observer
import java.lang.Math.min

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var locationManager: LocationManager
    private lateinit var dateTimeHandler: Handler
//    private lateinit var dateTimeRunnable: Runnable

    var latitude = 0.0
    var longitude = 0.0
    private var lastLatitude = 0.0
    private var lastLongitude = 0.0
    var bearing = 0.0F
    var speed = 0.0F
    var direction = ""
    private var busConfig = ""
    private var busname = ""
    var aid = ""
    private var busDataCache = ""
    private var jsonString = ""
    var token = ""
    var config: List<BusItem>? = emptyList()
    var route: List<BusRoute> = emptyList()
    var stops: List<BusStop> = emptyList()
    private var busRouteData: List<RouteData> = emptyList()
    private var selectedRouteData: RouteData? = null
    var durationBetweenStops: List<Double> = emptyList()
    private var busStopInfo: List<BusStopInfo> = emptyList()
    var arrBusData: List<BusItem> = emptyList()
    private var firstTime = true
    private var upcomingStop: String = "Unknown"
    var stopAddress: String = "Unknown"
    var upcomingStopName: String = "Unknown"

    private lateinit var aidTextView: TextView
    private lateinit var latitudeTextView: TextView
    private lateinit var longitudeTextView: TextView
    private lateinit var bearingTextView: TextView
    private lateinit var speedTextView: TextView
    lateinit var upcomingBusStopTextView: TextView
    private lateinit var scheduleStatusIcon: ImageView
    private lateinit var scheduleStatusText: TextView
    private lateinit var timingPointandStopsTextView: TextView
    private lateinit var tripEndTimeTextView: TextView

    private var routePolyline: org.mapsforge.map.layer.overlay.Polyline? = null
    var busMarker: org.mapsforge.map.layer.overlay.Marker? = null
    var markerBus = HashMap<String, org.mapsforge.map.layer.overlay.Marker>()

    private lateinit var simulationHandler: Handler
    private lateinit var simulationRunnable: Runnable
    private var currentRouteIndex = 0
    private var isSimulating = false
    var simulationStartTime: Long = 0L
    lateinit var scheduleList: List<ScheduleItem>
    lateinit var scheduleData: List<ScheduleItem>
    val redBusStops = mutableSetOf<String>()

    private lateinit var actualTimeHandler: Handler
    private lateinit var actualTimeRunnable: Runnable
    private lateinit var actualTimeTextView: TextView
    private lateinit var timingPointValueTextView: TextView
    private lateinit var ApiTimeValueTextView: TextView
    lateinit var scheduleStatusValueTextView: TextView
    private lateinit var thresholdRangeValueTextView: TextView

    private var apiTimeLocked = false
    private var lockedApiTime: String? = null
    private var simulationSpeedFactor: Int = 1
    private lateinit var arriveButtonContainer: LinearLayout
    private var nextTimingPoint: String = "Unknown"
    lateinit var currentTimeTextView: TextView
    lateinit var nextTripCountdownTextView: TextView
    var busStopRadius: Double = 50.0
    val forceAheadStatus = false
    private var statusText  = "Please wait..."
    var baseTimeStr  = "00:00:00"
    var customTime  = "00:00:00"
    private var lastTimingPointStopAddress: String? = null
    // Class-level variable (initialize it to zero or a default simulation value)
    var smoothedSpeed: Float = 0f
    // Choose an alpha value between 0 and 1: smaller alpha means slower adjustment (more smoothing)
    private val smoothingAlpha = 0.2f
    var apiService = ApiServiceBuilder.buildService(ApiService::class.java)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // for other buses
    val lastSeen       = mutableMapOf<String, Long>()                  // you already have this
    val prevCoords     = mutableMapOf<String, Pair<Double, Double>>() // remember last lat/lon per token

    // for self
    private var prevOwnCoords: Pair<Double,Double>? = null
    private lateinit var mqttHelper: MqttHelper
    var tokenConfigData = "oRSsbeuqDMSckyckcMyE"
    val clientKeys       = "latitude,longitude,bearing,speed,direction"

    lateinit var mqttManagerConfig: MqttManager
    lateinit var mqttManager:        MqttManager
    lateinit var timeManager: TimeManager
    lateinit var mapController: MapViewController
    private lateinit var scheduleStatusManager: ScheduleStatusManager
    val otherBusLabels = mutableMapOf<String,String>()
    val connectivityManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private lateinit var connectionStatusTextView: TextView
    private lateinit var networkStatusIndicator: View
    private var hasDumpedPanelLog = false
    private var lastPanelDump: String? = null
    private var panelDebugEnabled = true
    private var upcomingStopAddress = "Unknown"
    private var upcomingTimingPoint = "Unknown"
    private var autoTapArrivalDone = false

    companion object {
        const val SERVER_URI = "tcp://43.226.218.97:1883"
        const val CLIENT_ID = "jasonAndroidClientId"
        const val PUB_POS_TOPIC = "v1/devices/me/telemetry"
        const val SUB_MSG_TOPIC = "v1/devices/me/attributes/response/+"
        const val PUB_MSG_TOPIC = "v1/devices/me/attributes/request/1"
        const val REQUEST_PERIODIC_TIME = 1000L
        private const val PUBLISH_POSITION_TIME = 1000L
        private const val LAST_MSG_KEY = "lastMessageKey"
        private const val MSG_KEY = "messageKey"
        private const val SOUND_FILE_NAME = "notif.wav"
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @SuppressLint("LongLogTag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FileLogger.d("MapActivity", "onCreate")

        autoTapArrivalDone = savedInstanceState?.getBoolean("autoTapArrivalDone") ?: false

        hideSystemUI()

        // Add logger
        FileLogger.init(this)
        FileLogger.markAppOpened("MapActivity")

        // Initialize managers before using them
        initializeManagers()

        // before creating the helper, set up the config‐fetching client:
        mqttManagerConfig = MqttManager(
            serverUri = SERVER_URI,
            clientId  = "$CLIENT_ID-config",
            username  = tokenConfigData
        )

        // ── ADD THIS: initialize mqttManager for offline use ──
        mqttManager = MqttManager(
            serverUri = SERVER_URI,
            clientId  = CLIENT_ID
        )

        // Initialize Managers before using it
        scheduleStatusManager = ScheduleStatusManager(this, binding)
        timeManager = TimeManager(this, scheduleStatusManager)
        mqttHelper = MqttHelper(this, binding)
        mapController  = MapViewController(this, binding, mqttHelper, binding.detailIconsContainer)

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
        val timelineLabels = intent.getStringArrayListExtra("TIMELINE_LABELS") ?: emptyList<String>()
        panelDebugNo = intent.getIntExtra("EXTRA_PANEL_DEBUG_NO", 0)

        Log.d("MapActivity onCreate retrieve", "Received aid: $aid")
        Log.d("MapActivity onCreate retrieve", "Received config: ${config.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received jsonString: $jsonString")
        Log.d("MapActivity onCreate retrieve", "Received route: ${route.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received stops: ${stops.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received durationBetweenStops: ${durationBetweenStops.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received busRouteData: ${busRouteData.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received scheduleList: ${scheduleList.toString()}")
        Log.d("MapActivity onCreate retrieve", "Received scheduleData: ${scheduleData.toString()}")
        Log.d("MapActivity onCreate retrieve", "▶ Received timelineLabels = $timelineLabels")

        FileLogger.d("MapActivity onCreate retrieve", "Received aid: $aid")
        FileLogger.d("MapActivity onCreate retrieve", "Received config: ${config.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received jsonString: $jsonString")
        FileLogger.d("MapActivity onCreate retrieve", "Received route: ${route.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received stops: ${stops.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received durationBetweenStops: ${durationBetweenStops.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received busRouteData: ${busRouteData.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received scheduleList: ${scheduleList.toString()}")
        FileLogger.d("MapActivity onCreate retrieve", "Received scheduleData: ${scheduleData.toString()}")

        val selfLabel = scheduleList.firstOrNull()?.let { formatPanelLabel(it) }
        mapController.activeSegment = selfLabel // let the controller draw using this exact text
        selfLabel?.let {
            publishActiveSegment(it)            // tell the other tablet right now
            Handler(Looper.getMainLooper()).postDelayed({ publishActiveSegment(it) }, 1200) // nudge once more
        }
        mapController.refreshDetailPanelIcons()

        // Prefer the exact RouteData sent via Intent, else index, else first
        @Suppress("DEPRECATION") // for getSerializableExtra() on older APIs
        selectedRouteData =
            (intent.getSerializableExtra("SELECTED_ROUTE_DATA") as? RouteData)
                ?: run {
                    // Fallback to index if only SELECTED_ROUTE_INDEX was sent
                    val idx = intent.getIntExtra("SELECTED_ROUTE_INDEX", -1)
                    busRouteData.getOrNull(idx)
                }
                        ?: busRouteData.firstOrNull()

// If we have a selected route, derive the map vectors from it
        selectedRouteData?.let { rd ->
            val (r, s, d) = processSingleRouteData(rd)
            route = r            // override flat polyline for this trip
            stops = s            // override stop list for this trip
            durationBetweenStops = d // override per-leg durations
            Log.d("MapActivity", "✅ Using selectedRouteData with ${stops.size} stops and ${route.size} polyline points")
        } ?: run {
            Log.w("MapActivity", "⚠️ No selectedRouteData; leaving existing route/stops as-is")
        }

        /** If we have a selected route, derive the map vectors from it */
        selectedRouteData?.let { rd ->
            val (r, s, d) = processSingleRouteData(rd)
            route = r            /** override flat polyline for this trip */
            stops = s            /** override stop list for this trip     */
            durationBetweenStops = d /** override per-leg durations       */
            Log.d("MapActivity", "✅ Using selectedRouteData with ${stops.size} stops and ${route.size} polyline points")
        } ?: run {
            Log.w("MapActivity", "⚠️ No selectedRouteData; leaving existing route/stops as-is")
        }

        extractRedBusStops()

        // ✅ Print the stop index → name list at startup
        logAllStopsWithIndex()

// ✅ Print the red timing-point indices → names
        logRedStopsWithIndex()

        // Initialize UI components
        initializeUIComponents()

        // Start the current time counter
//        startCurrentTimeUpdater()

        // start the simulated clock
        timeManager.startStartTime()

        // Start the next trip countdown updater
        timeManager.startNextTripCountdownUpdater()

        updateApiTime() // Ensure API time is updated at the start

        initializeTimingPoint()

        // ✅ Ensure locationManager is initialized
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
                    mqttHelper.fetchConfig { success ->
                        if (success) {
                            // rebuild your MQTT client with the new token
                            getAccessToken()
                            mqttManager = MqttManager(
                                serverUri = SERVER_URI,
                                clientId  = CLIENT_ID,
                                username  = token
                            )
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
                            mapController.refreshDetailPanelIcons()
                            // Log immediately
                            if (!hasDumpedPanelLog) {
                                logPanelDebugFromDetailPanel()
                                hasDumpedPanelLog = true
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
        mqttHelper.fetchConfig { success ->
            // now you’re inside the callback, and `success` is in scope
            runOnUiThread {
                if (success) {
                    getAccessToken()
                    mqttManager = MqttManager(
                        serverUri = SERVER_URI,
                        clientId  = CLIENT_ID,
                        username  = token
                    )
                    // build your markers etc.
                    mapController.getDefaultConfigValue()
                    mapController.activeSegment = selfLabel
                    mapController.refreshDetailPanelIcons()

                    // Log immediately
                    logPanelDebugFromDetailPanel()

                    // Log again shortly after MQTT polling begins (gives time for other buses to populate)
                    Handler(Looper.getMainLooper()).postDelayed({
                        logPanelDebugFromDetailPanel()
                    }, 1200)

                    mqttHelper.requestAdminMessage()
                    mqttHelper.connectAndSubscribe()
                    mqttHelper.startAttributePolling()
//                    mqttHelper.sendRequestAttributes()
                    mapController.startActivityMonitor()
                } else {
                    // --- FAILURE HANDLING ---
                    // ensure mqttManager is assigned even on config-fetch failure
                    mqttManager = MqttManager(
                        serverUri = SERVER_URI,
                        clientId  = CLIENT_ID
                    )
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
                    mapController.activeSegment = selfLabel
                    mapController.refreshDetailPanelIcons()

                    // disable any UI that needs live data
                    binding.startSimulationButton.isEnabled = false
                    binding.stopSimulationButton.isEnabled  = false
                }
            }
        }

        updateBusNameFromConfig()

        // Ensure `locationManager` is properly initialized before use
        locationManager.getCurrentLocation(object : LocationListener {
            override fun onLocationUpdate(location: Location) {
                latitude = location.latitude
                longitude = location.longitude
                Log.d("MapActivity onCreate Latitude", latitude.toString())
                Log.d("MapActivity onCreate Longitude", longitude.toString())
            }
        })

        // Load offline map first
        mapController.openMapFromAssets()

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

            timeManager.stopCurrentTime()
            timeManager.startCustomTime(customTime)
//            startActualTimeUpdater()

            // Trigger visual change to test schedule status UI
            scheduleStatusValueTextView.text = "Calculating..."
            scheduleStatusManager.checkScheduleStatus()
        }

        binding.map.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // ensure we only do this once and the map is actually visible
                if (!autoTapArrivalDone && binding.map.width > 0 && binding.map.height > 0) {
                    autoTapArrivalDone = true
                    binding.map.viewTreeObserver.removeOnGlobalLayoutListener(this)

                    // only auto-tap if it makes sense
                    val canConfirm = scheduleList.isNotEmpty() && stops.isNotEmpty()
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
            override fun onChange() {
                val zoom = binding.map.model.mapViewPosition.zoomLevel.toDouble()
                Log.d("MapActivity", "Zoom changed to $zoom")
                runOnUiThread {
                    mapController.refreshDetailPanelIcons()
                    logPanelDebugFromDetailPanel()
                }
            }
        })

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
            val dlgBuilder = androidx.appcompat.app.AlertDialog.Builder(this)
            val numberPadView = layoutInflater.inflate(R.layout.dialog_number_pad, null)
            val numberPadInput = numberPadView.findViewById<EditText>(R.id.numberPadInput)

            // 1) DO NOT mark it as a "password" input type (that triggers credential suggestions).
            //    Use plain numeric input + manually mask it.
            numberPadInput.inputType = InputType.TYPE_CLASS_NUMBER
            numberPadInput.transformationMethod = PasswordTransformationMethod.getInstance()

            // 2) Turn off saving & autofill on the exact field.
            numberPadInput.setSaveEnabled(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                numberPadInput.setAutofillHints(null) // not emptyArray()
                ViewCompat.setImportantForAutofill(numberPadInput, View.IMPORTANT_FOR_AUTOFILL_NO)
            }

            // (optional) keep the keyboard from learning this content
            numberPadInput.imeOptions = numberPadInput.imeOptions or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

            dlgBuilder.setView(numberPadView)
                .setTitle("Enter Passcode")
                .setPositiveButton("Confirm") { _, _ ->
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

    @SuppressLint("LongLogTag")
    private fun logPanelDebugFromDetailPanel() {
        if (!panelDebugEnabled) return

        val sb = StringBuilder()
        sb.appendLine("no: ${currentPanelDebugNo()}")

        val first = scheduleList.firstOrNull()
        if (first != null) {
            sb.appendLine("currentDetailPanel: ic_bus_symbol ${formatPanelLabel(first)}")
        }

        val fromStop = first?.busStops?.firstOrNull()?.let { it.abbreviation ?: it.name ?: it.address }
        val toStop   = first?.busStops?.lastOrNull()?.let  { it.abbreviation ?: it.name ?: it.address }

        TripLog.start(
            this,
            TripLog.ActiveTrip(
                startedAt   = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date()),
                type        = "trip",
                label       = mapController.activeSegment, // your “08:10 RUN … A → B”
                aid         = aid,
                runNo       = first?.runNo,
                runName     = first?.runName,
                startTime   = first?.startTime,
                endTime     = first?.endTime,
                fromStop    = fromStop,
                toStop      = toStop,
                scheduleSize = scheduleData.size,
                routeDataSize = busRouteData.size
            ),
            extraDump = mapOf(
                "configCount" to (config?.size ?: 0),
                "stopsCount" to (stops.size),
                "durationsCount" to (durationBetweenStops.size)
            )
        )

        // detailIconsContainer[0] is "me"; others are other buses
        val container = binding.detailIconsContainer
        if (container.childCount > 1) {
            for (i in 1 until container.childCount) {
                val row = container.getChildAt(i) as? LinearLayout ?: continue
                val text = (0 until row.childCount)
                    .mapNotNull { j -> (row.getChildAt(j) as? TextView)?.text?.toString() }
                    .firstOrNull()
                if (!text.isNullOrBlank()) {
                    val iconName = "ic_bus_symbol${kotlin.math.min(i + 1, 10)}"
                    sb.appendLine("otherDetailPanel: \"$iconName\" $text")
                }
            }
        }

        val dump = sb.toString().trimEnd()
        if (dump == lastPanelDump) return  // ← only log when changed
        lastPanelDump = dump
        Log.d("PanelDebug", dump)
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
     * Returns the first label whose time‐range contains the given `HH:mm` time.
     */
    private fun findActiveLabel(
        labels: List<String>,
        nowMinutes: Int
    ): String? {
        return labels.firstOrNull { label ->
            val start = label.substring(0,5)
            val end   = labels
                .dropWhile { !it.startsWith(start) }
                .getOrNull(1)?.substring(0,5)
                ?: start
            val sMin = start.split(":").let { it[0].toInt()*60 + it[1].toInt() }
            val eMin = end.split(":").let   { it[0].toInt()*60 + it[1].toInt() }
            nowMinutes in sMin until eMin
        }
    }

    /** Turn a single RouteData into flat polyline, stops, and durations */
    private fun processSingleRouteData(routeData: RouteData): Triple<List<BusRoute>, List<BusStop>, List<Double>> {
        // Working lists for this one route
        val route = mutableListOf<BusRoute>()
        val stops = mutableListOf<BusStop>()
        val durations = mutableListOf<Double>()

        // Starting point is a stop
        stops.add(
            BusStop(
                latitude = routeData.startingPoint.latitude,
                longitude = routeData.startingPoint.longitude,
                address = routeData.startingPoint.address
            )
        )

        // Walk each next point
        for (next in routeData.nextPoints) {
            // Each next point is also a stop
            stops.add(
                BusStop(
                    latitude = next.latitude,
                    longitude = next.longitude,
                    address = next.address
                )
            )

            // Build polyline, skipping consecutive duplicates
            var last: Pair<Double, Double>? = null
            for (coord in next.routeCoordinates) {
                val lat = coord[1]
                val lon = coord[0]
                if (last == lat to lon) continue
                route.add(BusRoute(latitude = lat, longitude = lon))
                last = lat to lon
            }

            // Parse "X.Y minutes" → X.Y
            durations.add(next.duration.split(" ")[0].toDoubleOrNull() ?: 0.0)
        }

        // Return as (polyline, stops, durations)
        return Triple(route, stops, durations)
    }

    /**
     * Retrieves the access token for the current device's Android ID from the configuration list.
     */
    @SuppressLint("HardwareIds")
    private fun getAccessToken() {
        val listConfig = config
        Log.d("getAccessToken config", config.toString())
        for (configItem in listConfig.orEmpty()) {
            if (configItem.aid == aid) {
                token = configItem.accessToken
                break
            }
        }
    }

    /**
     * Mark a bus stop as "arrived" and prevent duplicate arrivals.
     */
    private var isManualMode = false
    @SuppressLint("LongLogTag")
    private fun confirmArrival() {
        Log.d("MapActivity confirmArrival", "🚨 ConfirmArrival Triggered - Starting Process")

        selectedRouteData?.startingPoint?.let { sp ->
            BusStop(latitude = sp.latitude, longitude = sp.longitude, address = sp.address)
        }

        if (stops.isEmpty() || route.isEmpty()) {
            Log.e("MapActivity confirmArrival", "❌ No stops or route data available.")
            return
        }

        Log.d("MapActivity confirmArrival", "🔎 Starting nearest route point search...")

        // 1) find nearest route‑point index
        val nearestIndex = mapController.findNearestBusRoutePoint(latitude, longitude)
        Log.d("MapActivity confirmArrival", "✅ Nearest Route Point Found at Index: $nearestIndex")

        var nearestStop: BusStop? = null

        // 2) mark every stop up to that point as passed
        var snappedStop: BusStop? = null
        for (stop in stops) {
            val routeIdx = route.indexOfFirst {
                it.latitude == stop.latitude && it.longitude == stop.longitude
            }
            if (routeIdx != -1 && routeIdx <= nearestIndex) {
                if (!passedStops.contains(stop)) passedStops.add(stop)
                snappedStop = stop
            } else break
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

        // 3) clear & redraw all detection zones _once_
        mapController.drawDetectionZones(stops)

        // 4) snap UI to the snappedStop (if any)
        snappedStop?.let { stop ->
            latitude  = stop.latitude!!
            longitude = stop.longitude!!
            stopAddress = findAddressByCoordinates(latitude, longitude)
                ?: stop.address.orEmpty()
            upcomingBusStopTextView.text = stopAddress
            hasPassedFirstStop = true
        }

        // 5) continue with API‑time, schedule status updates, live GPS restart…
        // 🔹 Ensure schedule status updates correctly
        Log.d("MapActivity confirmArrival", "🔄 Updating API Time...")
        var busStopIndex = getBusStopIndex(latitude, longitude, stops)
        currentStopIndex = maxOf(0, getBusStopIndex(latitude, longitude, stops))

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
        scheduleStatusManager.checkScheduleStatus()   // Immediately refresh the schedule status
        Log.d("MapActivity confirmArrival", "✅ Schedule Status Checked")

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

            // take the first ScheduleItem’s busStops and turn each into a "lat,lon" string
            scheduleList.firstOrNull()?.busStops
                ?.map { "${it.latitude},${it.longitude}" }
                ?.let { redBusStops.addAll(it) }

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
            val nextStartMinutes = timeManager.convertTimeToMinutes(nextTrip.startTime)
            val currentMinutes = timeManager.simulatedStartTime.get(Calendar.HOUR_OF_DAY) * 60 +
                    timeManager.simulatedStartTime.get(Calendar.MINUTE)
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
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            // Parse the locked time into a Calendar object
            val lockedCal = Calendar.getInstance().apply {
                time = timeFormat.parse(lockedApiTime!!) ?: return
            }

            // Get timing list and last red timing point
            val baseRoute = selectedRouteData ?: busRouteData.firstOrNull()
            val timingList = baseRoute?.let { BusStopWithTimingPoint.fromRouteData(it) } ?: emptyList()
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
        val baseRoute = selectedRouteData ?: busRouteData.firstOrNull()
        val timingList = baseRoute?.let { BusStopWithTimingPoint.fromRouteData(it) } ?: emptyList()
        Log.d("MapActivity updateApiTime", "Timing list: $timingList")

        val upcomingAddress = stopAddress
        if (upcomingAddress.isBlank() || upcomingAddress == "Unknown") {
            Log.d("MapActivity updateApiTime", "No upcoming address yet; skipping.")
            return
        }
        Log.d("MapActivity updateApiTime", "Upcoming stop address: $upcomingAddress")

        // Find the target index.
        val targetIndex = timingList.indexOfFirst { it.address?.equals(upcomingAddress, true) == true }
        if (targetIndex == -1) {
            Log.e("...","Upcoming stop address not found in timing list.")
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
            val distance = mapController.calculateDistance(currentLat, currentLon, stopLat, stopLon)

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
    @SuppressLint("LongLogTag")
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
    val passedStops = mutableListOf<BusStop>() // Track stops that have been passed
    var currentStopIndex = 0 // Keep track of the current stop in order
    private var hasPassedFirstStopAgain = false
    private val isCircularRoute: Boolean
        get() = stops.isNotEmpty() && stops.first().address == stops.last().address


    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    private fun checkPassedStops(currentLat: Double, currentLon: Double) {
        if (stops.isEmpty()) {
            Log.d("MapActivity checkPassedStops", "❌ No bus stops available.")
            return
        }

        // ─── NEW: figure out where you are on the polyline ───
        val nearestRouteIdx = mapController.findNearestBusRoutePoint(currentLat, currentLon)
        Log.d("MapActivity checkPassedStops", "Nearest route index: $nearestRouteIdx")

        // ─── NEW: auto-pass any stops whose route-index ≤ your position ───
        stops.forEach { stop ->
            val idx = route.indexOfFirst {
                it.latitude == stop.latitude && it.longitude == stop.longitude
            }
            if (idx != -1 && idx <= nearestRouteIdx && !passedStops.contains(stop)) {
                passedStops.add(stop)
                Log.d("MapActivity checkPassedStops", "🟢 Auto-passed stop: ${stop.address}")
            }
        }

        // ─── NEW: recompute currentStopIndex from passedStops ───
        currentStopIndex = stops.indexOfFirst { it !in passedStops }
            .takeIf { it >= 0 } ?: stops.size

        if (currentStopIndex >= stops.size) {
            Log.d("MapActivity checkPassedStops", "✅ All stops have been passed.")
            // update UI to “end of route” and fire the summary dialog:
            runOnUiThread {
                upcomingBusStopTextView.text = "End of Route"
                Toast.makeText(this@MapActivity, "✅ You have reached the final stop.", Toast.LENGTH_SHORT).show()
                showSummaryDialog()
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

        val distance = mapController.calculateDistance(currentLat, currentLon, stopLat, stopLon)

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
                    mapController.drawDetectionZones(stops) // Redraw zones to reflect changes
                }

                passedStops.add(nextStop)
                currentStopIndex++

                // Ensure the next stop is updated correctly even if a stop is skipped
                while (currentStopIndex < stops.size && mapController.calculateDistance(
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
            val baseRoute = selectedRouteData ?: busRouteData.firstOrNull()
            val timingList = baseRoute?.let { BusStopWithTimingPoint.fromRouteData(it) } ?: emptyList()
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
        val distance = mapController.calculateDistance(currentLat, currentLon, stopLat, stopLon)
        return distance <= radius
    }

    /** Finds the nearest upcoming bus stop */
    @SuppressLint("LongLogTag")
    private fun getUpcomingBusStopName(lat: Double, lon: Double): String {
        // Prefer the strongly-typed data you already have
        findAddressByCoordinates(lat, lon)?.let { return it }

        if (jsonString.isBlank()) {
            Log.w("MapActivity getUpcomingBusStopName", "jsonString empty; falling back")
            return "Unknown Stop"
        }

        return try {
            val jsonArray = JSONArray(jsonString)
            if (jsonArray.length() == 0) return "No Upcoming Stop"
            val jsonObject = jsonArray.getJSONObject(0)
            if (!jsonObject.has("next_points")) return "No Upcoming Stop"
            val routeArray = jsonObject.getJSONArray("next_points")
            if (routeArray.length() == 0) return "No Upcoming Stop"

            var nearest: String? = null
            var min = Double.MAX_VALUE
            for (i in 0 until routeArray.length()) {
                val stop = routeArray.getJSONObject(i)
                val stopLat = stop.optDouble("latitude", Double.NaN)
                val stopLon = stop.optDouble("longitude", Double.NaN)
                val stopAddr = stop.optString("address", "")
                if (stopLat.isNaN() || stopLon.isNaN() || stopAddr.isBlank()) continue
                val d = mapController.calculateDistance(lat, lon, stopLat, stopLon)
                if (d < min) { min = d; nearest = stopAddr }
            }
            nearest ?: "Unknown Stop"
        } catch (e: Exception) {
            Log.e("MapActivity getUpcomingBusStopName", "Error: ${e.localizedMessage}", e)
            "Unknown Stop"
        }
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
                timeManager.simulatedStartTime.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
                timeManager.simulatedStartTime.set(Calendar.MINUTE, timeParts[1].toInt())
                timeManager.simulatedStartTime.set(Calendar.SECOND, 0)
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
            mapController.drawPolyline()
        }
    }

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
    var hasPassedFirstStop = false
    private val jumpThreshold = 3 // Prevents sudden jumps
    private val detectionZoneRadius = 200.0 // 200m detection zone
    private var panelDebugNo: Int = 0


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
            @RequiresApi(Build.VERSION_CODES.M)
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
                    val nearestIndex = mapController.findNearestBusRoutePoint(latitude, longitude)

                    // Handle First Bus Stop Rule
                    if (!hasPassedFirstStop) {
                        if (nearestIndex == 0) {
                            hasPassedFirstStop = true // Mark the first bus stop as passed
                            Log.d("MapActivity", "✅ First bus stop passed. Rules activated.")
                        } else {
                            Log.d("MapActivity", "⚠️ Waiting for first bus stop to be passed.")
                            // Use live GPS data until the first stop is passed
                            runOnUiThread {
                                mapController.updateBusMarkerPosition(latitude, longitude, bearing)
                                binding.map.invalidate()
                            }
                            return
                        }
                    }

                    // Ignore backward movement
                    if (nearestIndex < nearestRouteIndex) return

                    // Unlock detection zone logic
                    val distance = mapController.calculateDistance(lastLatitude, lastLongitude, latitude, longitude)
                    if (distance > detectionZoneRadius * 2) {
                        nearestRouteIndex = mapController.findNearestBusRoutePoint(latitude, longitude)
                    }

                    // Ignore sudden jumps (skip unexpected spikes)
                    if (nearestIndex > nearestRouteIndex + jumpThreshold) return

                    // Smooth marker animation for consecutive points
                    if (nearestIndex >= nearestRouteIndex) {
                        mapController.animateMarkerThroughPoints(nearestRouteIndex, nearestIndex)
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

                    bearing = mapController.calculateBearing(
                        latitude, longitude,
                        targetPoint.latitude ?: 0.0,
                        targetPoint.longitude ?: 0.0
                    )

                    Log.d("GPS_DEBUG", "Latitude: ${location.latitude}, Longitude: ${location.longitude}, Accuracy: ${location.accuracy}")
                    Log.d("GPS_DEBUG", "Speed: ${location.speed}, Bearing: ${location.bearing}")

//                    showCustomToast("Latitude: ${location.latitude}, Longitude: ${location.longitude}, LocAccuracy: ${location.accuracy}, Speed: ${location.speed}, Bearing: ${location.bearing}, BearAccuracy: ${location.bearingAccuracyDegrees}")
//                    Toast.makeText(this@MapActivity, "Lat: ${location.latitude}, Lon: ${location.longitude}, LocAcc: ${location.accuracy}, Speed: ${location.speed}, Bear: ${location.bearing}, BearAcc: ${location.bearingAccuracyDegrees}", Toast.LENGTH_LONG).show()
//                    Toast.makeText(this@MapActivity, "Speed: ${location.speed}, Bearing: ${location.bearing}", Toast.LENGTH_LONG).show()

                    runOnUiThread {
                        speedTextView.text = "Speed: ${"%.2f".format(speed)} km/h"
                        mapController.updateBusMarkerPosition(latitude, longitude, bearing)
                        checkPassedStops(latitude, longitude)
                        updateTimingPointBasedOnLocation(latitude, longitude)
                        scheduleStatusValueTextView.text = "Calculating..."
                        scheduleStatusManager.checkScheduleStatus()
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
     * Updates the client attributes by posting the current location, bearing, speed, and direction data to the server.
     */
    @SuppressLint("LongLogTag")
    fun updateClientAttributes() {
        // before you build & post…
        val curr = latitude to longitude
        if (prevOwnCoords == curr) {
            Log.d("MapActivity updateClientAttributes",
                "self notActive: location unchanged (${curr.first},${curr.second}) → skipping publish")
            return
        } else {
            Log.d("MapActivity updateClientAttributes",
                "self active: location changed → publishing client attributes")
            prevOwnCoords = curr
        }

        val url = ApiService.BASE_URL + "$token/attributes"
        val scheduleJson = Gson().toJson(scheduleData)
        // this remains the ORIGINAL first item passed in via intent
        val currentLabel = scheduleList.firstOrNull()?.let { formatPanelLabel(it) }

        val attributesData = AttributesData(
            latitude        = latitude,
            longitude       = longitude,
            bearing         = bearing,
            bearingCustomer = null,
            speed           = speed,
            direction       = direction,
            scheduleData    = scheduleJson
        )

        Log.d("MapActivity updateClientAttributes", "Posting client-attrs for aid=$aid → $attributesData")

        val call = apiService.postAttributes(url, "application/json", attributesData)
        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                Log.d("MapActivity updateClientAttributes", "postAttrs response for aid=$aid: code=${response.code()}  msg=${response.message()}")
            }
            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.e("MapActivity updateClientAttributes", "postAttrs fail for aid=$aid: ${t.message}")
            }
        })
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
        val currentStopIndexFromDisplay = stops.indexOfFirst {
            it.address?.equals(currentDisplayedStop, ignoreCase = true) == true
        }
        if (currentStopIndexFromDisplay != -1) {
            val currentStop = stops[currentStopIndexFromDisplay]
            // Compute the distance between the bus marker position and this bus stop.
            val distanceToStop = mapController.calculateDistance(
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
        connectionStatusTextView   = binding.connectionStatusTextView
        networkStatusIndicator     = binding.networkStatusIndicator
    }

    override fun onBackPressed() { /* no-op */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH -> true  // swallow Back & Recents
            else                         -> super.onKeyDown(keyCode, event)
        }
    }

    /** Cleans up resources on activity destruction. */
    override fun onDestroy() {
        FileLogger.markAppClosed("MapActivity")
        super.onDestroy()
        panelDebugEnabled = false
        if (::mqttHelper.isInitialized) mqttHelper.stopAttributePolling()

        // Remove polyline from Mapsforge map
        routePolyline?.let {
            binding.map.layerManager.layers.remove(it)
            binding.map.invalidate()
        }
        Log.d("MapActivity", "🗑️ Removed polyline on destroy.")
        timeManager.currentTimeHandler.removeCallbacks(timeManager.currentTimeRunnable)
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

    private fun formatPanelLabel(item: ScheduleItem): String {
        val from = item.busStops.firstOrNull()?.abbreviation ?: item.busStops.firstOrNull()?.name ?: "?"
        val to   = item.busStops.lastOrNull()?.abbreviation  ?: item.busStops.lastOrNull()?.name  ?: "?"
        return "${item.startTime} ${saferunName(item)} $from → $to"
    }

    private fun currentPanelDebugNo(): Int =
        getSharedPreferences("panel_debug_pref", MODE_PRIVATE)
            .getInt("panel_debug_no", 0)

    override fun onStop() {
        super.onStop()
        panelDebugEnabled = false
        if (::mqttHelper.isInitialized) mqttHelper.stopAttributePolling()
        // remove any observers/timers you set that could call logPanelDebugFromDetailPanel()
    }

    private fun publishActiveSegment(label: String) {
        val topic = "v1/devices/me/attributes"
        // CHANGE activeSegment → currentTripLabel
        val payload = "{\"currentTripLabel\":\"${label.replace("\"", "\\\"")}\"}"
        try {
            if (::mqttManager.isInitialized) {
                mqttManager.publish(topic, payload)
            }
        } catch (_: Exception) { /* ignore when offline */ }
    }

    // Pretty lat/lon if we don't have an address
    private fun Double?.fmt6(): String = if (this == null) "?" else String.format(Locale.US, "%.6f", this)

    // Resolve a display name for a BusStop: address → (fallback) address-from-RouteData → lat,lon
    private fun stopDisplayName(stop: BusStop): String {
        stop.address?.let { if (it.isNotBlank()) return it }
        val addr = findAddressByCoordinates(stop.latitude ?: 0.0, stop.longitude ?: 0.0)
        if (!addr.isNullOrBlank()) return addr
        return "${stop.latitude.fmt6()},${stop.longitude.fmt6()}"
    }

    // Log every stop with its index
    private fun logAllStopsWithIndex(tag: String = "MapActivity") {
        if (stops.isEmpty()) {
            FileLogger.d(tag, "🗺️ Stops: (none)")
            return
        }
        val list = stops.mapIndexed { idx, s -> "[${idx}] ${stopDisplayName(s)}" }
        FileLogger.d(tag, "🗺️ Stops (${stops.size}): ${list.joinToString(", ")}")
    }

    // Build the same "lat,lon" key format you stored in redBusStops
    private fun latLonKey(lat: Double?, lon: Double?): String = "${lat ?: 0.0},${lon ?: 0.0}"

    // Log only red timing-point stops with their indices
    private fun logRedStopsWithIndex(tag: String = "MapActivity") {
        if (stops.isEmpty()) {
            FileLogger.d(tag, "🔴 Red timing points: (no stops loaded)")
            return
        }
        if (redBusStops.isEmpty()) {
            FileLogger.d(tag, "🔴 Red timing points: (none)")
            return
        }

        val redAddrSet = redBusStops.map { it.trim().lowercase(Locale.getDefault()) }.toSet()

        val redIndexed = stops.mapIndexedNotNull { idx, stop ->
            val addrMatch = stop.address?.trim()?.lowercase(Locale.getDefault())?.let { it in redAddrSet } == true
            val keyMatch  = latLonKey(stop.latitude, stop.longitude).lowercase(Locale.getDefault()) in redAddrSet
            if (addrMatch || keyMatch) idx to stopDisplayName(stop) else null
        }

        if (redIndexed.isEmpty()) {
            FileLogger.d(tag, "🔴 Red timing points: (none matched against current stops)")
        } else {
            val list = redIndexed.joinToString(", ") { (i, name) -> "[${i}] ${name}" }
            FileLogger.d(tag, "🔴 Red timing points (${redIndexed.size}): $list")
        }
    }
}
