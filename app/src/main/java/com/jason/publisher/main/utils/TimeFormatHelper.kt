package com.jason.publisher.main.utils

import android.util.Log

object TimeFormatHelper {
    /**
     * Formats seconds into "xx mins yy seconds" format if >= 60 seconds,
     * otherwise returns "xx seconds"
     *
     * @param totalSeconds Total seconds to format
     * @return Formatted string like "5 mins 30 seconds" or "45 seconds"
     */
    fun formatSecondsToMinutesAndSeconds(totalSeconds: Int): String {
        return if (totalSeconds >= 60) {
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            if (secs > 0) {
                "$mins mins $secs seconds"
            } else {
                "$mins mins"
            }
        } else {
            "$totalSeconds seconds"
        }
    }

    /**
     * Formats seconds into "xx mins yy seconds" format for logging
     * Always shows both minutes and seconds for consistency
     *
     * @param totalSeconds Total seconds to format
     * @return Formatted string like "5 mins 30 seconds"
     */
    fun formatSecondsForLog(totalSeconds: Int): String {
        val mins = totalSeconds / 60
        val secs = totalSeconds % 60
        return "$mins mins $secs seconds"
    }
}
