package com.jason.publisher.model

import android.util.Log
import kotlin.math.*

data class Coordinate(val latitude: Double, val longitude: Double)

/**
 * Finds the nearest coordinate to the current location from a list of coordinates.
 *
 * @param currentLocation The current location of the bus.
 * @param route List of coordinates defining the route.
 * @return The nearest coordinate in the route, or null if the route is empty.
 */
fun findNearestCoordinate(currentLocation: Coordinate, route: List<Coordinate>): Coordinate? {
    if (route.isEmpty()) return null

    return route.minByOrNull { calculateDistance(currentLocation, it) }
}

// Calculate distance between two coordinates in meters
private fun calculateDistance(loc1: Coordinate, loc2: Coordinate): Double {
    val earthRadius = 6371000.0 // Radius of Earth in meters
    val latDiff = Math.toRadians(loc2.latitude - loc1.latitude)
    val lonDiff = Math.toRadians(loc2.longitude - loc1.longitude)
    val a = sin(latDiff / 2).pow(2) +
            cos(Math.toRadians(loc1.latitude)) * cos(Math.toRadians(loc2.latitude)) *
            sin(lonDiff / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadius * c
}
