package com.jason.publisher.main.services.background

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import com.jason.publisher.main.loggers.FetchSessionStore
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.utils.getOrCreateDeviceAid
import com.jason.publisher.modules.map.mqtt.helpers.MqttConfigHelper
import com.jason.publisher.modules.map.mqtt.helpers.MqttHelper.Companion.ATTR_TOPIC
import com.jason.publisher.modules.map.mqtt.helpers.MqttHelper.Companion.PUB_MSG_TOPIC
import com.jason.publisher.modules.map.mqtt.services.MqttManager
import org.json.JSONObject

class ClientAttributesService : Service() {
    val mqttConfigHelper = MqttConfigHelper()

    /** Access token of the current device */
    var token = ""

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("HardwareIds")
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        mqttConfigHelper.fetchConfig { configList ->
            // Must use the app's canonical AID (aid.txt if present), not the raw hardware
            // ANDROID_ID - they can differ, and the bus config is keyed on the former. This was
            // silently resolving to an empty token before (confirmed on-device), meaning this
            // service's currentTripLabel clearing was publishing under no proper device identity.
            val aid = getOrCreateDeviceAid(applicationContext)
            token = MqttConfigHelper.getAccessToken(aid, configList)
            Log.d("ClientAttributesService", "access token resolved: ${token.isNotEmpty()}")
            clearActiveSegmentAndRefresh()
        }
        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onDestroy() {
        clearActiveSegmentAndRefresh()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("ClientAttributesService", "onTaskRemoved starts")
        // Called when the user swipes your task from Recents
        // Do the same cleanup you do in onDestroy, then stop.
        clearActiveSegmentAndRefresh()

        // This is the one reliable "the driver actually closed the app" signal available -
        // Android kills the process directly on a Recents swipe without guaranteeing any
        // Activity.onDestroy() call, which is why App.kt's liveActivities-based tracking alone
        // left this flag stuck forever after a swipe-away (never seen as "closed", same as it
        // correctly never clears on an actual crash - the two are indistinguishable to that
        // tracking). Clearing it here means the next open shows Fetch Roster/Use Cache again
        // instead of silently skipping straight to cache forever.
        FetchSessionStore.clear(applicationContext)

        super.onTaskRemoved(rootIntent)
    }

    /**
     * Clear the shared currentTripLabel on the server and coerce other tablets to refresh.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun clearActiveSegmentAndRefresh() {
        val payload = "{\"currentTripLabel\":\"\", \"activityState\":\"\"}"
        val refresh = JSONObject().apply { put("sharedKeys", "message,busRoute,busStop,config") }.toString()
        publishOnce(listOf(ATTR_TOPIC to payload, PUB_MSG_TOPIC to refresh))
    }

    /**
     * Connects, publishes, and disconnects right away. ThingsBoard keeps only one MQTT session per
     * device token, so a long-lived client here (same token as the activities' client) made the two
     * sessions kick each other off every couple of seconds. Runs off the main thread because
     * MqttClient calls block.
     */
    private fun publishOnce(messages: List<Pair<String, String>>) {
        val deviceToken = token
        Thread({
            val manager = if (deviceToken.isNotEmpty()) MqttManager(username = deviceToken) else MqttManager()
            try {
                manager.connect { success ->
                    if (success) {
                        messages.forEach { (topic, body) -> manager.publish(topic, body) }
                        Log.d("ClientAttributesService", "published ${messages.size} message(s)")
                    } else {
                        Log.w("ClientAttributesService", "unable to connect to MQTT")
                    }
                }
            } catch (e: Exception) {
                FileLogger.w("ClientAttributesService", "publishOnce failed | ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                manager.disconnect()
            }
        }, "ClientAttributesService-publish").start()
    }

    /**
     * Nudges ThingsBoard to re-broadcast shared attributes with a single request.
     */
    fun requestAdminMessage() {
        val jsonObject = JSONObject().apply {
            put("sharedKeys", "message,busRoute,busStop,config")
        }
        publishOnce(listOf(PUB_MSG_TOPIC to jsonObject.toString()))
    }
}