package com.jason.publisher.modules.map.utils

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val BUS_STOP_RADIUS: Double = 50.0

/** Calculates distance between two lat/lon points in meters */
fun calculateDistance(
    lat1: Double, lon1: Double,
    lat2: Double, lon2: Double
): Double {
    val radiusOfEarth = 6371000.0
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val deltaPhi = Math.toRadians(lat2 - lat1)
    val deltaLambda = Math.toRadians(lon2 - lon1)
    val a = sin(deltaPhi / 2).pow(2) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return radiusOfEarth * c
}

/** Calculates the bearing between two geographical points. */
fun calculateBearing(
    lat1: Double, lon1: Double,
    lat2: Double, lon2: Double
): Float {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val deltaLambda = Math.toRadians(lon2 - lon1)
    val y = sin(deltaLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
    return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
}