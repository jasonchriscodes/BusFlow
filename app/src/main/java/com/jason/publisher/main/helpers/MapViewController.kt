package com.jason.publisher.main.helpers

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.activity.MapActivity
import com.jason.publisher.main.helpers.MqttHelper
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
import kotlin.math.pow

class MapViewController(
    private val activity: MapActivity,
    private val binding: ActivityMapBinding,
    private val mqttHelper: MqttHelper
) {
    private var routePolyline: Polyline? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Monitor other buses every second, logging their count and active/inactive status. */
    fun startActivityMonitor() {
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()

                activity.markerBus.keys
                    .filter { it != activity.token }
                    .forEach { t ->
                        val last = activity.lastSeen[t] ?: 0L
                        if (last != 0L && now - last >= 10_000L) {
                            binding.map.layerManager.layers.remove(activity.markerBus[t])
                            activity.markerBus.remove(t)
                            activity.prevCoords.remove(t)
                        }
                    }

                binding.map.invalidate()
                handler.postDelayed(this, 1_000L)
            }
        })
    }

    /**
     * Pick the right bus icon based on AID (or bus name).
     */
    fun getBusIconFor(aid: String): MfBitmap {
        val res = if (aid == activity.aid) R.drawable.ic_bus_symbol else R.drawable.ic_bus_symbol2
        return createBusIcon(res)
    }

    /** Clears any existing bus data from the map and other UI elements. */
    fun clearBusData() {
        binding.map.invalidate()
        activity.markerBus.clear()
    }

    /**
     * Creates a Mapsforge‐compatible bitmap from a VectorDrawable resource.
     */
    fun createBusIcon(@DrawableRes id: Int): MfBitmap {
        val drawable = ResourcesCompat.getDrawable(activity.resources, id, null)!!
        val size = 64
        drawable.setBounds(0, 0, size, size)
        return AndroidGraphicFactory.convertToBitmap(drawable)
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
                strokeWidth = 8f
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
        val mapFile = File(activity.cacheDir, "new-zealand.map")
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
                    binding.map.model.mapViewPosition.setZoomLevel(16)
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

    /** Place the bus marker at a given latitude and longitude */
    fun addBusMarker(lat: Double, lon: Double) {
        val pos = LatLong(lat, lon)
        val bm = ResourcesCompat.getDrawable(activity.resources, R.drawable.ic_bus_symbol, null)!!
        val mf = AndroidGraphicFactory.convertToBitmap(bm)

        activity.busMarker?.let { binding.map.layerManager.layers.remove(it) }

        activity.busMarker = Marker(pos, mf, 0, 0)
        binding.map.layerManager.layers.add(activity.busMarker)
    }

    /** Adds bus stops to the map. */
    @SuppressLint("LongLogTag")
    fun addBusStopMarkers(busStops: List<com.jason.publisher.main.model.BusStop>) {
        val total = busStops.size
        busStops.forEachIndexed { idx, stop ->
            val key = "${stop.latitude},${stop.longitude}"
            val isRed = activity.redBusStops.contains(key)
            val sym = com.jason.publisher.main.utils.Helper.createBusStopSymbol(
                activity, idx, total, isRed
            )
            val bmp = AndroidGraphicFactory.convertToBitmap(sym)

            val m = Marker(LatLong(stop.latitude!!, stop.longitude!!), bmp, 0, 0)
            binding.map.layerManager.layers.add(m)
        }
        binding.map.invalidate()
    }

    /**
     * Function to add circular markers to represent the detection area for each stop.
     */
    fun drawDetectionZones(
        busStops: List<com.jason.publisher.main.model.BusStop>,
        radiusMeters: Double = activity.busStopRadius
    ) {
        busStops.forEach { stop ->
            val passed = activity.passedStops.any {
                it.latitude == stop.latitude && it.longitude == stop.longitude
            }
            val fill = if (passed) Color.argb(64, 0, 255, 0) else Color.argb(64, 255, 0, 0)
            val circle = Circle(
                LatLong(stop.latitude!!, stop.longitude!!),
                radiusMeters.toFloat(),
                AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    color = fill; setStyle(org.mapsforge.core.graphics.Style.FILL)
                },
                AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    color = if (passed) Color.GREEN else Color.RED
                    strokeWidth = 2f; setStyle(org.mapsforge.core.graphics.Style.STROKE)
                }
            )
            binding.map.layerManager.layers.add(circle)
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
     * Move the bus marker dynamically with updated bearing.
     */
    fun updateBusMarkerPosition(lat: Double, lon: Double, bearing: Float) {
        val newPos = LatLong(lat, lon)
        mqttHelper.publishTelemetryData()
        activity.updateClientAttributes()
        mqttHelper.sendRequestAttributes()

        val rotated = rotateDrawable(bearing)
        activity.busMarker?.let { binding.map.layerManager.layers.remove(it) }

        activity.busMarker = Marker(newPos, rotated, 0, 0)
        binding.map.layerManager.layers.add(activity.busMarker)

        binding.map.rotation = -bearing
        binding.map.scaleX = 1f; binding.map.scaleY = 1f
        binding.map.setCenter(newPos)
        binding.map.invalidate()

        activity.onBusMarkerUpdated()
    }

    /** Rotates the bus symbol drawable based on the given angle. */
    fun rotateDrawable(angle: Float): MfBitmap {
        val d = ResourcesCompat.getDrawable(activity.resources, R.drawable.ic_bus_symbol, null)
            ?: return AndroidBitmap(Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888))
        val bmp = Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0,0,canvas.width,canvas.height); d.draw(canvas)
        val matrix = android.graphics.Matrix().apply { postRotate(angle) }
        val rot = Bitmap.createBitmap(bmp,0,0,bmp.width,bmp.height,matrix,true)
        return AndroidBitmap(rot)
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
    fun getDefaultConfigValue() {
        activity.arrBusData = activity.config!!.filter { it.aid != activity.aid }
        activity.arrBusData.forEach { bus ->
            val pos = LatLong(activity.latitude, activity.longitude)
            val icon = createBusIcon(R.drawable.ic_bus_symbol2)
            val marker = Marker(pos, icon, 0, 0)
            binding.map.layerManager.layers.add(marker)
            activity.markerBus[bus.accessToken] = marker
        }
    }
}
