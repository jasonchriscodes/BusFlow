//package com.jason.publisher.model
//
//import com.google.gson.Gson
//import com.google.gson.annotations.SerializedName
//
//data class RouteData(
//    @SerializedName("starting_point") val startingPoint: StartingPoint,
//    @SerializedName("next_points") val nextPoints: List<NextPoint>
//) {
//    data class StartingPoint(
//        val latitude: Double,
//        val longitude: Double,
//        val address: String
//    )
//
//    data class NextPoint(
//        val latitude: Double,
//        val longitude: Double,
//        val address: String,
//        val duration: String,
//        @SerializedName("route_coordinates") val routeCoordinates: List<List<Double>>
//    )
//
//    /**
//     * Parses a JSON string into a Triple containing:
//     * - List of BusRoute (route)
//     * - List of BusStop (stops)
//     * - List of durations between stops
//     */
//    companion object {
//        fun fromJson(jsonString: String): Triple<List<BusRoute>, List<BusStop>, List<Double>> {
//            val routeDataList: List<RouteData> = Gson().fromJson(jsonString, Array<RouteData>::class.java).toList()
//
//            // Since your JSON is an array, process only the first element (assuming only one route exists)
//            val routeData = routeDataList.firstOrNull() ?: throw IllegalArgumentException("Invalid JSON format")
//
//            val route = mutableListOf<BusRoute>()
//            val stops = mutableListOf<BusStop>()
//            val durationBetweenStops = mutableListOf<Double>()
//
//            // Add all route coordinates
//            for (nextPoint in routeData.nextPoints) {
//                for (coord in nextPoint.routeCoordinates) {
//                    route.add(BusRoute(latitude = coord[1], longitude = coord[0]))
//                }
//            }
//
//            // Add all stop points
//            stops.add(BusStop(latitude = routeData.startingPoint.latitude, longitude = routeData.startingPoint.longitude))
//            for (nextPoint in routeData.nextPoints) {
//                stops.add(BusStop(latitude = nextPoint.latitude, longitude = nextPoint.longitude))
//            }
//
//            // Extract duration values
//            for (nextPoint in routeData.nextPoints) {
//                val durationValue = nextPoint.duration.split(" ")[0].toDoubleOrNull() ?: 0.0
//                durationBetweenStops.add(durationValue)
//            }
//
//            return Triple(route, stops, durationBetweenStops)
//        }
//    }
//}
