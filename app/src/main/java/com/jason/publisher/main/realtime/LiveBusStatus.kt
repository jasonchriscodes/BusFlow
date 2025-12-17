package com.jason.publisher.main.realtime

data class LiveBusStatus(
    val aid : String,
    val lat : Double,
    val lon : Double,
    val speed : Float,
    val currentTripLabel : String?,
    val lastSeen : Long
)