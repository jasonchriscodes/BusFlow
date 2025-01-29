package com.jason.publisher.utils

import NetworkReceiver
import android.app.Activity
import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jason.publisher.R

object NetworkStatusHelper {

    private var networkReceiver: NetworkReceiver? = null

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
}
