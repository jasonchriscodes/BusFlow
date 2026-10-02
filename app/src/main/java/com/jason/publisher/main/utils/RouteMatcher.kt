package com.jason.publisher.main.utils

import com.jason.publisher.main.model.RouteData
import com.jason.publisher.main.model.ScheduleItem
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Matches a schedule item (Trip / REP) to its entry in busRouteData. Pure so it can be unit tested. */
object RouteMatcher {
    const val MAX_MATCH_SCORE_METERS = 250.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** start-to-start + end-to-end distance in meters, or null if the route has no end point. */
    fun score(item: ScheduleItem, route: RouteData): Double? {
        val first = item.busStops.firstOrNull() ?: return null
        val last = item.busStops.lastOrNull() ?: return null
        val end = route.nextPoints.lastOrNull() ?: return null
        return distanceMeters(first.latitude, first.longitude, route.startingPoint.latitude, route.startingPoint.longitude) +
                distanceMeters(last.latitude, last.longitude, end.latitude, end.longitude)
    }

    /**
     * Returns the busRouteData index for [item], or -1 when nothing matches (callers must not launch).
     *
     * busRouteData is index-aligned with the full server roster (Sign On / Break get placeholder
     * routes), and several routes can share both terminals (e.g. a REP and the Sign On placeholder).
     * So the route at the item's roster position wins whenever it also matches geometrically.
     * [roster] may be the full roster or a front-trimmed one (cache mode); items are only ever
     * removed from the front, so it is aligned to the END of busRouteData.
     */
    fun findRouteIndex(item: ScheduleItem, routes: List<RouteData>, roster: List<ScheduleItem>): Int {
        if (routes.isEmpty() || item.busStops.isEmpty()) return -1

        val scores = routes.map { score(item, it) ?: Double.MAX_VALUE }

        val rosterPos = roster.indexOfFirst {
            it.runNo == item.runNo && it.startTime == item.startTime && it.runName == item.runName
        }
        if (rosterPos >= 0 && roster.size <= routes.size) {
            val alignedIdx = rosterPos + (routes.size - roster.size)
            if (scores[alignedIdx] <= MAX_MATCH_SCORE_METERS) return alignedIdx
        }

        val bestIdx = scores.indices.minByOrNull { scores[it] } ?: return -1
        return if (scores[bestIdx] <= MAX_MATCH_SCORE_METERS) bestIdx else -1
    }
}