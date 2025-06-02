package com.jason.publisher.main.services

import com.jason.publisher.main.model.AttributesData
import com.jason.publisher.main.model.TelemetryEntry
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Interface defining API endpoints for attribute-related operations.
 */
interface ApiService {
    /**
     * Sends attributes data to the specified URL.
     * @param url The URL to send the attributes data.
     * @param contentType The content type of the request.
     * @param requestBody The body of the request.
     * @return A [Call] object representing the asynchronous request.
     */
    @POST
    fun postAttributes(
        @Url url: String,
        @Header("Content-Type") contentType: String,
        @Body requestBody: Any
    ): Call<Void>

    /**
     * Retrieves attributes data from the specified URL.
     * @param url The URL to retrieve the attributes data.
     * @param contentType The content type of the request.
     * @param clientKeys The client keys.
     * @return A [Call] object representing the asynchronous request.
     */
    @GET
    fun getAttributes(
        @Url url: String,
        @Header("Content-Type") contentType: String,
        @Query("clientKeys") clientKeys: String
    ): Call<ClientAttributesResponse>

    companion object {
        const val BASE_URL = "http://43.226.218.97:8080/api/v1/"
    }

    // → NEW: fetch “latest telemetry” for a given device (latitude, longitude, bearing)
    @GET("{accessToken}/telemetry")
    fun getLatestTelemetry(
        @Path("accessToken") accessToken: String,
        @Query("keys") keys: String,           // e.g. "latitude,longitude,bearing"
        @Query("limit") limit: Int = 1         // only the very latest point
    ): Call<Map<String, List<TelemetryEntry>>>
}

/**
 * Data class representing the response containing client attributes.
 * @property client The client attributes data.
 */
data class ClientAttributesResponse(
    val client: AttributesData
)