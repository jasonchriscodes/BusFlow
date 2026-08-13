package com.jason.publisher.main.loggers

import java.util.Locale

/**
 * Always-fresh, in-memory snapshot of "what the driver is currently seeing" — which page is
 * open, the current/upcoming stop, how many stops have been passed, and the last known
 * position/speed/schedule status. Fields are plain @Volatile writes (no I/O, no locking), so
 * updating them on every GPS tick is effectively free.
 *
 * Purpose: every error/crash log line can call [describe] to attach full context, so a bug
 * report never needs a screen recording to know what the UI showed at the moment of failure.
 */
object TripStateSnapshot {

    @Volatile var currentActivity: String? = null

    @Volatile var upcomingStop: String? = null
    @Volatile var currentStopAddress: String? = null
    @Volatile var passedStopsCount: Int = 0
    @Volatile var passedStopsLast: String? = null

    @Volatile var latitude: Double = 0.0
    @Volatile var longitude: Double = 0.0
    @Volatile var speed: Float = 0f
    @Volatile var bearing: Float = 0f

    @Volatile var scheduleStatus: String? = null

    fun onActivityEntered(name: String) {
        currentActivity = name
    }

    fun updateStops(upcoming: String?, current: String?, passedCount: Int, passedLast: String?) {
        upcomingStop = upcoming
        currentStopAddress = current
        passedStopsCount = passedCount
        passedStopsLast = passedLast
    }

    fun updatePosition(lat: Double, lon: Double, spd: Float, brg: Float) {
        latitude = lat
        longitude = lon
        speed = spd
        bearing = brg
    }

    fun updateScheduleStatus(status: String?) {
        scheduleStatus = status
    }

    /** One compact line summarizing current page + trip state, safe to append to any log line. */
    fun describe(): String {
        return "page=${currentActivity ?: "unknown"}" +
                " | upcoming=${upcomingStop ?: "-"}" +
                " | current=${currentStopAddress ?: "-"}" +
                " | passed=$passedStopsCount(last=${passedStopsLast ?: "-"})" +
                " | lat=${fmt(latitude)} lon=${fmt(longitude)} speed=${fmt(speed.toDouble())} bearing=${fmt(bearing.toDouble())}" +
                " | scheduleStatus=${scheduleStatus ?: "-"}"
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)
}
