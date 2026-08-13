package com.jason.publisher.main.loggers

import android.content.Context
import android.util.Log
import com.jason.publisher.main.services.ApiService
import com.jason.publisher.main.services.ApiServiceBuilder
import com.jason.publisher.main.utils.getOrCreateDeviceAid
import com.jason.publisher.modules.map.mqtt.helpers.MqttConfigHelper
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Publishes [FileLogger]'s buffered log text to ThingsBoard as a client attribute (key
 * "deviceLog"), the same way MapViewModel/RepViewModel's updateClientAttributes() already posts
 * GPS/schedule data - a plain REST POST to v1/{token}/attributes. A dedicated MQTT connection
 * from a background service was tried first but isn't reliable enough to depend on for this.
 *
 * Called from App.kt on every activity change and when the app is judged closed - not on a
 * timer, so a quiet session doesn't generate any network traffic at all.
 */
object RemoteLogPublisher {
    private const val TAG = "RemoteLogPublisher"
    private const val LOG_ATTRIBUTE_KEY = "deviceLog"

    private val apiService by lazy { ApiServiceBuilder.buildService(ApiService::class.java) }
    private val configHelper = MqttConfigHelper()

    @Volatile private var token: String? = null
    @Volatile private var resolvingToken = false
    @Volatile private var lastPublishedVersion = -1L

    /** Publish the current buffer if it's changed since the last successful publish. */
    fun publishIfChanged(context: Context) {
        val version = FileLogger.getBufferedLogVersion()
        if (version == lastPublishedVersion) return

        val knownToken = token
        if (knownToken != null) {
            postLog(knownToken, version)
            return
        }
        if (resolvingToken) return
        resolvingToken = true

        // Must use the app's canonical AID (Documents/.vlrshiddenfolder/aid.txt if present, NOT
        // the raw hardware Settings.Secure.ANDROID_ID) - they can differ, and the bus config is
        // keyed on the former. Confirmed on-device: using the raw id resolved to an AID with no
        // matching config entry at all, silently breaking token resolution entirely.
        val aid = getOrCreateDeviceAid(context)
        Log.d(TAG, "Resolving access token for aid=$aid")
        configHelper.fetchConfig { configList ->
            resolvingToken = false
            val resolved = MqttConfigHelper.getAccessToken(aid, configList)
            if (resolved.isNotEmpty()) {
                Log.d(TAG, "Resolved token (len=${resolved.length}), publishing")
                token = resolved
                postLog(resolved, FileLogger.getBufferedLogVersion())
            } else {
                Log.w(TAG, "No matching config entry for aid=$aid among ${configList.size} entries - cannot publish yet")
            }
        }
    }

    private fun postLog(token: String, version: Long) {
        val url = ApiService.BASE_URL + "$token/attributes"
        val text = FileLogger.getBufferedLogText()
        val body = mapOf(LOG_ATTRIBUTE_KEY to text)

        Log.d(TAG, "POST $url (deviceLog len=${text.length})")
        apiService.postAttributes(url, "application/json", body).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Log.d(TAG, "postLog OK (code=${response.code()})")
                    lastPublishedVersion = version
                } else {
                    Log.w(TAG, "postLog failed: code=${response.code()} body=${response.errorBody()?.string()}")
                }
            }
            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.w(TAG, "postLog error: ${t.javaClass.simpleName}: ${t.message}")
            }
        })
    }
}
