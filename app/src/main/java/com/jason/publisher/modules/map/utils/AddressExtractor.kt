package com.jason.publisher.modules.map.utils

import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.modules.map.models.BusStopWithTimingPoint
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