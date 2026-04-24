package com.jason.publisher.modules.map.mqtt.services

import android.util.Log
import com.jason.publisher.main.loggers.FileLogger
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

                Log.w("MqttManager", "MQTT connection lost: ${cause?.message}", cause)

                // Important: do not throw here.
                // Just log it. Optional reconnect can be added later.
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
                e.printStackTrace()
                Log.e("MqttManager", "Failed to fetch shared attributes", e)
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
                                Log.e("MqttManager", "No 'busConfig' found in the config.")
                                callback(emptyList()) // Return empty list if no busConfig found
                            }
                        } else {
                            Log.e("MqttManager", "No 'config' found in the shared attributes.")
                            callback(emptyList()) // Return empty list if no config found
                        }
                    } catch (e: JSONException) {
                        Log.e("MqttManager", "Failed to parse JSON", e)
                        callback(emptyList()) // Handle JSON parsing errors
                    }
                } else {
                    Log.e("MqttManager", "Response was not successful: ${response.message}")
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
            Log.w("MqttManager", "Connect skipped: already connecting")
            callback(false)
            return
        }

        connecting = true
        manuallyDisconnected = false

        val ok = try {
            mqttClient.connect(connectOptions)
            true
        } catch (e: Exception) {
            Log.e("MqttManager", "MQTT connect failed: ${e.message}", e)
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
    fun publish(topic: String, message: String, qos: Int = 0) {
        if (!mqttClient.isConnected) {
            Log.d("MqttManager", "Publish skipped: not connected")
            return
        }
        try {
            val mqttMessage = MqttMessage(message.toByteArray()).apply {
                this.qos = qos
                isRetained = false
            }
            mqttClient.publish(topic, mqttMessage)
        } catch (e: Exception) {
            Log.d("MqttManager", "Failed to publish message: ${e.message}", e)
        }
    }

    /**
     * Subscribes to a specified topic and sets a callback to handle incoming messages.
     *
     * @param topic The topic to subscribe to.
     * @param callback The callback to handle incoming messages.
     */
    fun subscribe(topic: String, callback: (String) -> Unit) {
        if (!mqttClient.isConnected) {
            Log.e("MqttManager", "Subscribe skipped: not connected")
            return
        }
        mqttClient.subscribe(topic) { _, msg -> callback(String(msg.payload)) }
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
            Log.w("MqttManager", "Disconnect failed: ${e.message}", e)
        }

        try {
            mqttClient.close()
        } catch (e: Exception) {
            Log.w("MqttManager", "Close failed: ${e.message}", e)
        }
    }


}