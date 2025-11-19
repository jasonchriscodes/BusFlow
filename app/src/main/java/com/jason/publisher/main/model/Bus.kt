package com.jason.publisher.main.model

import android.os.Parcelable
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

/**
 * Data class representing a bus.
 *
 * @property shared The shared data related to the bus.
 */

@Parcelize
data class Bus(
    @field:SerializedName("shared")
    val shared: Shared? = null
): Parcelable

/**
 * Data class representing an item with latitude and longitude coordinates.
 *
 * @property latitude The latitude coordinate.
 * @property longitude The longitude coordinate.
 */
@Parcelize
data class JsonMember1Item(

    @field:SerializedName("latitude")
    val latitude: Double? = null,

    @field:SerializedName("longitude")
    val longitude: Double? = null
) : Parcelable

/**
 * Data class representing shared data.
 *
 * @property busStop1 The list of bus stop data in the new format.
 * @property busRoute1 The list of bus route data in the new format.
 * @property message The message related to the bus.
 * @property config The bus configuration data.
 * @property bearing The list of bus bearing data.
 * @property bearingCustomer The list of customer-specific bus bearing data.
 */
@Parcelize
data class Shared(
    @field:SerializedName("busStop")
    val busStop1: List<BusStop>? = null,

    @field:SerializedName("busRoute")
    val busRoute1: List<BusRoute>? = null,

    @field:SerializedName("busStop2")
    val busStop: List<BusStop>? = null,

    @field:SerializedName("busRoute2")
    val busRoute: List<BusRoute>? = null,

    @field:SerializedName("message")
    val message: String? = null,

    @field:SerializedName("config")
    val config: BusConfig? = null,

    @field:SerializedName("bearing")
    val bearing: List<BusBearing>? = null,

    @field:SerializedName("bearingCustomer")
    val bearingCustomer: List<BusBearingCustomer>? = null,

    @field:SerializedName("busRouteData")
    val busRouteData1: List<RouteData>? = null,

    @field:SerializedName("scheduleData")
    val scheduleData1: List<ScheduleItem>? = null
) : Parcelable

/**
 * Data class representing bus bearing data.
 *
 * @property bearing The bearing angle.
 */
@Parcelize
data class BusBearing(
    @field:SerializedName("bearing")
    val bearing: Double? = null
): Parcelable

/**
 * Data class representing customer-specific bus bearing data.
 *
 * @property bearingCustomer The customer-specific bearing angle.
 */
@Parcelize
data class BusBearingCustomer(
    @field:SerializedName("bearing")
    val bearingCustomer: Double? = null
): Parcelable

/**
 * Data class representing bus configuration data.
 *
 * @property busConfig The list of bus items.
 */
@Parcelize
data class BusConfig(
    @field:SerializedName("busConfig")
    val busConfig: List<BusItem>

) : Parcelable

/**
 * Data class representing a bus item.
 *
 * @property aid The aid of the bus.
 * @property bus The name of the bus.
 * @property accessToken The access token of the bus.
 */
@Parcelize
data class BusItem(
    @field:SerializedName("aid")
    val aid: String,

    @field:SerializedName("bus")
    val bus: String,

    @field:SerializedName("accessToken")
    val accessToken: String

) : Parcelable

/**
 * Data class representing bus stop data in the new format.
 *
 * @property latitude The latitude of the bus stop.
 * @property longitude The longitude of the bus stop.
 */
@Parcelize
data class BusStop(
    @field:SerializedName("latitude")
    val latitude: Double? = null,

    @field:SerializedName("longitude")
    val longitude: Double? = null,

    var address: String? = null
) : Parcelable

/**
 * Data class representing bus route data in the new format.
 *
 * @property latitude The latitude of the bus route point.
 * @property longitude The longitude of the bus route point.
 */
@Parcelize
data class BusRoute(
    @field:SerializedName("latitude")
    val latitude: Double? = null,

    @field:SerializedName("longitude")
    val longitude: Double? = null
) : Parcelable

@Parcelize
data class RouteData(
    @SerializedName("starting_point") val startingPoint: StartingPoint,
    @SerializedName("next_points") val nextPoints: List<NextPoint>
) : Parcelable {

    @Parcelize
    data class StartingPoint(
        val latitude: Double,
        val longitude: Double,
        val address: String
    ) : Parcelable

    @Parcelize
    data class NextPoint(
        val latitude: Double,
        val longitude: Double,
        val address: String,
        val duration: String,
        @SerializedName("route_coordinates") val routeCoordinates: List<List<Double>>
    ) : Parcelable

    /**
     * Parses a JSON string into a Triple containing:
     * - List of BusRoute (route)
     * - List of BusStop (stops)
     * - List of durations between stops
     */
    companion object {
        fun fromJson(jsonString: String): Triple<List<BusRoute>, List<BusStop>, List<Double>> {
            val routeDataList: List<RouteData> = Gson().fromJson(jsonString, Array<RouteData>::class.java).toList()

            // Since your JSON is an array, process only the first element (assuming only one route exists)
            val routeData = routeDataList.firstOrNull() ?: throw IllegalArgumentException("Invalid JSON format")

            val route = mutableListOf<BusRoute>()
            val stops = mutableListOf<BusStop>()
            val durationBetweenStops = mutableListOf<Double>()

            // Add all route coordinates (with duplicate filtering)
            var previousCoordinate: BusRoute? = null
            for (nextPoint in routeData.nextPoints) {
                for (coord in nextPoint.routeCoordinates) {
                    val newCoordinate = BusRoute(latitude = coord[1], longitude = coord[0])
                    if (previousCoordinate == null || newCoordinate != previousCoordinate) {
                        route.add(newCoordinate)
                    }
                    previousCoordinate = newCoordinate
                }
            }

            // Add bus stops with address
            stops.add(
                BusStop(
                    latitude = routeData.startingPoint.latitude,
                    longitude = routeData.startingPoint.longitude,
                    address = routeData.startingPoint.address
                )
            )
            for (nextPoint in routeData.nextPoints) {
                stops.add(
                    BusStop(
                        latitude = nextPoint.latitude,
                        longitude = nextPoint.longitude,
                        address = nextPoint.address
                    )
                )
            }

            // Extract duration values
            for (nextPoint in routeData.nextPoints) {
                val durationValue = nextPoint.duration.split(" ")[0].toDoubleOrNull() ?: 0.0
                durationBetweenStops.add(durationValue)
            }

            return Triple(route, stops, durationBetweenStops)
        }
    }
}
