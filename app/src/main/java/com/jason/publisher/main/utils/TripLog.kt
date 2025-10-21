package com.jason.publisher.main.utils

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.*

object TripLog {
    private const val SP = "trip_log_sp"
    private const val KEY_ACTIVE = "active_trip_json"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    data class ActiveTrip(
        val startedAt: String,
        val type: String,              // "trip" | "break" | "reposition"
        val label: String?,            // e.g. "08:10 Route 2 ABC → XYZ" or "09:00 Break BCS → BCS"
        val aid: String?,
        val runNo: String?,
        val runName: String?,
        val startTime: String?,
        val endTime: String?,
        val fromStop: String?,
        val toStop: String?,
        val scheduleSize: Int,
        val routeDataSize: Int,
        val androidApi: Int = Build.VERSION.SDK_INT
    )

    private fun now() = fmt.format(Date())
    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun start(ctx: Context, active: ActiveTrip, extraDump: Map<String, Any?> = emptyMap()) {
        val json = Gson().toJson(active)
        sp(ctx).edit().putString(KEY_ACTIVE, json).apply()

        // One clear line you can grep for:
        FileLogger.d("TripEvent", "TRIP_STARTED | ${active.type} | ${active.label ?: "-"} | at=${active.startedAt}")

        // Snapshot (compact but rich)
        FileLogger.d("TripSnapshot",
            buildString {
                append("TRIP_START_SNAPSHOT | type=${active.type}")
                append(" | aid=${active.aid}")
                append(" | runNo=${active.runNo} | runName=${active.runName}")
                append(" | start=${active.startTime} | end=${active.endTime}")
                append(" | from=${active.fromStop} | to=${active.toStop}")
                append(" | scheduleSize=${active.scheduleSize} | routeDataSize=${active.routeDataSize}")
            }
        )

        // Optional fat dump (config/schedule/route sizes + hashes/firsts to avoid huge logs)
        if (extraDump.isNotEmpty()) {
            FileLogger.d("TripDataDump", "START_EXTRA | ${Gson().toJson(extraDump)}")
        }
    }

    fun mark(ctx: Context, message: String) {
        FileLogger.d("TripEvent", message)
    }

    fun end(ctx: Context, reason: String = "ReturnedToScheduleActivity", extraDump: Map<String, Any?> = emptyMap()) {
        val activeJson = sp(ctx).getString(KEY_ACTIVE, null)
        sp(ctx).edit().remove(KEY_ACTIVE).apply()

        FileLogger.d("TripEvent", "TRIP_ENDED | reason=$reason | at=${now()}")

        if (activeJson != null) {
            FileLogger.d("TripSnapshot", "TRIP_END_SNAPSHOT | active=$activeJson")
        }
        if (extraDump.isNotEmpty()) {
            FileLogger.d("TripDataDump", "END_EXTRA | ${Gson().toJson(extraDump)}")
        }
    }

    fun hasActive(ctx: Context): Boolean = sp(ctx).getString(KEY_ACTIVE, null) != null
}

