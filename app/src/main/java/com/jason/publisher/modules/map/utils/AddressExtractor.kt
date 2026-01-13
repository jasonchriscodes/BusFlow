package com.jason.publisher.modules.map.utils

import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.modules.map.models.BusStopWithTimingPoint
import java.util.Calendar
import java.util.Locale.getDefault

/**
 * Returns the address of the final scheduled bus stop from [timingList].
 */
fun getLastScheduledAddress(
    timingList: List<BusStopWithTimingPoint>,
    scheduleList: List<ScheduleItem>
): String? {
    val scheduledIndices = getScheduledIndices(timingList, scheduleList)
    return if (scheduledIndices.isNotEmpty()) timingList[scheduledIndices.last()].address else null
}

/**
 * Returns a sorted list of indices in [timingList] whose addresses appear in the schedule.
 */
fun getScheduledIndices(
    timingList: List<BusStopWithTimingPoint>,
    scheduleList: List<ScheduleItem>
): List<Int> {
    if (scheduleList.isEmpty() || timingList.isEmpty()) return emptyList()
    val scheduledAddresses = scheduleList.first().busStops.map { it.address.lowercase(getDefault()) }
    return timingList.withIndex()
        .filter { it.value.address?.lowercase(getDefault()) in scheduledAddresses }
        .map { it.index }
        .sorted()
}

fun getNextTripFormattedLabel(nextTripStartTime: String?, currentTime: Calendar): String {
    return if (nextTripStartTime != null) {
        val timeParts = nextTripStartTime.split(":").map { it.toInt() }
        val nextTripCalendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, currentTime.get(Calendar.YEAR))
            set(Calendar.MONTH, currentTime.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, currentTime.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, timeParts[0])
            set(Calendar.MINUTE, timeParts[1])
            set(Calendar.SECOND, 0)
            if (timeInMillis <= currentTime.timeInMillis) add(Calendar.DATE, 1)
        }
        val diff = nextTripCalendar.timeInMillis - currentTime.timeInMillis
        if (diff > 0) {
            val totalSeconds = (diff / 1000).toInt()
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            "Next run in: $mins mins $secs seconds"
        } else {
            // Format late time as "xx mins" only (no seconds) if >= 60 seconds
            val lateSeconds = (-diff / 1000).toInt()
            if (lateSeconds >= 60) {
                val mins = lateSeconds / 60
                "You are late for the next run by $mins mins"
            } else {
                "You are late for the next run by ${lateSeconds}s"
            }
        }
    } else {
        "No more scheduled trips for today"
    }
}