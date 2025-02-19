package com.jason.publisher.model
import java.io.Serializable

data class ScheduleItem(
    val routeNo: String,
    val busStopTimepoint: String,
    val startTime: String,
    val endTime: String
) : Serializable
