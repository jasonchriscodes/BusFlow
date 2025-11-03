package com.jason.publisher.main.utils

import android.content.Context
import android.content.Context.MODE_PRIVATE

object LastLocationStore {
    private const val PREF = "sos_last_location"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"

    fun save(context: Context, lat: Double, lon: Double) {
        context.getSharedPreferences(PREF, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAT, lat.toString())
            .putString(KEY_LON, lon.toString())
            .apply()
    }

    fun load(context: Context): Pair<Double, Double>? {
        val sp = context.getSharedPreferences(PREF, MODE_PRIVATE)
        val lat = sp.getString(KEY_LAT, null)?.toDoubleOrNull()
        val lon = sp.getString(KEY_LON, null)?.toDoubleOrNull()
        return if (lat != null && lon != null) lat to lon else null
    }
}

