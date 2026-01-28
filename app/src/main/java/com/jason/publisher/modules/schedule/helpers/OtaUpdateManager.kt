package com.jason.publisher.modules.schedule.helpers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.jason.publisher.main.loggers.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class OtaInfo(
    val title: String,
    val version: String,
    val size: Long?,
    val checksum: String?,
    val checksumAlg: String?
)

sealed class OtaCheckResult {
    data object NoUpdate : OtaCheckResult()
    data class UpdateAvailable(val info: OtaInfo) : OtaCheckResult()
    data class Failed(val reason: String) : OtaCheckResult()
}

class OtaUpdateManager(
    private val context: Context,
    private val tbHost: String,
    private val deviceToken: String
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Call this on Splash:
     * - Returns true if it started an install flow
     * - Returns false if no update / failed
     */
    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun checkDownloadAndPromptInstall(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val ota = fetchOtaSharedAttrs() ?: return@withContext false

            // Basic "is it new?" rule:
            // Prefer comparing downloaded APK versionCode vs installed versionCode
            val apkFile = downloadOtaBinary(ota) ?: return@withContext false

            if (!verifyDownloadedApk(apkFile, ota)) {
                FileLogger.e("OtaUpdate", "Verification failed; not installing.")
                return@withContext false
            }

            // Trigger install UI (cannot be fully silent unless device-owner)
            promptInstall(apkFile)
            return@withContext true
        }.getOrElse { e ->
            FileLogger.e("OtaUpdate", "OTA check failed: ${e.message}")
            false
        }
    }

    private fun hostNoTrailingSlash(): String = tbHost.trimEnd('/')

    /**
     * Reads shared attrs:
     * GET /api/v1/$TOKEN/attributes?sharedKeys=sw_title,sw_version,sw_checksum,sw_checksum_algorithm,sw_size
     */
    private fun fetchOtaSharedAttrs(): OtaInfo? {
        val url =
            "${hostNoTrailingSlash()}/api/v1/$deviceToken/attributes" +
                    "?sharedKeys=sw_title,sw_version,sw_checksum,sw_checksum_algorithm,sw_size"

        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                FileLogger.e("OtaUpdate", "Shared attrs HTTP ${resp.code}")
                return null
            }
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)

            val shared = json.optJSONObject("shared") ?: return null

            val title = shared.optString("sw_title", "")
            val version = shared.optString("sw_version", "")
            if (title.isBlank() || version.isBlank()) return null

            val size = shared.optLong("sw_size", -1L).let { if (it >= 0) it else null }
            val checksum = shared.optString("sw_checksum", "").ifBlank { null }
            val alg = shared.optString("sw_checksum_algorithm", "").ifBlank { null }

            return OtaInfo(title, version, size, checksum, alg)
        }
    }

    /**
     * Downloads binary via device API.
     * ThingsBoard docs show device downloads using:
     * GET /api/v1/$TOKEN/firmware?title=$TITLE&version=$VERSION
     */
    private fun downloadOtaBinary(ota: OtaInfo): File? {
        val url =
            "${hostNoTrailingSlash()}/api/v1/$deviceToken/firmware" +
                    "?title=${Uri.encode(ota.title)}&version=${Uri.encode(ota.version)}"

        val outDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val outFile = File(outDir, "tb_${ota.title}_${ota.version}.apk")

        // If already downloaded, reuse it
        if (outFile.exists() && outFile.length() > 0) return outFile

        FileLogger.d("OtaUpdate", "Downloading OTA: $url")

        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                FileLogger.e("OtaUpdate", "Download HTTP ${resp.code}")
                return null
            }
            resp.body?.byteStream()?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        if (!outFile.exists() || outFile.length() == 0L) return null
        return outFile
    }

    /**
     * Verify:
     * 1) checksum (if provided)
     * 2) packageName matches your app
     * 3) signature matches installed app signature
     * 4) versionCode is higher than installed (recommended)
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun verifyDownloadedApk(apkFile: File, ota: OtaInfo): Boolean {
        // 1) checksum
        val alg = (ota.checksumAlg ?: "SHA256").uppercase()
        if (ota.checksum != null) {
            val digest = when (alg) {
                "SHA256", "SHA-256" -> sha256Hex(apkFile)
                else -> sha256Hex(apkFile) // fallback
            }
            if (!digest.equals(ota.checksum, ignoreCase = true)) {
                FileLogger.e("OtaUpdate", "Checksum mismatch. local=$digest server=${ota.checksum}")
                return false
            }
        }

        val pm = context.packageManager

        // 2) APK package name check
        val archiveInfo = pm.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: return false

        if (archiveInfo.packageName != context.packageName) {
            FileLogger.e("OtaUpdate", "PackageName mismatch: ${archiveInfo.packageName}")
            return false
        }

        // 3) Signature match check (strongest protection against wrong-signed OTA)
        val installedInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )

        val installedSigners = installedInfo.signingInfo.apkContentsSigners
        val archiveSigners = archiveInfo.signingInfo?.apkContentsSigners

        if (installedSigners.isNullOrEmpty() || archiveSigners.isNullOrEmpty()) return false

        val installedHash = sha256(installedSigners[0].toByteArray())
        val archiveHash = sha256(archiveSigners[0].toByteArray())

        if (!installedHash.contentEquals(archiveHash)) {
            FileLogger.e("OtaUpdate", "Signature mismatch (installed vs downloaded).")
            return false
        }

        // 4) versionCode comparison (recommended)
        val installedVc = installedInfo.longVersionCode
        val downloadedVc = archiveInfo.longVersionCode
        if (downloadedVc <= installedVc) {
            FileLogger.e("OtaUpdate", "Not newer. downloaded=$downloadedVc installed=$installedVc")
            return false
        }

        return true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun promptInstall(apkFile: File) {
        // Android 8+: need “Install unknown apps” permission for your app
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    suspend fun checkForUpdateOnly(): OtaCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val ota = fetchOtaSharedAttrs() ?: return@withContext OtaCheckResult.NoUpdate

            // Compare installed versionCode vs "latest apk" versionCode is not possible
            // until you download the apk. We'll treat "has title+version" as available,
            // OR you can store last-installed tag/version in SharedPreferences.
            OtaCheckResult.UpdateAvailable(ota)
        }.getOrElse { e ->
            OtaCheckResult.Failed(e.message ?: "Unknown error")
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun downloadAndInstall(ota: OtaInfo): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val apk = downloadOtaBinary(ota) ?: return@withContext false
            if (!verifyDownloadedApk(apk, ota)) return@withContext false
            withContext(Dispatchers.Main) { promptInstall(apk) }
            true
        }.getOrElse { false }
    }

}
