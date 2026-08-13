package com.jason.publisher.modules.schedule.helpers

import android.util.Log
import com.jason.publisher.main.loggers.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class TbLatestOta(
    val title: String,
    val version: String,
    val type: String,
    val createdTime: Long,
    val id: String
)

class TbAdminOtaLatest(
    private val tbHost: String,
    private val tbUser: String,
    private val tbPass: String
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun log(msg: String) = FileLogger.d("TbAdminOtaLatest", msg)
    private fun hostNoSlash(): String = tbHost.trimEnd('/')

    private suspend fun loginJwt(): String? = withContext(Dispatchers.IO) {
        val url = "${hostNoSlash()}/api/auth/login"
        val bodyJson = JSONObject()
            .put("username", tbUser)
            .put("password", tbPass)
            .toString()

        log("login: POST $url user.len=${tbUser.length} pass.len=${tbPass.length}")

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            log("login: HTTP ${resp.code}")

            // ✅ Read body ONCE
            val bodyStr = resp.body?.string().orEmpty()
            log("login: body=$bodyStr")

            if (!resp.isSuccessful) return@withContext null

            val json = runCatching { JSONObject(bodyStr) }.getOrElse { e ->
                FileLogger.e("TbAdminOtaLatest", "login: JSON parse failed | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
                null
            }
            if (json == null) {
                log("login: FAIL cannot parse JSON")
                return@withContext null
            }

            val token = json.optString("token")
                .ifBlank { json.optString("accessToken") }
                .ifBlank { null }

            log("login: tokenPresent=${token != null}")
            return@withContext token
        }
    }

    suspend fun fetchLatest(titleSearch: String): TbLatestOta? = withContext(Dispatchers.IO) {
        val jwt = loginJwt() ?: run {
            log("fetchLatest: login failed")
            return@withContext null
        }

        val url =
            "${hostNoSlash()}/api/otaPackages" +
                    "?pageSize=50&page=0&sortProperty=createdTime&sortOrder=DESC" +
                    "&textSearch=${java.net.URLEncoder.encode(titleSearch, "UTF-8")}"

        log("fetchLatest: GET $url")

        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("X-Authorization", "Bearer $jwt")
            .build()

        client.newCall(req).execute().use { resp ->
            log("fetchLatest: HTTP ${resp.code}")

            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                log("fetchLatest: FAIL body=$bodyStr")
                return@withContext null
            }

            val root = JSONObject(bodyStr)
            val data: JSONArray = root.optJSONArray("data") ?: JSONArray()
            if (data.length() == 0) {
                log("fetchLatest: empty data[]")
                return@withContext null
            }

            val first = data.getJSONObject(0)
            val idObj = first.optJSONObject("id")
            val id = idObj?.optString("id").orEmpty()

            val title = first.optString("title")
            val version = first.optString("version")
            val type = first.optString("type")
            val createdTime = first.optLong("createdTime", 0L)

            log("fetchLatest: latest title=$title version=$version type=$type createdTime=$createdTime id=$id")

            if (title.isBlank() || version.isBlank() || id.isBlank()) return@withContext null
            TbLatestOta(title, version, type, createdTime, id)
        }
    }

    suspend fun fetchLatestExact(title: String, type: String, pageSize: Int = 200): TbLatestOta? =
        withContext(Dispatchers.IO) {
            val jwt = loginJwt() ?: run {
                log("fetchLatestExact: login failed")
                return@withContext null
            }

            val url = "${hostNoSlash()}/api/otaPackages?pageSize=$pageSize&page=0"
            log("fetchLatestExact: GET $url (filter title=$title type=$type)")

            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("X-Authorization", "Bearer $jwt")
                .build()

            client.newCall(req).execute().use { resp ->
                log("fetchLatestExact: HTTP ${resp.code}")

                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    log("fetchLatestExact: FAIL body=$bodyStr")
                    return@withContext null
                }

                val root = JSONObject(bodyStr)
                val data: JSONArray = root.optJSONArray("data") ?: JSONArray()
                log("fetchLatestExact: data.length=${data.length()}")

                var best: JSONObject? = null
                var bestCreated = Long.MIN_VALUE

                for (i in 0 until data.length()) {
                    val obj = data.optJSONObject(i) ?: continue
                    if (obj.optString("title") != title) continue
                    if (obj.optString("type") != type) continue

                    val created = obj.optLong("createdTime", 0L)
                    if (created > bestCreated) {
                        bestCreated = created
                        best = obj
                    }
                }

                if (best == null) {
                    log("fetchLatestExact: no match for title=$title type=$type")
                    return@withContext null
                }

                val id = best.optJSONObject("id")?.optString("id").orEmpty()
                val out = TbLatestOta(
                    title = best.optString("title"),
                    version = best.optString("version"),
                    type = best.optString("type"),
                    createdTime = best.optLong("createdTime", 0L),
                    id = id
                )

                log("fetchLatestExact: latest title=${out.title} version=${out.version} type=${out.type} createdTime=${out.createdTime} id=${out.id}")

                if (out.title.isBlank() || out.version.isBlank() || out.id.isBlank()) null else out
            }
        }

    suspend fun downloadApkById(otaId: String, outFile: File): File? = withContext(Dispatchers.IO) {
        val jwt = loginJwt() ?: run {
            log("downloadApkById: login failed")
            return@withContext null
        }

        if (outFile.exists() && outFile.length() > 0) {
            log("downloadApkById: reuse existing ${outFile.absolutePath} size=${outFile.length()}")
            return@withContext outFile
        }

        val url = "${hostNoSlash()}/api/otaPackage/$otaId/download"
        log("downloadApkById: GET $url -> ${outFile.absolutePath}")

        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("X-Authorization", "Bearer $jwt")
            .build()

        client.newCall(req).execute().use { resp ->
            log("downloadApkById: HTTP ${resp.code} contentType=${resp.header("Content-Type")} contentLength=${resp.header("Content-Length")}")

            // ✅ Read bytes ONCE
            val bytes = resp.body?.bytes()

            if (!resp.isSuccessful) {
                val err = bytes?.decodeToString().orEmpty()
                log("downloadApkById: FAIL body=$err")
                return@withContext null
            }

            if (bytes == null || bytes.isEmpty()) {
                log("downloadApkById: FAIL body bytes empty")
                return@withContext null
            }

            outFile.outputStream().use { it.write(bytes) }
        }

        log("downloadApkById: saved size=${outFile.length()}")
        if (outFile.exists() && outFile.length() > 0) outFile else null
    }
}
