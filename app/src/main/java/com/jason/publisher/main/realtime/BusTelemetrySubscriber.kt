package com.jason.publisher.main.realtime

import android.util.Log
import com.jason.publisher.main.services.MqttManager
import org.json.JSONObject

class BusTelemetrySubscriber(
    private val mqttManager: MqttManager,
    private val onUpdate: () -> Unit
) {

    fun subscribe() {
        mqttManager.subscribe("v1/devices/me/telemetry") { message ->
            try {
                val json = JSONObject(message)

                val aid = json.optString("aid")
                if (aid.isBlank()) return@subscribe

                val lat = json.optDouble("lat", Double.NaN)
                val lon = json.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@subscribe

                val bus = LiveBusStatus(
                    aid = aid,
                    lat = lat,
                    lon = lon,
                    speed = json.optDouble("speed", 0.0).toFloat(),
                    currentTripLabel = json.optString("currentTripLabel", null),
                    lastSeen = System.currentTimeMillis()
                )

                LiveBusStore.update(bus)
                onUpdate()

            } catch (e: Exception) {
                Log.e("BusTelemetry", "Invalid telemetry", e)
            }
        }
    }
}
