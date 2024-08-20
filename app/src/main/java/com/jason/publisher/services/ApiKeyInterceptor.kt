package com.jason.publisher.services

import com.jason.publisher.BuildConfig
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class ApiKeyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest: Request = chain.request()
        val requestWithHeaders: Request = originalRequest.newBuilder()
            .header("Authorization", "Bearer ${BuildConfig.MAILERSEND_API_KEY}")
            .header("Content-Type", "application/json")
            .build()
        return chain.proceed(requestWithHeaders)
    }
}
