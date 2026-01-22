package com.jason.publisher.modules.rep.viewmodels

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import com.jason.publisher.R
import com.jason.publisher.main.loggers.LifecycleLogger
import com.jason.publisher.main.model.BusRoute
import com.jason.publisher.main.model.BusStop
import com.jason.publisher.main.ui.DrawableHelper
import com.jason.publisher.modules.map.activities.MapActivity
import com.jason.publisher.modules.map.activities.RepActivity
import com.jason.publisher.modules.map.utils.BUS_STOP_RADIUS
import com.jason.publisher.modules.map.mqtt.helpers.MqttHelper
import com.jason.publisher.modules.rep.mqtt.helpers.RepMqttHelper
import java.io.File
import java.util.HashMap
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

class RepMapViewController(
    private val activity: RepActivity,
    private val mapView: MapView,
    private val mqttHelper: RepMqttHelper,
) {
    private var routePolyline: Polyline? = null
    var activeBusToken: String? = null
    var currentBusMarker: Marker? = null

    var busMarkerRegistry = HashMap<String, Marker>()

    /** remember last time other buses are seen */
    val lastSeenOtherBuses       = mutableMapOf<String, Long>()
    /** remember last lat/lon per token */
    val prevOtherBusCoordinates     = mutableMapOf<String, Pair<Double, Double>>()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activityMonitorHandler: Handler? = null
    private var activityMonitorRunnable: Runnable? = null

    // Cache last position to avoid unnecessary updates
    private var lastMarkerLat = Double.NaN
    private var lastMarkerLon = Double.NaN
    private var lastMarkerBearing = Float.NaN
    private var lastMarkerUpdateTime = 0L

    // Track map layer to ensure it's never removed
    private var mapTileLayer: TileRendererLayer? = null

    companion object {
        /** Minimum 100ms between marker updates for smooth movement */
        private const val MARKER_UPDATE_MIN_INTERVAL_MS = 100L
    }

    /** Monitor other buses every second, logging their count and active/inactive status. */
    @SuppressLint("LongLogTag")
    fun startActivityMonitor() {
        // Stop existing monitor first
        stopActivityMonitor()

        activityMonitorHandler = Handler(Looper.getMainLooper())
        activityMonitorRunnable = object : Runnable {
            @SuppressLint("LongLogTag")
            override fun run() {
                try {
                    val now = System.currentTimeMillis()
                    var removedCount = 0

                    // Get valid bus tokens from arrBusData to ensure we only track valid buses
                    val validBusTokens = activity.viewModel.arrBusData.map { it.accessToken }.toSet()

                    // 1) remove any buses that are no longer in arrBusData (orphaned markers)
                    busMarkerRegistry.keys
                        .filter { it != activity.viewModel.token && it !in validBusTokens }
                        .forEach { t ->
                            val label = activity.otherBusLabels[t] ?: "Unknown"
                            val destination = label.split("→").getOrNull(1)?.trim() ?: "Unknown"

                            LifecycleLogger.logOtherBusRemoved(
                                token = t,
                                label = label,
                                destination = destination,
                                reason = "not_in_arrBusData"
                            )

                            removeBusMarker(t)
                            removedCount++
                        }

                    // 2a) Remove buses that don't have valid currentTripLabel (haven't started)
                    // This ensures buses that haven't started are removed even if they have markers
                    busMarkerRegistry.keys
                        .filter { it != activity.viewModel.token && it in validBusTokens }
                        .forEach { t ->
                            val label = activity.otherBusLabels[t]
                            if (label.isNullOrBlank()) {
                                // Bus doesn't have currentTripLabel - remove marker
                                removeBusMarker(t)
                                removedCount++
                            } else if (label.contains("Break", ignoreCase = true)) {
                                // Remove marker for buses on break but keep label for detail panel
                                // Bus on break should appear in detail panel but not on map
                                val marker = busMarkerRegistry[t]
                                marker?.let {
                                    mapView.layerManager.layers.remove(it)
                                }
                                busMarkerRegistry.remove(t)
                                removedCount++
                            }
                        }

                    // 2b) remove any buses inactive ≥2 minutes (reduced from 5 minutes for faster cleanup)
                    // Bus that was started but app was closed will have currentTripLabel but no recent location updates
                    busMarkerRegistry.keys
                        .filter { it != activity.viewModel.token && it in validBusTokens }
                        .forEach { t ->
                            val last = lastSeenOtherBuses[t] ?: 0L
                            // Use 2 minutes to quickly remove buses that closed app
                            // This ensures buses that were started but app was closed are removed quickly
                            if (last != 0L && now - last >= 2 * 60 * 1000L) {
                                // Log bus removal with destination info
                                val label = activity.otherBusLabels[t] ?: "Unknown"
                                val destination = label.split("→").getOrNull(1)?.trim() ?: "Unknown"

                                LifecycleLogger.logOtherBusRemoved(
                                    token = t,
                                    label = label,
                                    destination = destination,
                                    reason = "inactive_2min"
                                )

                                removeBusMarker(t)
                                removedCount++
                            }
                        }

                    // Only log when buses are removed or count changes significantly
                    if (removedCount > 0) {
                        Log.d("MapViewController", "Removed $removedCount inactive bus marker(s)")
                    }

                    // 2) refresh the map
                    mapView.invalidate()

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
    private fun createBusIcon(@DrawableRes id: Int, sizeDp: Int = 16): Bitmap {
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
    fun drawPolyline(route: List<BusRoute>) {
        if (route.isNotEmpty()) {
            val routePoints = route.map { LatLong(it.latitude!!, it.longitude!!) }

            routePolyline?.let { mapView.layerManager.layers.remove(it) }

            AndroidGraphicFactory.createInstance(activity.application)
            val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                color = Color.BLUE
                strokeWidth = 4f
                setStyle(Style.STROKE)
            }

            routePolyline = Polyline(paint, AndroidGraphicFactory.INSTANCE).apply {
                addPoints(routePoints)
            }
            mapView.layerManager.layers.add(routePolyline)
            mapView.invalidate()
        }
    }

    fun clearPolyline() {
        routePolyline?.let {
            mapView.layerManager.layers.remove(it)
            mapView.invalidate()
        }
    }

    /**
     * Loads the offline map from assets and configures the map.
     */
    fun openMapFromAssets() {
        mapView.mapScaleBar.isVisible = true
        mapView.setBuiltInZoomControls(true)

        val cache = AndroidUtil.createTileCache(
            activity, "preloadCache",
            mapView.model.displayModel.tileSize,
            1f,
            mapView.model.frameBufferModel.overdrawFactor
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
                    Toast.makeText(
                        activity,
                        "Error loading map file: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            val renderTheme = AssetsRenderTheme(
                activity.assets,
                "",
                "custom_render_theme.xml"
            )
            val layer = TileRendererLayer(
                cache,
                store,
                mapView.model.mapViewPosition,
                AndroidGraphicFactory.INSTANCE
            ).apply { setXmlRenderTheme(renderTheme) }

            withContext(Dispatchers.Main) {
                try {
                    // Ensure map layer is added correctly and map is rendered
                    // Remove any existing TileRendererLayer first to avoid duplicates
                    val existingLayers =
                        mapView.layerManager.layers.filterIsInstance<TileRendererLayer>()
                    existingLayers.forEach { mapView.layerManager.layers.remove(it) }

                    // Store reference to map layer
                    mapTileLayer = layer

                    // Add the new layer at index 0 (bottom layer, so map tiles render first)
                    mapView.layerManager.layers.add(0, layer)

                    mapView.post {
                        try {
                            mapView.model.mapViewPosition.zoomLevel = 15
                            mapView.model.mapViewPosition.center =
                                LatLong(activity.viewModel.latitude, activity.viewModel.longitude)
                            drawDetectionZones(activity.viewModel.stops, activity.viewModel.passedStops)
                            drawPolyline(activity.viewModel.route)
                            addBusStopMarkers(activity.viewModel.stops)
                            addBusMarker(activity.viewModel.latitude, activity.viewModel.longitude)

                            // Force map to render by invalidating
                            mapView.invalidate()

                            // Additional invalidate after a short delay to ensure map renders
                            mapView.postDelayed({
                                mapView.invalidate()
                                Log.d("MapViewController", "✅ Map layer added and invalidated")
                            }, 200)
                        } catch (e: Exception) {
                            Log.e("MapViewController", "Error setting up map: ${e.message}", e)
                            // Ensure map is invalidated even on error
                            mapView.invalidate()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MapViewController", "Error adding map layer: ${e.message}", e)
                    // Ensure map is invalidated even on error
                    mapView.invalidate()
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
    private fun addBusMarker(lat: Double, lon: Double) {
        val pos = LatLong(lat, lon)
        val mf = createBusIcon(R.drawable.ic_bus_symbol, sizeDp = 16)
        currentBusMarker = Marker(LatLong(lat, lon), mf, 0, 0)

        currentBusMarker?.let { mapView.layerManager.layers.remove(it) }

        currentBusMarker = Marker(pos, mf, 0, 0)
        mapView.layerManager.layers.add(currentBusMarker)
    }

    /** Adds bus stops to the map. */
    private fun addBusStopMarkers(busStops: List<BusStop>) {
        val total = busStops.size
        // desired symbol square in dp
        val desiredDp = 30
        val sizePx = activity.dpToPx(desiredDp)

        busStops.forEachIndexed { idx, stop ->
            val key = "${stop.latitude},${stop.longitude}"
            val isRed = activity.viewModel.redBusStops.contains(key)

            // 1) get the Drawable from your helper
            val symDrawable: Drawable =
                DrawableHelper.createBusStopSymbol(
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
            val mfBmp: Bitmap = AndroidBitmap(bmp)

            // 6) add the marker
            val marker = Marker(
                LatLong(stop.latitude!!, stop.longitude!!),
                mfBmp,
                0,
                0
            )
            mapView.layerManager.layers.add(marker)
        }

        mapView.invalidate()
    }

    /**
     * Function to add circular markers to represent the detection area for each stop.
     */
    fun drawDetectionZones(
        busStops: List<BusStop>,
        passedStops: List<BusStop>,
        radiusMeters: Double = BUS_STOP_RADIUS
    ) {
        // remove all old circles
        val layers = mapView.layerManager.layers
        layers.filterIsInstance<Circle>()
            .forEach { layers.remove(it) }

        // draw fresh ones based on passedStops
        busStops.forEach { stop ->
            val passed = passedStops.any {
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
        mapView.invalidate()
    }

    /**
     * Move (or create) the bus marker at [lat],[lon], rotated to [bearing],
     * then spin the map so that "up" is always this bus's heading.
     */
    fun updateBusMarkerPosition(lat: Double, lon: Double, bearing: Float) {
        try {
            val currentTime = System.currentTimeMillis()

            // Only skip heavy operations if position hasn't changed
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
                mqttHelper.publishTelemetryData(activity.viewModel.createTelemetryJson())
                activity.viewModel.updateClientAttributes()
            }

            // 2) build a rotated icon for *this* bus (always update for smooth rotation)
            val newPos  = LatLong(lat, lon)
            val rotated = rotateDrawable(R.drawable.ic_bus_symbol, bearing)

            // 3) Always update marker position (even if position hasn't changed much)
            // This ensures marker and map are always rendered correctly
            if (currentBusMarker != null) {
                currentBusMarker!!.latLong = newPos
                currentBusMarker!!.bitmap = rotated
            } else {
                // 4) Create new marker if it doesn't exist
                currentBusMarker = Marker(newPos, rotated, 0, 0)
                mapView.layerManager.layers.add(currentBusMarker)
            }

            // 5) spin the *map* so that this bus's bearing is "up"
            mapView.rotation = -bearing

            // 6) optional: zoom, scale, center (always update to ensure map is visible)
            mapView.scaleX = 1.9f
            mapView.scaleY = 1.9f
            mapView.setCenter(newPos)

            // Ensure map layer exists before invalidating
            // If map layer is missing, try to reload it
            val hasMapLayer = mapView.layerManager.layers.any { it is TileRendererLayer }
            if (!hasMapLayer && mapTileLayer != null) {
                Log.w("MapViewController", "⚠️ Map layer missing, re-adding...")
                try {
                    if (!mapView.layerManager.layers.contains(mapTileLayer)) {
                        mapView.layerManager.layers.add(0, mapTileLayer)
                    }
                } catch (e: Exception) {
                    Log.e("MapViewController", "Error re-adding map layer: ${e.message}", e)
                }
            }

            // 7) Always invalidate map to prevent white screen
            // This ensures map is always rendered, even if position hasn't changed
            mapView.invalidate()

            if (shouldDoFullUpdate) {
                activity.onBusMarkerUpdated()
            }
        } catch (e: Exception) {
            Log.e("MapViewController", "Error updating bus marker: ${e.message}", e)
            // Ensure map is invalidated even on error to prevent white screen
            try {
                mapView.invalidate()
            } catch (e2: Exception) {
                Log.e("MapViewController", "Error invalidating map: ${e2.message}", e2)
            }
        }
    }

    /**
     *  Remove the bus marker from the map view, map states, and panel detail.
     *  @param invalidateImmediately Whether to invalidate the map immediately after removing the marker.
     *  Defaults to false for efficiency.
     *  */
    fun removeBusMarker(token: String, invalidateImmediately: Boolean = false) {
        busMarkerRegistry[token]?.let {
            mapView.layerManager.layers.remove(it)
            if (invalidateImmediately) mapView.invalidate()
        }
        busMarkerRegistry.remove(token)
        prevOtherBusCoordinates.remove(token)
        lastSeenOtherBuses.remove(token)
        activity.otherBusLabels.remove(token)
    }

    /** Rotates any vector‐drawable @id by `angle`° around its center. */
    fun rotateDrawable(@DrawableRes id: Int, angle: Float, sizeDp: Int = 16): Bitmap {
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

    /**
     * Retrieves default configuration values for the activity,
     * such as adding other-bus markers.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    fun getDefaultConfigValue() {
        activity.viewModel.arrBusData.forEachIndexed { idx, bus ->
            val slot = min(idx + 2, 10)
            Log.d("MapViewController getDefaultConfigValue", "📍 getDefaultConfigValue: bus-aid=${bus.accessToken} → ic_bus_symbol$slot")
            val lat = activity.viewModel.latitude
            val lon = activity.viewModel.longitude
            val token = bus.accessToken
            val iconRes = activity.resources.getIdentifier(
                "ic_bus_symbol${min(idx + 2, 10)}",
                "drawable",
                activity.packageName
            )
            val icon  = createBusIcon(iconRes)

            if (busMarkerRegistry.containsKey(token)) {
                // just move the existing one
                busMarkerRegistry[token]?.latLong = LatLong(lat, lon)
            } else {
                // first time
                val marker = Marker(LatLong(lat, lon), icon, 0, 0)
                mapView.layerManager.layers.add(marker)
                busMarkerRegistry[token] = marker
                activity.panelController.refreshDetailPanelIcons(mapView.model.mapViewPosition.zoomLevel.toDouble())
            }
        }
    }

}