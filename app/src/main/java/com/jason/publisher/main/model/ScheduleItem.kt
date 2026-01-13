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
) : Parcelable

data class BusScheduleInfo(
    val name: String,
    val time: String,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val abbreviation: String
) : Serializable
