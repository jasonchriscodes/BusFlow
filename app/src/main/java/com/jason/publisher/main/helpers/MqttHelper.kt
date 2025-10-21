package com.jason.publisher.main.helpers

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.activity.MapActivity
import com.jason.publisher.main.model.Bus
import com.jason.publisher.main.model.ScheduleItem
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

    companion object {
        private const val MIN_FETCH_INTERVAL_MS = 2_000L
        // include scheduleData in your GET
        private const val CLIENT_KEYS = "latitude,longitude,bearing,speed,direction,scheduleData,currentTripLabel"
    }

    // track when we last fetched attributes for each token
    private val lastFetchTime = mutableMapOf<String, Long>()

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

//    /**
//     * Sends data attributes to the server periodically.
//     */
//    fun sendRequestAttributes() {
//        val handler = Handler(Looper.getMainLooper())
//        handler.postDelayed(object : Runnable {
//            override fun run() {
//                owner.arrBusData.forEach { bus ->
//                    if (owner.markerBus.containsKey(bus.accessToken)) {
//                        getAttributes(apiService, bus.accessToken, clientKeys)
//                    }
//                }
//                handler.postDelayed(this, MIN_FETCH_INTERVAL_MS)
//            }
//        }, MIN_FETCH_INTERVAL_MS)
//    }

    // ----------------------------------------------------------------
    // 1) cancellable poller
    // ----------------------------------------------------------------
    private var pollingHandler: Handler? = null
    private val pollRunnable = object : Runnable {
        override fun run() {
            owner.arrBusData.forEach { bus ->
                getAttributes(apiService, bus.accessToken, clientKeys)
            }
            pollingHandler?.postDelayed(this, MIN_FETCH_INTERVAL_MS)
        }
    }

    /** Start or re-start the 2 s polling loop. */
    fun startAttributePolling() {
        stopAttributePolling()
        pollingHandler = Handler(Looper.getMainLooper())
        pollingHandler!!.postDelayed(pollRunnable, MIN_FETCH_INTERVAL_MS)
    }

    /** Immediately stop the 2 s polling loop. */
    fun stopAttributePolling() {
        pollingHandler?.removeCallbacksAndMessages(null)
    }

    // ----------------------------------------------------------------
    // 2) one-off full refresh on reconnect
    // ----------------------------------------------------------------
    /**
     * Clears throttling timestamps and fetches attributes
     * for every bus exactly once.
     */
    fun refreshAllAttributes() {
        lastFetchTime.clear()
        owner.arrBusData.forEach { bus ->
            getAttributes(apiService, bus.accessToken, clientKeys)
        }
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
        val now = System.currentTimeMillis()

        // 1) throttle to once every 2 s per bus
        val last = lastFetchTime[token] ?: 0L
        if (now - last < MIN_FETCH_INTERVAL_MS) return
        lastFetchTime[token] = now

        apiService.getAttributes(
            "${ApiService.BASE_URL}$token/attributes",
            "application/json",
            CLIENT_KEYS
        ).enqueue(object : Callback<ClientAttributesResponse> {
            @RequiresApi(Build.VERSION_CODES.M)
            @SuppressLint("LongLogTag")
            override fun onResponse(
                call: Call<ClientAttributesResponse>,
                response: Response<ClientAttributesResponse>
            ) {
                val client = response.body()?.client ?: return

                // ---------- NEW: resolve a stable label for "other bus" ----------
                var labelUpdated = false
                var resolvedLabel: String? = null

                // 1) Prefer the stable label published by the other tablet
                val labelFromPeer = client.currentTripLabel
                if (!labelFromPeer.isNullOrBlank()) {
                    resolvedLabel = labelFromPeer
                } else {
                    // 2) Fallback: reconstruct from the *first* item of their scheduleData
                    val scheduleJson = client.scheduleData
                    if (!scheduleJson.isNullOrBlank()) {
                        try {
                            val arr = Gson().fromJson(scheduleJson, Array<ScheduleItem>::class.java).toList()
                            arr.firstOrNull()?.let { first ->
                                val from = first.busStops.firstOrNull()?.abbreviation
                                    ?: first.busStops.firstOrNull()?.name ?: "?"
                                val to = first.busStops.lastOrNull()?.abbreviation
                                    ?: first.busStops.lastOrNull()?.name ?: "?"

                                // avoid showing a token-like dutyName (e.g., 20–40 alnum chars)
                                val dutyNameLooksLikeToken =
                                    first.runName.length in 20..40 && first.runName.all { it.isLetterOrDigit() }
                                val duty = if (dutyNameLooksLikeToken) "${first.runNo} $from → $to" else first.runName

                                resolvedLabel = "${first.startTime} $duty $from → $to"
                            }
                        } catch (_: Exception) { /* ignore */ }
                    }
                }

                resolvedLabel?.let { lbl ->
                    if (owner.otherBusLabels[token] != lbl) {
                        owner.otherBusLabels[token] = lbl
                        labelUpdated = true
                    }
                }
                // ------------------------------------------------------------------

                val lat = client.latitude
                val lon = client.longitude

                // If no usable coords but label changed, still refresh the panel once.
                if (lat == 0.0 && lon == 0.0) {
                    if (labelUpdated) {
                        owner.runOnUiThread { owner.mapController.refreshDetailPanelIcons() }
                    }
                    Log.d("MqttHelper getAttributes", "Ignoring $token at (0,0)")
                    return
                }

                // First time we see this token → record but don’t draw yet
                val prev = owner.prevCoords[token]
                if (prev == null) {
                    owner.prevCoords[token] = lat to lon
                    owner.lastSeen[token] = now
                    if (labelUpdated) {
                        owner.runOnUiThread { owner.mapController.refreshDetailPanelIcons() }
                    }
                    Log.d("MqttHelper getAttributes", "First fetch for $token; awaiting movement")
                    return
                }

                // No movement → maybe still refresh panel if label changed
                if (prev.first == lat && prev.second == lon) {
                    if (labelUpdated) {
                        owner.runOnUiThread { owner.mapController.refreshDetailPanelIcons() }
                    }
                    return
                }

                // Movement detected → update marker (and re-rotate), then refresh panel if needed
                owner.prevCoords[token] = lat to lon
                owner.lastSeen[token] = now

                owner.runOnUiThread {
                    val pos = LatLong(lat, lon)
                    val existing = owner.markerBus[token]

                    // slot icon selection stays stable per bus position in arrBusData
                    val idx = owner.arrBusData.indexOfFirst { it.accessToken == token }
                    val slot = ((idx + 2).coerceAtMost(10)).coerceAtLeast(2)
                    val iconName = "ic_bus_symbol$slot"
                    val iconRes = owner.resources.getIdentifier(iconName, "drawable", owner.packageName)
                    val rotated = client.bearing?.let { owner.mapController.rotateDrawable(iconRes, it) }

                    if (existing == null) {
                        val marker = Marker(pos, rotated, 0, 0)
                        binding.map.layerManager.layers.add(marker)
                        owner.markerBus[token] = marker
                    } else {
                        existing.latLong = pos
                        if (rotated != null) existing.bitmap = rotated
                    }

                    binding.map.invalidate()
                    owner.mapController.activeBusToken = token

                    // ensure the two-line panel updates when label changed
                    if (labelUpdated) owner.mapController.refreshDetailPanelIcons()
                    else owner.mapController.refreshDetailPanelIcons()
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