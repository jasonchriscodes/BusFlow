package com.jason.publisher.main.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.jason.publisher.main.`interface`.LocationListener

/**
 * Class responsible for managing location updates.
 *
 * @param context The application context.
 */
class LocationManager(private val context: Context) {

    private val fusLocation: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Gets the current location and notifies the listener with the current location.
     *
     * @param listener The listener to be notified with the current location.
     */
    fun getCurrentLocation(listener: LocationListener) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission is not granted, so return without getting location
            return
        }
        fusLocation.lastLocation.addOnSuccessListener { location ->
            location?.let {
                listener.onLocationUpdate(it)
            }
        }
    }
}