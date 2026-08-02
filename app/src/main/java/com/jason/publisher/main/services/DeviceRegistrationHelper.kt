package com.jason.publisher.main.services

import android.util.Log
import com.jason.publisher.BuildConfig
import com.jason.publisher.main.model.BusItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class DeviceRegistrationHelper {

    private val client = OkHttpClient()
    private val json = "application/json".toMediaType()
    private val base = "${BuildConfig.TB_HOST}/api"

    /**
     * Full registration flow for a new bus device:
     * 1. Admin login → JWT
     * 2. Create ThingsBoard device
     * 3. Get new device's access token
     * 4. Copy shared attributes from the template device (first existing bus), override name
     * 5. Read current Config Data to get existing bus list
     * 6. List all ThingsBoard devices to resolve device IDs by name
     * 7. Push updated busConfig to Config Data + every existing bus device + new device
     *
     * @param templateToken  Access token of an existing bus device used as attribute template.
     *                       Pass blank if no existing devices exist yet.
     */
    fun register(
        aid: String,
        busName: String,
        templateToken: String,
        onProgress: (String) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        onProgress("Logging in to ThingsBoard…")
        login { jwt ->
            if (jwt == null) { onError("Login failed. Check admin credentials."); return@login }

            onProgress("Creating device on ThingsBoard…")
            createDevice(jwt, "$busName Device") { deviceId ->
                if (deviceId == null) { onError("Failed to create device."); return@createDevice }

                onProgress("Fetching device access token…")
                getCredentials(jwt, deviceId) { accessToken ->
                    if (accessToken == null) { onError("Failed to get device credentials."); return@getCredentials }

                    onProgress("Copying attributes from template…")
                    applyTemplateAttributes(jwt, deviceId, busName, templateToken) {

                        onProgress("Reading Config Data…")
                        readConfigData(jwt) { existingList ->
                            if (existingList == null) {
                                onError("Could not read Config Data. Aborting to avoid data loss.")
                                return@readConfigData
                            }
                            val updatedList = existingList + BusItem(aid, busName, accessToken)

                            onProgress("Resolving device IDs…")
                            listAllDevices(jwt) { deviceMap ->

                                onProgress("Pushing updated config to all devices…")
                                postConfigToDevice(jwt, BuildConfig.TB_CONFIG_DEVICE_ID, updatedList) { cdOk ->
                                    if (!cdOk) { onError("Failed to update Config Data device."); return@postConfigToDevice }
                                    pushConfigToAllBusDevices(jwt, existingList, updatedList, deviceId, deviceMap) {
                                        onSuccess()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Step 1 ────────────────────────────────────────────────────────────────

    private fun login(callback: (String?) -> Unit) {
        val body = JSONObject().apply {
            put("username", BuildConfig.TB_USER)
            put("password", BuildConfig.TB_PASS)
        }.toString().toRequestBody(json)

        enqueue(Request.Builder().url("$base/auth/login").post(body).build(),
            onError = { callback(null) }
        ) { body ->
            callback(JSONObject(body).optString("token").takeIf { it.isNotBlank() })
        }
    }

    // ── Step 2 ────────────────────────────────────────────────────────────────

    private fun createDevice(jwt: String, deviceName: String, callback: (String?) -> Unit) {
        val body = JSONObject().apply {
            put("name", deviceName)
            put("type", "default")
        }.toString().toRequestBody(json)

        enqueue(Request.Builder().url("$base/device")
            .header("X-Authorization", "Bearer $jwt").post(body).build(),
            onError = { callback(null) }
        ) { body ->
            callback(JSONObject(body).optJSONObject("id")?.optString("id")?.takeIf { it.isNotBlank() })
        }
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────

    private fun getCredentials(jwt: String, deviceId: String, callback: (String?) -> Unit) {
        enqueue(Request.Builder().url("$base/device/$deviceId/credentials")
            .header("X-Authorization", "Bearer $jwt").get().build(),
            onError = { callback(null) }
        ) { body ->
            callback(JSONObject(body).optString("credentialsId").takeIf { it.isNotBlank() })
        }
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    /**
     * Fetches shared attributes from the template device (via device-level API, no JWT needed)
     * and applies them to the new device, overriding 'name' with [newBusName].
     * The 'config' key is intentionally excluded — it will be set in step 7 with the full list.
     * Falls back to minimal defaults if the template fetch fails or [templateToken] is blank.
     */
    private fun applyTemplateAttributes(
        jwt: String,
        newDeviceId: String,
        newBusName: String,
        templateToken: String,
        callback: () -> Unit
    ) {
        if (templateToken.isBlank()) {
            applyDefaultAttributes(jwt, newDeviceId, newBusName, callback)
            return
        }

        val keys = "busRoute,busRouteData,busStop,message,operatorMessage,scheduleData"
        enqueue(
            Request.Builder()
                .url("${BuildConfig.TB_HOST}/api/v1/$templateToken/attributes?sharedKeys=$keys")
                .get().build(),
            onError = { applyDefaultAttributes(jwt, newDeviceId, newBusName, callback) }
        ) { responseBody ->
            try {
                val shared = JSONObject(responseBody).optJSONObject("shared") ?: JSONObject()
                shared.put("name", newBusName)   // override name; exclude 'config' (set in step 7)
                val body = shared.toString().toRequestBody(json)
                enqueue(
                    Request.Builder()
                        .url("$base/plugins/telemetry/DEVICE/$newDeviceId/attributes/SHARED_SCOPE")
                        .header("X-Authorization", "Bearer $jwt").post(body).build(),
                    onError = { callback() }
                ) { callback() }
            } catch (e: Exception) {
                Log.e(TAG, "applyTemplateAttributes parse error", e)
                applyDefaultAttributes(jwt, newDeviceId, newBusName, callback)
            }
        }
    }

    private fun applyDefaultAttributes(jwt: String, deviceId: String, busName: String, callback: () -> Unit) {
        val body = JSONObject().apply {
            put("busRoute", "")
            put("busRouteData", "")
            put("busStop", "")
            put("message", "")
            put("name", busName)
            put("operatorMessage", "")
            put("scheduleData", "")
        }.toString().toRequestBody(json)

        enqueue(
            Request.Builder()
                .url("$base/plugins/telemetry/DEVICE/$deviceId/attributes/SHARED_SCOPE")
                .header("X-Authorization", "Bearer $jwt").post(body).build(),
            onError = { callback() }
        ) { callback() }
    }

    // ── Step 5 ────────────────────────────────────────────────────────────────

    private fun readConfigData(jwt: String, callback: (List<BusItem>?) -> Unit) {
        // ThingsBoard admin REST API: GET uses /values/attributes/, POST uses /attributes/
        val request = Request.Builder()
            .url("$base/plugins/telemetry/DEVICE/${BuildConfig.TB_CONFIG_DEVICE_ID}/values/attributes/SHARED_SCOPE?keys=config")
            .header("X-Authorization", "Bearer $jwt").get().build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "readConfigData network failure", e)
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: ""
                if (response.code == 404) {
                    // No 'config' attribute on Config Data yet — safe to treat as empty for first registration
                    callback(emptyList())
                    return
                }
                if (!response.isSuccessful) {
                    Log.e(TAG, "readConfigData → ${response.code}: $body")
                    callback(null)
                    return
                }
                try {
                    // Admin API returns: [{ "key": "config", "value": { "busConfig": [...] } }]
                    val arr = JSONArray(body)
                    val configEntry = (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("key") == "config" }
                    val busConfigArr = configEntry?.optJSONObject("value")?.optJSONArray("busConfig")
                    val list = buildList {
                        busConfigArr?.let {
                            for (i in 0 until it.length()) {
                                val item = it.getJSONObject(i)
                                add(BusItem(
                                    aid = item.getString("aid"),
                                    bus = item.getString("bus"),
                                    accessToken = item.getString("accessToken")
                                ))
                            }
                        }
                    }
                    callback(list)
                } catch (e: Exception) {
                    Log.e(TAG, "readConfigData parse error", e)
                    callback(null)
                }
            }
        })
    }

    // ── Step 6 ────────────────────────────────────────────────────────────────

    /** Returns a map of deviceName → deviceId for all tenant devices. */
    private fun listAllDevices(jwt: String, callback: (Map<String, String>) -> Unit) {
        enqueue(
            Request.Builder()
                .url("$base/tenant/devices?pageSize=100&page=0")
                .header("X-Authorization", "Bearer $jwt").get().build(),
            onError = { callback(emptyMap()) }
        ) { responseBody ->
            try {
                val arr = JSONObject(responseBody).optJSONArray("data") ?: JSONArray()
                val map = buildMap<String, String> {
                    for (i in 0 until arr.length()) {
                        val d = arr.getJSONObject(i)
                        val name = d.optString("name")
                        val id = d.optJSONObject("id")?.optString("id") ?: ""
                        if (name.isNotBlank() && id.isNotBlank()) put(name, id)
                    }
                }
                callback(map)
            } catch (e: Exception) {
                Log.e(TAG, "listAllDevices parse error", e)
                callback(emptyMap())
            }
        }
    }

    // ── Step 7 ────────────────────────────────────────────────────────────────

    /**
     * Posts the updated busConfig list to:
     *  • every existing bus device (resolved by "Bus X Device" name in [deviceMap])
     *  • the newly created device ([newDeviceId])
     * Config Data is updated separately before this is called.
     */
    private fun pushConfigToAllBusDevices(
        jwt: String,
        existingList: List<BusItem>,
        updatedList: List<BusItem>,
        newDeviceId: String,
        deviceMap: Map<String, String>,
        callback: () -> Unit
    ) {
        val targets = buildList {
            existingList.forEach { item -> deviceMap["${item.bus} Device"]?.let { add(it) } }
            add(newDeviceId)
        }

        fun next(index: Int) {
            if (index >= targets.size) { callback(); return }
            postConfigToDevice(jwt, targets[index], updatedList) { next(index + 1) }
        }
        next(0)
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun postConfigToDevice(
        jwt: String,
        deviceId: String,
        busItems: List<BusItem>,
        callback: (Boolean) -> Unit = {}
    ) {
        val busConfigArr = JSONArray().apply {
            busItems.forEach { item ->
                put(JSONObject().apply {
                    put("aid", item.aid)
                    put("bus", item.bus)
                    put("accessToken", item.accessToken)
                })
            }
        }
        val body = JSONObject()
            .put("config", JSONObject().put("busConfig", busConfigArr))
            .toString().toRequestBody(json)

        enqueue(
            Request.Builder()
                .url("$base/plugins/telemetry/DEVICE/$deviceId/attributes/SHARED_SCOPE")
                .header("X-Authorization", "Bearer $jwt").post(body).build(),
            onError = { callback(false) }
        ) { callback(true) }
    }

    private fun enqueue(request: Request, onError: () -> Unit, onSuccess: (String) -> Unit) {
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "request failed: ${request.url}", e)
                onError()
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) onSuccess(body)
                else { Log.e(TAG, "${request.url} → ${response.code}: $body"); onError() }
            }
        })
    }

    companion object {
        private const val TAG = "DeviceRegistration"
    }
}
