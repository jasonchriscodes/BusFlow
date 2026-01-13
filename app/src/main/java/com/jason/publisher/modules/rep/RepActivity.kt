package com.jason.publisher.modules.rep

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.createBitmap
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.ui.DrawableHelper
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.TripLog
import com.jason.publisher.modules.battery.ui.hookBatteryToasts
import com.jason.publisher.modules.mqtt.helpers.MqttHelper
import com.jason.publisher.modules.mqtt.services.MqttManager
import org.mapsforge.core.graphics.Bitmap
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RepActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding

    // ---- Reposition target ----
    private var stopLat = 0.0
    private var stopLon = 0.0
    private var stopName = "Reposition Stop"
    private var stopAddr = "Unknown"

    // ---- Schedule ----
    private var scheduleList: List<ScheduleItem> = emptyList()

    // ---- Map ----
    private lateinit var mapView: MapView
    private var busMarker: Marker? = null
    private var stopMarker: Marker? = null
    private var busIcon: Bitmap? = null

    // ---- Location ----
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var callback: LocationCallback
    private var speedKmh = 0f

    // ---- Clock / UI ----
    private val ui = Handler(Looper.getMainLooper())
    private val clock = Handler(Looper.getMainLooper())

    // ---- MQTT (WAJIB untuk tracking lintas device) ----
    private lateinit var mqttManager: MqttManager
    private val mqttHandler = Handler(Looper.getMainLooper())
    private lateinit var repLabel: String
    @SuppressLint("SimpleDateFormat")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FileLogger.d("RepActivity", "onCreate")
        hookBatteryToasts()

        // ===== Read Intent =====
        stopLat  = intent.getDoubleExtra("REP_STOP_LAT", 0.0)
        stopLon  = intent.getDoubleExtra("REP_STOP_LON", 0.0)
        stopName = intent.getStringExtra("REP_STOP_NAME") ?: "Reposition Stop"
        stopAddr = intent.getStringExtra("REP_STOP_ADDR") ?: stopName

        scheduleList =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM", ScheduleItem::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM")
            }
                ?: emptyList()

        if (scheduleList.isEmpty()) {
            FileLogger.e("RepActivity", "FIRST_SCHEDULE_ITEM EMPTY — possible cache reset")
        }

        repLabel = "Reposition → $stopAddr"

        // ===== UI =====
        binding.upcomingBusStopTextView.text = stopAddr
        findLabelById("upcomingBusStopTitleTextView")?.text = "Reposition Stop"

        val start = scheduleList.firstOrNull()?.startTime ?: "00:00"
        val end   = scheduleList.firstOrNull()?.endTime   ?: "00:00"
        binding.tripEndTimeTextView.text = "$end:00"
        startStaticCountdown(start, end)

        // ===== Trip Log =====
        TripLog.start(
            this,
            TripLog.ActiveTrip(
                startedAt = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.getDefault()
                ).format(Date()),
                type = "reposition",
                label = repLabel,
                aid = intent.getStringExtra("AID"),
                runNo = scheduleList.firstOrNull()?.runNo,
                runName = scheduleList.firstOrNull()?.runName,
                startTime = start,
                endTime = end,
                fromStop = scheduleList.firstOrNull()?.busStops?.firstOrNull()?.name,
                toStop = stopAddr,
                scheduleSize = scheduleList.size,
                routeDataSize = 0
            )
        )
        TripLog.mark("user repositioning")

        // ===== MQTT (INTI FIX) =====
        val token = intent.getStringExtra("ACCESS_TOKEN")
        if (!token.isNullOrBlank()) {
            mqttManager = MqttManager(username = token)
            mqttManager.connect { ok ->
                if (ok) {
                    publishRepAttributes()
                    mqttHandler.post(repHeartbeat)
                } else {
                    FileLogger.e("RepActivity", "MQTT connect failed")
                }
            }
        }

        // ===== Map =====
        mapView = binding.map
        openOfflineMap()
        addStopMarker(LatLong(stopLat, stopLon))
        startLocation()
        startClock()

        // ===== Back button =====
        binding.backButton.setOnClickListener {
            if (speedKmh > 5f) {
                Toast.makeText(this, "❌ Bus must be slower than 5 km/h.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            finish()
        }
    }

    // =========================================================
    // MQTT
    // =========================================================

    private fun publishRepAttributes() {
        val payload = """
            {
              "currentTripLabel": "${repLabel.replace("\"", "\\\"")}",
              "activityState": "REP",
              "updatedAt": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        try {
            mqttManager.publish(MqttHelper.ATTR_TOPIC, payload)
        } catch (e: Exception) {
            FileLogger.e("RepActivity", "Publish failed: ${e.message}")
        }
    }

    private val repHeartbeat = object : Runnable {
        override fun run() {
            publishRepAttributes()
            mqttHandler.postDelayed(this, 15_000)
        }
    }

    // =========================================================
    // Location
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        fused = LocationServices.getFusedLocationProviderClient(this)

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        ).build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc: Location = result.lastLocation ?: return
                speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
                updateBusMarker(LatLong(loc.latitude, loc.longitude))
            }
        }

        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    // =========================================================
    // Map helpers
    // =========================================================

    private fun openOfflineMap() {
        val hidden = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val mapFile = File(File(hidden, ".vlrshiddenfolder"), "new-zealand.map")
        if (!mapFile.exists()) return

        val cache = AndroidUtil.createTileCache(
            this, "repCache",
            mapView.model.displayModel.tileSize, 1f,
            mapView.model.frameBufferModel.overdrawFactor
        )

        val layer = TileRendererLayer(
            cache,
            MapFile(mapFile),
            mapView.model.mapViewPosition,
            AndroidGraphicFactory.INSTANCE
        ).apply {
            setXmlRenderTheme(InternalRenderTheme.DEFAULT)
        }

        mapView.layerManager.layers.add(layer)
        mapView.model.mapViewPosition.zoomLevel = 17
        mapView.model.mapViewPosition.center = LatLong(stopLat, stopLon)
    }

    private fun addStopMarker(pos: LatLong) {
        val drawable = DrawableHelper.createBusStopSymbol(this, 0, 1, false)
        val sizePx = (resources.displayMetrics.density * 30f).toInt()
        val bmp = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)

        val mfBmp = AndroidBitmap(bmp)
        stopMarker = Marker(pos, mfBmp, 0, -mfBmp.height / 2)
        mapView.layerManager.layers.add(stopMarker)
    }

    private fun updateBusMarker(pos: LatLong) {
        if (busIcon == null) {
            val drawable = AppCompatResources.getDrawable(this, R.drawable.ic_bus_symbol)
                ?: return
            val sizePx = (resources.displayMetrics.density * 32f).toInt()
            val bmp = createBitmap(sizePx, sizePx)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            busIcon = AndroidBitmap(bmp)
        }

        if (busMarker == null) {
            busMarker = Marker(pos, busIcon!!, 0, -busIcon!!.height / 2)
            mapView.layerManager.layers.add(busMarker)
        } else {
            busMarker!!.latLong = pos
        }
        mapView.invalidate()
    }

    // =========================================================
    // UI helpers
    // =========================================================

    private fun startStaticCountdown(startHHmm: String, endHHmm: String) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val s = runCatching { fmt.parse(startHHmm) }.getOrNull()
        val e = runCatching { fmt.parse(endHHmm) }.getOrNull()
        if (s == null || e == null) return

        var sec = ((e.time - s.time) / 1000).coerceAtLeast(0)
        ui.post(object : Runnable {
            override fun run() {
                binding.nextTripCountdownTextView.text =
                    "Next Trip: %02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
                if (sec-- > 0) ui.postDelayed(this, 1000)
            }
        })
    }

    private fun startClock() {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        clock.post(object : Runnable {
            override fun run() {
                binding.currentTimeTextView.text = fmt.format(Date())
                clock.postDelayed(this, 1000)
            }
        })
    }

    private fun findLabelById(idName: String): TextView? {
        val id = resources.getIdentifier(idName, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }

    // =========================================================
    // Lifecycle
    // =========================================================

    override fun onDestroy() {
        super.onDestroy()
        mqttHandler.removeCallbacksAndMessages(null)
        ui.removeCallbacksAndMessages(null)
        clock.removeCallbacksAndMessages(null)
        if (::fused.isInitialized) fused.removeLocationUpdates(callback)
        // ❗ DO NOT disconnect MQTT here
    }
}