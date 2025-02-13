package com.jason.publisher.model

// Data class to store cached bus data
data class BusDataCache(
    val aid: String,
    val busRouteData: List<RouteData>?,
    val config: List<BusItem>?
)
