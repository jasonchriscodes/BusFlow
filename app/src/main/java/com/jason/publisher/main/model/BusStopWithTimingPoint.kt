package com.jason.publisher.main.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class BusStopWithTimingPoint(
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    // Duration in minutes (for example, the value from each nextPoint – e.g. 0.8, 0.7, …)
    val duration: Double
) : Parcelable {
    companion object {
        /**
         * Converts a RouteData instance into a list of BusStopWithTimingPoint.
         * The starting point gets a duration of 0; each nextPoint uses its duration.
         */
        fun fromRouteData(routeData: RouteData): List<BusStopWithTimingPoint> {
            val list = mutableListOf<BusStopWithTimingPoint>()
            val nextPoints = routeData.nextPoints
            if (nextPoints.isNotEmpty()) {
                // Use first nextPoint's duration for the starting point.
                val firstDuration = nextPoints[0].duration.split(" ")[0].toDoubleOrNull() ?: 0.0
                list.add(
                    BusStopWithTimingPoint(
                        latitude = routeData.startingPoint.latitude,
                        longitude = routeData.startingPoint.longitude,
                        address = routeData.startingPoint.address,
                        duration = firstDuration
                    )
                )
                // For each next point except the last, assign the duration from the following segment.
                for (i in 0 until nextPoints.size - 1) {
                    val nextDuration = nextPoints[i + 1].duration.split(" ")[0].toDoubleOrNull() ?: 0.0
                    list.add(
                        BusStopWithTimingPoint(
                            latitude = nextPoints[i].latitude,
                            longitude = nextPoints[i].longitude,
                            address = nextPoints[i].address,
                            duration = nextDuration
                        )
                    )
                }
                // Append the starting point again with duration 0.0
                list.add(
                    BusStopWithTimingPoint(
                        latitude = routeData.startingPoint.latitude,
                        longitude = routeData.startingPoint.longitude,
                        address = routeData.startingPoint.address,
                        duration = 0.0
                    )
                )
            } else {
                // If no next points exist, just return the starting point with duration 0.0.
                list.add(
                    BusStopWithTimingPoint(
                        latitude = routeData.startingPoint.latitude,
                        longitude = routeData.startingPoint.longitude,
                        address = routeData.startingPoint.address,
                        duration = 0.0
                    )
                )
            }
            return list
        }
    }
}
