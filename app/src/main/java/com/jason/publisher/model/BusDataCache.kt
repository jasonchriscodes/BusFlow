package com.jason.publisher.model

// Data class to store cached bus data
data class BusDataCache(
    val aid: String,
    val config: List<BusItem>?,
    val busRoute: List<BusRoute>?,
    val busStop: List<BusStop>?
)