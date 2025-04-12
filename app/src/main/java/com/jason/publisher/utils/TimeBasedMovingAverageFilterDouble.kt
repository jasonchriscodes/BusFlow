package com.jason.publisher.utils

// Place this near the top of your file (or in a separate file if you prefer)
data class DoubleEntry(val timestamp: Long, val value: Double)

class TimeBasedMovingAverageFilterDouble(private val windowMillis: Long) {
    private val entries = mutableListOf<DoubleEntry>()

    /**
     * Adds a new t₁ measurement.
     * Removes any old entries beyond the window and returns the current average.
     */
    fun add(value: Double): Double {
        val now = System.currentTimeMillis()
        entries.add(DoubleEntry(now, value))
        while (entries.isNotEmpty() && now - entries.first().timestamp > windowMillis) {
            entries.removeAt(0)
        }
        return average()
    }

    /**
     * Returns the average t₁ value in the current time window.
     */
    fun average(): Double {
        return if (entries.isEmpty()) 0.0 else entries.map { it.value }.average()
    }
}
