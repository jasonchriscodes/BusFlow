package com.jason.publisher.modules.`break`.activities

import com.jason.publisher.main.model.ScheduleItem

class TestBreakActivity : BreakActivity() {
    override fun computeTimerDurationMillis(item: ScheduleItem): Long {
        return computeScheduledDurationMillis(item.startTime, item.endTime)
    }

    override fun getEndAtText(item: ScheduleItem, durationMs: Long): String {
        return "Break until ${formatScheduleTime(item.endTime)}"
    }
}
