package com.jason.publisher

import com.jason.publisher.model.BusItem
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

/**
 * Object that provides online data for bus configurations.
 */
object OnlineData {

    /**
     * Retrieves a list of BusItem objects representing the bus configuration.
     * The configuration is hardcoded as a JSON string.
     *
     * @return List of BusItem representing the bus configuration.
     */
    fun getConfig() : List<BusItem> {
        val jsonString = """
            {"busConfig":[{"aid":"8d34bdc9a5c78c42","bus":"Bus A","accessToken":"3KMpqehqIfz7V4NT6xW7"},{"aid":"2b039058a1a5f8a3","bus":"Bus B","accessToken":"wurH9PWz8Le2Lqcsu7tw"}, {"aid":"02372ba208415152","bus":"Bus C","accessToken":"c1T0vQ8QFf9pfiluG27Y"}]}
            
        """.trimIndent()
        val jsonString1 = """
            {}
        """.trimIndent()
        val configurationBus = mutableListOf<BusItem>()
        val jsonObject = JSONObject(jsonString)
        val jsonArray = jsonObject.getJSONArray("busConfig")

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val aid = item.getString("aid")
            val bus = item.getString("bus")
            val accessToken = item.getString("accessToken")
            configurationBus.add(BusItem(aid,bus,accessToken))
        }
        return configurationBus
    }
}