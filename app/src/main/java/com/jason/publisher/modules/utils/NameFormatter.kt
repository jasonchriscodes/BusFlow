package com.jason.publisher.modules.map.utils

import com.jason.publisher.main.model.ScheduleItem

private fun isTokenLike(s: String?): Boolean {
    if (s.isNullOrBlank()) return false
    val t = s.trim()
    return t.length in 20..40 && t.all { it.isLetterOrDigit() }
}

private fun safeRunName(item: ScheduleItem): String {
    if (!isTokenLike(item.runName)) return item.runName
    val from = item.busStops.firstOrNull()?.abbreviation ?: item.busStops.firstOrNull()?.name ?: "?"
    val to   = item.busStops.lastOrNull()?.abbreviation  ?: item.busStops.lastOrNull()?.name  ?: "?"
    return "${item.runNo} $from → $to"
}

fun formatPanelLabel(item: ScheduleItem): String {
    val from = item.busStops.firstOrNull()?.abbreviation ?: item.busStops.firstOrNull()?.name ?: "?"
    val to   = item.busStops.lastOrNull()?.abbreviation  ?: item.busStops.lastOrNull()?.name  ?: "?"
    return "${item.startTime} ${safeRunName(item)} $from → $to"
}