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
import androidx.core.content.res.ResourcesCompat
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.activity.MapActivity
import com.jason.publisher.main.model.BusStop
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
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.graphics.Style
import kotlin.math.abs
import kotlin.math.pow
import androidx.core.graphics.createBitmap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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

    private var activityMonitorHandler: Handler? = null
    private var activityMonitorRunnable: Runnable? = null

    /** Monitor other buses every second, logging their count and active/inactive status. */
    @SuppressLint("LongLogTag")
    fun startActivityMonitor() {
        // ✅ OPTIMIZED: Removed verbose startup log

        // Stop existing monitor first
        stopActivityMonitor()

        activityMonitorHandler = Handler(Looper.getMainLooper())
        activityMonitorRunnable = object : Runnable {
            @SuppressLint("LongLogTag")
            override fun run() {
                try {
                    val now = System.currentTimeMillis()
                    var removedCount = 0

                    // ✅ FIX: Get valid bus tokens from arrBusData to ensure we only track valid buses
                    val validBusTokens = activity.arrBusData.map { it.accessToken }.toSet()

                    // 1) remove any buses that are no longer in arrBusData (orphaned markers)
                    activity.markerBus.keys
                        .filter { it != activity.token && it !in validBusTokens }
                        .forEach { t ->
                            val label = activity.otherBusLabels[t] ?: "Unknown"
                            val destination = label.split("→").getOrNull(1)?.trim() ?: "Unknown"
                            val marker = activity.markerBus[t]

                            com.jason.publisher.main.utils.LifecycleLogger.logOtherBusRemoved(
                                token = t,
                                label = label,
                                destination = destination,
                                reason = "not_in_arrBusData"
                            )

                            marker?.let {
                                binding.map.layerManager.layers.remove(it)
                            }
                            activity.markerBus.remove(t)
                            activity.prevCoords.remove(t)
                            activity.lastSeen.remove(t)
                            activity.otherBusLabels.remove(t)
                            removedCount++
                        }

                    // 2a) ✅ FIX: Remove buses that don't have valid currentTripLabel (haven't started)
                    // This ensures buses that haven't started are removed even if they have markers
                    activity.markerBus.keys
                        .filter { it != activity.token && it in validBusTokens }
                        .forEach { t ->
                            val label = activity.otherBusLabels[t]
                            if (label.isNullOrBlank()) {
                                // Bus doesn't have currentTripLabel - remove marker
                                val marker = activity.markerBus[t]
                                marker?.let {
                                    binding.map.layerManager.layers.remove(it)
                                }
                                activity.markerBus.remove(t)
                                activity.prevCoords.remove(t)
                                activity.lastSeen.remove(t)
                                activity.otherBusLabels.remove(t)
                                removedCount++
                            } else if (label.contains("Break", ignoreCase = true)) {
                                // ✅ FIX: Remove marker for buses on break but keep label for detail panel
                                // Bus on break should appear in detail panel but not on map
                                val marker = activity.markerBus[t]
                                marker?.let {
                                    binding.map.layerManager.layers.remove(it)
                                }
                                activity.markerBus.remove(t)
                                // ✅ FIX: Keep otherBusLabels, prevCoords, and lastSeen for detail panel
                                removedCount++
                            }
                        }

                    // 2b) ✅ FIX: remove any buses inactive ≥2 minutes (reduced from 5 minutes for faster cleanup)
                    // Bus that was started but app was closed will have currentTripLabel but no recent location updates
                    activity.markerBus.keys
                        .filter { it != activity.token && it in validBusTokens }
                        .forEach { t ->
                            val last = activity.lastSeen[t] ?: 0L
                            // ✅ FIX: Use 2 minutes to quickly remove buses that closed app
                            // This ensures buses that were started but app was closed are removed quickly
                            if (last != 0L && now - last >= 2 * 60 * 1000L) {
                                // ✅ ENHANCED: Log bus removal with destination info
                                val label = activity.otherBusLabels[t] ?: "Unknown"
                                val destination = label.split("→").getOrNull(1)?.trim() ?: "Unknown"
                                val marker = activity.markerBus[t]
                                val lat = marker?.latLong?.latitude
                                val lon = marker?.latLong?.longitude

                                com.jason.publisher.main.utils.LifecycleLogger.logOtherBusRemoved(
                                    token = t,
                                    label = label,
                                    destination = destination,
                                    reason = "inactive_2min"
                                )

                                marker?.let {
                                    binding.map.layerManager.layers.remove(it)
                                }
                                activity.markerBus.remove(t)
                                activity.prevCoords.remove(t)
                                activity.lastSeen.remove(t)
                                activity.otherBusLabels.remove(t)
                                removedCount++
                            }
                        }

                    // ✅ OPTIMIZED: Only log when buses are removed or count changes significantly
                    if (removedCount > 0) {
                        Log.d("MapViewController", "Removed $removedCount inactive bus marker(s)")
                    }

                    // 2) refresh the map
                    binding.map.invalidate()

                    // 3) schedule next check in 1s only if handler is still valid
                    activityMonitorHandler?.postDelayed(this, 1_000L)
                } catch (e: Exception) {
                    Log.e("MapViewController", "Error in activity monitor: ${e.message}", e)
                }
            }
        }
        activityMonitorHandler?.post(activityMonitorRunnable!!)
    }

    /**
     * Stop the activity monitor
     */
    fun stopActivityMonitor() {
        activityMonitorHandler?.removeCallbacksAndMessages(null)
        activityMonitorRunnable = null
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
        val bmp = createBitmap(sizePx, sizePx)
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
                setStyle(Style.STROKE)
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
                Log.e("MapViewController", "Error loading map file: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Error loading map file: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val layer = TileRendererLayer(
                cache,
                store,
                binding.map.model.mapViewPosition,
                AndroidGraphicFactory.INSTANCE
            ).apply { setXmlRenderTheme(InternalRenderTheme.DEFAULT) }

            withContext(Dispatchers.Main) {
                try {
                    // ✅ FIX: Ensure map layer is added correctly and map is rendered
                    // Remove any existing TileRendererLayer first to avoid duplicates
                    val existingLayers = binding.map.layerManager.layers.filterIsInstance<TileRendererLayer>()
                    existingLayers.forEach { binding.map.layerManager.layers.remove(it) }

                    // Store reference to map layer
                    mapTileLayer = layer

                    // Add the new layer at index 0 (bottom layer, so map tiles render first)
                    binding.map.layerManager.layers.add(0, layer)

                    binding.map.post {
                        try {
                            binding.map.model.mapViewPosition.zoomLevel = 15
                            binding.map.model.mapViewPosition.center =
                                LatLong(activity.latitude, activity.longitude)
                            drawDetectionZones(activity.stops)
                            drawPolyline()
                            addBusStopMarkers(activity.stops)
                            addBusMarker(activity.latitude, activity.longitude)

                            // ✅ CRITICAL: Force map to render by invalidating
                            binding.map.invalidate()

                            // ✅ FIX: Additional invalidate after a short delay to ensure map renders
                            binding.map.postDelayed({
                                binding.map.invalidate()
                                Log.d("MapViewController", "✅ Map layer added and invalidated")
                            }, 200)
                        } catch (e: Exception) {
                            Log.e("MapViewController", "Error setting up map: ${e.message}", e)
                            // Ensure map is invalidated even on error
                            binding.map.invalidate()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MapViewController", "Error adding map layer: ${e.message}", e)
                    // Ensure map is invalidated even on error
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
    fun addBusStopMarkers(busStops: List<BusStop>) {
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
            val bmp = createBitmap(sizePx, sizePx)
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

    private var animationHandler: Handler? = null
    private var animationRunnable: Runnable? = null

    /**
     * Smoothly animate the marker's movement instead of jumping suddenly.
     */
    fun animateMarkerThroughPoints(startIndex: Int, endIndex: Int) {
        // Cancel any existing animation
        animationHandler?.removeCallbacksAndMessages(null)

        val pts = activity.route.subList(startIndex, minOf(endIndex + 1, activity.route.size))
        if (pts.isEmpty()) return

        var step = 0
        val total = pts.size
        animationHandler = Handler(Looper.getMainLooper())
        animationRunnable = object : Runnable {
            override fun run() {
                try {
                    if (step < total) {
                        val p = pts[step]
                        // Update marker directly without triggering full update cycle
                        val newPos = LatLong(p.latitude!!, p.longitude!!)
                        activity.busMarker?.let { marker ->
                            marker.latLong = newPos
                            binding.map.setCenter(newPos)
                            binding.map.invalidate()
                        }
                        step++
                        animationHandler?.postDelayed(this, 100) // Faster animation (100ms instead of 500ms)
                    } else {
                        // Animation complete, ensure final position is set
                        if (pts.isNotEmpty()) {
                            val finalP = pts[total - 1]
                            updateBusMarkerPosition(finalP.latitude!!, finalP.longitude!!, activity.bearing)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MapViewController", "Error in animation: ${e.message}", e)
                }
            }
        }
        animationHandler?.post(animationRunnable!!)
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
        val a = sin(dφ / 2).pow(2) + cos(φ1) *cos(φ2)*sin(dλ/2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
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
     * then spin the map so that "up" is always this bus's heading.
     */
    // ✅ OPTIMIZATION: Cache last position to avoid unnecessary updates
    private var lastMarkerLat = Double.NaN
    private var lastMarkerLon = Double.NaN
    private var lastMarkerBearing = Float.NaN
    private var lastMarkerUpdateTime = 0L
    private val MARKER_UPDATE_MIN_INTERVAL_MS = 100L // Minimum 100ms between marker updates for smooth movement

    // ✅ FIX: Track map layer to ensure it's never removed
    private var mapTileLayer: TileRendererLayer? = null

    fun updateBusMarkerPosition(lat: Double, lon: Double, bearing: Float) {
        try {
            val currentTime = System.currentTimeMillis()

            // ✅ OPTIMIZED: Only skip heavy operations if position hasn't changed
            // But always update marker position and invalidate map to prevent white screen
            val positionChanged = (lat != lastMarkerLat || lon != lastMarkerLon ||
                    abs(bearing - lastMarkerBearing) > 1.0f)
            val timePassed = (currentTime - lastMarkerUpdateTime) >= MARKER_UPDATE_MIN_INTERVAL_MS

            // Always update marker position and map, but skip expensive operations if not needed
            val shouldDoFullUpdate = positionChanged || timePassed

            if (shouldDoFullUpdate) {
                lastMarkerLat = lat
                lastMarkerLon = lon
                lastMarkerBearing = bearing
                lastMarkerUpdateTime = currentTime

                // 1) publish telemetry & client-attrs (throttled internally to avoid excessive calls)
                mqttHelper.publishTelemetryData()
                activity.updateClientAttributes()
            }

            // 2) build a rotated icon for *this* bus (always update for smooth rotation)
            val newPos  = LatLong(lat, lon)
            val rotated = rotateDrawable(R.drawable.ic_bus_symbol, bearing)

            // 3) ✅ CRITICAL: Always update marker position (even if position hasn't changed much)
            // This ensures marker and map are always rendered correctly
            if (activity.busMarker != null) {
                activity.busMarker!!.latLong = newPos
                activity.busMarker!!.bitmap = rotated
            } else {
                // 4) Create new marker if it doesn't exist
                activity.busMarker = Marker(newPos, rotated, 0, 0)
                binding.map.layerManager.layers.add(activity.busMarker)
            }

            // 5) spin the *map* so that this bus's bearing is "up"
            binding.map.rotation = -bearing

            // 6) optional: zoom, scale, center (always update to ensure map is visible)
            binding.map.scaleX = 1.9f
            binding.map.scaleY = 1.9f
            binding.map.setCenter(newPos)

            // ✅ FIX: Ensure map layer exists before invalidating
            // If map layer is missing, try to reload it
            val hasMapLayer = binding.map.layerManager.layers.any { it is TileRendererLayer }
            if (!hasMapLayer && mapTileLayer != null) {
                Log.w("MapViewController", "⚠️ Map layer missing, re-adding...")
                try {
                    if (!binding.map.layerManager.layers.contains(mapTileLayer)) {
                        binding.map.layerManager.layers.add(0, mapTileLayer)
                    }
                } catch (e: Exception) {
                    Log.e("MapViewController", "Error re-adding map layer: ${e.message}", e)
                }
            }

            // 7) ✅ CRITICAL: Always invalidate map to prevent white screen
            // This ensures map is always rendered, even if position hasn't changed
            binding.map.invalidate()

            if (shouldDoFullUpdate) {
                activity.onBusMarkerUpdated()
            }
        } catch (e: Exception) {
            Log.e("MapViewController", "Error updating bus marker: ${e.message}", e)
            // ✅ FIX: Ensure map is invalidated even on error to prevent white screen
            try {
                binding.map.invalidate()
            } catch (e2: Exception) {
                Log.e("MapViewController", "Error invalidating map: ${e2.message}", e2)
            }
        }
    }

    /** Rotates any vector‐drawable @id by `angle`° around its center. */
    fun rotateDrawable(@DrawableRes id: Int, angle: Float, sizeDp: Int = 16): MfBitmap {
        val d = ResourcesCompat.getDrawable(activity.resources, id, null)!!
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            sizeDp.toFloat(),
            activity.resources.displayMetrics
        ).toInt()

        val bmp = createBitmap(sizePx, sizePx)
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
        val bmp = createBitmap(sizePx, sizePx)
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

            // ✅ FIX: Other buses that are currently drawn, on screen, exist in arrBusData, AND have valid label
            // This ensures we only show buses that are actually in the system and have started a trip
            val validBusTokens = activity.arrBusData.map { it.accessToken }.toSet()

            // ✅ FIX: visibleOthers - buses that are visible on the map (within bounding box)
            val visibleOthers = activity.markerBus
                .filter { (t, m) ->
                    t != selfToken &&
                            t in validBusTokens &&  // ✅ FIX: Only show buses that exist in arrBusData
                            bb.contains(m.latLong.latitude, m.latLong.longitude) &&
                            // ✅ FIX: Only show buses that have a valid label (have started a trip)
                            !activity.otherBusLabels[t].isNullOrBlank() &&
                            // ✅ FIX: Filter out Break schedules - don't show buses that are on break
                            !activity.otherBusLabels[t]!!.contains("Break", ignoreCase = true)
                }
                .keys
                .toList()

            // ✅ FIX: allActiveOthers - ALL buses that have started (for detail panel), not just visible ones
            // Detail panel should show all active buses based on otherBusLabels
            // Only show buses that have a valid label (have started a trip)
            // ✅ FIX: In offline mode, don't show other buses - they can't be tracked
            val allActiveOthers = if (isReallyOnline()) {
                activity.otherBusLabels
                    .filter { (t, label) ->
                        t != selfToken &&
                                t in validBusTokens &&
                                // ✅ FIX: Only show buses that have a valid label (have started a trip)
                                label.isNotBlank()
                        // ✅ FIX: Don't filter Break schedules - bus on break should still be shown in detail panel
                        // Break buses are tracked but marker is not shown on map
                    }
                    .keys
                    .toList()
            } else {
                // ✅ FIX: In offline mode, return empty list - no other buses can be tracked
                emptyList()
            }

            // ✅ FIX: Use allActiveOthers for detail panel (not just visible ones)
            // If zoomed way in or there are no other active buses → show only self
            val displayOrderRaw = if (zoom > 25.0) {
                listOf(selfToken)
            } else {
                val prioritized = activeBusToken?.takeIf { it in allActiveOthers } ?: allActiveOthers.firstOrNull()
                if (prioritized != null) {
                    listOf(selfToken, prioritized) + allActiveOthers.filter { it != prioritized }
                } else {
                    listOf(selfToken)
                }
            }

            // ✅ FIX: Remove duplicate tokens from displayOrder to prevent duplicate panels
            val displayOrderDistinct = displayOrderRaw.distinct()
            if (displayOrderDistinct.size != displayOrderRaw.size) {
                Log.w("MapViewController refreshDetailPanelIcons", "⚠️ WARNING: Found duplicate tokens in displayOrder! Raw: $displayOrderRaw, Distinct: $displayOrderDistinct")
            }

            // ✅ FIX: Show all active buses in detail panel, even if they have the same label
            // Detail panel should display all buses that have started, not filter by label
            val displayOrder = displayOrderDistinct

            displayOrder.forEachIndexed { idx, token ->
                // If we're in deep zoom or there are no others, skip any non-self rows
                if (idx >= 1 && (zoom > 25.0 || allActiveOthers.isEmpty())) {
                    return@forEachIndexed
                }

                val iconName = if (idx == 0) "ic_bus_symbol"
                else "ic_bus_symbol${(idx + 1).coerceAtMost(10)}"
                val iconRes  = activity.resources.getIdentifier(iconName, "drawable", activity.packageName)

                // Labels:
                // - self: current trip label if available
                // - others: stored label only; if missing/blank, skip (no "---")
                val label: String = if (token == selfToken) {
                    val selfLbl = activeSegment ?: selfToken
                    // ✅ FIX: Show self bus even if on Break - it's still an active trip
                    selfLbl
                } else {
                    val lbl = activity.otherBusLabels[token]
                    if (lbl.isNullOrBlank()) {
                        return@forEachIndexed
                    }
                    // ✅ FIX: Show other buses even if on Break - they're still active and should be tracked
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
                    setTextColor(Color.BLACK)
                    // store the token on the view for logging use (not shown to user)
                    tag = token
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
                try {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            activity.logPanelDetailTextOnly()
                        } catch (e: Exception) {
                            Log.w("MapViewController", "Failed to call logPanelDetailTextOnly(): ${e.message}")
                        }
                    }, 120L)
                } catch (e: Exception) {
                    Log.w("MapViewController", "postDelayed logPanelDetailTextOnly failed: ${e.message}")
                }
            }

            if (allActiveOthers.isEmpty() && !isReallyOnline()) {
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

    /** Adds a single stop marker at (lat, lon) using the given drawable resource. */
    fun addStopMarker(lat: Double, lon: Double, @DrawableRes iconRes: Int) {
        // 1) get the drawable from resources
        val drawable = ResourcesCompat.getDrawable(activity.resources, iconRes, null) ?: return

        // 2) render it into an Android Bitmap (size ~24dp)
        val sizePx = dpToPx(24)
        val bmp = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)

        // 3) wrap as a Mapsforge bitmap and add the marker to the map
        val mf = AndroidBitmap(bmp)  // org.mapsforge.core.graphics.Bitmap
        val marker = Marker(
            LatLong(lat, lon), mf, 0, -mf.height / 2
        )
        binding.map.layerManager.layers.add(marker)
        binding.map.invalidate()
    }
}