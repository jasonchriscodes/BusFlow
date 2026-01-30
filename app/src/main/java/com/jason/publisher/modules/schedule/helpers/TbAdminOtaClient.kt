package com.jason.publisher.modules.schedule.helpers

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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class TbLatestOta(
    val title: String,
    val version: String,
    val type: String,
    val createdTime: Long,
    val id: String
)

class TbAdminOtaClient(
    private val tbHost: String,
    private val tbUser: String,
    private val tbPass: String
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun log(msg: String) = FileLogger.d("TbAdminOtaClient", msg)
    private fun hostNoSlash(): String = tbHost.trimEnd('/')

    suspend fun loginJwt(): String? = withContext(Dispatchers.IO) {
        val url = "${hostNoSlash()}/api/auth/login"
        val body = JSONObject()
            .put("username", tbUser)
            .put("password", tbPass)
            .toString()

        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            log("login: HTTP ${resp.code}")
            if (!resp.isSuccessful) {
                log("login failed: ${resp.body?.string().orEmpty()}")
                return@withContext null
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            json.optString("token").ifBlank { json.optString("accessToken") }.ifBlank { null }
        }
    }

    /**
     * Matches the PowerShell logic:
     * GET /api/otaPackages?pageSize=200&page=0&sortProperty=createdTime&sortOrder=DESC
     * then filter title/type on client side.
     */
    suspend fun fetchLatest(title: String, type: String): TbLatestOta? = withContext(Dispatchers.IO) {
        val jwt = loginJwt() ?: return@withContext null

        val url = "${hostNoSlash()}/api/otaPackages?pageSize=200&page=0&sortProperty=createdTime&sortOrder=DESC"
        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("X-Authorization", "Bearer $jwt")
            .build()

        client.newCall(req).execute().use { resp ->
            log("fetchLatest: HTTP ${resp.code}")
            if (!resp.isSuccessful) {
                log("fetchLatest failed: ${resp.body?.string().orEmpty()}")
                return@withContext null
            }

            val root = JSONObject(resp.body?.string().orEmpty())
            val arr: JSONArray = root.optJSONArray("data") ?: JSONArray()

            var best: TbLatestOta? = null

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val t = obj.optString("title")
                val ty = obj.optString("type")
                if (t != title || ty != type) continue

                val created = obj.optLong("createdTime", 0L)
                val id = obj.optJSONObject("id")?.optString("id").orEmpty()
                val ver = obj.optString("version")

                if (id.isBlank() || ver.isBlank()) continue

                val candidate = TbLatestOta(t, ver, ty, created, id)
                if (best == null || candidate.createdTime > best!!.createdTime) {
                    best = candidate
                }
            }

            if (best != null) {
                log("latest: title=${best!!.title} version=${best!!.version} type=${best!!.type} createdTime=${best!!.createdTime} id=${best!!.id}")
            } else {
                log("latest: none found for title=$title type=$type")
            }
            best
        }
    }

    /**
     * EXACTLY like CMD download:
     * GET /api/otaPackage/{id}/download  (Bearer JWT)
     */
    suspend fun downloadOtaApk(otaId: String, outFile: File): Boolean = withContext(Dispatchers.IO) {
        val jwt = loginJwt() ?: return@withContext false

        val url = "${hostNoSlash()}/api/otaPackage/$otaId/download"
        log("download: GET $url")
        log("download: outFile=${outFile.absolutePath}")

        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("X-Authorization", "Bearer $jwt")
            .build()

        client.newCall(req).execute().use { resp ->
            log("download: HTTP ${resp.code}")
            if (!resp.isSuccessful) {
                log("download failed body=${resp.body?.string().orEmpty()}")
                return@withContext false
            }
            outFile.parentFile?.mkdirs()
            resp.body?.byteStream()?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val ok = outFile.exists() && outFile.length() > 0
        log("download: saved size=${outFile.length()} ok=$ok")
        ok
    }
}
