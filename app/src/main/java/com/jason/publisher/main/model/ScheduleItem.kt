package com.jason.publisher.main.model
import java.io.Serializable

data class ScheduleItem(
    val routeNo: String,
    val startTime: String,
    val endTime: String,
    val dutyName: String,
    val busStops: List<BusScheduleInfo>
) : Serializable

data class BusScheduleInfo(
    val name: String,
    val time: String,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val abbreviation: String
) : Serializable

