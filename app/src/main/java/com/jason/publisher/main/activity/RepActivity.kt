package com.jason.publisher.main.activity

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.location.Location
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.utils.FileLogger
import com.jason.publisher.main.utils.Helper
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class RepActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding

    // Stop payload from ScheduleActivity
    private var stopLat = 0.0
    private var stopLon = 0.0
    private var stopName = "Reposition Stop"
    private var stopAddr = "Unknown"

    // Schedule payload
    private var scheduleList: List<ScheduleItem> = emptyList()

    // Map objects
    private lateinit var mapView: MapView
    private var busMarker: Marker? = null
    private var stopMarker: Marker? = null
    private var legPolyline: Polyline? = null
    private var busIcon: org.mapsforge.core.graphics.Bitmap? = null

    // Location
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var request: LocationRequest
    private lateinit var callback: LocationCallback

    // Countdown / time
    private val ui = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private val clock = Handler(Looper.getMainLooper())
    private var clockRunnable: Runnable? = null
    private var speedKmh: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FileLogger.d("RepActivity", "onCreate")

        // ---- read extras ----
        stopLat = intent.getDoubleExtra("REP_STOP_LAT", 0.0)
        stopLon = intent.getDoubleExtra("REP_STOP_LON", 0.0)
        stopName = intent.getStringExtra("REP_STOP_NAME") ?: "Reposition Stop"
        stopAddr = intent.getStringExtra("REP_STOP_ADDR") ?: stopName
        @Suppress("UNCHECKED_CAST")
        scheduleList = (intent.getSerializableExtra("FIRST_SCHEDULE_ITEM") as? ArrayList<ScheduleItem>) ?: arrayListOf()

        // ---- UI changes ----
        // Label “Upcoming Stop” → “Reposition Stop”
        findLabelById("upcomingBusStopTitleTextView")?.text = "Reposition Stop"
        binding.upcomingBusStopTextView.text = stopAddr

        // Show Start & End (from data, not current time)
        val start = scheduleList.firstOrNull()?.startTime ?: "--:--"
        val end   = scheduleList.firstOrNull()?.endTime   ?: "--:--"
        binding.tripEndTimeTextView.text = "$end:00"

        // Replace “Please wait…” with countdown of end - start
        startStaticCountdown(start, end)

        // Change label: Upcoming Stop → Reposition Stop
        findLabelById("upcomingStopLabel")?.text = "Reposition Stop:"

        // (These ids exist in your binding; if a title exists we hide that too)
        findLabelById("scheduleStatusText")?.apply {
            text = "Repositioning..."
            visibility = View.VISIBLE
            android.R.color.white
        }
        val icon = findViewById<ImageView>(R.id.scheduleAheadIcon)
        icon.setImageResource(R.drawable.ic_reposition)
// make it white
        icon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        findViewById<ImageView>(R.id.scheduleAheadIcon).setImageResource(R.drawable.ic_reposition)

        // Put "Until next Trip:" before the RIGHT countdown
        // Try a few likely ids safely (only the one that exists will be set)
        findLabelById("nextTripCountdownTitleTextView")?.text = "Until next Trip:"
        findLabelById("nextTripTitleTextView")?.text        = "Until next Trip:"
        findLabelById("nextTripLabelTextView")?.text        = "Until next Trip:"

        // Keep the address only (no distance)
        binding.upcomingBusStopTextView.text = stopAddr

        // Map + markers
        mapView = binding.map
        openOfflineMap()
        addStopMarker(
            LatLong(stopLat, stopLon),
            stopIndex = 0,
            totalStops = 1,
            isRed = false
        )

        // Back button as in MapActivity
        binding.backButton.setOnClickListener {
            if (speedKmh > 5.0f) {  // >5 km/h considered moving
                Toast.makeText(this, "❌ Bus must be moving slower than 5 km/h before ending the trip.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Number pad confirmation (0000) with autofill disabled, same as MapActivity
            val dlgBuilder = androidx.appcompat.app.AlertDialog.Builder(this)
            val numberPadView = layoutInflater.inflate(R.layout.dialog_number_pad, null)
            val numberPadInput = numberPadView.findViewById<android.widget.EditText>(R.id.numberPadInput)

            // 1) numeric input + mask, not a credential field
            numberPadInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            numberPadInput.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()

            // 2) turn off saving & autofill on the field
            numberPadInput.setSaveEnabled(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                numberPadInput.setAutofillHints(null)
                androidx.core.view.ViewCompat.setImportantForAutofill(numberPadInput, android.view.View.IMPORTANT_FOR_AUTOFILL_NO)
            }
            numberPadInput.imeOptions = numberPadInput.imeOptions or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

            dlgBuilder.setView(numberPadView)
                .setTitle("Enter Passcode")
                .setPositiveButton("Confirm") { _, _ ->
                    val enteredCode = numberPadInput.text.toString()
                    if (enteredCode == "0000") {
                        // mirror MapActivity: go back to ScheduleActivity and clear stack
                        val intent = android.content.Intent(this, com.jason.publisher.main.activity.ScheduleActivity::class.java)
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this, "❌ Incorrect code. Please enter 0000.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }

            val dialog = dlgBuilder.create()
            dialog.setOnShowListener {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    dialog.window?.decorView?.importantForAutofill =
                        android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    // cancel any inline autofill suggestions
                    getSystemService(android.view.autofill.AutofillManager::class.java)?.cancel()
                }
            }
            dialog.show()
        }

        // Live GPS → draw/shorten the single leg to the stop
        startLocation()

        startCurrentClock()

        refreshDetailPanelForRep()
    }

    // ---- helpers ----

    /** Find a label TextView by name if it exists in your layout */
    private fun findLabelById(idName: String): TextView? {
        val id = resources.getIdentifier(idName, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }

    /** Show countdown of (endTime - startTime) and tick it down every second. */
    private fun startStaticCountdown(startHHmm: String, endHHmm: String) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val start = runCatching { fmt.parse(startHHmm) }.getOrNull()
        val end   = runCatching { fmt.parse(endHHmm) }.getOrNull()

        fun render(sec: Long): String {
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return "%02d:%02d:%02d".format(h, m, s)
        }

        // If we can’t parse, still show the label with zeros.
        if (start == null || end == null) {
            binding.nextTripCountdownTextView.text = "Next Trip: 00:00:00"
            return
        }

        var remaining = ((end.time - start.time) / 1000).coerceAtLeast(0) // seconds

        countdownRunnable?.let { ui.removeCallbacks(it) }
        countdownRunnable = object : Runnable {
            override fun run() {
                binding.nextTripCountdownTextView.text = "Next Trip: ${render(remaining)}"
                if (remaining > 0) { remaining--; ui.postDelayed(this, 1000) }
            }
        }
        ui.post(countdownRunnable!!)
    }

    /** Load offline .map exactly like your ScheduleActivity preload */
    private fun openOfflineMap() {
        val hidden = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val mapFile = File(File(hidden, ".vlrshiddenfolder"), "new-zealand.map")
        if (!mapFile.exists()) {
            Toast.makeText(this, "Offline map not found.", Toast.LENGTH_LONG).show()
            return
        }

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
        ).apply { setXmlRenderTheme(InternalRenderTheme.DEFAULT) }

        mapView.layerManager.layers.add(layer)
        mapView.model.mapViewPosition.setZoomLevel(17)
        mapView.model.mapViewPosition.center = LatLong(stopLat, stopLon)
    }

    /** Place the single stop marker (the reposition stop) */
    private fun addStopMarker(pos: LatLong, stopIndex: Int, totalStops: Int, isRed: Boolean) {
        // 1) build the Drawable symbol
        val symDrawable = Helper.createBusStopSymbol(this, stopIndex, totalStops, isRed)

        // 2) pick a square size (dp → px) and render the Drawable into an Android Bitmap
        val sizePx = (resources.displayMetrics.density * 30f).toInt() // ~30dp
        val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)

        // center/fit the source drawable into our square (keeps aspect)
        val iw = symDrawable.intrinsicWidth
        val ih = symDrawable.intrinsicHeight
        val scale = kotlin.math.min(sizePx / iw.toFloat(), sizePx / ih.toFloat())
        val dw = (iw * scale).toInt()
        val dh = (ih * scale).toInt()
        val left = (sizePx - dw) / 2
        val top  = (sizePx - dh) / 2
        symDrawable.setBounds(left, top, left + dw, top + dh)
        symDrawable.draw(canvas)

        // 3) wrap as a Mapsforge bitmap and place the marker (anchor bottom-center)
        val mfBmp: org.mapsforge.core.graphics.Bitmap = org.mapsforge.map.android.graphics.AndroidBitmap(bmp)

        stopMarker?.let { mapView.layerManager.layers.remove(it) } // remove old one if any
        stopMarker = Marker(pos, mfBmp, 0, -mfBmp.height / 2)
        mapView.layerManager.layers.add(stopMarker)
        mapView.invalidate()
    }

    /** Begin live location updates and redraw the leg (current → stop) every tick */
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        fused = LocationServices.getFusedLocationProviderClient(this)
        request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        callback = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                r.lastLocation?.let { loc ->
                    // keep existing code…
                    val here = LatLong(loc.latitude, loc.longitude)

                    // 🔹 Track current speed in km/h for the end-trip guard
                    if (loc.hasSpeed()) {
                        speedKmh = (loc.speed * 3.6f)
                    }

                    addOrUpdateBusMarker(here)
                    updateLeg(here)
                }
            }
        }

        // You already ask location permission earlier; assuming granted here
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    /** Draw/shorten the one-segment polyline from `current` to the stop. */
    private fun updateLeg(current: LatLong) {
        // Just keep the camera roughly centered between current and stop
        mapView.model.mapViewPosition.center = LatLong(
            ((current.latitude + stopLat) / 2.0),
            ((current.longitude + stopLon) / 2.0)
        )

        // Do NOT draw a polyline and do NOT append distance text anymore
        // Keep the label as plain address
        binding.upcomingBusStopTextView.text = stopAddr

        mapView.invalidate()
    }

    private fun startCurrentClock() {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        clockRunnable?.let { clock.removeCallbacks(it) }
        clockRunnable = object : Runnable {
            override fun run() {
                binding.currentTimeTextView.text = "${fmt.format(Date())}"
                clock.postDelayed(this, 1000)
            }
        }
        clock.post(clockRunnable!!)
    }

    private fun refreshDetailPanelForRep() {
        val container = binding.detailIconsContainer
        container.removeAllViews()

        // ---- row: self (reposition) ----
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = (resources.displayMetrics.density * 8).toInt()
            setPadding(pad, pad/2, pad, pad/2)
        }

        // icon (same size as MapActivity)
        val iconSize = (resources.displayMetrics.density * 16).toInt()
        val iv = android.widget.ImageView(this).apply {
            setImageResource(com.jason.publisher.R.drawable.ic_bus_symbol)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }

        // label
        val tv = android.widget.TextView(this).apply {
            // Match MapActivity style: 14sp
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding((resources.displayMetrics.density * 8).toInt(), 0, 0, 0)
            text = "Reposition → $stopAddr"
        }

        row.addView(iv)
        row.addView(tv)
        container.addView(row)

        // divider
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 1).toInt()
            ).apply {
                topMargin = (resources.displayMetrics.density * 4).toInt()
                bottomMargin = (resources.displayMetrics.density * 4).toInt()
            }
            setBackgroundColor(android.graphics.Color.LTGRAY)
        }
        container.addView(divider)

        // offline hint (same phrasing MapViewController shows when others aren’t available)
        val hint = android.widget.TextView(this).apply {
            text = "Other bus tracking is not available in this mode"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(android.graphics.Color.GRAY)
            val pad = (resources.displayMetrics.density * 8).toInt()
            setPadding(pad, (resources.displayMetrics.density * 4).toInt(), pad, (resources.displayMetrics.density * 8).toInt())
            gravity = android.view.Gravity.CENTER
        }
        container.addView(hint)
    }

    private fun ensureBusIcon(): org.mapsforge.core.graphics.Bitmap {
        busIcon?.let { return it }

        // Load as Drawable (works for vector + bitmap drawables)
        val drawable = AppCompatResources.getDrawable(this, R.drawable.ic_bus_symbol)
            ?: ContextCompat.getDrawable(this, R.drawable.ic_bus_symbol)
            ?: run {
                // Last-ditch fallback: draw a simple circle so we never crash
                val sizePx = (resources.displayMetrics.density * 32f).toInt().coerceAtLeast(24)
                val fallback = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(fallback)
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    style = android.graphics.Paint.Style.FILL
                }
                val r = sizePx / 2f
                c.drawCircle(r, r, r * 0.85f, p)
                busIcon = org.mapsforge.map.android.graphics.AndroidBitmap(fallback)
                return busIcon!!
            }

        val sizePx = (resources.displayMetrics.density * 32f).toInt().coerceAtLeast(24)
        val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)

        // Optional: tint if you want it white
        // DrawableCompat.setTint(drawable, Color.WHITE)

        // Draw the drawable stretched to our square
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)

        busIcon = org.mapsforge.map.android.graphics.AndroidBitmap(bmp)
        return busIcon!!
    }

    private fun addOrUpdateBusMarker(pos: LatLong) {
        val icon = ensureBusIcon()

        if (busMarker == null) {
            // anchor bottom-center
            busMarker = Marker(pos, icon, 0, -icon.height / 2)
            mapView.layerManager.layers.add(busMarker)
        } else {
            busMarker!!.latLong = pos
        }
        mapView.invalidate()
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownRunnable?.let { ui.removeCallbacks(it) }
        if (::fused.isInitialized) fused.removeLocationUpdates(callback)
        clockRunnable?.let { clock.removeCallbacks(it) }

        // Optional: remove marker layers
        busMarker?.let { mapView.layerManager.layers.remove(it) }
        stopMarker?.let { mapView.layerManager.layers.remove(it) }
        mapView.invalidate()
    }
}

