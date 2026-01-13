package com.jason.publisher.main.utils

import java.util.Calendar
import java.util.Date

/**
 * Convert a three-part String (delimited by an ":") to a Date
 */
fun String.parseTimeToday(): Date {
    val parts = this.split(":")
    if (parts.size != 3) return Date()
    return Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, parts[0].toInt())
        set(Calendar.MINUTE, parts[1].toInt())
        set(Calendar.SECOND, parts[2].toInt())
        set(Calendar.MILLISECOND, 0)
    }.time
}