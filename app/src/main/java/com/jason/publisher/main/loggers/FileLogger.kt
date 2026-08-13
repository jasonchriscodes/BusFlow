package com.jason.publisher.main.loggers

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object FileLogger {
    private const val TAG = "FileLogger"
    private const val RELATIVE_DIR = "Documents/Log"
    private val lock = ReentrantLock()
    private lateinit var appContext: Context

    // All actual disk/MediaStore I/O happens on this single background thread so that
    // callers (main thread, GPS callback thread, MQTT callback thread, etc.) never block.
    @Volatile
    private var writerExecutor: ExecutorService? = null

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

    // === In-memory buffer for remote publishing (RemoteLogPublisher reads this) ===
    // Holds the tail of the CURRENT session's log text - capped so a busy stretch (e.g. an MQTT
    // reconnect storm) can't grow this without bound or blow past ThingsBoard's attribute size
    // limit. The on-disk file is unaffected by this cap; it keeps everything. Trimmed from the
    // front (oldest first) so what's visible remotely is always the most recent activity, which
    // is what actually matters for live crash triage. 400K chars comfortably covers a normal
    // shift's full log in one place; only an unusually bad session (e.g. a reconnect storm) would
    // ever trim anything, and even then it keeps the most recent (most diagnostically useful) part.
    private const val REMOTE_BUFFER_CAP_CHARS = 400_000
    private val remoteBufferLock = ReentrantLock()
    private val remoteBuffer = StringBuilder()
    @Volatile
    private var remoteBufferVersion = 0L

    private fun appendToRemoteBuffer(text: String) {
        remoteBufferLock.withLock {
            remoteBuffer.append(text).append('\n')
            val excess = remoteBuffer.length - REMOTE_BUFFER_CAP_CHARS
            if (excess > 0) remoteBuffer.delete(0, excess)
            remoteBufferVersion++
        }
    }

    private fun resetRemoteBuffer() {
        remoteBufferLock.withLock {
            remoteBuffer.setLength(0)
            remoteBufferVersion++
        }
    }

    /** Current session's buffered log tail, for publishing to ThingsBoard. */
    fun getBufferedLogText(): String = remoteBufferLock.withLock { remoteBuffer.toString() }

    /** Monotonic counter that changes whenever the buffer changes - lets a poller skip no-op publishes. */
    fun getBufferedLogVersion(): Long = remoteBufferVersion

    private fun ready(): Boolean = ::appContext.isInitialized

    // init() is called from App.onCreate() plus every top-level Activity's onCreate() as a
    // "make sure it's ready" safety net. Only the first call in a process should actually pick
    // a target file / write the header - repeat calls used to re-run resolveOrCreateMediaStoreFile()
    // for the same display name, which (depending on MediaStore query timing) could create a
    // second, uniquely-renamed row and leave this session split across two files on disk.
    @Volatile
    private var initialized = false

    // Survives process death (crash + auto-restart, or the OS killing the app in the
    // background) so the next process to call init() can find and append to the SAME file
    // instead of starting a new one. Which file counts as "current" is controlled entirely by
    // the driver's Fetch Roster / Use Cache choice (see startNewSession) - NOT by whether the
    // app was closed in between, so "Use Cache" always continues the most recent log even after
    // a full close+reopen, and only "Fetch Roster" ever starts a new one.
    private const val SESSION_PREFS = "file_logger_session"
    private const val KEY_SESSION_FILE = "session_file_name"
    private fun sessionPrefs() = appContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

    /**
     * Foundational setup only (writer executor, log directory) - does NOT attach to or create
     * any file. Safe to call as early and as often as needed (App.onCreate, every Activity's
     * onCreate safety net) without writing a single byte to the front/animation page before the
     * driver has made a choice. A file only ever gets attached via [startNewSession] (Fetch
     * Roster) or [resumeSession] (Use Cache / crash-recovery / any screen reached after that).
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        initialized = true

        if (writerExecutor == null) {
            writerExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "FileLogger-Writer").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            }
        }
        try { getLogDir().mkdirs() } catch (_: Exception) {}
    }

    /**
     * Attaches to the existing session file, if any was recorded by a previous [startNewSession]
     * call - a no-op if there isn't one (nothing has ever been fetched yet) or if already
     * attached this process. Call this from "Use Cache", the crash-recovery auto-bypass, and
     * defensively from ScheduleActivity/MapActivity/RepActivity's own onCreate() - Android can
     * recreate those directly after a crash, skipping SplashActivity (and its button click)
     * entirely, so this is the only thing several different entry points can all safely call.
     */
    fun resumeSession(context: Context) {
        appContext = context.applicationContext
        if (fileDisplayName != null) return
        val resumedName = sessionPrefs().getString(KEY_SESSION_FILE, null) ?: return
        beginSession(resumedName)
    }

    /**
     * Forces a brand new log file starting now, abandoning whatever was previously active - call
     * this when the driver picks "Fetch Roster", since that's the one action that means "start
     * over," matching the schedule reset with a clean log instead of mixing a new shift into an
     * old file. This is the ONLY thing that ever creates a file - nothing is written on the
     * front/animation page, or anywhere else, until this has been called at least once.
     */
    fun startNewSession() {
        if (!::appContext.isInitialized) return
        if (fileDisplayName != null) {
            line("=== Switching to a new log (Fetch Roster selected) ===")
        }
        resetRemoteBuffer()
        beginSession(resumedName = null)
    }

    private fun beginSession(resumedName: String?) {
        sessionStart = Date()
        fileDisplayName = resumedName ?: "app-log_${nameFmt.format(sessionStart)}.txt"
        prepareTarget()

        if (resumedName != null) {
            line("=== Session resumed ${now()} (same log continues) ===")
        } else {
            sessionPrefs().edit { putString(KEY_SESSION_FILE, fileDisplayName) }
            writeCreationHeader()
        }

        // 🔧 keep only the newest 6 log files
        Thread { pruneOldLogs(maxKeep = 6) }.start()
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
     *
     * Never touches disk/MediaStore on the calling thread — the actual I/O is queued onto a
     * single background thread so this call returns immediately (safe to call from the main
     * thread / GPS callback / MQTT callback without risking lag or ANRs).
     */
    private fun line(text: String) {
        if (!ready()) {
            Log.w(TAG, "Called before init(); dropping line"); return
        }
        appendToRemoteBuffer(text)
        val exec = writerExecutor
        if (exec == null) {
            Log.w(TAG, "Writer not ready; dropping line"); return
        }
        try {
            exec.execute { writeLineBlocking(text) }
        } catch (e: RejectedExecutionException) {
            Log.e(TAG, "Log executor rejected task: ${e.message}")
        }
    }

    /** Actual disk/MediaStore write. Only ever called on the background writer thread. */
    private fun writeLineBlocking(text: String) {
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

    /**
     * Blocks the calling thread until all log lines queued so far have been written to disk,
     * or [timeoutMs] elapses. Intended ONLY for use right before the process is about to die
     * (e.g. from an uncaught exception handler) where an async write could otherwise be lost.
     * Do not call this from normal app code / hot paths.
     */
    fun flushBlocking(timeoutMs: Long = 2000) {
        val exec = writerExecutor ?: return
        try {
            val latch = CountDownLatch(1)
            exec.execute { latch.countDown() }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "flushBlocking failed: ${e.message}")
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

    // inside object FileLogger { ... }

    private fun pruneOldLogs(maxKeep: Int = 6) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                // MediaStore (Documents/Log)
                val collection = MediaStore.Files.getContentUri("external")
                val projection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.RELATIVE_PATH
                )
                val selection = "(${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? OR " +
                        "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?) AND " +
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
                val args = arrayOf("$RELATIVE_DIR%", "$RELATIVE_DIR/", "app-log_%")
                val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} ASC" // oldest first

                val ids = mutableListOf<Long>()
                appContext.contentResolver.query(collection, projection, selection, args, sortOrder)?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    while (c.moveToNext()) ids += c.getLong(idIdx)
                }

                val extras = (ids.size - maxKeep).coerceAtLeast(0)
                for (i in 0 until extras) {
                    val delUri = ContentUris.withAppendedId(collection, ids[i])
                    runCatching { appContext.contentResolver.delete(delUri, null, null) }
                        .onFailure { e -> Log.w(TAG, "Delete failed: $delUri", e) }
                }
            } else {
                // Legacy public storage: /Documents/Log
                val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val dir  = File(docs, "Log")
                if (dir.exists()) {
                    val files = dir.listFiles { f ->
                        f.isFile && f.name.startsWith("app-log_") && f.name.endsWith(".txt")
                    }?.sortedBy { it.lastModified() } ?: emptyList()

                    val extras = (files.size - maxKeep).coerceAtLeast(0)
                    for (i in 0 until extras) {
                        runCatching { files[i].delete() }
                            .onFailure { e -> Log.w(TAG, "Delete failed: ${files[i]}", e) }
                    }
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Prune logs failed", e)
        }
    }
}