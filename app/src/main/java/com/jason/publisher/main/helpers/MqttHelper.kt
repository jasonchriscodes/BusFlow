package com.jason.publisher.main.activity

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.model.Bus
import com.jason.publisher.services.ClientAttributesResponse
import com.jason.publisher.services.ApiService
import com.jason.publisher.main.services.MqttManager
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.layer.overlay.Marker

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

                // build markers for each other bus
                newArr.forEach { bus ->
                    val iconRes = if (bus.aid == owner.aid) R.drawable.ic_bus_symbol else R.drawable.ic_bus_symbol2
                    val icon = owner.createBusIcon(iconRes)
                    val m = Marker(LatLong(-36.8558512, 174.7648727), icon, -icon.width/2, -icon.height)
                    binding.map.layerManager.layers.add(m)
                    owner.markerBus[bus.accessToken] = m
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
    fun getAttributes(
        apiService: ApiService,
        token: String,
        clientKeys: String
    ) {
        val call = apiService.getAttributes(
            "${ApiService.BASE_URL}$token/attributes",
            "application/json",
            clientKeys
        )

        call.enqueue(object : Callback<ClientAttributesResponse> {
            override fun onResponse(
                call: Call<ClientAttributesResponse>,
                response: Response<ClientAttributesResponse>
            ) {
                owner.lastSeen[token] = System.currentTimeMillis()
                val client = response.body()?.client ?: return
                val lat = client.latitude
                val lon = client.longitude
                val prev = owner.prevCoords[token]
                if (prev?.first == lat && prev.second == lon) return
                owner.prevCoords[token] = Pair(lat, lon)

                owner.runOnUiThread {
                    owner.markerBus[token]?.latLong = LatLong(lat, lon)
                    binding.map.invalidate()
                }
            }
            @SuppressLint("LongLogTag")
            override fun onFailure(call: Call<ClientAttributesResponse>, t: Throwable) {
                Log.e("MapActivity getAttributes", "Network error: ${'$'}{t.message}")
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
