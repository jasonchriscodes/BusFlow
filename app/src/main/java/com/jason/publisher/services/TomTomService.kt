/**
 *
 * This class uses the TomTom API, specifically, the Routing API to provide the following functions:
 *
 * The main functionality provided is:
 * - Estimating the travel time from one point to another.
 * - Calculate distance between two points.
 *
 * @see getEstimateTimeFromPointToPoint
 * @see getDistanceBetweenPointToPoint
 *
 * Example usage:
 * ```
 * val eta = TomTomService.getEstimateTimeFromPointToPoint(52.370216, 4.895168, 48.856613, 2.352222)
 * val distance = TomTomService.getDistanceBetweenPointToPoint(52.370216, 4.895168, 48.856613, 2.352222)
 * ```
 */

package com.jason.publisher.services

import android.annotation.SuppressLint
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jason.publisher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class TomTomService {
    companion object TomTomService {
        private const val API_KEY = BuildConfig.TOMTOM_API_KEY
        private val client = OkHttpClient()
        private val gson = Gson()

        /**
         * Estimates the travel time from one point to another using the TomTom Routing API.
         *
         * @param originLatitude The latitude of the starting point.
         * @param originLongitude The longitude of the starting point.
         * @param destinationLatitude The latitude of the destination point.
         * @param destinationLongitude The longitude of the destination point.
         *
         * @return The estimated travel time in the format HH:MM:SS.
         *
         * @throws IOException if the network request fails.
         */
        @SuppressLint("DefaultLocale")
        suspend fun getEstimateTimeFromPointToPoint(
            originLatitude: Double, originLongitude: Double,
            destinationLatitude: Double, destinationLongitude: Double
        ): String = withContext(Dispatchers.IO) {
            val url = "https://api.tomtom.com/routing/1/calculateRoute/$originLatitude,$originLongitude:$destinationLatitude,$destinationLongitude/json?key=$API_KEY"
            val request = Request.Builder().url(url).build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            val jsonResponse = response.body?.string()
            val jsonObject = gson.fromJson(jsonResponse, JsonObject::class.java)
            val travelTimeInSeconds = jsonObject.getAsJsonArray("routes")
                .get(0).asJsonObject
                .getAsJsonObject("summary")
                .get("travelTimeInSeconds").asInt

            val hours = TimeUnit.SECONDS.toHours(travelTimeInSeconds.toLong())
            val minutes = TimeUnit.SECONDS.toMinutes(travelTimeInSeconds.toLong()) % 60
            val seconds = travelTimeInSeconds % 60

            return@withContext String.format("%02d:%02d:%02d", hours, minutes, seconds)
        }

        /**
         * Calculates the distance between two points using the TomTom Routing API.
         *
         * @param originLatitude The latitude of the starting point.
         * @param originLongitude The longitude of the starting point.
         * @param destinationLatitude The latitude of the destination point.
         * @param destinationLongitude The longitude of the destination point.
         *
         * @return The distance between the points in kilometers.
         *
         * @throws IOException if the network request fails.
         */
        suspend fun getDistanceBetweenPointToPoint(
            originLatitude: Double, originLongitude: Double,
            destinationLatitude: Double, destinationLongitude: Double
        ): Double = withContext(Dispatchers.IO) {
            val url = "https://api.tomtom.com/routing/1/calculateRoute/$originLatitude,$originLongitude:$destinationLatitude,$destinationLongitude/json?key=$API_KEY"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                val jsonResponse = response.body?.string()
                val jsonObject = gson.fromJson(jsonResponse, JsonObject::class.java)
                val lengthInMeters = jsonObject.getAsJsonArray("routes")
                    .get(0).asJsonObject
                    .getAsJsonObject("summary")
                    .get("lengthInMeters").asDouble

                return@withContext lengthInMeters / 1000.0 // Convert to kilometers
            }
        }
    }
}