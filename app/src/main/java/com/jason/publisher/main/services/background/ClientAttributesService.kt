package com.jason.publisher.main.services.background

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.jason.publisher.main.loggers.FetchSessionStore
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.utils.getOrCreateDeviceAid
import com.jason.publisher.modules.map.mqtt.helpers.MqttConfigHelper
import com.jason.publisher.modules.map.mqtt.helpers.MqttHelper.Companion.ATTR_TOPIC
import com.jason.publisher.modules.map.mqtt.helpers.MqttHelper.Companion.PUB_MSG_TOPIC
import com.jason.publisher.modules.map.mqtt.helpers.MqttHelper.Companion.REQUEST_PERIODIC_TIME
import com.jason.publisher.modules.map.mqtt.services.MqttManager
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
            // Must use the app's canonical AID (aid.txt if present), not the raw hardware
            // ANDROID_ID - they can differ, and the bus config is keyed on the former. This was
            // silently resolving to an empty token before (confirmed on-device), meaning this
            // service's currentTripLabel clearing was publishing under no proper device identity.
            val aid = getOrCreateDeviceAid(applicationContext)
            token = MqttConfigHelper.getAccessToken(aid, configList)
            mqttManager = if (token.isNotEmpty()) MqttManager(username = token) else MqttManager()
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
                    FileLogger.w("ClientAttributesService", "clearActiveSegment follow-up failed | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
                }
            } catch (e: Exception) {
                Log.w("ClientAttributesService", "clearActiveSegment failed: ${e.message}")
                FileLogger.w("ClientAttributesService", "clearActiveSegment failed | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
            }

            // 2) ask ThingsBoard to re-broadcast and then force a poll/refresh
            try {
                // this triggers any admin broadcast side-effects you use
                try { requestAdminMessage() } catch (e: Exception) {
                    Log.w("ClientAttributesService", "requestAdminMessage failed: ${e.message}")
                    FileLogger.w("ClientAttributesService", "requestAdminMessage failed | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
                }
                return
            } catch (e: Exception) {
                Log.w("ClientAttributesService", "mqttHelper interaction failed: ${e.message}")
                FileLogger.w("ClientAttributesService", "mqttHelper interaction failed | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
            }

        } catch (e: Exception) {
            Log.w("ClientAttributesService", "clearActiveSegmentAndRefresh failed: ${e.message}")
            FileLogger.w("ClientAttributesService", "clearActiveSegmentAndRefresh failed | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
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