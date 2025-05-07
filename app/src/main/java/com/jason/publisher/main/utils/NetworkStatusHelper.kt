package com.jason.publisher.main.utils

import NetworkReceiver
import android.app.Activity
import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jason.publisher.R

object NetworkStatusHelper {

    var networkReceiver: NetworkReceiver? = null
    private var isReceiverRegistered = false // ✅ Added flag to track registration

    /** Initializes and sets up network status views */
    fun setupNetworkStatus(activity: Activity, connectionStatusTextView: TextView, networkStatusIndicator: View) {
        // ✅ Initial Check for Immediate Status Update
        if (isNetworkAvailable(activity)) {
            Log.d("NetworkStatusHelper", "✅ Initial Check: Network is Online")
            connectionStatusTextView.text = "Online"
            connectionStatusTextView.setTextColor(ContextCompat.getColor(activity, android.R.color.white))
            networkStatusIndicator.setBackgroundResource(R.drawable.circle_shape_green)
        } else {
            Log.d("NetworkStatusHelper", "❌ Initial Check: Network is Offline")
            connectionStatusTextView.text = "Offline"
            connectionStatusTextView.setTextColor(ContextCompat.getColor(activity, android.R.color.white))
            networkStatusIndicator.setBackgroundResource(R.drawable.circle_shape_grey)
        }

        if (!isReceiverRegistered) {  // ✅ Only register if not already registered
            networkReceiver = NetworkReceiver(object : NetworkReceiver.NetworkListener {
                override fun onNetworkAvailable() {
                    Log.d("NetworkReceiver", "🔄 Network detected as AVAILABLE")
                    activity.runOnUiThread {
                        connectionStatusTextView.text = "Online"
                        connectionStatusTextView.setTextColor(ContextCompat.getColor(activity, android.R.color.white))
                        networkStatusIndicator.setBackgroundResource(R.drawable.circle_shape_green)
                    }
                }

                override fun onNetworkUnavailable() {
                    Log.d("NetworkReceiver", "🔄 Network detected as UNAVAILABLE")
                    activity.runOnUiThread {
                        connectionStatusTextView.text = "Offline"
                        connectionStatusTextView.setTextColor(ContextCompat.getColor(activity, android.R.color.white))
                        networkStatusIndicator.setBackgroundResource(R.drawable.circle_shape_grey)
                    }
                }
            })

            val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            activity.registerReceiver(networkReceiver, intentFilter)
            isReceiverRegistered = true  // ✅ Mark receiver as registered
        }
    }

    /** Unregisters the network receiver when activity is destroyed */
    fun unregisterReceiver(activity: Activity) {
        if (isReceiverRegistered) {  // ✅ Only unregister if registered
            networkReceiver?.let {
                activity.unregisterReceiver(it)
            }
            isReceiverRegistered = false // ✅ Mark receiver as unregistered
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
