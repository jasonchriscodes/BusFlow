package com.jason.publisher.main.helpers

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.activity.MapActivity
import com.jason.publisher.main.helpers.MqttHelper
import com.jason.publisher.main.model.BusStop
import org.json.JSONArray
import org.mapsforge.core.graphics.Bitmap as MfBitmap
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import java.io.File
import java.lang.Math.*
import java.util.*
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.graphics.Style
import kotlin.math.pow

class MapViewController(
    private val activity: MapActivity,
    private val binding: ActivityMapBinding,
    private val mqttHelper: MqttHelper,
    private val detailContainer: LinearLayout
) {
    private var routePolyline: Polyline? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var activeSegment: String? = null
    var activeBusToken: String? = null

    /** Monitor other buses every second, logging their count and active/inactive status. */
    @SuppressLint("LongLogTag")
    fun startActivityMonitor() {
        Log.d("MapViewController ActivityMonitor", "▶ startActivityMonitor() called")
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            @SuppressLint("LongLogTag")
            override fun run() {
                val now = System.currentTimeMillis()

                // 1) remove any buses inactive ≥10s
                activity.markerBus.keys
                    .filter { it != activity.token }
                    .forEach { t ->
                        val last = activity.lastSeen[t] ?: 0L
                        if (last != 0L && now - last >= 10_000L) {
                            Log.d("MapViewController ActivityMonitor",
                                "Removing bus marker for token=$t; inactive for ${now - last}ms")
                            binding.map.layerManager.layers.remove(activity.markerBus[t])
                            activity.markerBus.remove(t)
                            activity.prevCoords.remove(t)
                        }
                    }

                val active = activity.markerBus.keys.filter { it != activity.token }
                Log.d("MapViewController ActivityMonitor", "Current active bus markers: $active")

                // 2) refresh the map
                binding.map.invalidate()

                // 3) schedule next check in 1s
                handler.postDelayed(this, 1_000L)
            }
        })
    }

    /**
     * Creates a Mapsforge‐compatible bitmap from a VectorDrawable resource.
     */
    fun createBusIcon(@DrawableRes id: Int, sizeDp: Int = 16): MfBitmap {
        // 1) load the drawable
        val drawable = ResourcesCompat.getDrawable(activity.resources, id, null)!!

        // 2) convert dp → px
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            sizeDp.toFloat(),
            activity.resources.displayMetrics
        ).toInt()

        // 3) create a real Android Bitmap and draw the vector into it
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)

        // 4) wrap it for Mapsforge
        return AndroidBitmap(bmp)
    }


    /**
     * Draws a polyline on the Mapsforge map using the busRoute data.
     */
    @SuppressLint("LongLogTag")
    fun drawPolyline() {
        if (activity.route.isNotEmpty()) {
            val routePoints = activity.route.map { LatLong(it.latitude!!, it.longitude!!) }

            routePolyline?.let { binding.map.layerManager.layers.remove(it) }

            AndroidGraphicFactory.createInstance(activity.application)
            val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                color = Color.BLUE
                strokeWidth = 4f
                setStyle(org.mapsforge.core.graphics.Style.STROKE)
            }

            routePolyline = Polyline(paint, AndroidGraphicFactory.INSTANCE).apply {
                addPoints(routePoints)
            }
            binding.map.layerManager.layers.add(routePolyline)
            binding.map.invalidate()
        }
    }

    /**
     * Loads the offline map from assets and configures the map.
     */
    fun openMapFromAssets() {
        binding.map.mapScaleBar.isVisible = true
        binding.map.setBuiltInZoomControls(true)

        val cache = AndroidUtil.createTileCache(
            activity, "preloadCache",
            binding.map.model.displayModel.tileSize,
            1f,
            binding.map.model.frameBufferModel.overdrawFactor
        )
        // 1) copy it out of assets into cacheDir
        val mapFile = copyAssetToFile("new-zealand.map")

        // 2) now you know it’s there
        if (!mapFile.exists()) {
            Toast.makeText(activity, "Offline map missing", Toast.LENGTH_SHORT).show()
            return
        }

        ioScope.launch {
            val store = try {
                MapFile(mapFile)
            } catch (e: Exception) {
                return@launch
            }
            val layer = TileRendererLayer(
                cache,
                store,
                binding.map.model.mapViewPosition,
                AndroidGraphicFactory.INSTANCE
            ).apply { setXmlRenderTheme(InternalRenderTheme.DEFAULT) }

            withContext(Dispatchers.Main) {
                if (!binding.map.layerManager.layers.contains(layer))
                    binding.map.layerManager.layers.add(layer)

                binding.map.post {
                    binding.map.model.mapViewPosition.setZoomLevel(15)
                    binding.map.model.mapViewPosition.setCenter(
                        LatLong(activity.latitude, activity.longitude)
                    )
                    drawDetectionZones(activity.stops)
                    drawPolyline()
                    addBusStopMarkers(activity.stops)
                    addBusMarker(activity.latitude, activity.longitude)
                    binding.map.invalidate()
                }
            }
        }
    }

    /**
     * Copy a file from assets into cacheDir and return the File handle.
     */
    private fun copyAssetToFile(assetName: String): File {
        val outFile = File(activity.cacheDir, assetName)
        if (!outFile.exists()) {
            activity.assets.open(assetName).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile
    }

    /** Place the bus marker at a given latitude and longitude */
    fun addBusMarker(lat: Double, lon: Double) {
        val pos = LatLong(lat, lon)
        val mf = createBusIcon(R.drawable.ic_bus_symbol, sizeDp = 16)
        activity.busMarker = Marker(LatLong(lat, lon), mf, 0, 0)

        activity.busMarker?.let { binding.map.layerManager.layers.remove(it) }

        activity.busMarker = Marker(pos, mf, 0, 0)
        binding.map.layerManager.layers.add(activity.busMarker)
    }

    /** Adds bus stops to the map. */
    @SuppressLint("LongLogTag")
    fun addBusStopMarkers(busStops: List<com.jason.publisher.main.model.BusStop>) {
        val total = busStops.size
        // desired symbol square in dp
        val desiredDp = 30
        val sizePx = dpToPx(desiredDp)

        busStops.forEachIndexed { idx, stop ->
            val key = "${stop.latitude},${stop.longitude}"
            val isRed = activity.redBusStops.contains(key)

            // 1) get the Drawable from your helper
            val symDrawable: Drawable =
                com.jason.publisher.main.utils.Helper.createBusStopSymbol(
                    activity, idx, total, isRed
                )

            // 2) prepare a square bitmap
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            // 3) compute a centered, aspect-correct destination rect
            val iw = symDrawable.intrinsicWidth
            val ih = symDrawable.intrinsicHeight
            val scale = min(sizePx / iw.toFloat(), sizePx / ih.toFloat())
            val dw = (iw * scale).toInt()
            val dh = (ih * scale).toInt()
            val left = (sizePx - dw) / 2
            val top = (sizePx - dh) / 2
            symDrawable.setBounds(left, top, left + dw, top + dh)

            // 4) draw it
            symDrawable.draw(canvas)

            // 5) wrap for Mapsforge
            val mfBmp: MfBitmap = AndroidBitmap(bmp)

            // 6) add the marker
            val marker = Marker(
                LatLong(stop.latitude!!, stop.longitude!!),
                mfBmp,
                0,
                0
            )
            binding.map.layerManager.layers.add(marker)
        }

        binding.map.invalidate()
    }

    /**
     * Function to add circular markers to represent the detection area for each stop.
     */
    fun drawDetectionZones(
        busStops: List<BusStop>,
        radiusMeters: Double = activity.busStopRadius
    ) {
        // ① remove all old circles
        val layers = binding.map.layerManager.layers
        layers.filterIsInstance<Circle>()
            .forEach { layers.remove(it) }

        // ② draw fresh ones based on passedStops
        busStops.forEach { stop ->
            val passed = activity.passedStops.any {
                it.latitude == stop.latitude && it.longitude == stop.longitude
            }
            val fill   = if (passed) Color.argb(64, 0,255,0) else Color.argb(64,255,0,0)
            val stroke = if (passed) Color.GREEN else Color.RED

            val circle = Circle(
                LatLong(stop.latitude!!, stop.longitude!!),
                radiusMeters.toFloat(),
                AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    color = fill; setStyle(Style.FILL)
                },
                AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    color = stroke; strokeWidth = 2f; setStyle(Style.STROKE)
                }
            )
            layers.add(circle)
        }
        binding.map.invalidate()
    }

    /**
     * Smoothly animate the marker's movement instead of jumping suddenly.
     */
    fun animateMarkerThroughPoints(startIndex: Int, endIndex: Int) {
        val handler = Handler(Looper.getMainLooper())
        val pts = activity.route.subList(startIndex, endIndex + 1)

        var step = 0
        val total = pts.size
        handler.post(object : Runnable {
            override fun run() {
                if (step < total) {
                    val p = pts[step]
                    updateBusMarkerPosition(p.latitude!!, p.longitude!!, activity.bearing)
                    step++
                    handler.postDelayed(this, 500)
                }
            }
        })
    }

    /** Calculates distance between two lat/lon points in meters */
    fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371000.0
        val φ1 = toRadians(lat1)
        val φ2 = toRadians(lat2)
        val dφ = toRadians(lat2 - lat1)
        val dλ = toRadians(lon2 - lon1)
        val a = sin(dφ/2).pow(2) + cos(φ1)*cos(φ2)*sin(dλ/2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1-a))
        return R * c
    }

    /** Find nearest route‐point index for smoothing, etc. */
    fun findNearestBusRoutePoint(currentLat: Double, currentLon: Double): Int {
        var idx = 0
        var minD = Double.MAX_VALUE
        activity.route.forEachIndexed { i, pt ->
            val d = calculateDistance(currentLat, currentLon, pt.latitude!!, pt.longitude!!)
            if (d < minD) { minD = d; idx = i }
        }
        return idx
    }

    /**
     * Move (or create) the bus marker at [lat],[lon], rotated to [bearing],
     * then spin the map so that “up” is always this bus’s heading.
     */
    fun updateBusMarkerPosition(lat: Double, lon: Double, bearing: Float) {
        // 1) publish telemetry & client-attrs
        mqttHelper.publishTelemetryData()
        activity.updateClientAttributes()

        // 2) build a rotated icon for *this* bus
        val newPos  = LatLong(lat, lon)
        val rotated = rotateDrawable(R.drawable.ic_bus_symbol, bearing)

        // 3) remove the old marker (if any)
        activity.busMarker?.let { binding.map.layerManager.layers.remove(it) }

        // 4) add the new, rotated marker
        activity.busMarker = Marker(newPos, rotated, 0, 0)
        binding.map.layerManager.layers.add(activity.busMarker)

        // 5) spin the *map* so that this bus’s bearing is “up”
        binding.map.rotation = -bearing

        // 6) optional: zoom, scale, center
        binding.map.scaleX = 1.9f
        binding.map.scaleY = 1.9f
        binding.map.setCenter(newPos)

        // 7) redraw & update the little detail-panel
        binding.map.invalidate()
        activity.onBusMarkerUpdated()
    }

    /** Rotates any vector‐drawable @id by `angle`° around its center. */
    fun rotateDrawable(@DrawableRes id: Int, angle: Float, sizeDp: Int = 16): MfBitmap {
        val d = ResourcesCompat.getDrawable(activity.resources, id, null)!!
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            sizeDp.toFloat(),
            activity.resources.displayMetrics
        ).toInt()

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // rotate around the center
        canvas.rotate(angle, sizePx / 2f, sizePx / 2f)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(canvas)

        return AndroidBitmap(bmp)
    }

    /** Calculates the bearing between two geographical points. */
    fun calculateBearing(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Float {
        val φ1 = toRadians(lat1)
        val φ2 = toRadians(lat2)
        val Δλ = toRadians(lon2 - lon1)
        val y = sin(Δλ)*cos(φ2)
        val x = cos(φ1)*sin(φ2) - sin(φ1)*cos(φ2)*cos(Δλ)
        return ((toDegrees(atan2(y,x)) + 360) % 360).toFloat()
    }

    /**
     * Retrieves default configuration values for the activity,
     * such as adding other-bus markers.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    fun getDefaultConfigValue() {
        activity.arrBusData.forEachIndexed { idx, bus ->
            val slot = min(idx + 2, 10)
            Log.d("MapViewController getDefaultConfigValue", "📍 getDefaultConfigValue: bus-aid=${bus.accessToken} → ic_bus_symbol$slot")
            val lat = activity.latitude
            val lon = activity.longitude
            val token = bus.accessToken
            val iconRes = activity.resources.getIdentifier(
                "ic_bus_symbol${min(idx + 2, 10)}",
                "drawable",
                activity.packageName
            )
            val icon  = createBusIcon(iconRes)

            if (activity.markerBus.containsKey(token)) {
                // just move the existing one
                activity.markerBus[token]?.latLong = LatLong(lat, lon)
            } else {
                // first time
                val marker = Marker(LatLong(lat, lon), icon, 0, 0)
                binding.map.layerManager.layers.add(marker)
                activity.markerBus[token] = marker
                refreshDetailPanelIcons()
            }
        }
    }

    /**
     * utility to convert dp → px
     */
    private fun dpToPx(dp: Int): Int = TypedValue
        .applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            activity.resources.displayMetrics
        ).toInt()

    /** Wraps the vector drawable into an ImageView, adds with 8dp top-margin */
    private fun addIconToContainer(@DrawableRes id: Int, sizeDp: Int, parent: LinearLayout) {
        val bmp = drawableToBitmap(id, sizeDp)
        val iv = ImageView(activity).apply {
            setImageBitmap(bmp)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dpToPx(8)
            }
        }
        parent.addView(iv)
    }

    /** Inflate a VectorDrawable @id into an Android Bitmap at sizeDp × sizeDp */
    private fun drawableToBitmap(@DrawableRes id: Int, sizeDp: Int): Bitmap {
        val drawable = ResourcesCompat.getDrawable(activity.resources, id, null)!!
        val sizePx = dpToPx(sizeDp)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }


    /** Call this any time you re-draw or move markers on the map */
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    fun refreshDetailPanelIcons() {
        activity.runOnUiThread {
            detailContainer.removeAllViews()

            val zoom = binding.map.model.mapViewPosition.zoomLevel.toDouble()
            val bb   = binding.map.boundingBox
            val selfToken = activity.token

            // Other buses that are currently drawn AND on screen
            val visibleOthers = activity.markerBus
                .filter { (t, m) -> t != selfToken &&
                        bb.contains(m.latLong.latitude, m.latLong.longitude) }
                .keys
                .toList()

            // If zoomed way in or there are no other online/visible buses → show only self
            val displayOrder = if (zoom > 25.0 || visibleOthers.isEmpty()) {
                listOf(selfToken)
            } else {
                // Prefer the active bus if it's visible, else take the first visible other
                val prioritized = activeBusToken?.takeIf { it in visibleOthers }
                    ?: visibleOthers.first()
                listOf(selfToken, prioritized) + visibleOthers.filter { it != prioritized }
            }

            displayOrder.forEachIndexed { idx, token ->
                // If we're in deep zoom or there are no others, skip any non-self rows
                if (idx >= 1 && (zoom > 25.0 || visibleOthers.isEmpty())) return@forEachIndexed

                val iconName = if (idx == 0) "ic_bus_symbol"
                else "ic_bus_symbol${(idx + 1).coerceAtMost(10)}"
                val iconRes  = activity.resources.getIdentifier(iconName, "drawable", activity.packageName)

                // Labels:
                // - self: current trip label if available
                // - others: stored label only; if missing/blank, skip (no "---")
                val label: String = if (token == selfToken) {
                    activeSegment ?: selfToken
                } else {
                    val lbl = activity.otherBusLabels[token]
                    if (lbl.isNullOrBlank()) return@forEachIndexed
                    lbl
                }

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val pad = dpToPx(8)
                    setPadding(pad, pad/2, pad, pad/2)
                }

                val iv = ImageView(activity).apply {
                    setImageResource(iconRes)
                    layoutParams = LinearLayout.LayoutParams(dpToPx(16), dpToPx(16))
                }
                val tv = TextView(activity).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dpToPx(8), 0, 0, 0)
                }

                row.addView(iv)
                row.addView(tv)
                detailContainer.addView(row)

                if (idx < displayOrder.lastIndex) {
                    detailContainer.addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dpToPx(1)).apply {
                            topMargin = dpToPx(4); bottomMargin = dpToPx(4)
                        }
                        setBackgroundColor(Color.LTGRAY)
                    })
                }
            }

            if (visibleOthers.isEmpty() && !isReallyOnline()) {
                val hint = TextView(activity).apply {
                    text = "Other bus tracking is not available in offline mode"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.GRAY)
                    setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(8))
                    gravity = Gravity.CENTER
                }
                detailContainer.addView(hint)
            }
        }
    }

    /**
     * Returns true if the system’s current network is both capable of, and validated for, Internet.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun isReallyOnline(): Boolean {
        val cm = activity.connectivityManager
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)?.run {
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false
    }
}