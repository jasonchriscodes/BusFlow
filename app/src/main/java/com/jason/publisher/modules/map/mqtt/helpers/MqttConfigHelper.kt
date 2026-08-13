package com.jason.publisher.modules.map.mqtt.helpers

import android.annotation.SuppressLint
import android.util.Log
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.model.BusItem
import com.jason.publisher.modules.map.mqtt.services.MqttManager

class MqttConfigHelper {
    private val mqttManagerConfig = MqttManager(username = CONFIG_TOKEN)

    companion object {
        private const val CONFIG_TOKEN = "BEXBIArF3URHeYBslJE2"

        /**
         * Retrieves the access token for the current device's Android ID ([aid]) from
         * the configuration list ([config]].
         */
        @SuppressLint("HardwareIds")
        fun getAccessToken(aid: String, config: List<BusItem>): String {
            Log.d("MqttConfigHelper", config.toString())
            for (configItem in config) {
                if (configItem.aid == aid) {
                    return configItem.accessToken
                }
            }
            return ""
        }
    }

    /**
     * Fetches the schedule configuration data and initializes the config variable.
     */
    fun fetchConfig(callback: (List<BusItem>) -> Unit) {
        Log.d("MqttConfigHelper", "Fetching config...")
        mqttManagerConfig.fetchSharedAttributes(CONFIG_TOKEN) { configList ->
            if (configList.isNotEmpty()) {
                Log.d("MqttConfigHelper", "Config received: $configList")
                callback(configList)
            } else {
                FileLogger.e("MqttConfigHelper", "Failed to initialize config. No bus information available.")
                callback(emptyList())
            }
        }
    }
}