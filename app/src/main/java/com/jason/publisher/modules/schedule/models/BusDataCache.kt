package com.jason.publisher.modules.schedule.models

import com.jason.publisher.main.model.BusItem
import com.jason.publisher.main.model.RouteData

/** Data class to store cached bus data */
data class BusDataCache(
    val aid: String,
    val busRouteData: List<RouteData>?,
    val config: List<BusItem>?
)