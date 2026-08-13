package com.jason.publisher.modules.`break`.activities

import android.content.Intent
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.UserActionLogger
import com.jason.publisher.main.model.ScheduleItem

class TestBreakActivity : BreakActivity() {
    override fun computeTimerDurationMillis(item: ScheduleItem): Long {
        val duration = computeScheduledDurationMillis(item.startTime, item.endTime)
        FileLogger.d(
            "TestBreakActivity",
            "computeTimerDurationMillis (test override, full scheduled duration) | start=${item.startTime} end=${item.endTime} duration=$duration"
        )
        return duration
    }

    override fun getEndAtText(item: ScheduleItem, durationMs: Long): String {
        val text = "Break until ${formatScheduleTime(item.endTime)}"
        FileLogger.d(
            "TestBreakActivity",
            "getEndAtText (test override) | endTime=${item.endTime} durationMs=$durationMs text=$text"
        )
        return text
    }

    override fun onDoneClicked(fullRemaining: ArrayList<ScheduleItem>) {
        UserActionLogger.click("TestBreakActivity", "btnDone (test override)", "remainingBefore=${fullRemaining.size}")
        val remainingAfterBreak = ArrayList(fullRemaining.drop(1))
        UserActionLogger.stateChanged("TestBreakActivity", "remainingSchedule.size", fullRemaining.size, remainingAfterBreak.size)
        val resultIntent = Intent().apply {
            putParcelableArrayListExtra("UPDATED_FULL_SCHEDULE_DATA", remainingAfterBreak)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
