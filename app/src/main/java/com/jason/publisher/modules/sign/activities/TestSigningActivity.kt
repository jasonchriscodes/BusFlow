package com.jason.publisher.modules.signing.activities

import com.jason.publisher.main.model.ScheduleItem

class TestSigningActivity : SigningActivity() {
    override fun computeTimerDurationMillis(item: ScheduleItem): Long {
        return computeScheduledDurationMillis(item.startTime, item.endTime)
    }
}
