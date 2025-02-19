package com.jason.publisher.utils

import NetworkReceiver
import android.app.Activity
import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jason.publisher.R

object NetworkStatusHelper {

    var networkReceiver: NetworkReceiver? = null

    /** Initializes and sets up network status views */
    fun setupNetworkStatus(activity: Activity, connectionStatusTextView: TextView, networkStatusIndicator: View) {
        networkReceiver = NetworkReceiver(object : NetworkReceiver.NetworkListener {
            override fun onNetworkAvailable() {
                activity.runOnUiThread {
                    connectionStatusTextView.text = "Connected"
                    connectionStatusTextView.setTextColor(ContextCompat.getColor(activity, android.R.color.holo_green_dark))
                    networkStatusIndicator.setBackgroundResource(R.drawable.circle_shape_green)
                }
            }

            override fun onNetworkUnavailable() {
                activity.runOnUiThread {
                    connectionStatusTextView.text = "Disconnected"
                    connectionStatusTextView.setTextColor(ContextCompat.getColor(activity, android.R.color.holo_red_dark))
                    networkStatusIndicator.setBackgroundResource(R.drawable.circle_shape_red)
                }
            }
        })

        val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        activity.registerReceiver(networkReceiver, intentFilter)
    }

    /** Unregisters the network receiver when activity is destroyed */
    fun unregisterReceiver(activity: Activity) {
        networkReceiver?.let {
            activity.unregisterReceiver(it)
        }
    }

    /**
     * Checks if the device has an active internet connection.
     * @param context The application context.
     * @return True if network is available, false otherwise.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            activeNetworkInfo != null && activeNetworkInfo.isConnected
        }
    }
}
