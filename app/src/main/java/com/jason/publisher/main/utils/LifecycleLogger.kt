package com.jason.publisher.main.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * ✅ OPTIMIZED: Structured lifecycle logging system
 * Tracks app lifecycle events with minimal, meaningful logs
 */
object LifecycleLogger {
    private const val TAG = "Lifecycle"
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Track current state
    private var currentActivity: String? = null
    private var currentRoute: String? = null
    private var lastLocationLogTime = 0L

    /** Log location every 5 seconds */
    private const val LOCATION_LOG_INTERVAL_MS = 5000L
    private var lastBusStopPassLogTime = 0L
    private var lastScheduleStatusLogTime = 0L

    /** Log bus stop pass details every 5 seconds */
    private const val BUS_STOP_PASS_LOG_INTERVAL_MS = 5000L

    /** Log schedule status details every 5 seconds */
    private const val SCHEDULE_STATUS_LOG_INTERVAL_MS = 5000L

    /**
     * Log activity lifecycle event
     */
    fun logActivityEvent(activity: String, event: String, data: Map<String, Any?> = emptyMap()) {
        val timestamp = timeFormat.format(Date())
        val dataStr = if (data.isNotEmpty()) {
            " | ${data.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
        } else {
            ""
        }
        Log.d(TAG, "[$timestamp] $activity.$event$dataStr")
        FileLogger.d(TAG, "[$timestamp] $activity.$event$dataStr")
    }

    /**
     * Log when MapActivity is opened
     */
    fun logMapActivityOpen(routeName: String, currentStop: String, upcomingStop: String) {
        currentActivity = "MapActivity"
        logActivityEvent("MapActivity", "OPENED", mapOf(
            "route" to routeName,
            "currentStop" to currentStop,
            "upcomingStop" to upcomingStop
        ))
    }

    /**
     * Log location and trip status (throttled to every 10 seconds)
     */
    fun logLocationUpdate(
        lat: Double,
        lon: Double,
        speed: Float,
        upcomingStop: String,
        currentStop: String,
        eta: String?,
        scheduleStatus: String
    ) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLocationLogTime < LOCATION_LOG_INTERVAL_MS) {
            return // Skip if too soon
        }
        lastLocationLogTime = currentTime

        logActivityEvent("MapActivity", "LOCATION_UPDATE", mapOf(
            "lat" to String.format(Locale.US, "%.6f", lat),
            "lon" to String.format(Locale.US, "%.6f", lon),
            "speed" to String.format(Locale.US, "%.2f", speed),
            "upcomingStop" to upcomingStop,
            "currentStop" to currentStop,
            "eta" to (eta ?: "N/A"),
            "scheduleStatus" to scheduleStatus
        ))
    }

    /**
     * Log trip completion
     */
    fun logTripComplete(routeName: String, reason: String) {
        logActivityEvent("MapActivity", "TRIP_COMPLETE", mapOf(
            "route" to routeName,
            "reason" to reason
        ))
        currentRoute = null
    }

    /**
     * ✅ ENHANCED: Log detailed bus stop pass with ETA calculation data
     */
    fun logBusStopPass(
        upcomingStop: String,
        currentStop: String,
        lat: Double,
        lon: Double,
        speed: Float,
        d1: Double? = null, // Distance from current location to red timing point
        d2: Double? = null, // Total distance from route start to red timing point
        t1: Double? = null, // Estimated time to arrival from current position
        t2: Double? = null, // Total scheduled travel time from start to red timing point
        effectiveSpeed: Double? = null, // Effective speed in m/s
        scheduleStatusText: String? = null,
        timingPointTime: String? = null,
        predictedArrival: String? = null,
        deltaSec: Int? = null // Time difference in seconds
    ) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBusStopPassLogTime < BUS_STOP_PASS_LOG_INTERVAL_MS) {
            return // Skip if too soon
        }
        lastBusStopPassLogTime = currentTime

        val data = mutableMapOf<String, Any?>(
            "upcomingStop" to upcomingStop,
            "currentStop" to currentStop,
            "lat" to String.format(Locale.US, "%.6f", lat),
            "lon" to String.format(Locale.US, "%.6f", lon),
            "speed" to String.format(Locale.US, "%.2f", speed)
        )

        // Add ETA calculation data if provided
        d1?.let { data["d1_meters"] = String.format(Locale.US, "%.2f", it) }
        d2?.let { data["d2_meters"] = String.format(Locale.US, "%.2f", it) }
        t1?.let { data["t1_seconds"] = String.format(Locale.US, "%.2f", it) }
        t2?.let { data["t2_seconds"] = String.format(Locale.US, "%.2f", it) }
        effectiveSpeed?.let { data["effectiveSpeed_mps"] = String.format(Locale.US, "%.2f", it) }
        scheduleStatusText?.let { data["scheduleStatus"] = it }
        timingPointTime?.let { data["timingPointTime"] = it }
        predictedArrival?.let { data["predictedArrival"] = it }
        deltaSec?.let { data["deltaSec"] = it }

        logActivityEvent("MapActivity", "BUS_STOP_PASS", data)
    }

    /**
     * ✅ ENHANCED: Log activity entry with detailed data
     */
    fun logActivityEntry(activity: String, data: Map<String, Any?>) {
        currentActivity = activity
        logActivityEvent(activity, "ENTRY", data)
    }

    /**
     * ✅ ENHANCED: Log when other bus is detected
     */
    fun logOtherBusDetected(token: String, label: String, destination: String? = null, lat: Double? = null, lon: Double? = null) {
        val data = mutableMapOf<String, Any?>(
            "token" to token,
            "label" to label
        )
        destination?.let { data["destination"] = it }
        lat?.let { data["lat"] = String.format(Locale.US, "%.6f", it) }
        lon?.let { data["lon"] = String.format(Locale.US, "%.6f", it) }

        logActivityEvent("MapActivity", "OTHER_BUS_DETECTED", data)
    }

    /**
     * ✅ ENHANCED: Log when other bus is removed/disappears
     */
    fun logOtherBusRemoved(token: String, label: String, destination: String? = null, reason: String = "timeout") {
        val data = mutableMapOf<String, Any?>(
            "token" to token,
            "label" to label,
            "reason" to reason
        )
        destination?.let { data["destination"] = it }

        logActivityEvent("MapActivity", "OTHER_BUS_REMOVED", data)
    }

    /**
     * ✅ ENHANCED: Log detailed schedule status with ETA calculation data
     */
    fun logScheduleStatus(
        d1: Double,
        d2: Double,
        t1: Double,
        t2: Double,
        effectiveSpeed: Double,
        predictedArrival: String,
        deltaSec: Int,
        scheduleStatusText: String,
        timingPointTime: String
    ) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScheduleStatusLogTime < SCHEDULE_STATUS_LOG_INTERVAL_MS) {
            return // Skip if too soon
        }
        lastScheduleStatusLogTime = currentTime

        logActivityEvent("MapActivity", "SCHEDULE_STATUS_DETAIL", mapOf(
            "d1_meters" to String.format(Locale.US, "%.2f", d1),
            "d2_meters" to String.format(Locale.US, "%.2f", d2),
            "t1_seconds" to String.format(Locale.US, "%.2f", t1),
            "t2_seconds" to String.format(Locale.US, "%.2f", t2),
            "effectiveSpeed_mps" to String.format(Locale.US, "%.2f", effectiveSpeed),
            "predictedArrival" to predictedArrival,
            "deltaSec" to deltaSec,
            "scheduleStatus" to scheduleStatusText,
            "timingPointTime" to timingPointTime
        ))
    }
}