package com.jason.publisher.modules.rep.activities

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
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Build
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import com.jason.publisher.databinding.ActivityTestrepBinding
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.LifecycleLogger
import com.jason.publisher.main.model.BusItem
import com.jason.publisher.main.model.BusStop
import com.jason.publisher.main.model.RouteData
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.ui.DrawableHelper
import com.jason.publisher.modules.battery.ui.hookBatteryToasts
import com.jason.publisher.modules.map.models.BusStopWithTimingPoint
import com.jason.publisher.modules.map.utils.BUS_STOP_RADIUS
import com.jason.publisher.modules.map.utils.calculateDistance
import com.jason.publisher.modules.map.utils.calculateDurationForUpdate
import com.jason.publisher.modules.rep.viewmodels.RepViewModel
import com.jason.publisher.modules.map.viewmodels.TimeManager
import com.jason.publisher.modules.network.utils.NetworkStatusHelper
import com.jason.publisher.modules.schedule.activities.ScheduleActivity
import com.jason.publisher.R
import com.jason.publisher.main.utils.parseTimeToday
import com.jason.publisher.modules.map.utils.calculateBearing
import org.mapsforge.core.graphics.Bitmap
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.util.Locale.getDefault
import kotlin.math.abs
import kotlin.math.min

class TestRepActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestrepBinding

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

    val viewModel: RepViewModel by viewModels {
        RepViewModel.provideFactory()
    }

    val timeManager: TimeManager by viewModels {
        TimeManager.provideFactory()
    }

    private var routePolyline: Polyline? = null
    var currentBusMarker: Marker? = null
    private val checkMarkers = mutableListOf<Marker>()

    // Handler untuk periodic UI update
    private var periodicUIUpdateHandler: Handler? = null
    private var periodicUIUpdateRunnable: Runnable? = null

    /** Throttling and debouncing for better performance */
    private var lastUIUpdateTime = 0L
    private val uiUpdateThrottleMs = 500L
    private var lastMapInvalidateTime = 0L
    private val mapInvalidateThrottleMs = 1000L

    // Simulation variables
    private var isSimulating = false
    private var simulatedTimeMillis = 0L
    private var lastRealTimeMillis = 0L
    private var currentRouteDistance = 0.0

    @RequiresApi(Build.VERSION_CODES.N)
    @SuppressLint("LongLogTag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroidGraphicFactory.createInstance(application)
        binding = ActivityTestrepBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FileLogger.d("TestRepActivity", "onCreate - Simulation Mode")

        viewModel.hasShownFinalStopMessage = false
        hookBatteryToasts()
        hideSystemUI()
        initializeUIComponents()

        // Retrieve data
        viewModel.aid = intent.getStringExtra("AID") ?: "Unknown"
        viewModel.jsonString = intent.getStringExtra("JSON_STRING") ?: ""
        viewModel.durationBetweenStops = intent.getDoubleArrayExtra("DURATION_BETWEEN_BUS_STOP")?.toList() ?: emptyList()

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            viewModel.config = intent.getParcelableArrayListExtra("CONFIG", BusItem::class.java) ?: emptyList()
            viewModel.busRouteData = intent.getParcelableArrayListExtra("BUS_ROUTE_DATA", RouteData::class.java) ?: emptyList()
            viewModel.scheduleList = intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM", ScheduleItem::class.java) ?: emptyList()
            viewModel.scheduleData = intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA", ScheduleItem::class.java) ?: emptyList()
        } else {
            viewModel.config = intent.getParcelableArrayListExtra("CONFIG") ?: emptyList()
            viewModel.busRouteData = intent.getParcelableArrayListExtra("BUS_ROUTE_DATA") ?: emptyList()
            viewModel.scheduleList = intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM") ?: emptyList()
            viewModel.scheduleData = intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA") ?: emptyList()
        }

        val no = intent.getIntExtra("EXTRA_PANEL_DEBUG_NO", -1)
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

        viewModel.deriveMapVectors()
        viewModel.extractRedBusStops()

        // Initialize Simulation Time
        if (viewModel.scheduleList.isNotEmpty()) {
            val startTimeStr = viewModel.scheduleList.first().startTime + ":00"
            simulatedTimeMillis = startTimeStr.parseTimeToday().time
            lastRealTimeMillis = System.currentTimeMillis()
        }

        // Observer setup
        viewModel.nextTimingPoint.observe(this) { timingPointValueTextView.text = it }
        viewModel.upcomingStopName.observe(this) { upcomingBusStopTextView.text = it }
        viewModel.lastSpeedText.observe(this) { speedTextView.text = it }
        viewModel.lastTripEndTimeText.observe(this) { tripEndTimeTextView.text = it }

        startSimulatedTimeUpdater()
        timeManager.startNextTripCountdownUpdater(ArrayList(viewModel.scheduleList + viewModel.scheduleData))
        timeManager.nextTripCountdown.observe(this) { nextTripCountdownTextView.text = it }

        updateApiTime()
        timingPointValueTextView.text = viewModel.initializeTimingPoint()

        // Simulation controls
        binding.startSimulationButton.setOnClickListener {
            isSimulating = true
            lastRealTimeMillis = System.currentTimeMillis()
            Toast.makeText(this, "Simulation Started", Toast.LENGTH_SHORT).show()
        }
        binding.stopSimulationButton.setOnClickListener {
            isSimulating = false
            Toast.makeText(this, "Simulation Stopped", Toast.LENGTH_SHORT).show()
        }
        binding.speedUpButton.setOnClickListener {
            viewModel.speedUp()
            viewModel.speed += 5f
            viewModel.updateSpeedText()
        }
        binding.slowDownButton.setOnClickListener {
            viewModel.slowDown()
            viewModel.speed = (viewModel.speed - 5f).coerceAtLeast(0f)
            viewModel.updateSpeedText()
        }

        binding.backButton.setOnClickListener {
            val intent = Intent(this, ScheduleActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }

        // Initialize Map
        openMapFromAssets()
        if (viewModel.route.isNotEmpty()) {
            drawPolyline(viewModel.route)
            val first = viewModel.route.first()
            viewModel.latitude = first.latitude!!
            viewModel.longitude = first.longitude!!
            updateBusMarkerPosition(viewModel.latitude, viewModel.longitude, 0f)
        }

        startPeriodicUIUpdate()
    }

    private fun startSimulatedTimeUpdater() {
        val handler = Handler(Looper.getMainLooper())
        val timeFormat = SimpleDateFormat("HH:mm:ss", getDefault())
        val runnable = object : Runnable {
            override fun run() {
                if (isSimulating) {
                    val now = System.currentTimeMillis()
                    val elapsed = (now - lastRealTimeMillis) * viewModel.simulationSpeedFactor
                    simulatedTimeMillis += elapsed
                    lastRealTimeMillis = now
                } else {
                    lastRealTimeMillis = System.currentTimeMillis()
                }
                currentTimeTextView.text = timeFormat.format(Date(simulatedTimeMillis))
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(runnable)
    }

    private fun startPeriodicUIUpdate() {
        periodicUIUpdateHandler = Handler(Looper.getMainLooper())
        periodicUIUpdateRunnable = object : Runnable {
            override fun run() {
                if (isSimulating) {
                    simulateMovement()
                }
                updateUIElementsThrottled()
                periodicUIUpdateHandler?.postDelayed(this, 1000)
            }
        }
        periodicUIUpdateHandler?.post(periodicUIUpdateRunnable!!)
    }

    private fun simulateMovement() {
        if (viewModel.route.isEmpty() || viewModel.speed <= 0f) return

        val speedMps = (viewModel.speed * viewModel.simulationSpeedFactor) / 3.6
        val distanceToMove = speedMps * 1.0 // 1 second update interval

        currentRouteDistance += distanceToMove
        
        var traveled = 0.0
        for (i in 0 until viewModel.route.size - 1) {
            val p1 = viewModel.route[i]
            val p2 = viewModel.route[i + 1]
            val segmentDist = calculateDistance(p1.latitude!!, p1.longitude!!, p2.latitude!!, p2.longitude!!)
            
            if (traveled + segmentDist >= currentRouteDistance) {
                val remaining = currentRouteDistance - traveled
                val fraction = remaining / segmentDist
                
                val lat = p1.latitude!! + (p2.latitude!! - p1.latitude!!) * fraction
                val lon = p1.longitude!! + (p2.longitude!! - p1.longitude!!) * fraction
                
                val oldLat = viewModel.latitude
                val oldLon = viewModel.longitude
                viewModel.latitude = lat
                viewModel.longitude = lon
                viewModel.bearing = calculateBearing(oldLat, oldLon, lat, lon)
                
                updateBusMarkerPosition(lat, lon, viewModel.bearing)
                return
            }
            traveled += segmentDist
        }
        
        val last = viewModel.route.last()
        viewModel.latitude = last.latitude!!
        viewModel.longitude = last.longitude!!
        updateBusMarkerPosition(viewModel.latitude, viewModel.longitude, viewModel.bearing)
        isSimulating = false
        Toast.makeText(this, "Destination Reached", Toast.LENGTH_SHORT).show()
    }

    private fun updateUIElementsThrottled() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUIUpdateTime < uiUpdateThrottleMs) return
        lastUIUpdateTime = currentTime

        checkPassedStops()
        updateTimingPointBasedOnLocation()
        checkScheduleStatus()
        updateApiTime()
        
        if (viewModel.scheduleList.isNotEmpty()) {
            viewModel.updateTripEndTimeText()
        }

        if (currentTime - lastMapInvalidateTime >= mapInvalidateThrottleMs) {
            binding.map.invalidate()
            lastMapInvalidateTime = currentTime
        }
    }

    private fun checkPassedStops() {
        val currentLat = viewModel.latitude
        val currentLon = viewModel.longitude
        if (viewModel.stops.isEmpty() || (currentLat == 0.0 && currentLon == 0.0)) return

        val nearestRouteIdx = viewModel.findNearestBusRoutePoint(currentLat, currentLon)
        
        val beforeSize = viewModel.passedStops.size
        viewModel.stops.forEach { stop ->
            val idx = viewModel.route.indexOfFirst {
                it.latitude == stop.latitude && it.longitude == stop.longitude
            }
            if (idx != -1 && idx <= nearestRouteIdx && !viewModel.passedStops.contains(stop)) {
                viewModel.passedStops.add(stop)
            }
        }

        if (viewModel.passedStops.size > beforeSize) {
            drawDetectionZones(viewModel.stops, viewModel.passedStops)
        }

        viewModel.currentStopIndex = viewModel.stops.indexOfFirst { it !in viewModel.passedStops }
            .takeIf { it >= 0 } ?: viewModel.stops.size

        if (viewModel.currentStopIndex >= viewModel.stops.size) {
            upcomingBusStopTextView.text = "End of Route"
            if (!viewModel.hasShownFinalStopMessage) {
                Toast.makeText(this, "✅ You have reached the final stop.", Toast.LENGTH_SHORT).show()
                viewModel.hasShownFinalStopMessage = true
            }
            return
        }

        val nextStop = viewModel.stops[viewModel.currentStopIndex]
        viewModel.stopAddress = nextStop.address ?: "Unknown"
        upcomingBusStopTextView.text = viewModel.stopAddress
    }

    private fun updateTimingPointBasedOnLocation() {
        if (viewModel.scheduleList.isEmpty()) return
        val stopList = viewModel.scheduleList.first().busStops
        if (stopList.isEmpty()) return

        for ((index, stop) in stopList.withIndex()) {
            val dist = calculateDistance(viewModel.latitude, viewModel.longitude, stop.latitude, stop.longitude)
            if (dist <= 25.0) {
                val next = if (index + 1 < stopList.size) stopList[index + 1].time + ":00" else viewModel.scheduleList.first().endTime + ":00"
                viewModel.nextTimingPoint.postValue(next)
                return
            }
        }
    }

    private fun updateApiTime() {
        if (viewModel.scheduleList.isEmpty()) return
        val firstSchedule = viewModel.scheduleList.first()
        val startTimeParts = firstSchedule.startTime.split(":")
        if (startTimeParts.size != 2) return

        val startCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startTimeParts[0].toInt())
            set(Calendar.MINUTE, startTimeParts[1].toInt())
            set(Calendar.SECOND, 0)
        }

        val baseRoute = viewModel.selectedRouteData ?: viewModel.busRouteData.firstOrNull()
        val timingList = baseRoute?.let { BusStopWithTimingPoint.fromRouteData(it) } ?: emptyList()
        val targetIndex = timingList.indexOfFirst { it.address?.equals(viewModel.stopAddress, true) == true }
        
        val totalDurationMinutes = if (targetIndex != -1) {
            calculateDurationForUpdate(timingList, viewModel.scheduleList, targetIndex)
        } else {
            calculateDurationForUpdate(timingList, viewModel.scheduleList, viewModel.currentStopIndex.coerceAtMost(timingList.lastIndex))
        }

        if (totalDurationMinutes != null) {
            startCalendar.add(Calendar.SECOND, (totalDurationMinutes * 60).toInt())
            apiTimeValueTextView.text = SimpleDateFormat("HH:mm:ss", getDefault()).format(startCalendar.time)
        }
    }

    private fun checkScheduleStatus() {
        try {
            val timeFormat = SimpleDateFormat("HH:mm:ss", getDefault())
            val scheduledTimeStr = timingPointValueTextView.text.toString()
            val timingPointTime = scheduledTimeStr.parseTimeToday()
            
            // In simulation, we use simulatedTimeMillis instead of real system time
            val currentSimulatedTime = simulatedTimeMillis
            
            val deltaSec = ((timingPointTime.time - currentSimulatedTime) / 1000).toInt()
            val deltaMin = deltaSec / 60
            val absMin = abs(deltaMin)
            val timeDiff = if (absMin == 1) "1 min" else "$absMin min"

            val statusText = when {
                deltaMin >= 1 -> "Early (~$timeDiff early)"
                deltaMin in -1..1 -> "On Time (~$timeDiff on time)"
                deltaMin in -5..-1 -> "Slightly Behind (~$timeDiff late)"
                else -> "Very Behind (~$timeDiff late)"
            }

            val symbolRes = when {
                deltaMin >= 1 -> R.drawable.ic_schedule_very_ahead
                deltaMin in -1..1 -> R.drawable.ic_schedule_on_time
                deltaMin in -5..-1 -> R.drawable.ic_schedule_slightly_behind
                else -> R.drawable.ic_schedule_very_behind
            }

            val colorRes = when {
                deltaMin >= 1 -> R.color.blind_red
                deltaMin in -1..1 -> R.color.blind_green
                deltaMin in -5..-1 -> R.color.blind_blue
                else -> R.color.blind_purple
            }

            scheduleStatusValueTextView.text = statusText
            scheduleStatusValueTextView.setTextColor(ContextCompat.getColor(this, colorRes))
            binding.scheduleAheadIcon.setImageResource(symbolRes)
            
        } catch (e: Exception) {
            Log.e("TestRepActivity", "Error checkScheduleStatus: ${e.message}")
        }
    }

    private fun initializeUIComponents() {
        speedTextView = binding.speedTextView
        timingPointandStopsTextView = binding.timingPointandStopsTextView
        tripEndTimeTextView = binding.tripEndTimeTextView
        timingPointValueTextView = binding.timingPointValueTextView
        apiTimeValueTextView = binding.ApiTimeValueTextView
        scheduleStatusValueTextView = binding.scheduleStatusValueTextView
        upcomingBusStopTextView = binding.upcomingBusStopTextView
        currentTimeTextView = binding.currentTimeTextView
        nextTripCountdownTextView = binding.nextTripCountdownTextView
        connectionStatusTextView = binding.connectionStatusTextView
        networkStatusIndicator = binding.networkStatusIndicator
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // Map Helper Methods (Copied/Adapted from MapViewController)
    private fun openMapFromAssets() {
        binding.map.mapScaleBar.isVisible = true
        binding.map.setBuiltInZoomControls(true)
        val cache = AndroidUtil.createTileCache(this, "preloadCache", binding.map.model.displayModel.tileSize, 1f, binding.map.model.frameBufferModel.overdrawFactor)
        val mapFile = File(viewModel.getHiddenFolder(), "new-zealand.map")
        if (!mapFile.exists()) return

        val store = MapFile(mapFile)
        val renderTheme = AssetsRenderTheme(assets, "", "custom_render_theme.xml")
        val layer = TileRendererLayer(cache, store, binding.map.model.mapViewPosition, AndroidGraphicFactory.INSTANCE).apply { setXmlRenderTheme(renderTheme) }
        binding.map.layerManager.layers.add(0, layer)
        binding.map.model.mapViewPosition.zoomLevel = 15
        addBusStopMarkers(viewModel.stops)
    }

    private fun drawPolyline(route: List<BusRoute>) {
        if (route.isEmpty()) return
        val routePoints = route.map { LatLong(it.latitude!!, it.longitude!!) }
        routePolyline?.let { binding.map.layerManager.layers.remove(it) }
        val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = Color.BLUE
            strokeWidth = 4f
            setStyle(Style.STROKE)
        }
        routePolyline = Polyline(paint, AndroidGraphicFactory.INSTANCE).apply { addPoints(routePoints) }
        binding.map.layerManager.layers.add(routePolyline)
    }

    private fun addBusStopMarkers(busStops: List<BusStop>) {
        val total = busStops.size
        val sizePx = dpToPx(30)
        busStops.forEachIndexed { idx, stop ->
            val key = "${stop.latitude},${stop.longitude}"
            val isRed = viewModel.redBusStops.contains(key)
            val symDrawable = DrawableHelper.createBusStopSymbol(this, idx, total, isRed)
            val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            symDrawable.setBounds(0, 0, sizePx, sizePx)
            symDrawable.draw(canvas)
            val marker = Marker(LatLong(stop.latitude!!, stop.longitude!!), AndroidBitmap(bmp), 0, 0)
            binding.map.layerManager.layers.add(marker)
        }
    }

    private fun drawDetectionZones(stops: List<BusStop>, passedStops: List<BusStop>) {
        val layers = binding.map.layerManager.layers
        layers.filterIsInstance<Circle>().forEach { layers.remove(it) }
        checkMarkers.forEach { layers.remove(it) }
        checkMarkers.clear()

        stops.forEach { stop ->
            val passed = passedStops.contains(stop)
            val zoneColor = if (passed) Color.parseColor("#2E7D32") else Color.parseColor("#90A4AE")
            val circle = Circle(LatLong(stop.latitude!!, stop.longitude!!), BUS_STOP_RADIUS.toFloat(),
                AndroidGraphicFactory.INSTANCE.createPaint().apply { color = Color.argb(64, Color.red(zoneColor), Color.green(zoneColor), Color.blue(zoneColor)); setStyle(Style.FILL) },
                AndroidGraphicFactory.INSTANCE.createPaint().apply { color = zoneColor; strokeWidth = 2f; setStyle(Style.STROKE) }
            )
            layers.add(circle)
        }
    }

    private fun updateBusMarkerPosition(lat: Double, lon: Double, bearing: Float) {
        val newPos = LatLong(lat, lon)
        val rotated = rotateBusIcon(bearing)
        if (currentBusMarker != null) {
            currentBusMarker!!.latLong = newPos
            currentBusMarker!!.bitmap = rotated
        } else {
            currentBusMarker = Marker(newPos, rotated, 0, 0)
            binding.map.layerManager.layers.add(currentBusMarker)
        }
        binding.map.rotation = -bearing
        binding.map.setCenter(newPos)
        binding.map.invalidate()
    }

    private fun rotateBusIcon(angle: Float): Bitmap {
        val d = ResourcesCompat.getDrawable(resources, R.drawable.ic_bus_symbol, null)!!
        val sizePx = dpToPx(16)
        val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.rotate(angle, sizePx / 2f, sizePx / 2f)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(canvas)
        return AndroidBitmap(bmp)
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        super.onDestroy()
        periodicUIUpdateHandler?.removeCallbacksAndMessages(null)
    }
}