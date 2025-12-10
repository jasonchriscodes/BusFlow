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
import com.jason.publisher.main.utils.LifecycleLogger
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
        // Minimum distance change to consider as movement (in meters)
        private const val MIN_MOVEMENT_DISTANCE = 5.0
        // ✅ FIX: Maximum time (2 minutes) before considering a bus inactive
        // Bus that hasn't sent location update in 2 minutes is considered inactive
        private const val MAX_INACTIVE_TIME_MS = 2 * 60 * 1000L  // 2 minutes
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

                // ✅ FIX: remove dropped-out buses and clean up all tracking data
                // Also remove buses that are no longer in arrBusData (orphaned from ThingsBoard)
                val newArrTokens = newArr.map { it.accessToken }.toSet()
                val toRemove = owner.markerBus.keys.filter {
                    it != owner.token && it !in newArrTokens
                }
                toRemove.forEach { token ->
                    binding.map.layerManager.layers.remove(owner.markerBus[token])
                    owner.markerBus.remove(token)
                    owner.prevCoords.remove(token)
                    owner.lastSeen.remove(token)
                    owner.otherBusLabels.remove(token)
                    Log.d("MqttHelper subscribeSharedData", "Removed bus $token - no longer in arrBusData")
                }

                owner.arrBusData = newArr
                binding.map.invalidate()
                // ✅ FIX: Refresh detail panel after arrBusData is updated
                // This ensures detail panel shows all buses that are in the system
                owner.mapController.refreshDetailPanelIcons()
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
        //Log.d("MqttHelper getAttributes", "→ getAttributes for token=$token")
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

                // ✅ FIX: Only use currentTripLabel - bus must have started a trip to be shown
                // Don't use fallback to scheduleData because bus that hasn't started shouldn't be displayed
                val labelFromPeer = client.currentTripLabel
                if (!labelFromPeer.isNullOrBlank()) {
                    resolvedLabel = labelFromPeer
                }
                // Note: We don't fallback to scheduleData because bus that hasn't started
                // shouldn't be shown on the map. Only buses with active trips are displayed.

                resolvedLabel?.let { lbl ->
                    val wasEmpty = owner.otherBusLabels[token].isNullOrBlank()
                    if (owner.otherBusLabels[token] != lbl) {
                        owner.otherBusLabels[token] = lbl
                        labelUpdated = true
                        // ✅ FIX: If this is the first time we set a label for this bus, refresh detail panel
                        // This ensures detail panel shows the bus even if marker hasn't been created yet
                        if (wasEmpty) {
                            owner.runOnUiThread { owner.mapController.refreshDetailPanelIcons() }
                        }
                    }
                }
                // ------------------------------------------------------------------

                val lat = client.latitude
                val lon = client.longitude

                // ✅ FIX: If no usable coords, don't create/update marker and clean up if exists
                if (lat == 0.0 && lon == 0.0) {
                    // Remove marker if it exists (invalid coordinates)
                    owner.runOnUiThread {
                        owner.markerBus[token]?.let { marker ->
                            binding.map.layerManager.layers.remove(marker)
                            owner.markerBus.remove(token)
                            owner.prevCoords.remove(token)
                            owner.lastSeen.remove(token)
                            owner.otherBusLabels.remove(token)
                            binding.map.invalidate()
                        }
                        if (labelUpdated) {
                            owner.mapController.refreshDetailPanelIcons()
                        }
                    }
                    Log.d("MqttHelper getAttributes", "Ignoring $token at (0,0) - removed if exists")
                    return
                }

                // ✅ FIX: Don't show bus that hasn't started (no currentTripLabel)
                // Only show buses that have actually started a trip and published currentTripLabel
                if (resolvedLabel.isNullOrBlank()) {
                    // Bus hasn't started yet - remove marker if exists
                    val hadMarker = owner.markerBus.containsKey(token)
                    owner.runOnUiThread {
                        owner.markerBus[token]?.let { marker ->
                            binding.map.layerManager.layers.remove(marker)
                            owner.markerBus.remove(token)
                            owner.prevCoords.remove(token)
                            owner.lastSeen.remove(token)
                            owner.otherBusLabels.remove(token)
                            binding.map.invalidate()
                        }
                        owner.mapController.refreshDetailPanelIcons()
                    }
                    return
                }

                // ✅ FIX: Don't show buses with "Break" labels - remove marker if exists
                if (resolvedLabel.contains("Break", ignoreCase = true)) {
                    // Bus is on break - remove marker if exists
                    val hadMarker = owner.markerBus.containsKey(token)
                    owner.runOnUiThread {
                        owner.markerBus[token]?.let { marker ->
                            binding.map.layerManager.layers.remove(marker)
                            owner.markerBus.remove(token)
                            owner.prevCoords.remove(token)
                            owner.lastSeen.remove(token)
                            owner.otherBusLabels.remove(token)
                            binding.map.invalidate()
                        }
                        owner.mapController.refreshDetailPanelIcons()
                    }
                    return
                }

                // ✅ FIX: Check if bus is still active (has sent location update recently)
                // Bus that was started but app was closed will have currentTripLabel but no recent updates
                val lastSeenTime = owner.lastSeen[token] ?: 0L
                if (lastSeenTime > 0L && (now - lastSeenTime) > MAX_INACTIVE_TIME_MS) {
                    // Bus hasn't sent update in 2 minutes - consider it inactive and remove
                    owner.runOnUiThread {
                        owner.markerBus[token]?.let { marker ->
                            binding.map.layerManager.layers.remove(marker)
                            owner.markerBus.remove(token)
                            owner.prevCoords.remove(token)
                            owner.lastSeen.remove(token)
                            owner.otherBusLabels.remove(token)
                            binding.map.invalidate()
                        }
                        owner.mapController.refreshDetailPanelIcons()
                    }
                    Log.d("MqttHelper getAttributes", "Removing $token - bus inactive (no update in ${(now - lastSeenTime) / 1000}s)")
                    return
                }

                // ✅ FIX: If bus already exists in markerBus but coordinates haven't changed,
                // it might be a stale bus (app was closed). Don't update lastSeen if coordinates are stale.
                val existingPrev = owner.prevCoords[token]
                if (existingPrev != null && existingPrev.first == lat && existingPrev.second == lon) {
                    // Coordinates haven't changed - this might be stale data from ThingsBoard
                    // Only update lastSeen if it's a recent first-time fetch (within last 30 seconds)
                    if (lastSeenTime == 0L) {
                        // First time seeing this bus with stale coordinates - set lastSeen but don't create marker yet
                        // Wait for next update to see if coordinates change
                        owner.lastSeen[token] = now
                        Log.d("MqttHelper getAttributes", "First fetch for $token with stale coordinates - waiting for movement")
                        return
                    } else if ((now - lastSeenTime) > 30_000L) {
                        // Coordinates haven't changed in 30 seconds - likely stale, remove
                        owner.runOnUiThread {
                            owner.markerBus[token]?.let { marker ->
                                binding.map.layerManager.layers.remove(marker)
                                owner.markerBus.remove(token)
                                owner.prevCoords.remove(token)
                                owner.lastSeen.remove(token)
                                owner.otherBusLabels.remove(token)
                                binding.map.invalidate()
                            }
                            owner.mapController.refreshDetailPanelIcons()
                        }
                        Log.d("MqttHelper getAttributes", "Removing $token - stale coordinates (no movement in 30s)")
                        return
                    }
                    // Coordinates are same but recent - just update lastSeen and return (no marker update needed)
                    owner.lastSeen[token] = now
                    if (labelUpdated) {
                        owner.runOnUiThread { owner.mapController.refreshDetailPanelIcons() }
                    }
                    return
                }

                // First time we see this token → record and draw immediately
                // ✅ FIX: Only create marker if bus has started (has currentTripLabel) and is not on Break
                val prev = owner.prevCoords[token]
                if (prev == null) {
                    // ✅ FIX: Don't create marker if bus hasn't started yet
                    if (resolvedLabel.isNullOrBlank()) {
                        // Bus hasn't started - just record coordinates but don't create marker
                        owner.prevCoords[token] = lat to lon
                        owner.lastSeen[token] = now
                        // ✅ FIX: Refresh detail panel even if bus hasn't started (to remove it if it was there)
                        if (labelUpdated) {
                            owner.runOnUiThread { owner.mapController.refreshDetailPanelIcons() }
                        }
                        return
                    }

                    // ✅ FIX: Don't create marker if bus is on Break
                    if (resolvedLabel.contains("Break", ignoreCase = true)) {
                        // Bus is on break - just record coordinates but don't create marker
                        owner.prevCoords[token] = lat to lon
                        owner.lastSeen[token] = now
                        // ✅ FIX: Refresh detail panel even if bus is on break (to remove it if it was there)
                        if (labelUpdated) {
                            owner.runOnUiThread { owner.mapController.refreshDetailPanelIcons() }
                        }
                        return
                    }

                    owner.prevCoords[token] = lat to lon
                    owner.lastSeen[token] = now
                    // ✅ ENHANCED: Log bus detection with destination info
                    val label = resolvedLabel ?: owner.otherBusLabels[token] ?: "Unknown"
                    val destination = label.split("→").getOrNull(1)?.trim() ?: "Unknown"
                    LifecycleLogger.logOtherBusDetected(
                        token = token,
                        label = label,
                        destination = destination,
                        lat = lat,
                        lon = lon
                    )
                    // Draw marker immediately only if bus has started
                    owner.runOnUiThread {
                        val pos = LatLong(lat, lon)
                        val idx = owner.arrBusData.indexOfFirst { it.accessToken == token }
                        val slot = ((idx + 2).coerceAtMost(10)).coerceAtLeast(2)
                        val iconName = "ic_bus_symbol$slot"
                        val iconRes = owner.resources.getIdentifier(iconName, "drawable", owner.packageName)
                        val rotated = client.bearing?.let { owner.mapController.rotateDrawable(iconRes, it) }
                        val marker = Marker(pos, rotated, 0, 0)
                        binding.map.layerManager.layers.add(marker)
                        owner.markerBus[token] = marker
                        binding.map.invalidate()
                        // ✅ FIX: Always refresh detail panel when marker is created for a new bus
                        // This ensures detail panel shows all active buses, even if label wasn't updated
                        owner.mapController.refreshDetailPanelIcons()
                    }
                    Log.d("MqttHelper getAttributes", "First fetch for $token; marker created (bus has started)")
                    return
                }

                // No movement → still update lastSeen and refresh panel if label changed
                if (prev.first == lat && prev.second == lon) {
                    owner.lastSeen[token] = now // Update lastSeen even if no movement
                    // ✅ FIX: If label changed to "Break", remove marker
                    if (labelUpdated && resolvedLabel.contains("Break", ignoreCase = true)) {
                        owner.runOnUiThread {
                            owner.markerBus[token]?.let { marker ->
                                binding.map.layerManager.layers.remove(marker)
                                owner.markerBus.remove(token)
                                owner.prevCoords.remove(token)
                                owner.lastSeen.remove(token)
                                owner.otherBusLabels.remove(token)
                                binding.map.invalidate()
                            }
                            owner.mapController.refreshDetailPanelIcons()
                        }
                        return
                    }
                    if (labelUpdated) {
                        owner.runOnUiThread { owner.mapController.refreshDetailPanelIcons() }
                    }
                    return
                }

                // Movement detected → update marker (and re-rotate), then refresh panel if needed
                // Check if movement is significant enough (at least 5 meters)
                val distance = owner.mapController.calculateDistance(prev.first, prev.second, lat, lon)
                if (distance < MIN_MOVEMENT_DISTANCE && !labelUpdated) {
                    // Small movement, just update lastSeen
                    owner.lastSeen[token] = now
                    return
                }

                // ✅ FIX: If label changed to "Break", remove marker instead of updating
                if (resolvedLabel.contains("Break", ignoreCase = true)) {
                    owner.runOnUiThread {
                        owner.markerBus[token]?.let { marker ->
                            binding.map.layerManager.layers.remove(marker)
                            owner.markerBus.remove(token)
                            owner.prevCoords.remove(token)
                            owner.lastSeen.remove(token)
                            owner.otherBusLabels.remove(token)
                            binding.map.invalidate()
                        }
                        owner.mapController.refreshDetailPanelIcons()
                    }
                    return
                }

                owner.prevCoords[token] = lat to lon
                owner.lastSeen[token] = now

                owner.runOnUiThread {
                    try {
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
                        if (labelUpdated) {
                            owner.mapController.refreshDetailPanelIcons()
                        }
                    } catch (e: Exception) {
                        Log.e("MqttHelper", "Error updating marker: ${e.message}", e)
                    }
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