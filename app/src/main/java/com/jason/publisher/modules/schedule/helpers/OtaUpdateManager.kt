package com.jason.publisher.modules.schedule.helpers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
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
    val version: String,      // e.g. "1.0.2"
    val versionCode: Long?,   // e.g. 10002
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

    private fun otaLog(msg: String) = FileLogger.d("OtaUpdate", msg)

    private fun hostNoTrailingSlash(): String = tbHost.trimEnd('/')

    /**
     * Call this on Splash:
     * - Returns true if it started an install flow
     * - Returns false if no update / failed
     */
    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun checkDownloadAndPromptInstall(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            otaLog("checkDownloadAndPromptInstall: START")

            val ota = fetchOtaSharedAttrs() ?: run {
                otaLog("checkDownloadAndPromptInstall: STOP (attrs missing)")
                return@withContext false
            }

            otaLog("checkDownloadAndPromptInstall: attrs title=${ota.title} version=${ota.version} vc=${ota.versionCode} size=${ota.size}")

            val apkFile = downloadOtaBinary(ota) ?: run {
                otaLog("checkDownloadAndPromptInstall: STOP (download null)")
                return@withContext false
            }

            otaLog("checkDownloadAndPromptInstall: downloaded apk=${apkFile.absolutePath} size=${apkFile.length()}")

            val verified = verifyDownloadedApk(apkFile, ota)
            otaLog("checkDownloadAndPromptInstall: verify result=$verified")

            if (!verified) {
                FileLogger.e("OtaUpdate", "Verification failed; not installing.")
                return@withContext false
            }

            withContext(Dispatchers.Main) {
                otaLog("checkDownloadAndPromptInstall: promptInstall()")
                promptInstall(apkFile)
            }

            otaLog("checkDownloadAndPromptInstall: DONE")
            true
        }.getOrElse { e ->
            FileLogger.e("OtaUpdate", "OTA check failed: ${e.message}")
            otaLog("checkDownloadAndPromptInstall: EXCEPTION ${e.message}")
            false
        }
    }

    /**
     * Reads shared attrs:
     * GET /api/v1/$TOKEN/attributes?sharedKeys=sw_title,sw_version,sw_checksum,sw_checksum_algorithm,sw_size
     */
    private fun fetchOtaSharedAttrs(): OtaInfo? {
        val url =
            "${hostNoTrailingSlash()}/api/v1/$deviceToken/attributes" +
                    "?sharedKeys=sw_title,sw_version,sw_version_code,sw_checksum,sw_checksum_algorithm,sw_size"

        otaLog("fetchOtaSharedAttrs: GET $url")

        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            otaLog("fetchOtaSharedAttrs: HTTP ${resp.code}")
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                otaLog("fetchOtaSharedAttrs: FAIL body=$err")
                return null
            }

            // ✅ Read body ONCE
            val bodyStr = resp.body?.string().orEmpty()
            otaLog("fetchOtaSharedAttrs: body=$bodyStr")

            val json = JSONObject(bodyStr)

            val title = json.optString("sw_title", "")
            val version = json.optString("sw_version", "")
            val versionCode = json.optLong("sw_version_code", -1L).let { if (it >= 0) it else null }
            val size = json.optLong("sw_size", -1L).let { if (it >= 0) it else null }
            val checksum = json.optString("sw_checksum", "").ifBlank { null }
            val alg = json.optString("sw_checksum_algorithm", "").ifBlank { null }

            otaLog("fetchOtaSharedAttrs: parsed title=$title version=$version vc=$versionCode size=$size alg=$alg checksumPresent=${checksum != null}")

            if (title.isBlank() || version.isBlank()) return null

            return OtaInfo(
                title = title,
                version = version,
                versionCode = versionCode,
                size = size,
                checksum = checksum,
                checksumAlg = alg
            )
        }
    }

    /**
     * Downloads binary via device API.
     * GET /api/v1/$TOKEN/software?title=$TITLE&version=$VERSION
     */
    private fun downloadOtaBinary(ota: OtaInfo): File? {
        val url =
            "${hostNoTrailingSlash()}/api/v1/$deviceToken/software" +
                    "?title=${Uri.encode(ota.title)}&version=${Uri.encode(ota.version)}"

        val outDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: run {
            otaLog("downloadOtaBinary: FAIL outDir=null")
            return null
        }

        val outFile = File(outDir, "tb_${ota.title}_${ota.version}.apk")

        otaLog("downloadOtaBinary: GET $url")
        otaLog("downloadOtaBinary: outFile=${outFile.absolutePath} exists=${outFile.exists()} size=${if (outFile.exists()) outFile.length() else 0L}")

        if (outFile.exists() && outFile.length() > 0) {
            otaLog("downloadOtaBinary: reuse existing size=${outFile.length()}")
            return outFile
        }

        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            otaLog("downloadOtaBinary: HTTP ${resp.code} contentType=${resp.header("Content-Type")} contentLength=${resp.header("Content-Length")}")

            // ✅ error body (read once)
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                otaLog("downloadOtaBinary: FAIL body=$err")
                return null
            }

            // ✅ Read bytes ONCE, then write to file
            val bytes = resp.body?.bytes()
            if (bytes == null || bytes.isEmpty()) {
                otaLog("downloadOtaBinary: FAIL body bytes empty")
                return null
            }

            outFile.outputStream().use { it.write(bytes) }
        }

        otaLog("downloadOtaBinary: saved size=${outFile.length()}")
        return if (outFile.exists() && outFile.length() > 0) outFile else null
    }

    /**
     * Verify:
     * 1) checksum (if provided)
     * 2) packageName matches your app
     * 3) signature matches installed app signature
     * 4) versionCode higher OR versionName semver newer
     */
    @RequiresApi(Build.VERSION_CODES.P)
    internal fun verifyDownloadedApk(apkFile: File, ota: OtaInfo): Boolean {
        otaLog("verify: START file=${apkFile.absolutePath} size=${apkFile.length()}")

        // 1) checksum
        val alg = (ota.checksumAlg ?: "SHA256").uppercase()
        if (ota.checksum != null) {
            val digest = when (alg) {
                "SHA256", "SHA-256" -> sha256Hex(apkFile)
                else -> sha256Hex(apkFile)
            }
            otaLog("verify: checksum local=$digest server=${ota.checksum} alg=$alg")
            if (!digest.equals(ota.checksum, ignoreCase = true)) {
                FileLogger.e("OtaUpdate", "Checksum mismatch. local=$digest server=${ota.checksum}")
                return false
            }
        } else {
            otaLog("verify: checksum skipped (server checksum missing)")
        }

        val pm = context.packageManager

        // 2) APK package name check
        val archiveInfo = pm.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: run {
            otaLog("verify: FAIL getPackageArchiveInfo=null")
            return false
        }

        otaLog("verify: archive package=${archiveInfo.packageName} vc=${archiveInfo.longVersionCode} vn=${archiveInfo.versionName}")

        if (archiveInfo.packageName != context.packageName) {
            FileLogger.e("OtaUpdate", "PackageName mismatch: ${archiveInfo.packageName}")
            otaLog("verify: FAIL package mismatch expected=${context.packageName}")
            return false
        }

        // 3) Signature match check
        val installedInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )

        otaLog("verify: installed vc=${installedInfo.longVersionCode} vn=${installedInfo.versionName}")

        val installedSigners = installedInfo.signingInfo.apkContentsSigners
        val archiveSigners = archiveInfo.signingInfo?.apkContentsSigners

        if (installedSigners.isNullOrEmpty() || archiveSigners.isNullOrEmpty()) {
            otaLog("verify: FAIL signers empty installed=${installedSigners?.size} archive=${archiveSigners?.size}")
            return false
        }

        val installedHash = sha256(installedSigners[0].toByteArray())
        val archiveHash = sha256(archiveSigners[0].toByteArray())

        if (!installedHash.contentEquals(archiveHash)) {
            FileLogger.e("OtaUpdate", "Signature mismatch (installed vs downloaded).")
            otaLog("verify: FAIL signature mismatch")
            return false
        }

        // 4) version comparison
        val installedVc = installedInfo.longVersionCode
        val downloadedVc = archiveInfo.longVersionCode

        if (downloadedVc <= installedVc) {
            val downloadedName = archiveInfo.versionName.orEmpty()
            val installedName = installedInfo.versionName.orEmpty()

            otaLog("verify: versionCode not increasing downloadedVc=$downloadedVc installedVc=$installedVc -> fallback semver")
            val semverOk = isSemVerNewer(downloadedName, installedName)
            otaLog("verify: semver compare downloadedName=$downloadedName installedName=$installedName result=$semverOk")

            if (!semverOk) {
                FileLogger.e(
                    "OtaUpdate",
                    "Not newer. downloadedVc=$downloadedVc installedVc=$installedVc | downloadedName=$downloadedName installedName=$installedName"
                )
                return false
            }
        }

        otaLog("verify: OK")
        return true
    }

    private fun isSemVerNewer(server: String, installed: String): Boolean {
        fun parse(v: String): Triple<Int, Int, Int>? {
            val p = v.trim().split(".")
            val a = p.getOrNull(0)?.toIntOrNull() ?: return null
            val b = p.getOrNull(1)?.toIntOrNull() ?: 0
            val c = p.getOrNull(2)?.toIntOrNull() ?: 0
            return Triple(a, b, c)
        }
        val s = parse(server) ?: return false
        val i = parse(installed) ?: return false
        return when {
            s.first != i.first -> s.first > i.first
            s.second != i.second -> s.second > i.second
            else -> s.third > i.third
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    internal fun promptInstall(apkFile: File) {
        otaLog("promptInstall: START file=${apkFile.absolutePath} size=${apkFile.length()}")

        val canInstall = context.packageManager.canRequestPackageInstalls()
        otaLog("promptInstall: canRequestPackageInstalls=$canInstall")

        if (!canInstall) {
            otaLog("promptInstall: BLOCKED (unknown apps disabled)")
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            otaLog("promptInstall: authority=$authority")

            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
            otaLog("promptInstall: apkUri=$apkUri")

            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = apkUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            otaLog("promptInstall: launched installer intent ACTION_INSTALL_PACKAGE")
        } catch (t: Throwable) {
            otaLog("promptInstall: EXCEPTION ${t.javaClass.simpleName}: ${t.message}")
            FileLogger.e("OtaUpdate", "promptInstall exception: ${t.message}")
        }
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
            otaLog("checkForUpdateOnly: start")
            val ota = fetchOtaSharedAttrs() ?: run {
                otaLog("checkForUpdateOnly: NoUpdate (attrs missing)")
                return@withContext OtaCheckResult.NoUpdate
            }
            otaLog("checkForUpdateOnly: UpdateAvailable version=${ota.version} versionCode=${ota.versionCode}")
            OtaCheckResult.UpdateAvailable(ota)
        }.getOrElse { e ->
            otaLog("checkForUpdateOnly: Failed ${e.message}")
            OtaCheckResult.Failed(e.message ?: "Unknown error")
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun downloadAndInstall(ota: OtaInfo): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            otaLog("downloadAndInstall: START title=${ota.title} version=${ota.version}")
            val apk = downloadOtaBinary(ota) ?: return@withContext false
            if (!verifyDownloadedApk(apk, ota)) return@withContext false
            withContext(Dispatchers.Main) { promptInstall(apk) }
            otaLog("downloadAndInstall: DONE")
            true
        }.getOrElse {
            otaLog("downloadAndInstall: EXCEPTION ${it.message}")
            false
        }
    }
}
