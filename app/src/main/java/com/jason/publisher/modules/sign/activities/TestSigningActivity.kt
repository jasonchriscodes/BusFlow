package com.jason.publisher.modules.signing.activities

import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.model.ScheduleItem

class TestSigningActivity : SigningActivity() {
    override fun computeTimerDurationMillis(item: ScheduleItem): Long {
        val duration = computeScheduledDurationMillis(item.startTime, item.endTime)
        FileLogger.d(
            "TestSigningActivity",
            "computeTimerDurationMillis (test override, full scheduled duration) | start=${item.startTime} end=${item.endTime} duration=$duration"
        )
        return duration
    }
}
