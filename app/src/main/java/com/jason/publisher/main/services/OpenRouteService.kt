/**
 *
 * This class uses OpenRouteService API to provide the following functions:
 *
 * The main functionality provided is:
 * - Estimating the travel time from one point to another.
 * - Calculate distance between two points.
 *
 * @see getEstimateTimeFromPointToPoint
 *
 * Example usage:
 * ```
 * val eta = OpenRouteService.getEstimateTimeFromPointToPoint(52.370216, 4.895168, 48.856613, 2.352222)
 * ```
 */

package com.jason.publisher.main.services

import android.annotation.SuppressLint
import com.jason.publisher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class OpenRouteService {
    companion object {
        private const val API_KEY = BuildConfig.OpenRouteService_API_KEY
        private val client = OkHttpClient()

        /**
         * Estimates the travel time from one point to another using the OpenRouteService (ORS) API.
         *
         * @param originLatitude The latitude of the starting point.
         * @param originLongitude The longitude of the starting point.
         * @param destinationLatitude The latitude of the destination point.
         * @param destinationLongitude The longitude of the destination point.
         *
         * @return The estimated travel time in the format HH:MM:SS.
         *
         * @throws IOException if the network request fails or if no routes are found.
         */
        @SuppressLint("DefaultLocale")
        suspend fun getEstimateTimeFromPointToPoint(
            originLatitude: Double, originLongitude: Double,
            destinationLatitude: Double, destinationLongitude: Double
        ): String = withContext(Dispatchers.IO) {
//            val url = "https://api.openrouteservice.org/v2/directions/driving-car"
            val url = "http://43.226.218.99:8080/ors/v2/directions/driving-car"


            // Construct the coordinates array
            val coordinates = JSONArray().apply {
                put(JSONArray().apply {
                    put(originLongitude)
                    put(originLatitude)
                })
                put(JSONArray().apply {
                    put(destinationLongitude)
                    put(destinationLatitude)
                })
            }

            // Construct JSON body
            val jsonBody = JSONObject().apply {
                put("coordinates", coordinates)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $API_KEY")
                .post(requestBody)
                .build()

            // Execute the request
            val response = client.newCall(request).execute()

            // Handle response
            if (!response.isSuccessful) {
                throw IOException("Unexpected code $response")
            }

            // Parse response and calculate ETA
            val jsonResponse = response.body?.string()
            val jsonObject = JSONObject(jsonResponse)
            val routes = jsonObject.getJSONArray("routes")
            if (routes.length() > 0) {
                val route = routes.getJSONObject(0)
                val durationSeconds = route.getJSONObject("summary").getInt("duration") // in seconds

                val hours = durationSeconds / 3600
                val minutes = (durationSeconds % 3600) / 60
                val seconds = durationSeconds % 60

                return@withContext String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                throw IOException("No routes found")
            }
        }
    }
}