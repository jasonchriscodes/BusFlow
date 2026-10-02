package com.jason.publisher.modules.map.mqtt.services

import android.util.Log
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.TripStateSnapshot
import com.jason.publisher.main.model.BusItem
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Class responsible for managing MQTT connections, publishing, and subscribing to topics.
 *
 * @param serverUri The URI of the MQTT server.
 * @param clientId The client ID to use for the connection.
 * @param username The username for the MQTT connection (default is "cngz9qqls7dk5zgi3y4j").
 */
class MqttManager(
    serverUri: String = SERVER_URI,
    clientId: String = newClientId(),
    private var username: String = "BEXBIArF3URHeYBslJE2" // Config Data
) {
    private val persistence = MemoryPersistence()
    private val mqttClient = MqttClient(serverUri, clientId, persistence)
    private val connectOptions = MqttConnectOptions()
    @Volatile
    private var manuallyDisconnected = false

    companion object {
        /** One shared thread for blocking MQTT calls issued from the main thread. */
        private val mqttIo = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "MqttManager-io").apply { isDaemon = true } }

        const val SERVER_URI = "ssl://mqtt.thingsboard.cloud:8883"

        fun newClientId(): String {
            return "jasonAndroidClientId-${System.currentTimeMillis()}-${(1000..9999).random()}"
        }
    }

    init {
        connectOptions.userName = username
        connectOptions.isCleanSession = true
        connectOptions.connectionTimeout = 10
        connectOptions.keepAliveInterval = 60
        // Bus connectivity drops constantly (tunnels, dead zones, etc.) - let Paho retry the
        // connection itself instead of leaving tracking silently dark until some other screen
        // happens to call connect() again.
        connectOptions.isAutomaticReconnect = true
        // Blocking Paho calls must never wait longer than this (they used to hang the UI → ANR).
        mqttClient.timeToWait = 3000L

        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.d("MqttManager", "MQTT connectComplete reconnect=$reconnect server=$serverURI")
                FileLogger.d("MqttManager", "MQTT connectComplete reconnect=$reconnect server=$serverURI")
            }

            override fun connectionLost(cause: Throwable?) {
                if (manuallyDisconnected) {
                    Log.d("MqttManager", "MQTT disconnected manually")
                    return
                }

                val causeText = cause?.let { "${it.javaClass.simpleName}: ${it.message}\n${Log.getStackTraceString(it)}" } ?: "unknown"
                FileLogger.w("MqttManager", "MQTT connection lost | $causeText | ${TripStateSnapshot.describe()}")

                // Important: do not throw here. isAutomaticReconnect handles getting back
                // online; connectComplete(reconnect=true, ...) fires once it succeeds.
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                // You use mqttClient.subscribe(topic) { _, msg -> ... }
                // so this can stay empty.
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                // no-op
            }
        })

        Log.d("MqttManager", "Initializing MQTT client")
        FileLogger.d("MqttManager", "Initializing MQTT client")
    }

    private val client = OkHttpClient()

    /**
     * Function to fetch shared attributes from ThingsBoard
     */
    fun fetchSharedAttributes(deviceToken: String, callback: (List<BusItem>) -> Unit) {
        val url = "https://thingsboard.cloud/api/v1/$deviceToken/attributes?sharedKeys=config"

        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                FileLogger.e("MqttManager", "Failed to fetch shared attributes | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
                callback(emptyList()) // Return an empty list in case of failure
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    Log.d("MqttManager", "Response data: $responseData") // Log the entire response for debugging

                    try {
                        val jsonObject = JSONObject(responseData!!)
                        val sharedObject = jsonObject.optJSONObject("shared")

                        // Access "config" inside "shared"
                        if (sharedObject != null && sharedObject.has("config")) {
                            val configObject = sharedObject.getJSONObject("config")

                            // Now look for "busConfig" inside "config"
                            if (configObject.has("busConfig")) {
                                val configJson = configObject.getJSONArray("busConfig")
                                val busList = mutableListOf<BusItem>()

                                for (i in 0 until configJson.length()) {
                                    val busConfigItem = configJson.getJSONObject(i)
                                    val aid = busConfigItem.getString("aid")
                                    val bus = busConfigItem.getString("bus")
                                    val accessToken = busConfigItem.getString("accessToken")
                                    busList.add(BusItem(aid, bus, accessToken))
                                }

                                Log.d("MqttManager", "Parsed bus config: $busList")
                                callback(busList)
                            } else {
                                FileLogger.e("MqttManager", "No 'busConfig' found in the config. | ${TripStateSnapshot.describe()}")
                                callback(emptyList()) // Return empty list if no busConfig found
                            }
                        } else {
                            FileLogger.e("MqttManager", "No 'config' found in the shared attributes. | ${TripStateSnapshot.describe()}")
                            callback(emptyList()) // Return empty list if no config found
                        }
                    } catch (e: JSONException) {
                        FileLogger.e("MqttManager", "Failed to parse JSON | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
                        callback(emptyList()) // Handle JSON parsing errors
                    }
                } else {
                    FileLogger.e("MqttManager", "Response was not successful: ${response.message} | ${TripStateSnapshot.describe()}")
                    callback(emptyList()) // Return empty list if response failed
                }
            }
        })
    }

    /**
     * Connects to the MQTT broker and executes the callback on success or failure.
     *
     * @param callback The callback to execute after attempting to connect.
     */
    @Volatile private var connecting = false

    fun connect(callback: (Boolean) -> Unit) {
        if (mqttClient.isConnected) {
            callback(true)
            return
        }

        if (connecting) {
            FileLogger.w("MqttManager", "Connect skipped: already connecting | ${TripStateSnapshot.describe()}")
            callback(false)
            return
        }

        connecting = true
        manuallyDisconnected = false

        val ok = try {
            mqttClient.connect(connectOptions)
            true
        } catch (e: Exception) {
            FileLogger.e("MqttManager", "MQTT connect failed | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
            false
        } finally {
            connecting = false
        }

        callback(ok)
    }

    /**
     * Checks if the MQTT client is connected.
     *
     * @return True if connected, false otherwise.
     */
    fun isMqttConnect(): Boolean {
        return mqttClient.isConnected
    }

    /**
     * Publishes a message to a specified topic.
     *
     * @param topic The topic to publish to.
     * @param message The message to publish.
     * @param qos The Quality of Service level for the message (default is 0).
     */
    fun publish(topic: String, message: String, qos: Int = 0) = runOffMain {
        if (!mqttClient.isConnected) {
            Log.d("MqttManager", "Publish skipped: not connected")
            return@runOffMain
        }
        try {
            val mqttMessage = MqttMessage(message.toByteArray()).apply {
                this.qos = qos
                isRetained = false
            }
            mqttClient.publish(topic, mqttMessage)
        } catch (e: Exception) {
            FileLogger.w("MqttManager", "Failed to publish message to $topic | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
        }
    }

    /**
     * Subscribes to a specified topic and sets a callback to handle incoming messages.
     *
     * @param topic The topic to subscribe to.
     * @param callback The callback to handle incoming messages.
     */
    fun subscribe(topic: String, callback: (String) -> Unit) = runOffMain {
        if (!mqttClient.isConnected) {
            FileLogger.e("MqttManager", "Subscribe skipped: not connected | topic=$topic | ${TripStateSnapshot.describe()}")
            return@runOffMain
        }
        try {
            mqttClient.subscribe(topic) { _, msg -> callback(String(msg.payload)) }
        } catch (e: Exception) {
            // The connection can drop between isConnected and subscribe; Paho then rethrows
            // "Connection lost" on the caller's thread (often main), which froze the UI (ANR).
            FileLogger.w("MqttManager", "Subscribe failed | topic=$topic | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}")
        }
    }


    /**
     * MqttClient is synchronous: publish/subscribe block until the broker answers. Callers run on
     * the main thread (Handlers, activity code), so hand the work to a background thread there.
     */
    private fun runOffMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            mqttIo.execute {
                try { block() } catch (e: Exception) {
                    FileLogger.w("MqttManager", "Background MQTT call failed | ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } else {
            block()
        }
    }

    /**
     * Gets the username used for the MQTT connection.
     *
     * @return The username.
     */
    fun getUsername(): String {
        return username
    }
    /** True if MQTT is connected */
    fun isConnected(): Boolean = mqttClient.isConnected

    /** Disconnect safely (won't crash if already disconnected) */
    fun disconnect() {
        manuallyDisconnected = true

        try {
            if (mqttClient.isConnected) {
                mqttClient.disconnect()
            }
        } catch (e: Exception) {
            FileLogger.w("MqttManager", "Disconnect failed | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}")
        }

        try {
            mqttClient.close()
        } catch (e: Exception) {
            FileLogger.w("MqttManager", "Close failed | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}")
        }
    }


}