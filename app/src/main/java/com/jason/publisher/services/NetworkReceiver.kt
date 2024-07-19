import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * BroadcastReceiver to monitor network connectivity changes.
 * Notifies the listener when network becomes available or unavailable.
 */
class NetworkReceiver(private val listener: NetworkListener) : BroadcastReceiver() {
    interface NetworkListener {
        fun onNetworkAvailable()
        fun onNetworkUnavailable()
    }

    /**
     * Called when the network connectivity changes.
     *
     * @param context The context in which the receiver is running.
     * @param intent The intent being received.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onReceive(context: Context?, intent: Intent?) {
        val connectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)

        if (networkCapabilities != null && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            listener.onNetworkAvailable()
        } else {
            listener.onNetworkUnavailable()
        }
    }
}
