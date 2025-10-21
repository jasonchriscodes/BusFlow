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
    private val timeFmt   = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val nameFmt   = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()) // << time in file name

    // === Session naming ===
    private var sessionStart: Date = Date()
    private var fileDisplayName: String? = null   // e.g., app-log_2025-10-21_18-57-12.txt

    // Handles
    private var mediaStoreUri: Uri? = null   // API 29+
    private var legacyFile: File? = null     // API 28-

    fun init(context: Context) {
        appContext = context.applicationContext
        // Freeze the session start at first init
        if (fileDisplayName == null) {
            sessionStart = Date()
            fileDisplayName = "app-log_${nameFmt.format(sessionStart)}.txt"
        }
        prepareTarget() // create/resolve the file once for this session
        writeCreationHeader()
    }

    fun markAppOpened(extra: String? = null) = line("=== App opened ${now()} ${extra?.let { "($it)" } ?: ""} ===")
    fun markAppClosed(extra: String? = null) = line("=== App closed ${now()} ${extra?.let { "($it)" } ?: ""} ===")

    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        write("E", tag, msg + (t?.let { "\n${it.message}" } ?: ""))

    /** For showing to the user */
    fun currentLogHint(): String =
        if (Build.VERSION.SDK_INT >= 29) mediaStoreUri?.toString() ?: "<unavailable>"
        else legacyFile?.absolutePath ?: "<unavailable>"

    // --- internals ---

    private fun write(level: String, tag: String, msg: String) {
        when (level) {
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            else -> Log.e(tag, msg)
        }
        line("${now()} [$level/$tag] $msg")
    }

    private fun line(text: String) {
        lock.withLock {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val uri = mediaStoreUri ?: return
                    appContext.contentResolver.openOutputStream(uri, "wa")?.use { out ->
                        out.write((text + "\n").toByteArray())
                    }
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
        val tz = TimeZone.getDefault()
        val zoneId = if (Build.VERSION.SDK_INT >= 26) tz.id else tz.displayName
        line("=== Log file created ${now()} ($zoneId) ===")
    }

    private fun now(): String = timeFmt.format(Date())

    private fun prepareTarget() {
        val name = fileDisplayName ?: "app-log_${nameFmt.format(Date())}.txt"
        if (Build.VERSION.SDK_INT >= 29) {
            mediaStoreUri = resolveOrCreateMediaStoreFile(name)
        } else {
            legacyFile = resolveOrCreateLegacyFile(name)
        }
    }

    // -------- API 29+ (scoped storage) via MediaStore --------
    private fun resolveOrCreateMediaStoreFile(displayName: String): Uri? {
        val cr = appContext.contentResolver
        val collection = MediaStore.Files.getContentUri("external")

        // Try find existing (unlikely with unique timestamp, but safe)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )
        val sel  = "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH}=?"
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
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Files.FileColumns.IS_PENDING, 0)
        }
        return cr.insert(collection, values)
    }

    // -------- API 28- legacy public Documents/Log --------
    private fun resolveOrCreateLegacyFile(displayName: String): File? {
        return try {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val dir  = File(docs, "Log")
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
