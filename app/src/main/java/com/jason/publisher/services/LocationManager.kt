package com.jason.publisher.services

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.jason.publisher.LocationListener
import com.jason.publisher.MainActivity

/**
 * Class responsible for managing location updates.
 *
 * @param context The application context.
 */
class LocationManager(private val context: Context) {

    private val fusLocation: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    private val locationRequest: LocationRequest = LocationRequest.create().apply {
        interval = 1000
        fastestInterval = 500
        priority = LocationRequest.PRIORITY_HIGH_ACCURACY
    }

    private var locationCallback: LocationCallback? = null

    /**
     * Starts location updates and notifies the listener with the updated location.
     *
     * @param listener The listener to be notified with location updates.
     */
    fun startLocationUpdates(listener: LocationListener) {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(p0: LocationResult) {
                p0.lastLocation?.let { loc ->
                    listener.onLocationUpdate(loc)
                }
            }
        }
        checkLocationPermission()
    }

    /**
     * Checks if the location permission is granted. If not, it requests the permission and keeps prompting until the user grants it.
     */
    fun checkLocationPermission() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission is not granted
            showWarningDialog()
        } else {
            // Permission is granted, start requesting location updates
            fusLocation.requestLocationUpdates(
                locationRequest,
                locationCallback as LocationCallback,
                null
            )
        }
    }

    /**
     * Displays a warning dialog that informs the user that the application needs location access.
     */
    private fun showWarningDialog() {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Warning")
        builder.setMessage("VLRS needs location access")
        builder.setCancelable(false)
        builder.setOnKeyListener { _, key, _ ->
            key == KeyEvent.KEYCODE_BACK
        }
        builder.setPositiveButton("OK") { _, _ ->
            ActivityCompat.requestPermissions(
                context as Activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                123
            )
        }
        builder.show()
    }

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
