package com.jason.publisher

import NetworkReceiver
import android.annotation.SuppressLint
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.jason.publisher.databinding.ActivityMainBinding
import com.jason.publisher.model.Bus
import com.jason.publisher.model.BusRoute
import com.jason.publisher.services.MqttManager
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapController
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mqttManager: MqttManager
    private lateinit var mapController: MapController
    private lateinit var connectionStatusTextView: TextView
    private lateinit var networkReceiver: NetworkReceiver
    private lateinit var dateTimeHandler: Handler
    private lateinit var dateTimeRunnable: Runnable

    private var latitude = 0.0
    private var longitude = 0.0
    private var bearing = 0.0F
    private var route: List<BusRoute> = emptyList()
    private var firstTime = true
    private var upcomingRoadName: String = "Unknown"

    companion object {
        const val SERVER_URI = "tcp://43.226.218.97:1883"
        const val CLIENT_ID = "jasonAndroidClientId"
        const val PUB_POS_TOPIC = "v1/devices/me/telemetry"
        private const val SUB_MSG_TOPIC = "v1/devices/me/attributes/response/+"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Retrieve AID passed from TimeTableActivity
        val aid = intent.getStringExtra("AID") ?: "Unknown"
        Log.d("MainActivity", "Received AID: $aid")

        // Initialize UI components
        connectionStatusTextView = binding.connectionStatusTextView

        // Initialize MQTT manager
        mqttManager = MqttManager(serverUri = SERVER_URI, clientId = CLIENT_ID)

        // Load configuration
        Configuration.getInstance().load(this, getSharedPreferences(getString(R.string.app_name), MODE_PRIVATE))

        // Setup the map
        setupMap()

        // Register and initialize the NetworkReceiver
        registerNetworkReceiver()

        // Connect and subscribe to MQTT
        connectAndSubscribe()

        // Initialize the date/time updater
        startDateTimeUpdater()
    }

    /** Sets up the map and configures the initial position and zoom level. */
    private fun setupMap() {
        mapController = binding.map.controller as MapController
        mapController.setZoom(18.0)
        mapController.setCenter(GeoPoint(latitude, longitude))
    }

    /** Connects to the MQTT broker and subscribes to the required topics. */
    private fun connectAndSubscribe() {
        mqttManager.connect { isConnected ->
            if (isConnected) {
                Log.d("MainActivity", "Connected to MQTT broker")
                subscribeSharedData()
            } else {
                Log.e("MainActivity", "Failed to connect to MQTT broker")
            }
        }
    }

    /** Subscribes to shared data from the MQTT broker and processes updates. */
    private fun subscribeSharedData() {
        mqttManager.subscribe(SUB_MSG_TOPIC) { message ->
            runOnUiThread {
                try {
                    // Parse incoming message
                    val data = Gson().fromJson(message, Bus::class.java)

                    // Update route data
                    route = data.shared?.busRoute1 ?: emptyList()
                    Log.d("subscribeSharedData", "Route: $route")

                    // If this is the first time, set up the route on the map
                    if (firstTime && route.isNotEmpty()) {
                        generateRouteMarkers(route)
                        firstTime = false
                    }

                    // Update upcoming road name if available
                    updateUpcomingRoadName()
                } catch (e: Exception) {
                    Log.e("subscribeSharedData", "Error processing shared data: ${e.message}")
                }
            }
        }
    }

    /** Generates route markers and polylines for the bus route on the map. */
    private fun generateRouteMarkers(busRoute: List<BusRoute>) {
        val routes = busRoute.map { GeoPoint(it.latitude!!, it.longitude!!) }
        val polyline = org.osmdroid.views.overlay.Polyline()
        polyline.setPoints(routes)

        val marker = Marker(binding.map)
        marker.position = GeoPoint(latitude, longitude)
        marker.rotation = bearing

        binding.map.overlays.add(polyline)
        binding.map.overlays.add(marker)
        binding.map.invalidate()
    }

    /** Updates the upcoming road name in the UI. */
    private fun updateUpcomingRoadName() {
        if (route.isNotEmpty()) {
            upcomingRoadName = "Test1" ?: "Unknown"
            binding.upcomingRoadTextView.text = "Upcoming Road: $upcomingRoadName"
        }
    }

    /** Starts a periodic task to update the current date and time in the UI. */
    @SuppressLint("SimpleDateFormat")
    private fun startDateTimeUpdater() {
        dateTimeHandler = Handler(Looper.getMainLooper())
        dateTimeRunnable = object : Runnable {
            override fun run() {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val currentDateTime = dateFormat.format(Date())
                binding.busDirectionBearing.text = currentDateTime
                dateTimeHandler.postDelayed(this, 1000) // Update every second
            }
        }
        dateTimeHandler.post(dateTimeRunnable)
    }

    /** Stops the date/time updater when the activity is destroyed. */
    private fun stopDateTimeUpdater() {
        dateTimeHandler.removeCallbacks(dateTimeRunnable)
    }

    /** Registers the network receiver to monitor and display network status. */
    private fun registerNetworkReceiver() {
        networkReceiver = NetworkReceiver(object : NetworkReceiver.NetworkListener {
            override fun onNetworkAvailable() {
                runOnUiThread {
                    connectionStatusTextView.text = "Connected"
                    connectionStatusTextView.setTextColor(getColor(android.R.color.holo_green_dark))
                    binding.networkStatusIndicator.setBackgroundResource(R.drawable.circle_shape_green)
                }
            }

            override fun onNetworkUnavailable() {
                runOnUiThread {
                    connectionStatusTextView.text = "Disconnected"
                    connectionStatusTextView.setTextColor(getColor(android.R.color.holo_red_dark))
                    binding.networkStatusIndicator.setBackgroundResource(R.drawable.circle_shape_red)
                }
            }
        })
        val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(networkReceiver, intentFilter)
    }

    /** Cleans up resources and unregisters the network receiver on activity destruction. */
    override fun onDestroy() {
        mqttManager.disconnect()
        unregisterReceiver(networkReceiver)
        stopDateTimeUpdater()
        super.onDestroy()
    }
}
