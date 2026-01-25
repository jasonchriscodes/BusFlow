package com.jason.publisher.modules.map.services

import android.content.Context
import androidx.core.content.edit

object LastLocationStore {
    private const val PREF = "sos_last_location"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"

    fun save(context: Context, lat: Double, lon: Double) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_LAT, lat.toString())
                putString(KEY_LON, lon.toString())
            }
    }

}