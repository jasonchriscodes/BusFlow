package com.jason.publisher.modules.map.viewmodels

import android.annotation.SuppressLint
import android.location.Location
import android.os.Environment
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.LifecycleLogger
import com.jason.publisher.main.model.AttributesData
import com.jason.publisher.main.model.BusItem
import com.jason.publisher.main.model.BusRoute
import com.jason.publisher.main.model.BusStop
import com.jason.publisher.main.model.RouteData
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.services.ApiService
import com.jason.publisher.main.services.ApiServiceBuilder
import com.jason.publisher.main.utils.parseTimeToday
import com.jason.publisher.modules.map.`interface`.LocationListener
import com.jason.publisher.modules.map.models.BusStopWithTimingPoint
import com.jason.publisher.modules.map.utils.BUS_STOP_RADIUS
import com.jason.publisher.modules.map.utils.TimeBasedMovingAverageFilterDouble
import com.jason.publisher.modules.map.utils.calculateDistance
import com.jason.publisher.modules.map.utils.formatPanelLabel
import com.jason.publisher.modules.map.utils.getLastScheduledAddress
import com.jason.publisher.modules.map.utils.getNextTripFormattedLabel
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Locale.getDefault
import kotlin.math.abs
import kotlin.toString

class MapViewModel: ViewModel() {
    companion object {
        fun provideFactory(): ViewModelProvider.Factory = object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return (MapViewModel() as T)
            }
        }
    }

    val apiService = ApiServiceBuilder.buildService(ApiService::class.java)

    var latitude = 0.0
    var longitude = 0.0
    var bearing = 0.0F
    var speed = 0.0F
    private var direction = ""
    private var busName = ""

    var aid = ""
    var jsonString = ""
    var token = ""
    var config: List<BusItem>? = emptyList()
    var route: List<BusRoute> = emptyList()
    var stops: List<BusStop> = emptyList()
    var busRouteData: List<RouteData> = emptyList()
    var selectedRouteData: RouteData? = null
    var durationBetweenStops: List<Double> = emptyList()
    var arrBusData: List<BusItem> = emptyList()
    var upcomingStop: String = "Unknown"
    var stopAddress: String = "Unknown"
    val upcomingStopName by lazy { MutableLiveData("Unknown") }

    lateinit var scheduleList: List<ScheduleItem>
    lateinit var scheduleData: List<ScheduleItem>
    val redBusStops = mutableSetOf<String>()

    var apiTimeLocked = false
    var lockedApiTime: String? = null
    var simulationSpeedFactor: Int = 1

    val nextTimingPoint by lazy { MutableLiveData("Unknown") }

    val forceAheadStatus = false
    var lastTimingPointStopAddress: String? = null
    var smoothedSpeed: Float = 0f

    // for self
    private var prevOwnCoordinate: Pair<Double,Double>? = null
    private var lastOwnAttributePostMillis = 0L

    /** Track stops that have been passed */
    val passedStops = mutableListOf<BusStop>()
    /** Keep track of the current stop in order */
    var currentStopIndex = 0
    var hasPassedFirstStopAgain = false
    /** Flag to ensure final stop message only shows once */
    var hasShownFinalStopMessage = false
    /** Store latest ETA calculation data for logging */
    var latestETAData: Map<String, Any?>? = null
    val isCircularRoute: Boolean
        get() = stops.isNotEmpty() && stops.first().address == stops.last().address

    // Variables for tracking logic
    var nearestRouteIndex = 0
    var lastValidLatitude = 0.0
    var lastValidLongitude = 0.0
    var hasPassedFirstStop = false
    val jumpThreshold = 3 // Prevents sudden jumps

    // Cache TextView values to prevent unnecessary updates
    val lastSpeedText by lazy { MutableLiveData<String>() }
    val lastCurrentTimeText by lazy { MutableLiveData<String>() }
    val lastNextTripText by lazy { MutableLiveData<String>() }
    val lastTripEndTimeText by lazy { MutableLiveData<String>() }

    val stopStatusByKey: MutableMap<String, ScheduleStatusManager.StopVisualStatus> = mutableMapOf()
    var lastTimingPointPassedIndex: Int = -1

    private val speedFilter = TimeBasedMovingAverageFilterDouble(
        windowMillis = 15000,
        minValidValue = 1.8  // 1.8 km/h = 0.5 m/s - filter out very low speeds when bus is stopped
    )

    val locationListener = object : LocationListener {
        override fun onLocationUpdate(location: Location) {
            latitude = location.latitude
            longitude = location.longitude
            Log.d("MapViewModel", "locationListener update: $latitude, $longitude")
        }
    }

    fun stopKey(lat: Double?, lon: Double?): String {
        return "${lat ?: 0.0},${lon ?: 0.0}"
    }

    fun isTimingPointStop(stop: BusStop): Boolean {
        val addr = stop.address?.trim()?.lowercase(getDefault()) ?: ""
        val key = "${stop.latitude ?: 0.0},${stop.longitude ?: 0.0}".trim().lowercase(getDefault())

        return redBusStops.any { tp ->
            val t = tp.trim().lowercase(getDefault())
            t == addr || t == key
        }
    }

    fun logReceivedStates() {
        Log.d("MapViewModel", "Received aid: $aid")
        Log.d("MapViewModel", "Received config: ${config.toString()}")
        Log.d("MapViewModel", "Received route: $route")
        Log.d("MapViewModel", "Received stops: $stops")
        Log.d("MapViewModel", "Received durationBetweenStops: $durationBetweenStops")
        Log.d("MapViewModel", "Received busRouteData: $busRouteData")
        Log.d("MapViewModel", "Received scheduleList: $scheduleList")
        Log.d("MapViewModel", "Received scheduleData: $scheduleData")

        FileLogger.d("MapViewModel", "Received aid: $aid")
        FileLogger.d("MapViewModel", "Received config: ${config.toString()}")
        FileLogger.d("MapViewModel", "Received route: $route")
        FileLogger.d("MapViewModel", "Received stops: $stops")
        FileLogger.d("MapViewModel", "Received durationBetweenStops: $durationBetweenStops")
        FileLogger.d("MapViewModel", "Receive busRouteData: $busRouteData")
        FileLogger.d("MapViewModel", "Received scheduleList: $scheduleList")
        FileLogger.d("MapViewModel", "Received scheduleData: $scheduleData")
    }

    fun logMapActivityOpening() {
        val routeInfo = scheduleList.firstOrNull()?.let {
            "${it.startTime} ${it.runName} ${it.busStops.firstOrNull()?.address ?: "?"} → ${it.busStops.lastOrNull()?.address ?: "?"}"
        } ?: "Unknown"
        val currentStopInfo = stops.firstOrNull()?.address ?: "Unknown"
        val upcomingStopInfo = stops.getOrNull(1)?.address ?: "Unknown"
        LifecycleLogger.logMapActivityOpen(routeInfo, currentStopInfo, upcomingStopInfo)
    }

    fun logActivityEntry() {
        val firstSchedule = scheduleList.firstOrNull()
        LifecycleLogger.logActivityEntry("MapActivity", mapOf(
            "routeName" to (firstSchedule?.runName ?: "Unknown"),
            "stopsCount" to stops.size,
            "routePointsCount" to route.size,
            "firstStop" to (stops.firstOrNull()?.address ?: "Unknown"),
            "lastStop" to (stops.lastOrNull()?.address ?: "Unknown"),
            "startTime" to (firstSchedule?.startTime ?: "Unknown"),
            "endTime" to (firstSchedule?.endTime ?: "Unknown"),
            "scheduleDataSize" to scheduleData.size
        ))
    }

    fun logBusStopPass(currentLat: Double, currentLon: Double) {
        val currentStopName = stops.getOrNull(currentStopIndex - 1)?.address ?: "Unknown"
        val upcomingStopName = stops.getOrNull(currentStopIndex)?.address ?: "Unknown"
        val etaData = latestETAData
        LifecycleLogger.logBusStopPass(
            upcomingStop = upcomingStopName,
            currentStop = currentStopName,
            lat = currentLat,
            lon = currentLon,
            speed = speed,
            d1 = (etaData?.get("d1") as? Double),
            d2 = (etaData?.get("d2") as? Double),
            t1 = (etaData?.get("t1") as? Double),
            t2 = (etaData?.get("t2") as? Double),
            effectiveSpeed = (etaData?.get("effectiveSpeed") as? Double),
            scheduleStatusText = (etaData?.get("scheduleStatusText") as? String),
            timingPointTime = (etaData?.get("timingPointTime") as? String),
            predictedArrival = (etaData?.get("predictedArrival") as? String),
            deltaSec = (etaData?.get("deltaSec") as? Int)
        )
    }

    fun getHiddenFolder(): File {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val hiddenFolder = File(documentsDir, ".vlrshiddenfolder")

        if (!hiddenFolder.exists()) {
            hiddenFolder.mkdirs()
        }

        return hiddenFolder
    }

    /** Pretty lat/lon if we don't have an address */
    private fun Double?.fmt6(): String = if (this == null) "?" else String.format(Locale.US, "%.6f", this)

    /** Build the same "lat,lon" key format you stored in redBusStops */
    private fun latLonKey(lat: Double?, lon: Double?): String = "${lat ?: 0.0},${lon ?: 0.0}"

    /** Resolve a display name for a BusStop: address → (fallback) address-from-RouteData → lat,lon */
    private fun stopDisplayName(stop: BusStop): String {
        stop.address?.let { if (it.isNotBlank()) return it }
        val address = findAddressByCoordinates(stop.latitude ?: 0.0, stop.longitude ?: 0.0)
        if (!address.isNullOrBlank()) return address
        return "${stop.latitude.fmt6()},${stop.longitude.fmt6()}"
    }

    /** Log every stop with its index */
    private fun logAllStopsWithIndex(tag: String = "MapActivity") {
        if (stops.isEmpty()) {
            FileLogger.d(tag, "🗺️ Stops: (none)")
            return
        }
        val list = stops.mapIndexed { idx, s -> "[${idx}] ${stopDisplayName(s)}" }
        FileLogger.d(tag, "🗺️ Stops (${stops.size}): ${list.joinToString(", ")}")
    }

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

        val redAddressSet = redBusStops.map { it.trim().lowercase(getDefault()) }.toSet()

        val redIndexed = stops.mapIndexedNotNull { idx, stop ->
            val addressMatch = stop.address?.trim()?.lowercase(getDefault())?.let { it in redAddressSet } == true
            val keyMatch  = latLonKey(stop.latitude, stop.longitude).lowercase(getDefault()) in redAddressSet
            if (addressMatch || keyMatch) idx to stopDisplayName(stop) else null
        }

        if (redIndexed.isEmpty()) {
            FileLogger.d(tag, "🔴 Red timing points: (none matched against current stops)")
        } else {
            val list = redIndexed.joinToString(", ") { (i, name) -> "[${i}] $name" }
            FileLogger.d(tag, "🔴 Red timing points (${redIndexed.size}): $list")
        }
    }

    /** Set route and stops from selectedRouteData */
    fun deriveMapVectors() {
        selectedRouteData?.let { rd ->
            val (r, s, d) = processSingleRouteData(rd)
            route = r            // override flat polyline for this trip
            stops = s            // override stop list for this trip
            durationBetweenStops = d // override per-leg durations
            Log.d("MapViewModel", "✅ Using selectedRouteData with ${stops.size} stops and ${route.size} polyline points")
        } ?: run {
            Log.w("MapViewModel", "⚠️ No selectedRouteData; leaving existing route/stops as-is")
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
            for (coordinate in next.routeCoordinates) {
                val lat = coordinate[1]
                val lon = coordinate[0]
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

    /** Get the appropriate Android ID for a given access token */
    fun getAidOfToken(token: String?): String {
        return when (token) {
            null -> "unknown"
            this.token -> aid
            else -> arrBusData.find { it.accessToken == token }?.aid ?: token.takeLast(6)
        }
    }

    /**
     * extract first schedule item of bus stop to be marked red
     */
    fun extractRedBusStops() {
        redBusStops.clear()
        if (scheduleList.isNotEmpty()) {
            val firstSchedule = scheduleList.first()
            Log.d("MapViewModel", "extractRedBusStops schedule: $firstSchedule")

            // take the first ScheduleItem’s busStops and turn each into a "lat,lon" string
            scheduleList.firstOrNull()?.busStops
                ?.map { "${it.latitude},${it.longitude}" }
                ?.let { redBusStops.addAll(it) }

            val stops = firstSchedule.busStops.map { it.address }
            redBusStops.addAll(stops)
            Log.d("MapViewModel", "extractRedBusStops stops: $stops")
        }
        Log.d("MapViewModel", "Updated Red bus stops: $redBusStops")

        // Print the stop index → name list at startup
        logAllStopsWithIndex()

        // Print the red timing-point indices → names
        logRedStopsWithIndex()
    }

    /**
     * initialize first timing point
     *
     * @return the first timing point if schedule is available and "No Schedule Available" otherwise
     * */
    fun initializeTimingPoint(): String {
        if (scheduleList.isNotEmpty()) {
            val firstSchedule = scheduleList.first()
            var firstTimingPoint = "Unknown"
            firstSchedule.busStops.firstOrNull()?.let {
                firstTimingPoint = (it.time + ":00")
            }

            upcomingStop = firstTimingPoint // Set the upcoming stop initially
            Log.d("MapViewModel", "Initial Timing Point: $firstTimingPoint")
            return firstTimingPoint
        } else {
            return "No Schedule Available"
        }
    }

    /** Updates bus name if AID matches a config entry */
    fun updateBusNameFromConfig() {
        if (config.isNullOrEmpty()) return // No config available

        val matchingBus = config!!.find { it.aid == aid } // Find matching AID

        if (matchingBus != null) {
            busName = matchingBus.bus
            Log.d("MapViewModel", "✅ Bus name updated: $busName for AID: $aid")
        } else {
            Log.e("MapViewModel", "❌ No matching bus found for AID: $aid")
        }
    }

    fun updateSpeedText() {
        val speedText = "Speed: ${"%.2f".format(speed)} km/h"
        if (speedText != lastSpeedText.value) {
            lastSpeedText.postValue(speedText)
        }
    }

    fun updateCurrentTimeText(nowMillis: Long = System.currentTimeMillis()) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", getDefault())
        val currentTimeText = timeFormat.format(nowMillis)
        if (currentTimeText != lastCurrentTimeText.value) {
            lastCurrentTimeText.postValue(currentTimeText)
        }
    }

    fun updateNextTripText(nextTripStartTime: String?) {
        val nextTripText = getNextTripFormattedLabel(nextTripStartTime)
        if (nextTripText != lastNextTripText.value) {
            lastNextTripText.postValue(nextTripText)
        }
    }

    fun updateTripEndTimeText() {
        val endTime = "${scheduleList.first().endTime}:00"
        if (endTime != lastTripEndTimeText.value) {
            lastTripEndTimeText.postValue(endTime)
        }
    }

    /**
     * increase speed factor
     */
    fun speedUp() {
        simulationSpeedFactor++ // Increase by 1 second per tick
        Log.d("MapViewModel", "Speed Up: simulationSpeedFactor is now $simulationSpeedFactor")
    }

    /**
     * decrease speed factor
     */
    fun slowDown() {
        // Ensure never go below 0.
        if (simulationSpeedFactor > 0) {
            simulationSpeedFactor--
        }
        Log.d("MapViewModel", "Slow Down: simulationSpeedFactor is now $simulationSpeedFactor")
    }

    /**
     * Geofence Detection Method
     */
    fun isBusInDetectionArea(currentLat: Double, currentLon: Double, stopLat: Double, stopLon: Double, radius: Double = BUS_STOP_RADIUS): Boolean {
        val distance = calculateDistance(currentLat, currentLon, stopLat, stopLon)
        return distance <= radius
    }

    fun loadLockedApiTime(): String? {
        val timeFormat = SimpleDateFormat("HH:mm:ss", getDefault())

        // Parse the locked time into a Calendar object
        val lockedCal = Calendar.getInstance().apply {
            time = timeFormat.parse(lockedApiTime!!) ?: return null
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
            return updatedFinalApiTime
        } else {
            return lockedApiTime
        }
    }

    /** Finds the nearest upcoming bus stop */
    @SuppressLint("LongLogTag")
    fun getUpcomingBusStopName(lat: Double, lon: Double): String {
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
                val stopAddress = stop.optString("address", "")
                if (stopLat.isNaN() || stopLon.isNaN() || stopAddress.isBlank()) continue
                val d = calculateDistance(lat, lon, stopLat, stopLon)
                if (d < min) { min = d; nearest = stopAddress }
            }
            nearest ?: "Unknown Stop"
        } catch (e: Exception) {
            Log.e("MapActivity getUpcomingBusStopName", "Error: ${e.localizedMessage}", e)
            "Unknown Stop"
        }
    }

    /**
     * Smoothly updates the speed using an time‑based filter moving average.
     */
    fun updateSpeed(newSpeed: Float) {
        // The filter expects a Double value; newSpeed is given in km/h.
        // You can directly pass newSpeed.toDouble() to the filter.
        smoothedSpeed = speedFilter.add(newSpeed.toDouble()).toFloat()
    }

    /**
     * Finds the address for a given latitude and longitude from the busRouteData.
     */
    fun findAddressByCoordinates(latitude: Double, longitude: Double): String? {
        // Check starting point first
        busRouteData.forEach { routeData ->
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

    /** Find nearest route‐point index for smoothing, etc. */
    fun findNearestBusRoutePoint(currentLat: Double, currentLon: Double): Int {
        var idx = 0
        var minD = Double.MAX_VALUE
        route.forEachIndexed { i, pt ->
            val d = calculateDistance(currentLat, currentLon, pt.latitude!!, pt.longitude!!)
            if (d < minD) { minD = d; idx = i }
        }
        return idx
    }

    fun getExpectedDurationForNextSchedule(): Double? {
        val scheduledTimeForFinalStopStr = scheduleList.first().endTime + ":00"
        val finalStopScheduledTime = scheduledTimeForFinalStopStr.parseTimeToday()

        val baseTimeStr = scheduleList.first().startTime + ":00"
        val baseTime = baseTimeStr.parseTimeToday()

        return getExpectedDuration(baseTime, finalStopScheduledTime)
    }

    fun getExpectedDuration(start: Date, end: Date): Double? {
        val finalStop = stops.last()
        val stopLat = finalStop.latitude!!
        val stopLon = finalStop.longitude!!

        val d1 = calculateDistance(latitude, longitude, stopLat, stopLon)

        val finalStopRouteIndex = route.indexOfLast {
            calculateDistance(it.latitude!!, it.longitude!!, stopLat, stopLon) < 30.0
        }.coerceAtLeast(1)
        val d2 = (0 until finalStopRouteIndex).sumOf { i ->
            val p1 = route[i]
            val p2 = route[i + 1]
            calculateDistance(p1.latitude!!, p1.longitude!!, p2.latitude!!, p2.longitude!!)
        }
        if (d2 == 0.0) {
            Log.e("getExpectedDuration", "Total route distance is zero; cannot compute predicted arrival.")
            return null
        }

        val t2 = ((end.time - start.time) / 1000).toDouble()

        // Use smoothed speed with fallback to schedule average
        val minSpeedMps = 0.5
        val maxSpeedMps = 30.0
        val avgSpeedFromSchedule = if (d2 > 0 && t2 > 0) {
            (d2 / t2).coerceIn(minSpeedMps, maxSpeedMps)
        } else {
            minSpeedMps
        }

        val rawSpeedMps = smoothedSpeed / 3.6
        val speedMetersPerSec = when {
            rawSpeedMps >= minSpeedMps && rawSpeedMps <= maxSpeedMps -> rawSpeedMps
            rawSpeedMps < minSpeedMps -> avgSpeedFromSchedule
            else -> avgSpeedFromSchedule.coerceAtMost(maxSpeedMps)
        }

        return d1 / speedMetersPerSec
    }

    fun createTelemetryJson(): JSONObject {
        return JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            put("bearing", bearing)
            put("direction", direction)
            put("speed", speed)
            put("aid", aid)
            put("updatedAt", System.currentTimeMillis())
        }
    }

    /**
     * Updates the client attributes by posting the current location, bearing, speed, and direction data to the server.
     */
    @SuppressLint("LongLogTag")
    fun updateClientAttributes() {
        // Validation: make sure token has been set
        if (token.isBlank()) {
            return
        }

        val now = System.currentTimeMillis()

        // Post a heartbeat even when parked so other tablets can tell this tablet is still active.
        val curr = latitude to longitude
        val coordinateChanged = prevOwnCoordinate != curr
        val heartbeatDue = now - lastOwnAttributePostMillis >= 15_000L
        if (!coordinateChanged && !heartbeatDue) {
            return
        }
        prevOwnCoordinate = curr
        lastOwnAttributePostMillis = now

        val url = ApiService.BASE_URL + "$token/attributes"
        val scheduleJson = Gson().toJson(scheduleData)
        val currentLabel = scheduleList.firstOrNull()?.let { formatPanelLabel(it) }

        val attributesData = AttributesData(
            latitude        = latitude,
            longitude       = longitude,
            bearing         = bearing,
            bearingCustomer = null,
            speed           = speed,
            direction       = direction,
            scheduleData    = scheduleJson,
            currentTripLabel = currentLabel,
            updatedAt       = now
        )

        Log.d("MapActivity updateClientAttributes", "Posting client-attrs for aid=$aid → $attributesData")

        val call = apiService.postAttributes(url, "application/json", attributesData)
        call.enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                // For optimization, only log errors, not successful responses
                if (!response.isSuccessful) {
                    Log.w("MapActivity", "postAttrs failed: code=${response.code()}")
                }
            }
            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.e("MapActivity", "postAttrs error: ${t.message}")
            }
        })
    }
}
