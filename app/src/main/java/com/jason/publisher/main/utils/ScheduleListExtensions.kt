package com.jason.publisher.main.utils

import com.jason.publisher.main.model.ScheduleItem
import java.util.Calendar

/**
 * Returns the start time for the next schedule.
 * Assumes that the scheduleData list is sorted chronologically.
 */
fun List<ScheduleItem>.getNextScheduleStartTime(): String? {
    // Return second item if available
    return if (this.size > 1) this[1].startTime else null
}

/** Returns the items up to (but not including) the next break. */
fun List<ScheduleItem>.truncateUntilBreak(): List<ScheduleItem> {
    val breakIndex = this.indexOfFirst { it.isBreak() }
    return if (breakIndex == -1) this else this.take(breakIndex)
}

/** Extracts (start, end) time pairs and run names, skipping items with a blank start/end time. */
fun List<ScheduleItem>.extractWorkIntervalsAndRunNames(): Pair<List<Pair<String, String>>, List<String>> {
    val workIntervals = mutableListOf<Pair<String, String>>()
    val runNames = mutableListOf<String>()

    for (item in this) {
        if (item.startTime.isNotEmpty() && item.endTime.isNotEmpty()) {
            workIntervals.add(Pair(item.startTime, item.endTime))
            runNames.add(item.runName)
        }
    }

    return Pair(workIntervals, runNames)
}

/**
 * Get how many seconds to wait from predicted arrival until the next schedule.
 * @param t1 How many seconds until the next arrival.
 * */
fun List<ScheduleItem>.getDeltaNextSec(t1: Double, nowMillis: Long = System.currentTimeMillis()): Int? {
    val predictedArrival = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        add(Calendar.SECOND, t1.toInt())
    }

    val nextScheduleStartRaw = this.getNextScheduleStartTime() ?: return null
    val nextScheduleStartStr = "$nextScheduleStartRaw:00"
    val nextScheduleStartTime = nextScheduleStartStr.parseTimeToday()

    return ((nextScheduleStartTime.time - predictedArrival.time.time) / 1000).toInt()
}
