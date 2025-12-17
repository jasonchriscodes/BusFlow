package com.jason.publisher.main.helpers

import com.jason.publisher.main.model.BusRoute

object MapHelper {
     fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val x = dLat/2
        val y = dLon/2
        val h = Math.sin(x)*Math.sin(x) + Math.cos(Math.toRadians(aLat))*Math.cos(Math.toRadians(bLat))*Math.sin(y)*Math.sin(y)
        return 2*R*Math.asin(Math.sqrt(h))
    }

     fun remainingMetersFromRouteIndex(route: List<BusRoute>, idx: Int): Double {
        if (route.isEmpty()) return 0.0
        val start = idx.coerceIn(0, route.lastIndex)
        var sum = 0.0
        for (i in start until route.lastIndex) {
            val a = route[i]
            val b = route[i+1]
            val al = a.latitude ?: continue
            val ao = a.longitude ?: continue
            val bl = b.latitude ?: continue
            val bo = b.longitude ?: continue
            sum += distanceMeters(al, ao, bl, bo)
        }
        return sum
    }

     fun computeEtaSeconds(
        remainingMeters: Double,
        speedMpsFiltered: Double,
        distToNextStop: Double
    ): Long {
        val minSpeed = 1.2 // m/s ~ 4.3 km/h
        val v = maxOf(speedMpsFiltered, minSpeed)

        // dwell heuristic
        val dwell = if (distToNextStop < 30.0 && speedMpsFiltered < 1.0) 35.0 else 0.0

        val travel = remainingMeters / v
        return (travel + dwell).toLong().coerceAtLeast(0)
    }




}