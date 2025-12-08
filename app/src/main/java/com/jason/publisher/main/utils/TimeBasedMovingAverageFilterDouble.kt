package com.jason.publisher.main.utils

import android.annotation.SuppressLint
import android.util.Log

// Place this near the top of your file (or in a separate file if you prefer)
data class DoubleEntry(val timestamp: Long, val value: Double)

class TimeBasedMovingAverageFilterDouble(
    private val windowMillis: Long,
    private val minValidValue: Double? = null  // Optional minimum valid value to filter out invalid measurements
) {
    private val entries = mutableListOf<DoubleEntry>()
    private val TAG = "TimeBasedMovingAverageFilter"

    /**
     * Adds a new t₁ measurement.
     * Removes any old entries beyond the window and returns the current average.
     * ✅ FIX: Skip zero/low speed values when bus is stopped (e.g., at red lights)
     * to prevent speed spikes affecting schedule status.
     */
    @SuppressLint("LongLogTag")
    fun add(value: Double): Double {
        val now = System.currentTimeMillis()

        // ✅ FIX: Only add valid speed values (above threshold) to prevent spikes from stopped bus
        // When bus is stopped at red lights, speed is 0 or very low, which can cause spikes
        // in the moving average when the bus starts moving again
        // minValidValue is optional - if set, filter out values below threshold
        if (minValidValue != null && value < minValidValue) {
            // Don't add low/zero speed values, but still clean up old entries
            while (entries.isNotEmpty() && now - entries.first().timestamp > windowMillis) {
                entries.removeAt(0)
            }
            // Return last valid average instead of adding invalid value
            return average()
        }

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