package com.jason.publisher.services

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Builder object for creating instances of Retrofit service interfaces.
 */
object ApiServiceBuilder {
    private const val BASE_URL = "http://43.226.218.97:8080/api/v1/"
    private const val EMAIL_BASE_URL = "https://api.mailersend.com/v1/"  // Add your email service URL here

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val emailRetrofit = Retrofit.Builder()
        .baseUrl(EMAIL_BASE_URL)
        .client(OkHttpClient.Builder()
            .addInterceptor(ApiKeyInterceptor())  // Adding the interceptor for MailerSend
            .build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    /**
     * Builds a service instance for the specified service interface.
     *
     * @param T The type of the service interface.
     * @param service The service interface class.
     * @return An instance of the specified service interface.
     */
    fun <T> buildService(service: Class<T>): T {
        return retrofit.create(service)
    }

    /**
     * Builds a service instance for the email service interface with the EMAIL_BASE_URL.
     *
     * @param T The type of the service interface.
     * @param service The service interface class.
     * @return An instance of the specified service interface.
     */
    fun <T> buildEmailService(service: Class<T>): T {
        return emailRetrofit.create(service)
    }
}
