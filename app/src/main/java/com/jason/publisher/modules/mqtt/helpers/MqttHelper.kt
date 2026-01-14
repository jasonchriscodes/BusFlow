package com.jason.publisher.modules.mqtt.helpers

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.modules.map.activities.MapActivity
import com.jason.publisher.main.model.Bus
import com.jason.publisher.main.loggers.LifecycleLogger
import com.jason.publisher.main.services.ClientAttributesResponse
import com.jason.publisher.main.services.ApiService
import com.jason.publisher.modules.map.utils.calculateDistance
import com.jason.publisher.modules.mqtt.services.MqttManager
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
    private val mqttManager: MqttManager get() = owner.mqttManager
    private val apiService: ApiService get() = owner.viewModel.apiService

    companion object {
        /** Topic path for updating attributes */
        const val ATTR_TOPIC = "v1/devices/me/attributes"
        /** Topic path for requesting admin message */
        const val PUB_MSG_TOPIC = "v1/devices/me/attributes/request/1"
        /** Topic path for subscribing to shared data */
        const val SUB_MSG_TOPIC = "v1/devices/me/attributes/response/+"
        const val PUB_POS_TOPIC = "v1/devices/me/telemetry"
        private const val CLIENT_KEYS = "latitude,longitude,bearing,speed,direction,scheduleData,currentTripLabel"
        const val REQUEST_PERIODIC_TIME = 1000L
        private const val MIN_FETCH_INTERVAL_MS = 2_000L
        /** Minimum distance change to consider as movement (in meters) */
        private const val MIN_MOVEMENT_DISTANCE = 5.0
        /** Maximum time (2 minutes) before considering a bus inactive.
         * Bus that hasn't sent location update in 2 minutes is considered inactive.
         */
        private const val MAX_INACTIVE_TIME_MS = 2 * 60 * 1000L  // 2 minutes
    }

    // track when we last fetched attributes for each token
    private val lastFetchTime = mutableMapOf<String, Long>()

    /**
     * Connects to the MQTT broker and subscribes to shared data topic.
     */
    @RequiresApi(Build.VERSION_CODES.M)
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
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("LongLogTag")
    private fun subscribeSharedData() {
        mqttManager.subscribe(SUB_MSG_TOPIC) { message ->
            owner.runOnUiThread {
                val data = Gson().fromJson(message, Bus::class.java)
                val newConfig = data.shared?.config?.busConfig ?: return@runOnUiThread
                val newArr = newConfig.filter { it.aid != owner.viewModel.aid }

                // For each new bus, just request its attributes.
                // getAttributes() will create & log the marker exactly once.
                newArr.forEach { bus ->
                    getAttributes(apiService, bus.accessToken)
                }

                // Remove dropped-out buses and clean up all tracking data
                // Also remove buses that are no longer in arrBusData (orphaned from ThingsBoard)
                val newArrTokens = newArr.map { it.accessToken }.toSet()
                val toRemove = owner.mapController.busMarkerRegistry.keys.filter {
                    it != owner.viewModel.token && it !in newArrTokens
                }
                toRemove.forEach { token ->
                    owner.mapController.removeBusMarker(token)
                    Log.d("MqttHelper subscribeSharedData", "Removed bus $token - no longer in arrBusData")
                }

                owner.viewModel.arrBusData = newArr
                binding.map.invalidate()
                // Refresh detail panel after arrBusData is updated
                // This ensures detail panel shows all buses that are in the system
                owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())
            }
        }
    }

    // ----------------------------------------------------------------
    // 1) cancellable poller
    // ----------------------------------------------------------------
    private var pollingHandler: Handler? = null
    private val pollRunnable = object : Runnable {
        override fun run() {
            owner.viewModel.arrBusData.forEach { bus ->
                getAttributes(apiService, bus.accessToken)
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
        owner.viewModel.arrBusData.forEach { bus ->
            getAttributes(apiService, bus.accessToken)
        }
    }

    /**
     * Retrieves attributes data for each bus and updates marker.
     */
    @SuppressLint("LongLogTag")
    fun getAttributes(
        apiService: ApiService,
        token: String
    ) {
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

                // resolve a stable label for "other bus"
                var labelUpdated = false
                var resolvedLabel: String? = null

                // Only use currentTripLabel - bus must have started a trip to be shown
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
                        // If this is the first time we set a label for this bus, refresh detail panel
                        // This ensures detail panel shows the bus even if marker hasn't been created yet
                        if (wasEmpty) {
                            owner.runOnUiThread { owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble()) }
                        }
                    }
                    try {
                        owner.panelDebounceRunnable?.let {
                            owner.panelDebounceHandler.removeCallbacks(it)
                        }
                        owner.panelDebounceRunnable = Runnable {
                            try {
                                owner.panelController.refreshPanelDetailWithLogging(binding.map.model.mapViewPosition.zoomLevel.toDouble())
                            } catch (e: Exception) {
                                Log.w("MqttHelper", "panel debounce runnable failed: ${e.message}")
                            }
                        }
                        owner.panelDebounceHandler.postDelayed(owner.panelDebounceRunnable!!, 180L)
                    } catch (e: Exception) {
                        Log.w("MqttHelper", "scheduling panel debounce failed: ${e.message}")
                    }
                }
                // ------------------------------------------------------------------

                val lat = client.latitude
                val lon = client.longitude

                // If no usable coords, don't create/update marker and clean up if exists
                if (lat == 0.0 && lon == 0.0) {
                    // Remove marker if it exists (invalid coordinates)
                    owner.runOnUiThread {
                        owner.mapController.removeBusMarker(token, invalidateImmediately = true)
                        if (labelUpdated) {
                            owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())
                        }
                    }
                    Log.d("MqttHelper getAttributes", "Ignoring $token at (0,0) - removed if exists")
                    return
                }

                // Don't show bus that hasn't started (no currentTripLabel)
                // Only show buses that have actually started a trip and published currentTripLabel
                if (resolvedLabel.isNullOrBlank()) {
                    // Bus hasn't started yet - remove marker if exists
                    owner.runOnUiThread {
                        owner.mapController.removeBusMarker(token, invalidateImmediately = true)
                        owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())
                    }
                    return
                }

                // If bus on break, remove marker from map but keep label in otherBusLabels for detail panel
                // This allows bus on break to be tracked in detail panel but not shown on map
                if (resolvedLabel.contains("Break", ignoreCase = true)) {
                    // Bus is on break - remove marker from map but keep label for detail panel
                    owner.runOnUiThread {
                        owner.mapController.busMarkerRegistry[token]?.let { marker ->
                            binding.map.layerManager.layers.remove(marker)
                            owner.mapController.busMarkerRegistry.remove(token)
                            binding.map.invalidate()
                        }
                        // Keep otherBusLabels so bus on break appears in detail panel
                        // Don't remove prevCoords, lastSeen, or otherBusLabels
                        owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())
                    }
                    return
                }

                // Check if bus is still active (has sent location update recently)
                // Bus that was started but app was closed will have currentTripLabel but no recent updates
                val lastSeenTime = owner.mapController.lastSeenOtherBuses[token] ?: 0L
                if (lastSeenTime > 0L && (now - lastSeenTime) > MAX_INACTIVE_TIME_MS) {
                    // Bus hasn't sent update in 2 minutes - consider it inactive and remove
                    owner.runOnUiThread {
                        owner.mapController.removeBusMarker(token, invalidateImmediately = true)
                        owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())
                    }
                    Log.d("MqttHelper getAttributes", "Removing $token - bus inactive (no update in ${(now - lastSeenTime) / 1000}s)")
                    return
                }

                // ✅ FIX: If bus already exists in markerBus but coordinates haven't changed,
                // it might be a stale bus (app was closed). Don't update lastSeen if coordinates are stale.
                val existingPrev = owner.mapController.prevOtherBusCoordinates[token]
                if (existingPrev != null && existingPrev.first == lat && existingPrev.second == lon) {
                    // Coordinates haven't changed - this might be stale data from ThingsBoard
                    // Only update lastSeen if it's a recent first-time fetch (within last 30 seconds)
                    if (lastSeenTime == 0L) {
                        // First time seeing this bus with stale coordinates - set lastSeen but don't create marker yet
                        // Wait for next update to see if coordinates change
                        owner.mapController.lastSeenOtherBuses[token] = now
                        Log.d("MqttHelper getAttributes", "First fetch for $token with stale coordinates - waiting for movement")
                        return
                    } else if ((now - lastSeenTime) > 30_000L) {
                        // Coordinates haven't changed in 30 seconds - likely stale, remove
                        owner.runOnUiThread {
                            owner.mapController.removeBusMarker(token, invalidateImmediately = true)
                            owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())
                        }
                        Log.d("MqttHelper getAttributes", "Removing $token - stale coordinates (no movement in 30s)")
                        return
                    }
                    // Coordinates are same but recent - just update lastSeen and return (no marker update needed)
                    owner.mapController.lastSeenOtherBuses[token] = now
                    if (labelUpdated) {
                        owner.runOnUiThread { owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble()) }
                    }
                    return
                }

                // First time we see this token → record and draw immediately
                // Only create marker if bus has started (has currentTripLabel) and is not on Break
                val prev = owner.mapController.prevOtherBusCoordinates[token]
                if (prev == null) {
                    // Don't create marker if bus hasn't started yet
                    if (resolvedLabel.isBlank()) {
                        // Bus hasn't started - just record coordinates but don't create marker
                        owner.mapController.prevOtherBusCoordinates[token] = lat to lon
                        owner.mapController.lastSeenOtherBuses[token] = now
                        // Refresh detail panel even if bus hasn't started (to remove it if it was there)
                        if (labelUpdated) {
                            owner.runOnUiThread { owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble()) }
                        }
                        return
                    }

                    // Don't create marker if bus is on Break, but keep label for detail panel
                    if (resolvedLabel.contains("Break", ignoreCase = true)) {
                        // Bus is on break - record coordinates and label but don't create marker
                        owner.mapController.prevOtherBusCoordinates[token] = lat to lon
                        owner.mapController.lastSeenOtherBuses[token] = now
                        // Refresh detail panel so bus on break appears
                        owner.runOnUiThread { owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble()) }
                        return
                    }

                    owner.mapController.prevOtherBusCoordinates[token] = lat to lon
                    owner.mapController.lastSeenOtherBuses[token] = now

                    // Log bus detection with destination info
                    val label = resolvedLabel
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
                        val idx = owner.viewModel.arrBusData.indexOfFirst { it.accessToken == token }
                        val slot = ((idx + 2).coerceAtMost(10)).coerceAtLeast(2)
                        val iconName = "ic_bus_symbol$slot"
                        val iconRes = owner.resources.getIdentifier(iconName, "drawable", owner.packageName)
                        val rotated = client.bearing?.let { owner.mapController.rotateDrawable(iconRes, it) }
                        val marker = Marker(pos, rotated, 0, 0)
                        binding.map.layerManager.layers.add(marker)
                        owner.mapController.busMarkerRegistry[token] = marker
                        binding.map.invalidate()
                        // ✅ FIX: Always refresh detail panel when marker is created for a new bus
                        // This ensures detail panel shows all active buses, even if label wasn't updated
                        owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())
                    }
                    Log.d("MqttHelper getAttributes", "First fetch for $token; marker created (bus has started)")
                    return
                }

                // No movement → still update lastSeen and refresh panel if label changed
                if (prev.first == lat && prev.second == lon) {
                    owner.mapController.lastSeenOtherBuses[token] = now // Update lastSeen even if no movement
                    // ✅ FIX: If label changed to "Break", remove marker but keep label for detail panel
                    if (labelUpdated && resolvedLabel.contains("Break", ignoreCase = true)) {
                        owner.runOnUiThread {
                            owner.mapController.busMarkerRegistry[token]?.let { marker ->
                                binding.map.layerManager.layers.remove(marker)
                                owner.mapController.busMarkerRegistry.remove(token)
                                binding.map.invalidate()
                            }
                            // Keep otherBusLabels so bus on break appears in detail panel
                            owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())
                        }
                        return
                    }
                    if (labelUpdated) {
                        owner.runOnUiThread { owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble()) }
                    }
                    return
                }

                // Movement detected → update marker (and re-rotate), then refresh panel if needed
                // Check if movement is significant enough (at least 5 meters)
                val distance = calculateDistance(prev.first, prev.second, lat, lon)
                if (distance < MIN_MOVEMENT_DISTANCE && !labelUpdated) {
                    // Small movement, just update lastSeen
                    owner.mapController.lastSeenOtherBuses[token] = now
                    return
                }

                // ✅ FIX: If label changed to "Break", remove marker but keep label for detail panel
                if (resolvedLabel.contains("Break", ignoreCase = true)) {
                    owner.runOnUiThread {
                        owner.mapController.busMarkerRegistry[token]?.let { marker ->
                            binding.map.layerManager.layers.remove(marker)
                            owner.mapController.busMarkerRegistry.remove(token)
                            binding.map.invalidate()
                        }
                        // Keep otherBusLabels so bus on break appears in detail panel
                        owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())
                    }
                    return
                }

                owner.mapController.prevOtherBusCoordinates[token] = lat to lon
                owner.mapController.lastSeenOtherBuses[token] = now

                owner.runOnUiThread {
                    try {
                        val pos = LatLong(lat, lon)
                        val existing = owner.mapController.busMarkerRegistry[token]

                        // slot icon selection stays stable per bus position in arrBusData
                        val idx = owner.viewModel.arrBusData.indexOfFirst { it.accessToken == token }
                        val slot = ((idx + 2).coerceAtMost(10)).coerceAtLeast(2)
                        val iconName = "ic_bus_symbol$slot"
                        val iconRes = owner.resources.getIdentifier(iconName, "drawable", owner.packageName)
                        val rotated = client.bearing?.let { owner.mapController.rotateDrawable(iconRes, it) }

                        if (existing == null) {
                            val marker = Marker(pos, rotated, 0, 0)
                            binding.map.layerManager.layers.add(marker)
                            owner.mapController.busMarkerRegistry[token] = marker
                        } else {
                            existing.latLong = pos
                            if (rotated != null) existing.bitmap = rotated
                        }

                        binding.map.invalidate()
                        owner.mapController.activeBusToken = token

                        // ensure the two-line panel updates when label changed
                        if (labelUpdated) {
                            owner.panelController.refreshDetailPanelIcons(binding.map.model.mapViewPosition.zoomLevel.toDouble())
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
        mqttManager.publish(PUB_MSG_TOPIC, jsonObject.toString())
        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                mqttManager.publish(PUB_MSG_TOPIC, jsonObject.toString())
                Handler(Looper.getMainLooper()).postDelayed(this, REQUEST_PERIODIC_TIME)
            }
        })
    }

    /**
     * Publishes telemetry data including latitude, longitude, bearing, speed, direction, and aid.
     */
    fun publishTelemetryData(json: JSONObject) {
        Handler(Looper.getMainLooper()).post {
            mqttManager.publish(
                PUB_POS_TOPIC,
                json.toString(),
                1
            )
        }
    }
}