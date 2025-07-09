package com.jason.publisher.main.helpers

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.activity.MapActivity
import com.jason.publisher.main.model.Bus
import com.jason.publisher.services.ClientAttributesResponse
import com.jason.publisher.services.ApiService
import com.jason.publisher.main.services.MqttManager
import org.checkerframework.checker.units.qual.min
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.layer.overlay.Marker
import kotlin.math.min

/**
 * Helper to encapsulate all MQTT-related logic extracted from MapActivity.
 * Methods have identical names and bodies as in MapActivity.
 */
class MqttHelper(
    private val owner: MapActivity,
    private val binding: ActivityMapBinding
) {
    // Configured managers passed in or initialized in MapActivity
    private val mqttManagerConfig: MqttManager get() = owner.mqttManagerConfig
    private val mqttManager: MqttManager get() = owner.mqttManager
    private val apiService: ApiService get() = owner.apiService
    private val clientKeys: String get() = owner.clientKeys
    private val tokenSlots = mutableMapOf<String, Int>()
    // track which tokens suppressed
    private val suppressedTokens = mutableSetOf<String>()


    /**
     * Fetches the configuration data and initializes the config variable.
     */
    fun fetchConfig(callback: (Boolean) -> Unit) {
        Log.d("MapActivity fetchConfig", "Fetching config...")
        mqttManagerConfig.fetchSharedAttributes(owner.tokenConfigData) { listConfig ->
            if (listConfig.isNotEmpty()) {
                owner.config = listConfig
                Log.d("MapActivity fetchConfig", "Config received: ${'$'}{owner.config}")
                callback(true)
            } else {
                owner.config = emptyList()
                Log.e("MapActivity fetchConfig", "Failed to initialize config. No bus information available.")
                callback(false)
            }
        }
    }

    /**
     * Connects to the MQTT broker and subscribes to shared data topic.
     */
    fun connectAndSubscribe() {
        mqttManager.connect { isConnected ->
            if (isConnected) {
                Log.d("MapActivity", "Connected to MQTT broker")
                subscribeSharedData()
            } else {
                Log.e("MapActivity", "Failed to connect to MQTT broker")
            }
        }
    }

    /**
     * Subscribes to shared data from the server.
     */
    private fun subscribeSharedData() {
        mqttManager.subscribe(MapActivity.SUB_MSG_TOPIC) { message ->
            owner.runOnUiThread {
                val data = Gson().fromJson(message, Bus::class.java)
                val newConfig = data.shared?.config?.busConfig ?: return@runOnUiThread
                val newArr = newConfig.filter { it.aid != owner.aid }

                // For each new bus, just request its attributes.
                // getAttributes() will create & log the marker exactly once.
                newArr.forEach { bus ->
                    getAttributes(apiService, bus.accessToken, clientKeys)
                }

                // remove dropped-out buses
                val toRemove = owner.markerBus.keys - newArr.map { it.accessToken }.toSet()
                toRemove.forEach { token ->
                    binding.map.layerManager.layers.remove(owner.markerBus[token])
                    owner.markerBus.remove(token)
                }

                owner.arrBusData = newArr
                binding.map.invalidate()
            }
        }
    }

    /**
     * Sends data attributes to the server periodically.
     */
    fun sendRequestAttributes() {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                owner.arrBusData.forEach { bus ->
                    if (owner.markerBus.containsKey(bus.accessToken)) {
                        getAttributes(apiService, bus.accessToken, clientKeys)
                    }
                }
                handler.postDelayed(this, MapActivity.REQUEST_PERIODIC_TIME)
            }
        }, 0)
    }

    /**
     * Retrieves attributes data for each bus and updates marker.
     */
    @SuppressLint("LongLogTag")
    fun getAttributes(
        apiService: ApiService,
        token: String,
        clientKeys: String
    ) {
        Log.d("MqttHelper getAttributes", "→ getAttributes for token=$token")
        val call = apiService.getAttributes(
            "${ApiService.BASE_URL}$token/attributes",
            "application/json",
            clientKeys
        )

        call.enqueue(object : Callback<ClientAttributesResponse> {
            @SuppressLint("LongLogTag")
            override fun onResponse(
                call: Call<ClientAttributesResponse>,
                response: Response<ClientAttributesResponse>
            ) {
                val now = System.currentTimeMillis()
                val client = response.body()?.client ?: return

                val lat = client.latitude
                val lon = client.longitude

                // 1) throw away bad coords
                if (lat == 0.0 && lon == 0.0) {
                    Log.d("MqttHelper getAttributes", "Ignoring $token at (0.0,0.0)")
                    return
                }

                // 2) throw away “no change” → now remove marker completely
                val prev = owner.prevCoords[token]
                if (prev != null && prev.first == lat && prev.second == lon) {
                    // first time we see it static → remove marker, suppress until it moves
                    if (suppressedTokens.add(token)) {
                        owner.runOnUiThread {
                            binding.map.layerManager.layers.remove(owner.markerBus[token])
                            owner.markerBus.remove(token)
                            binding.map.invalidate()
                        }
                    }
                    // update lastSeen so activityMonitor doesn’t kill it prematurely
                    owner.lastSeen[token] = now
                    return
                }

                // 3) genuinely new position → clear suppression, update
                suppressedTokens.remove(token)
                owner.lastSeen[token] = now
                owner.prevCoords[token] = lat to lon

                owner.runOnUiThread {
                    // if we’re still suppressed (shouldn't happen) bail
                    if (token in suppressedTokens) return@runOnUiThread

                    val existing = owner.markerBus[token]
                    if (existing == null) {
                        // add new
                        val iconIdx = tokenSlots.getOrPut(token) { tokenSlots.size }
                        val slot = min(iconIdx + 2, 10)
                        val iconRes = owner.resources.getIdentifier(
                            "ic_bus_symbol$slot", "drawable", owner.packageName
                        )
                        val bmp = owner.mapController.createBusIcon(iconRes, 16)
                        val m = Marker(LatLong(lat, lon), bmp, 0, 0)
                        binding.map.layerManager.layers.add(m)
                        owner.markerBus[token] = m
                        owner.mapController.refreshDetailPanelIcons()
                    } else {
                        // move existing
                        existing.latLong = LatLong(lat, lon)
                    }
                    binding.map.invalidate()
                }
            }

            @SuppressLint("LongLogTag")
            override fun onFailure(call: Call<ClientAttributesResponse>, t: Throwable) {
                Log.e("MqttHelper getAttributes", "Network error: ${t.message}")
            }
        })
    }

    /**
     * Requests admin messages periodically.
     */
    fun requestAdminMessage() {
        val jsonObject = JSONObject().apply {
            put("sharedKeys", "message,busRoute,busStop,config")
        }
        mqttManager.publish(MapActivity.PUB_MSG_TOPIC, jsonObject.toString())
        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                mqttManager.publish(MapActivity.PUB_MSG_TOPIC, jsonObject.toString())
                Handler(Looper.getMainLooper()).postDelayed(this, MapActivity.REQUEST_PERIODIC_TIME)
            }
        })
    }

    /**
     * Publishes telemetry data including latitude, longitude, bearing, speed, direction, and aid.
     */
    fun publishTelemetryData() {
        val json = JSONObject().apply {
            put("latitude", owner.latitude)
            put("longitude", owner.longitude)
            put("bearing", owner.bearing)
            put("direction", owner.direction)
            put("speed", owner.speed)
            put("aid", owner.aid)
        }
        Handler(Looper.getMainLooper()).post {
            mqttManager.publish(
                MapActivity.PUB_POS_TOPIC,
                json.toString(),
                1
            )
        }
    }
}
