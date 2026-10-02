package com.jason.publisher.main.utils

import android.os.Environment
import com.google.gson.Gson
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.model.ScheduleItem
import java.io.File

/**
 * Writes the same scheduleDataCache.txt that ScheduleViewModel reads for "Use Cache".
 *
 * ScheduleActivity only persists a consumed item once the downstream flow returns RESULT_OK.
 * Downstream activities call [commitRemaining] the moment their item is actually finished, so a
 * process death between "trip done" and that result cannot bring the finished trip back.
 */
object ScheduleCache {
    private fun cacheFile(): File {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(File(docs, ".vlrshiddenfolder"), "scheduleDataCache.txt")
    }

    fun commitRemaining(tag: String, remaining: List<ScheduleItem>) {
        try {
            val file = cacheFile()
            file.parentFile?.mkdirs()
            file.writeText(Gson().toJson(remaining))
            FileLogger.i(tag, "Committed schedule cache after completion | remaining=${remaining.size} | next=${remaining.firstOrNull()?.runName}")
        } catch (e: Exception) {
            // ScheduleActivity still commits on RESULT_OK; this is only the crash-safety copy.
            FileLogger.e(tag, "Schedule cache commit failed | ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}