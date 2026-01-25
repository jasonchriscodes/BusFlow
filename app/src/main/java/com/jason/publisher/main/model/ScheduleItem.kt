package com.jason.publisher.main.model
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
data class ScheduleItem(
    val runNo: String,
    val startTime: String,
    val endTime: String,
    val runName: String,
    val busStops: List<BusScheduleInfo>
) : Parcelable {
    /**
     * Returns true if this schedule item represents a break (case-insensitive).
     */
    fun isBreak(): Boolean {
        // Accept "Break" or "break"
        return runName.equals("break", ignoreCase = true)
    }

    /** Returns true if the schedule item represents a Reposition (REP). */
    fun isReposition(): Boolean {
        return runName.equals("REP", true)
                || runName.contains("reposition", true)
    }
}

data class BusScheduleInfo(
    val name: String,
    val time: String,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val abbreviation: String
) : Serializable
