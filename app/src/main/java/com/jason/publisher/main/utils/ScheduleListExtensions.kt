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

/**
 * Get how many seconds to wait from predicted arrival until the next schedule.
 * @param t1 How many seconds until the next arrival.
 * */
fun List<ScheduleItem>.getDeltaNextSec(t1: Double): Int? {
    val predictedArrival = Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis()
        add(Calendar.SECOND, t1.toInt())
    }

    val nextScheduleStartRaw = this.getNextScheduleStartTime() ?: return null
    val nextScheduleStartStr = "$nextScheduleStartRaw:00"
    val nextScheduleStartTime = nextScheduleStartStr.parseTimeToday()

    return ((nextScheduleStartTime.time - predictedArrival.time.time) / 1000).toInt()
}