/**
 * This class provides utility functions for handling bus stop assignments and related operations.
 *
 * The main functionality provided is:
 * - Determining the closest bus stop to a given location.
 * - Checking if a bus stop is within a certain proximity.
 * - Ensuring bus stops are in sequence.
 * - Retrieving the next bus stop in sequence.
 *
 * @see getTheClosestBusStopToPubDevice
 * @see isBusStopClose
 * @see isBusStopInSequence
 * @see getNextBusStopInSequence
 *
 * Example usage:
 * ```
 * val closestBusStop = BusStopProximityManager.getTheClosestBusStopToPubDevice(52.370216, 4.895168, "none")
 * ```
 */

package com.jason.publisher.main.utils

import android.location.Location
import android.util.Log
import com.jason.publisher.main.model.BusStopInfo

/**
 * Utility class for handling bus stop assignments and related operations.
 */
class BusStopProximityManager {

    companion object {

        private var busStopList: List<BusStopInfo> = emptyList()

        /**
         * Determines the closest bus stop to the given location.
         * If the current closest bus stop is "none", it returns the newly found closest bus stop.
         * If the bus stop is in sequence and within close proximity, it updates the closest bus stop.
         *
         * @param lat Latitude of the current location.
         * @param lng Longitude of the current location.
         * @param currentClosestBusStop Name of the current closest bus stop.
         *
         * @return The name of the closest bus stop.
         */
        fun getTheClosestBusStopToPubDeviceOffline(lat: Double, lng: Double, currentClosestBusStop : String): String {
            val busStopWithShortestDistance : String = findTheBusStopWithClosestDistance(lat, lng)

            if(currentClosestBusStop == "none") return busStopWithShortestDistance;
            else if(isBusStopInSequence(currentClosestBusStop, busStopWithShortestDistance) && isBusStopClose(lat, lng, busStopWithShortestDistance)){
                return busStopWithShortestDistance;
            }

            return currentClosestBusStop;
        }

        /**
         * Determines the closest bus stop to the given location.
         * @param lat Latitude of the current location.
         * @param lng Longitude of the current location.
         * @param currentClosestBusStop Name of the current closest bus stop.
         * @return The name of the closest bus stop.
         */
        fun getTheClosestBusStopToPubDeviceOnline(lat: Double, lng: Double, currentClosestBusStop: String): String {
            if (busStopList.isEmpty()) {
                Log.e("getTheClosestBusStop", "busStopList is empty. Returning currentClosestBusStop.")
                return currentClosestBusStop
            }

            val busStopWithShortestDistance = findTheBusStopWithClosestDistance(lat, lng)
            return if (currentClosestBusStop == "none" ||
                (isBusStopInSequence(currentClosestBusStop, busStopWithShortestDistance) &&
                        isBusStopClose(lat, lng, busStopWithShortestDistance))) {
                busStopWithShortestDistance
            } else {
                currentClosestBusStop
            }
        }

        /**
         * Updates the list of bus stops for proximity calculations.
         *
         * @param busStops The list of bus stops.
         */
        fun setBusStopList(busStops: List<BusStopInfo>) {
            busStopList = busStops
            Log.d("BusStopProximityManager", "setBusStopList called. Updated busStopList: $busStopList")
        }

        /**
         * Finds the bus stop with the closest distance to the given location.
         *
         * @param lat Latitude of the current location.
         * @param lng Longitude of the current location.
         *
         * @return The name of the closest bus stop.
         */
        private fun findTheBusStopWithClosestDistance(lat: Double, lng: Double): String {
            var minDistance = Double.MAX_VALUE
            var closestBusStopName = ""

            if (busStopList.isEmpty()) {
                Log.e("busStopList 1", "Bus stop list is empty.")
            }

            Log.d("busStopList 1", busStopList.toString())

            for (busStop in busStopList) {
                val distance = calculateDistance(lat, lng, busStop.latitude, busStop.longitude)
                if (distance < minDistance) {
                    minDistance = distance
                    closestBusStopName = busStop.busStopName
                }
            }

            return closestBusStopName
        }

        /**
         * Calculates the distance between two geographical locations.
         *
         * @param lat1 Latitude of the first location.
         * @param lng1 Longitude of the first location.
         * @param lat2 Latitude of the second location.
         * @param lng2 Longitude of the second location
         * .
         * @return The distance between the two locations in meters.
         */
        private fun calculateDistance(
            lat1: Double,
            lng1: Double,
            lat2: Double,
            lng2: Double
        ): Double {
            val startPoint = Location("start")
            startPoint.latitude = lat1
            startPoint.longitude = lng1

            val endPoint = Location("end")
            endPoint.latitude = lat2
            endPoint.longitude = lng2

            return startPoint.distanceTo(endPoint).toDouble()
        }


        /**
         * Checks if a bus stop is within n meters of the given location.
         *
         * @param lat Latitude of the current location.
         * @param lng Longitude of the current location.
         * @param busStopName Name of the bus stop to check.
         *
         * @return True if the bus stop is close, false otherwise.
         */
        private fun isBusStopClose(lat: Double, lng: Double, busStopName: String): Boolean {
            if (busStopList.isEmpty()) {
                Log.e("busStopList 2", "Bus stop list is empty.")
            }
            Log.d("busStopList 2", busStopList.toString())
            val busStop = busStopList.find { it.busStopName == busStopName } ?: return false
            val distance = calculateDistance(lat, lng, busStop.latitude, busStop.longitude)
            return distance < 300 // Return true if the bus is within n meters radius
        }

        /**
         * Checks if the next bus stop is in sequence relative to the current bus stop.
         *
         * @param currentBusStopName Name of the current bus stop.
         * @param nextBusStopName Name of the next bus stop.
         *
         * @return True if the next bus stop is in sequence, false otherwise.
         */
        private fun isBusStopInSequence(currentBusStopName: String, nextBusStopName: String): Boolean {

            if (busStopList.isEmpty()) {
                Log.e("busStopList 3", "Bus stop list is empty.")
            }

            Log.d("busStopList 3", busStopList.toString())

            for(i in 0 until busStopList.size - 1){
                if(busStopList[i].busStopName == currentBusStopName && busStopList[i+1].busStopName == nextBusStopName){
                    return true;
                }
            }

            return busStopList.last().busStopName == currentBusStopName && busStopList.first().busStopName == nextBusStopName
        }

        /**
         * Gets the next bus stop in the sequence based on the current assigned bus stop.
         *
         * @param currentAssignedBusStop Name of the current assigned bus stop.
         *
         * @return The next bus stop information or null if not found.
         */
        fun getNextBusStopInSequenceOffline(currentAssignedBusStop: String): BusStopInfo? {

            if (busStopList.isEmpty()) {
                Log.e("busStopList 4", "Bus stop list is empty.")
            }

            Log.d("busStopList 4", busStopList.toString())

            // Check if the currentAssignedBusStop is the last bus stop in the list
            if (currentAssignedBusStop == busStopList.lastOrNull()?.busStopName) {
                return BusStopInfo(
                    busStopList.first().latitude,
                    busStopList.first().longitude,
                    busStopList.first().busStopName
                )
            }

            // Iterate through the bus stop list to find the next bus stop
            for (i in 0 until busStopList.size - 1) {
                if (busStopList[i].busStopName == currentAssignedBusStop) {
                    return BusStopInfo(
                        busStopList[i + 1].latitude,
                        busStopList[i + 1].longitude,
                        busStopList[i + 1].busStopName
                    )
                }
            }

            return null // Handle case where bus stop is not found or list is empty
        }

        /**
         * Gets the next bus stop in the sequence based on the current assigned bus stop.
         *
         * @param currentAssignedBusStop Name of the current assigned bus stop.
         *
         * @return The next bus stop information or null if not found.
         */
        fun getNextBusStopInSequenceOnline(currentAssignedBusStop: String): BusStopInfo? {

            if (busStopList.isEmpty()) {
                Log.e("busStopList 5", "Bus stop list is empty.")
            }
            Log.d("busStopList 5", busStopList.toString())

            // Check if the currentAssignedBusStop is the last bus stop in the list
            if (currentAssignedBusStop == busStopList.lastOrNull()?.busStopName) {
                return BusStopInfo(
                    busStopList.first().latitude,
                    busStopList.first().longitude,
                    busStopList.first().busStopName
                )
            }

            // Iterate through the bus stop list to find the next bus stop
            for (i in 0 until busStopList.size - 1) {
                if (busStopList[i].busStopName == currentAssignedBusStop) {
                    return BusStopInfo(
                        busStopList[i + 1].latitude,
                        busStopList[i + 1].longitude,
                        busStopList[i + 1].busStopName
                    )
                }
            }

            return null // Handle case where bus stop is not found or list is empty
        }

    }
}