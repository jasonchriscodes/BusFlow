package com.jason.publisher.model
import java.io.Serializable

data class ScheduleItem(
    val routeNo: String,
    val startTime: String,
    val endTime: String,
    val busStops: List<BusScheduleInfo> // Updated class name
) : Serializable

data class BusScheduleInfo( // Renamed from BusStopInfo
    val name: String,
    val time: String,
    val latitude: Double,
    val longitude: Double,
    val address: String
) : Serializable
