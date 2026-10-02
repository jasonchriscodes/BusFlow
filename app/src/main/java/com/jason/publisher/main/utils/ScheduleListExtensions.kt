package com.jason.publisher.main.utils

import com.jason.publisher.main.model.ScheduleItem

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