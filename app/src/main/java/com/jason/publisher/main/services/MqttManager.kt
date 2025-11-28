package com.jason.publisher.main.services

import android.util.Log
import com.jason.publisher.main.model.BusItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
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
    serverUri: String,
    clientId: String,
    private var username: String = "BEXBIArF3URHeYBslJE2" // Config Data
) {
    private val persistence = MemoryPersistence()
    private val mqttClient = MqttClient(serverUri, clientId, persistence)
    private val connectOptions = MqttConnectOptions()

    init {
        connectOptions.userName = username
        connectOptions.isCleanSession = true
        connectOptions.connectionTimeout = 10
        connectOptions.keepAliveInterval = 60
        Log.d("MqttManager", "Initializing MQTT client")
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

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                e.printStackTrace()
                Log.e("MqttManager", "Failed to fetch shared attributes", e)
                callback(emptyList()) // Return an empty list in case of failure
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
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
    fun connect(callback: (Boolean) -> Unit) {
        try {
            if (!mqttClient.isConnected) {
                mqttClient.connect(connectOptions)
                Log.d("MqttManager", "MQTT client connected")
                callback(true)
            } else {
                Log.d("MqttManager", "MQTT client is already connected")
                callback(true) // Assume success if already connected
            }
        } catch (e: MqttException) {
            Log.e("MqttManager", "Failed to connect to MQTT broker: ${e.message}", e)
            callback(false)
        } catch (e: NullPointerException) {
            Log.e("MqttManager", "MQTT client is not initialized: ${e.message}", e)
            callback(false)
        }
    }

    /**
     * Reconnects to the MQTT broker and executes the callback on success or failure.
     *
     * @param callback The callback to execute after attempting to connect.
     */
    fun reconnect() {
        try {
            if (mqttClient.isConnected) {
                Log.d("MqttManager", "MQTT client is already connected, no need to reconnect.")
            } else {
                mqttClient.reconnect()
                Log.d("MqttManager", "MQTT client reconnected successfully.")
            }
        } catch (e: MqttException) {
            Log.e("MqttManager", "Failed to reconnect to MQTT broker: ${e.message}", e)
        } catch (e: NullPointerException) {
            Log.e("MqttManager", "MQTT client is not initialized: ${e.message}", e)
        }
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
        try {
            Log.d("mqtt manager", "publish method call")
            val mqttMessage = MqttMessage(message.toByteArray())
            mqttMessage.qos = qos
            mqttMessage.isRetained = false
            mqttClient.publish(topic, mqttMessage)
        } catch (e: MqttException) {
            Log.d("MqttManager", "Failed to publish message: ${e.message}")
            // handle the exception according to your app's requirements
        }
    }

    /**
     * Subscribes to a specified topic and sets a callback to handle incoming messages.
     *
     * @param topic The topic to subscribe to.
     * @param callback The callback to handle incoming messages.
     */
    fun subscribe(topic: String, callback: (String) -> Unit) {
        mqttClient.subscribe(topic) { _, msg ->
            val payload = String(msg.payload)
            callback(payload)
        }
    }

    /**
     * Disconnects from the MQTT broker.
     */
    fun disconnect() {
        mqttClient.disconnect()
    }

    /**
     * Gets the username used for the MQTT connection.
     *
     * @return The username.
     */
    fun getUsername(): String {
        return username
    }
}