package com.jason.publisher.main.services.background

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.jason.publisher.modules.mqtt.helpers.MqttConfigHelper
import com.jason.publisher.modules.map.helpers.MqttHelper.Companion.ATTR_TOPIC
import com.jason.publisher.modules.map.helpers.MqttHelper.Companion.CLIENT_ID
import com.jason.publisher.modules.map.helpers.MqttHelper.Companion.PUB_MSG_TOPIC
import com.jason.publisher.modules.map.helpers.MqttHelper.Companion.REQUEST_PERIODIC_TIME
import com.jason.publisher.modules.map.helpers.MqttHelper.Companion.SERVER_URI
import com.jason.publisher.modules.mqtt.services.MqttManager
import org.json.JSONObject

class ClientAttributesService : Service() {
    val mqttConfigHelper = MqttConfigHelper()

    lateinit var mqttManager: MqttManager

    /** Access token of the current device */
    var token = ""

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("HardwareIds")
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        mqttConfigHelper.fetchConfig { configList ->
            val aid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            token = mqttConfigHelper.getAccessToken(aid, configList)
            mqttManager = if (token.isNotEmpty()) MqttManager(
                serverUri = SERVER_URI,
                clientId = CLIENT_ID,
                username = token,
            ) else MqttManager(
                serverUri = SERVER_URI,
                clientId = CLIENT_ID,
            )
            Log.d("ClientAttributesService", "access token: $token")
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
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Clear the shared currentTripLabel on the server and coerce other tablets to refresh.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun clearActiveSegmentAndRefresh() {
        try {
            // 1) publish empty label to clear server-side client attribute
            try {
                val payload = "{\"currentTripLabel\":\"\", \"activityState\":\"\"}"
                if (::mqttManager.isInitialized) {
                    if (!mqttManager.isMqttConnect()) {
                        mqttManager.connect { success ->
                            if (success) {
                                Log.d("ClientAttributesService", "publishing clearer payload")
                                mqttManager.publish(ATTR_TOPIC, payload)
                            } else {
                                Log.w("ClientAttributesService", "unable to connect to MQTT")
                            }
                        }
                    } else {
                        Log.d("ClientAttributesService", "publishing clearer payload")
                        mqttManager.publish(ATTR_TOPIC, payload)
                    }
                }

                // Ask server to re-publish shared data and force a quick attribute refresh
                // so other tablets observe the change promptly.
                try {
                    Log.d("ClientAttributesService", "requesting refresh")
                    requestAdminMessage()
                } catch (e: Exception) {
                    Log.w("ClientAttributesService", "clearActiveSegment follow-up failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w("ClientAttributesService", "clearActiveSegment failed: ${e.message}")
            }

            // 2) ask ThingsBoard to re-broadcast and then force a poll/refresh
            try {
                // this triggers any admin broadcast side-effects you use
                try { requestAdminMessage() } catch (e: Exception) {
                    Log.w("ClientAttributesService", "requestAdminMessage failed: ${e.message}")
                }
                return
            } catch (e: Exception) {
                Log.w("ClientAttributesService", "mqttHelper interaction failed: ${e.message}")
            }

        } catch (e: Exception) {
            Log.w("ClientAttributesService", "clearActiveSegmentAndRefresh failed: ${e.message}")
        }
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
}