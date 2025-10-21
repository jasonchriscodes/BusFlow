package com.jason.publisher.main.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object FileLogger {
    private const val TAG = "FileLogger"
    private const val RELATIVE_DIR = "Documents/Log"
    private val lock = ReentrantLock()
    private lateinit var appContext: Context

    // === Formats ===
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val nameFmt =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()) // << time in file name

    // === Session naming ===
    private var sessionStart: Date = Date()
    private var fileDisplayName: String? = null   // e.g., app-log_2025-10-21_18-57-12.txt

    // Handles
    private var mediaStoreUri: Uri? = null   // API 29+
    private var legacyFile: File? = null     // API 28-

    // 🔹 keep track of previous activity
    @Volatile
    private var lastActivity: String? = null

    private fun ready(): Boolean = ::appContext.isInitialized

    fun init(context: Context) {
        appContext = context.applicationContext
        // Freeze the session start at first init
        try {
            getLogDir().mkdirs()
        } catch (_: Exception) {
        }
        if (fileDisplayName == null) {
            sessionStart = Date()
            fileDisplayName = "app-log_${nameFmt.format(sessionStart)}.txt"
        }
        prepareTarget() // create/resolve the file once for this session
        writeCreationHeader()
    }

    fun markAppOpened(extra: String? = null) {
        line("=== App opened ${now()} ${extra?.let { "($it)" } ?: ""} ===")
    }

    fun markAppClosed(extra: String? = null) {
        line("=== App closed ${now()} ${extra?.let { "($it)" } ?: ""} ===")
    }

    fun d(tag: String, msg: String) {
        if (!ready()) {
            Log.w(TAG, "Called before init(); dropping log D/$tag"); return
        }
        maybeLogActivityTransition(tag, msg)
        write("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (!ready()) {
            Log.w(TAG, "Called before init(); dropping log I/$tag"); return
        }; write("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (!ready()) {
            Log.w(TAG, "Called before init(); dropping log W/$tag"); return
        }; write("W", tag, msg)
    }

    fun e(tag: String, msg: String) {
        if (!ready()) {
            Log.w(TAG, "Called before init(); dropping log E/$tag"); return
        }; write("E", tag, msg)
    }

    /** Use the session-stamped file name so each app run gets its own file */
    fun currentLogFile(): File {
        // Only called after ready() checks, but keep a soft guard anyway
        if (!ready()) throw IllegalStateException("FileLogger.init(context) not called")
        val name = fileDisplayName ?: "app-log_${nameFmt.format(sessionStart)}.txt"
        return File(getLogDir(), name)
    }

    private fun write(level: String, tag: String, msg: String) {
        when (level) {
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            else -> Log.e(tag, msg)
        }
        line("${now()} [$level/$tag] $msg")
    }

    /**
     * Write to public Documents/Log:
     *  - API 29+: MediaStore (no storage permission needed)
     *  - API 28-: Documents/Log via Environment.getExternalStoragePublicDirectory (needs WRITE_EXTERNAL_STORAGE)
     */
    private fun line(text: String) {
        if (!ready()) {
            Log.w(TAG, "Called before init(); dropping line"); return
        }

        lock.withLock {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val uri = mediaStoreUri ?: return
                    // "wa" = write+append
                    appContext.contentResolver.openOutputStream(uri, "wa")?.use { out ->
                        out.write((text + "\n").toByteArray())
                    } ?: Log.e(TAG, "openOutputStream returned null for $uri")
                } else {
                    val f = legacyFile ?: return
                    FileOutputStream(f, /*append=*/true).use { out ->
                        out.write((text + "\n").toByteArray())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log: ${e.message}")
            }
        }
    }

    private fun writeCreationHeader() {
        if (!ready()) return
        val tz = TimeZone.getDefault()
        val zoneId = if (Build.VERSION.SDK_INT >= 26) tz.id else tz.displayName
        line("=== Log file created ${now()} ($zoneId) ===")
    }

    private fun now(): String = timeFmt.format(Date())

    private fun getLogDir(): File {
        // This should only be called after ready() guards
        val base =
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: appContext.filesDir
        return File(base, "logs")
    }

    // ---------- Activity transition detector ----------
    private fun maybeLogActivityTransition(tag: String, msg: String) {
        val looksLikeActivity = tag.endsWith("Activity")
        if (!looksLikeActivity) return
        val enterSignals = arrayOf("oncreate", "onstart", "entered", "created", "resume", "open")
        val isEntering = enterSignals.any { msg.contains(it, ignoreCase = true) }
        if (!isEntering) return

        val from = lastActivity ?: "(cold start)"
        val to = tag
        write("I", "ActivityNav", "from=$from → to=$to")
        lastActivity = to
    }

    private fun prepareTarget() {
        val name = fileDisplayName ?: "app-log_${nameFmt.format(Date())}.txt"
        if (Build.VERSION.SDK_INT >= 29) {
            mediaStoreUri = resolveOrCreateMediaStoreFile(name)
        } else {
            legacyFile = resolveOrCreateLegacyFile(name)
        }
    }

    // API 29+: create/find Documents/Log/<displayName> in MediaStore
    private fun resolveOrCreateMediaStoreFile(displayName: String): Uri? {
        val cr = appContext.contentResolver
        val collection = MediaStore.Files.getContentUri("external")

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )
        val sel =
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH}=?"
        val args = arrayOf(displayName, "$RELATIVE_DIR/")

        cr.query(collection, projection, sel, args, null).use { c ->
            if (c != null && c.moveToFirst()) {
                val id = c.getLong(0)
                return ContentUris.withAppendedId(collection, id)
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, displayName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, RELATIVE_DIR) // => Documents/Log
            put(MediaStore.Files.FileColumns.IS_PENDING, 0)
        }
        return cr.insert(collection, values)
    }

    // API 28-: public Documents/Log/<displayName>
    private fun resolveOrCreateLegacyFile(displayName: String): File? {
        return try {
            val docs =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val dir = File(docs, "Log")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, displayName)
            if (!f.exists()) f.createNewFile()
            f
        } catch (e: Exception) {
            Log.e(TAG, "Legacy file error: ${e.message}")
            null
        }
    }
}