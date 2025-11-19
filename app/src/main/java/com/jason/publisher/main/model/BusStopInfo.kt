package com.jason.publisher.main.model

/**
 * Data class representing information about a bus stop.
 *
 * @property busStopName The name of the bus stop.
 * @property latitude The latitude coordinate of the bus stop.
 * @property longitude The longitude coordinate of the bus stop.
 */
data class BusStopInfo(
    val latitude : Double,
    val longitude : Double,
    val busStopName : String,
)