package com.jason.publisher.modules.`break`.activities

import android.content.Intent
import com.jason.publisher.main.model.ScheduleItem

class TestBreakActivity : BreakActivity() {
    override fun computeTimerDurationMillis(item: ScheduleItem): Long {
        return computeScheduledDurationMillis(item.startTime, item.endTime)
    }

    override fun getEndAtText(item: ScheduleItem, durationMs: Long): String {
        return "Break until ${formatScheduleTime(item.endTime)}"
    }

    override fun onDoneClicked(fullRemaining: ArrayList<ScheduleItem>) {
        val remainingAfterBreak = ArrayList(fullRemaining.drop(1))
        val resultIntent = Intent().apply {
            putParcelableArrayListExtra("UPDATED_FULL_SCHEDULE_DATA", remainingAfterBreak)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
