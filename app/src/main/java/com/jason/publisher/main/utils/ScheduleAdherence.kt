package com.jason.publisher.main.utils

import com.jason.publisher.main.model.ScheduleItem
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Single source of truth for schedule adherence (Ahead / On Time / Behind), next-run selection,
 * and time-of-day resolution. Pure Kotlin so it can be unit tested without Android.
 *
 * Every consumer (status text, icon, color, stop/zone marker, logs) must derive from
 * [calculateScheduleStatus]; do not re-implement thresholds elsewhere.
 */
object ScheduleAdherence {
    // Change tolerances here only.
    const val ON_TIME_EARLY_TOLERANCE_SECONDS = 30L
    const val ON_TIME_LATE_TOLERANCE_SECONDS = 30L
    const val VERY_BEHIND_THRESHOLD_SECONDS = 300L

    /** A state only changes once the delta is this far past the boundary (prevents GPS jitter flapping). */
    const val STATUS_HYSTERESIS_SECONDS = 10L

    /**
     * Offsets larger than this are treated as bad input (stale roster, wrong day, bad clock)
     * and are never shown as "Next run in 1250 mins" / "Late by 1200 mins".
     */
    const val MAX_PLAUSIBLE_OFFSET_SECONDS = 4 * 3600L

    /** Minimum layover before the next run; less than this triggers "Late for next run". */
    const val NEXT_RUN_MIN_LAYOVER_SECONDS = 300L

    const val MIN_SPEED_MPS = 0.5   // 1.8 km/h
    const val MAX_SPEED_MPS = 30.0  // 108 km/h

    const val SPEED_SOURCE_GPS = "GPS"
    const val SPEED_SOURCE_SCHEDULE_AVERAGE = "SCHEDULE_AVERAGE_FALLBACK"
}

enum class ScheduleState { AHEAD, ON_TIME, SLIGHTLY_BEHIND, VERY_BEHIND }

data class ScheduleStatusResult(
    /** scheduled - predicted; positive = ahead of schedule, negative = behind. */
    val deltaSeconds: Long,
    val state: ScheduleState,
    val displayText: String
)

data class SpeedEstimate(val mps: Double, val source: String)

private fun inBand(state: ScheduleState, delta: Long, margin: Long): Boolean {
    val early = ScheduleAdherence.ON_TIME_EARLY_TOLERANCE_SECONDS
    val late = ScheduleAdherence.ON_TIME_LATE_TOLERANCE_SECONDS
    val very = ScheduleAdherence.VERY_BEHIND_THRESHOLD_SECONDS
    return when (state) {
        ScheduleState.AHEAD -> delta > early - margin
        ScheduleState.ON_TIME -> delta >= -late - margin && delta <= early + margin
        ScheduleState.SLIGHTLY_BEHIND -> delta >= -very - margin && delta < -late + margin
        ScheduleState.VERY_BEHIND -> delta < -very + margin
    }
}

/**
 * Classifies a schedule delta. When [previous] is given, the previous state is kept while the
 * delta stays within its band widened by [ScheduleAdherence.STATUS_HYSTERESIS_SECONDS].
 */
fun classifyScheduleDelta(deltaSeconds: Long, previous: ScheduleState? = null): ScheduleState {
    val raw = ScheduleState.values().first { inBand(it, deltaSeconds, 0) }
    if (previous == null || previous == raw) return raw
    return if (inBand(previous, deltaSeconds, ScheduleAdherence.STATUS_HYSTERESIS_SECONDS)) previous else raw
}

fun calculateScheduleStatus(
    scheduledArrivalMillis: Long,
    predictedArrivalMillis: Long,
    previous: ScheduleState? = null
): ScheduleStatusResult {
    val delta = (scheduledArrivalMillis - predictedArrivalMillis) / 1000L
    val state = classifyScheduleDelta(delta, previous)
    val mins = abs(delta) / 60
    // 31-59 s would otherwise read "~0 min late".
    val label = when (mins) { 0L -> "<1 min"; 1L -> "1 min"; else -> "$mins min" }
    val text = when (state) {
        ScheduleState.AHEAD -> "Early (~$label early)"
        ScheduleState.ON_TIME -> "On Time"
        ScheduleState.SLIGHTLY_BEHIND -> "Slightly Behind (~$label late)"
        ScheduleState.VERY_BEHIND -> "Very Behind (~$label late)"
    }
    return ScheduleStatusResult(delta, state, text)
}

/**
 * Resolves a date-less "HH:mm" or "HH:mm:ss" string to epoch millis, picking the occurrence closest
 * to [referenceMillis] (so 00:05 seen at 23:58 is tomorrow, 23:55 seen at 00:02 is yesterday).
 * Returns null for malformed input instead of silently using "now".
 */
fun resolveTimeOfDay(
    time: String?,
    referenceMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault()
): Long? {
    val parts = time?.trim()?.split(":") ?: return null
    if (parts.size !in 2..3) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val s = if (parts.size == 3) parts[2].toIntOrNull() ?: return null else 0
    if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
    val cal = Calendar.getInstance(timeZone).apply {
        timeInMillis = referenceMillis
        set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m)
        set(Calendar.SECOND, s)
        set(Calendar.MILLISECOND, 0)
    }
    val diff = cal.timeInMillis - referenceMillis
    if (diff > 12 * 3600_000L) cal.add(Calendar.DATE, -1)
    else if (diff < -12 * 3600_000L) cal.add(Calendar.DATE, 1)
    return cal.timeInMillis
}

/**
 * Picks the speed used for ETA. GPS (smoothed) speed is used when plausible; otherwise the
 * schedule's average speed for this leg. The source is returned so it can be logged.
 */
fun estimateEffectiveSpeed(smoothedSpeedKmh: Double, scheduleAverageMps: Double?): SpeedEstimate {
    val gpsMps = smoothedSpeedKmh / 3.6
    if (gpsMps in ScheduleAdherence.MIN_SPEED_MPS..ScheduleAdherence.MAX_SPEED_MPS) {
        return SpeedEstimate(gpsMps, ScheduleAdherence.SPEED_SOURCE_GPS)
    }
    val avg = (scheduleAverageMps ?: ScheduleAdherence.MIN_SPEED_MPS)
        .takeIf { it.isFinite() }
        ?.coerceIn(ScheduleAdherence.MIN_SPEED_MPS, ScheduleAdherence.MAX_SPEED_MPS)
        ?: ScheduleAdherence.MIN_SPEED_MPS
    return SpeedEstimate(avg, ScheduleAdherence.SPEED_SOURCE_SCHEDULE_AVERAGE)
}

private fun ScheduleItem.sameRunAs(other: ScheduleItem): Boolean =
    runNo == other.runNo && startTime == other.startTime && runName == other.runName

/**
 * FULL_SCHEDULE_DATA sometimes includes the active item at index 0 (resumed trip) and sometimes
 * not (fresh start). This normalizes it to "items after the active one".
 */
fun remainingAfterActive(active: ScheduleItem?, data: List<ScheduleItem>): List<ScheduleItem> {
    val first = data.firstOrNull() ?: return emptyList()
    return if (active != null && first.sameRunAs(active)) data.drop(1) else data
}

/** The next item the driver must start after the active one (Trip, REP, Break or Signing). */
fun nextRunAfterActive(active: ScheduleItem?, data: List<ScheduleItem>): ScheduleItem? =
    remainingAfterActive(active, data).firstOrNull { it.startTime.isNotBlank() }

/** Countdown label for the next run; never produces multi-hour garbage values. */
fun formatNextRunCountdown(nextStartTime: String?, nowMillis: Long): String {
    if (nextStartTime == null) return "No more scheduled trips for today"
    val start = resolveTimeOfDay(nextStartTime, nowMillis) ?: return "Next run at $nextStartTime"
    val diffSec = (start - nowMillis) / 1000L
    if (abs(diffSec) > ScheduleAdherence.MAX_PLAUSIBLE_OFFSET_SECONDS) return "Next run at $nextStartTime"
    return if (diffSec > 0) {
        "Next run in: ${diffSec / 60} mins ${diffSec % 60} seconds"
    } else {
        val late = -diffSec
        if (late >= 60) "You are late for the next run by ${late / 60} mins"
        else "You are late for the next run by ${late}s"
    }
}

/**
 * Seconds of slack between arriving at the final stop and the next run's start.
 * Null when there is no next run or the value is implausible.
 */
fun nextRunSlackSeconds(nextStartTime: String?, secondsToFinalStop: Double, nowMillis: Long): Long? {
    if (!secondsToFinalStop.isFinite()) return null
    val start = resolveTimeOfDay(nextStartTime, nowMillis) ?: return null
    val slack = (start - (nowMillis + (secondsToFinalStop * 1000).toLong())) / 1000L
    return slack.takeIf { abs(it) <= ScheduleAdherence.MAX_PLAUSIBLE_OFFSET_SECONDS }
}