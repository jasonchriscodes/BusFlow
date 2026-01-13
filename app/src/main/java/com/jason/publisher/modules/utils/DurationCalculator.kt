package com.jason.publisher.modules.map.utils

import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.modules.map.models.BusStopWithTimingPoint

/**
 * Calculates the total duration (in minutes) for the update.
 *
 * If targetIndex is 0, returns the duration at index 0.
 * If the upcoming stop (at targetIndex) is scheduled:
 *   • If it is not the last scheduled stop, sum durations from index 0 up to (but not including) the next scheduled stop.
 *   • If it is the last scheduled stop, sum the entire timing list.
 * Otherwise (unscheduled and not index 0) returns null.
 */
fun calculateDurationForUpdate(
    timingList: List<BusStopWithTimingPoint>,
    scheduleList: List<ScheduleItem>,
    targetIndex: Int
): Double? {
    val scheduledIndices = getScheduledIndices(timingList, scheduleList)

    if (targetIndex == 0) {
        return timingList.subList(0, 1).sumOf { it.duration }
    }

    if (targetIndex in scheduledIndices) {
        val pos = scheduledIndices.indexOf(targetIndex)
        return if (pos < scheduledIndices.size - 1) {
            val nextScheduledIndex = scheduledIndices[pos + 1]
            timingList.subList(0, nextScheduledIndex).sumOf { it.duration }
        } else {
            // Last timing point → return full duration to end
            return timingList.subList(0, targetIndex + 1).sumOf { it.duration }
        }
    }

    // If not scheduled, check if it's after the last scheduled index
    if (scheduledIndices.isNotEmpty() && targetIndex >= scheduledIndices.last()) {
        // Add all remaining durations from this point onward
        return timingList.subList(0, targetIndex + 1).sumOf { it.duration }
    }
    return null
}