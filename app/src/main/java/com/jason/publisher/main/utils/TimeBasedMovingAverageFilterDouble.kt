package com.jason.publisher.main.utils

import android.annotation.SuppressLint
import android.util.Log

// Place this near the top of your file (or in a separate file if you prefer)
data class DoubleEntry(val timestamp: Long, val value: Double)

class TimeBasedMovingAverageFilterDouble(private val windowMillis: Long) {
    private val entries = mutableListOf<DoubleEntry>()
    private val TAG = "TimeBasedMovingAverageFilter"

    /**
     * Adds a new t₁ measurement.
     * Removes any old entries beyond the window and returns the current average.
     */
    @SuppressLint("LongLogTag")
    fun add(value: Double): Double {
        val now = System.currentTimeMillis()
        entries.add(DoubleEntry(now, value))

        while (entries.isNotEmpty() && now - entries.first().timestamp > windowMillis) {
            entries.removeAt(0)
        }

        // ✅ OPTIMIZED: Removed verbose logging - only calculate average
        val averageResult = average()

        return averageResult
    }

    /**
     * Returns the average t₁ value in the current time window.
     */
    fun average(): Double {
        return if (entries.isEmpty()) 0.0 else entries.map { it.value }.average()
    }
}