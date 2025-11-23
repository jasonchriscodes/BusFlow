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
    private val LOCATION_LOG_INTERVAL_MS = 10000L // Log location every 10 seconds

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
     * Log when SplashActivity animation starts
     */
    fun logSplashAnimationStart() {
        logActivityEvent("SplashActivity", "ANIMATION_START")
    }

    /**
     * Log when ScheduleActivity fetches data
     */
    fun logScheduleDataFetch(success: Boolean, scheduleCount: Int, routeCount: Int) {
        logActivityEvent("ScheduleActivity", "DATA_FETCH", mapOf(
            "success" to success,
            "scheduleCount" to scheduleCount,
            "routeCount" to routeCount
        ))
    }

    /**
     * Log when user starts a route
     */
    fun logRouteStart(routeName: String, startTime: String, endTime: String, fromStop: String, toStop: String) {
        currentRoute = routeName
        logActivityEvent("ScheduleActivity", "ROUTE_START", mapOf(
            "route" to routeName,
            "startTime" to startTime,
            "endTime" to endTime,
            "from" to fromStop,
            "to" to toStop
        ))
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
     * Log when BreakActivity is opened
     */
    fun logBreakActivityOpen(breakDuration: String) {
        currentActivity = "BreakActivity"
        logActivityEvent("BreakActivity", "OPENED", mapOf(
            "breakDuration" to breakDuration
        ))
    }

    /**
     * Log when returning to ScheduleActivity
     */
    fun logReturnToSchedule(currentRoute: String?) {
        currentActivity = "ScheduleActivity"
        logActivityEvent("ScheduleActivity", "RETURNED", mapOf(
            "previousRoute" to (currentRoute ?: "N/A")
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
}
