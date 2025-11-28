package com.jason.publisher.main.services

import android.app.*
import android.content.*
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jason.publisher.R
import java.util.Locale
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import java.io.File

class ScreenRecordService : Service() {

    companion object {
        private const val CH_ID = "screen_rec_ch_v2"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "STOP"

        // Optimize: Default audio to false to reduce CPU/GPU load
        // Audio encoding adds significant overhead, so make it opt-in
        fun start(ctx: Context, resultCode: Int, data: Intent, withAudio: Boolean = false) {
            val i = Intent(ctx, ScreenRecordService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
                .putExtra("withAudio", withAudio)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            // Use the same STOP action the notification uses
            val i = Intent(ctx, ScreenRecordService::class.java).apply { action = ACTION_STOP }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private var recorder: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null

    // --- Timer/notification state ---
    private lateinit var nm: NotificationManager
    private var startedWallClockMs: Long = 0L
    private var startedElapsedMs: Long = 0L
    private val tickHandler = Handler(Looper.getMainLooper())
    // Optimize: Update notification every 5 seconds instead of 1 second to reduce overhead
    private val NOTIFICATION_UPDATE_INTERVAL = 5000L
    private val ticker = object : Runnable {
        @RequiresApi(Build.VERSION_CODES.N)
        override fun run() {
            updateOngoingNotification()
            tickHandler.postDelayed(this, NOTIFICATION_UPDATE_INTERVAL)
        }
    }

    // Optimize: Cache PendingIntents to avoid recreating them on every notification update
    private var cachedOpenPi: PendingIntent? = null
    private var cachedStopPi: PendingIntent? = null

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ensureNotificationsEnabled()) return START_NOT_STICKY
        val inIntent = intent ?: return START_NOT_STICKY
        if (inIntent.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        createNotifChannel()
        nm = getSystemService(NotificationManager::class.java)

        // Mark start time for both wall clock (for chronometer) and elapsed time
        startedWallClockMs = System.currentTimeMillis()
        startedElapsedMs = SystemClock.elapsedRealtime()

        if (Build.VERSION.SDK_INT >= 26) {
            val ch = getSystemService(NotificationManager::class.java).getNotificationChannel(CH_ID)
            Log.d("SRService", "areEnabled=${NotificationManagerCompat.from(this).areNotificationsEnabled()} chImp=${ch?.importance}")
        }

        // Foreground RIGHT AWAY (required)
        val initial = buildNotification(elapsedSec = 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION // proper type for screen capture
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, initial)
        }

        // ---- MediaProjection + MediaRecorder init (same as you had, trimmed for clarity) ----
        val code = inIntent.getIntExtra("code", Activity.RESULT_CANCELED)

        val data: Intent = if (Build.VERSION.SDK_INT >= 33) {
            inIntent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") inIntent.getParcelableExtra("data")
        } ?: return START_NOT_STICKY

        val wantAudio = inIntent.getBooleanExtra("withAudio", true)
        val hasAudioPerm = if (Build.VERSION.SDK_INT >= 23) {
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else true

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data) ?: return START_NOT_STICKY

        // auto-stop service if user hits system "Stop sharing"
        // Optimize: Use Handler to ensure callback runs on main thread
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("SRService", "MediaProjection stopped by system")
                tickHandler.post {
                    cleanupResources()
                    stopSelf()
                }
            }
        }
        projection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        // Optimize: Scale down resolution to reduce encoding load (50% of original)
        // This significantly reduces CPU/GPU usage while maintaining acceptable quality
        val scaleFactor = 0.5f
        val width = (metrics.widthPixels * scaleFactor).toInt()
        val height = (metrics.heightPixels * scaleFactor).toInt()
        val dpi = (metrics.densityDpi * scaleFactor).toInt()

        val outUri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "busflow_rec_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BusFlow")
            }
        ) ?: return START_NOT_STICKY

        val outputFd = contentResolver.openFileDescriptor(outUri, "w")?.fileDescriptor
            ?: return START_NOT_STICKY

        fun buildRecorder(needAudio: Boolean): Pair<MediaRecorder, Boolean> {
            var audioEnabled = needAudio && hasAudioPerm
            var r = MediaRecorder()

            if (audioEnabled) {
                try {
                    r.setAudioSource(MediaRecorder.AudioSource.MIC)
                } catch (e: RuntimeException) {
                    // mic busy/blocked → retry silently without audio on a clean instance
                    Log.w("SRService", "Audio source unavailable, recording without audio: ${e.message}")
                    runCatching { r.reset(); r.release() }
                    audioEnabled = false
                    r = MediaRecorder()
                }
            }

            try {
                r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (audioEnabled) r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                // Optimize: Reduce bitrate from 8 Mbps to 2 Mbps (75% reduction)
                // This significantly reduces CPU/GPU load and file size while maintaining good quality
                r.setVideoEncodingBitRate(2_000_000)
                // Optimize: Reduce frame rate from 30 fps to 15 fps (50% reduction)
                // This reduces encoding load while still providing smooth playback
                r.setVideoFrameRate(15)
                r.setVideoSize(width, height)

                if (audioEnabled) {
                    // Optimize: Reduce audio bitrate from 128 kbps to 64 kbps
                    // This reduces audio encoding load while maintaining acceptable quality
                    r.setAudioEncodingBitRate(64_000)
                    // Optimize: Reduce sample rate from 44.1 kHz to 22.05 kHz
                    // This reduces audio processing load
                    r.setAudioSamplingRate(22050)
                }

                r.setOutputFile(outputFd)
                r.prepare()
                return r to audioEnabled
            } catch (e: Exception) {
                // Clean up on error
                runCatching { r.reset(); r.release() }
                throw e
            }
        }

        // Build recorder with error handling
        val (rec, audioEnabled) = try {
            buildRecorder(wantAudio)
        } catch (e: Exception) {
            Log.e("SRService", "Failed to build recorder: ${e.message}", e)
            cleanupResources()
            return START_NOT_STICKY
        }
        recorder = rec

        // Optimize: Create VirtualDisplay with AUTO_MIRROR flag (standard for screen recording)
        // Using AUTO_MIRROR ensures proper screen capture behavior
        vDisplay = projection!!.createVirtualDisplay(
            "BusFlow-Recorder",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            rec.surface, null, null
        )

        // Start recording with error handling
        try {
            rec.start()
            Log.d("SRService", "Recording started successfully (${width}x${height} @ ${15}fps, ${2_000_000/1_000_000}Mbps)")
        } catch (e: Exception) {
            Log.e("SRService", "Failed to start recording: ${e.message}", e)
            cleanupResources()
            return START_NOT_STICKY
        }

        // Prune old recordings in background thread (non-blocking)
        Thread { pruneOldRecordings(maxKeep = 6) }.start()

        // Start ticking the notification (optimized to every 5 seconds)
        tickHandler.removeCallbacks(ticker)
        tickHandler.post(ticker)

        return START_NOT_STICKY
    }

    /**
     * Centralized resource cleanup to prevent memory leaks
     */
    private fun cleanupResources() {
        Log.d("SRService", "Cleaning up resources...")

        // Stop ticker first to prevent further updates
        tickHandler.removeCallbacks(ticker)

        // Clear cached PendingIntents
        cachedOpenPi = null
        cachedStopPi = null

        // Stop recorder gracefully
        runCatching {
            recorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.w("SRService", "Error stopping recorder: ${e.message}")
                }
                try {
                    reset()
                } catch (e: Exception) {
                    Log.w("SRService", "Error resetting recorder: ${e.message}")
                }
                try {
                    release()
                } catch (e: Exception) {
                    Log.w("SRService", "Error releasing recorder: ${e.message}")
                }
            }
            recorder = null
        }

        // Release VirtualDisplay
        runCatching {
            vDisplay?.release()
            vDisplay = null
        }

        // Stop MediaProjection
        runCatching {
            projection?.stop()
            projection = null
        }

        Log.d("SRService", "Resources cleaned up")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Called when the user swipes your task from Recents
        // Do the same cleanup you do in onDestroy, then stop.
        Log.d("SRService", "Task removed, cleaning up...")
        cleanupResources()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d("SRService", "Service destroying, cleaning up...")
        cleanupResources()

        // Remove notification
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }

        // Cancel notification
        runCatching {
            nm.cancel(NOTIF_ID)
        }

        super.onDestroy()
        Log.d("SRService", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Notification helpers ----------

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CH_ID, "Screen Recording",
                NotificationManager.IMPORTANCE_DEFAULT   // visible
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun updateOngoingNotification() {
        try {
            val elapsedSec = ((SystemClock.elapsedRealtime() - startedElapsedMs) / 1000L).coerceAtLeast(0)
            nm.notify(NOTIF_ID, buildNotification(elapsedSec))
        } catch (e: Exception) {
            Log.w("SRService", "Failed to update notification: ${e.message}")
            // If notification fails, stop the ticker to prevent repeated failures
            tickHandler.removeCallbacks(ticker)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun buildNotification(elapsedSec: Long): Notification {
        // Optimize: Calculate time more efficiently
        val mm = elapsedSec / 60
        val ss = elapsedSec % 60
        val timeTxt = String.format(Locale.US, "%02d:%02d", mm, ss)

        // Optimize: Cache PendingIntents to avoid recreating them on every update
        // This reduces object allocation and improves performance
        if (cachedOpenPi == null) {
            cachedOpenPi = PendingIntent.getActivity(
                this, 0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        if (cachedStopPi == null) {
            cachedStopPi = PendingIntent.getService(
                this, 1,
                Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle("Screen recording")
            .setContentText("Recording • $timeTxt")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Recording • $timeTxt"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(false)
            .setWhen(startedWallClockMs)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Make the whole row tap-to-stop (most reliable across OEMs)
            .setContentIntent(cachedStopPi)
            // Keep explicit actions too (shown when expanded)
            .addAction(R.drawable.ic_open, "Open", cachedOpenPi)
            .addAction(R.drawable.ic_close, "Stop", cachedStopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun ensureNotificationsEnabled(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)

        // App-level toggle
        val appEnabled = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!appEnabled) {
            // Open app notification settings
            val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
            return false
        }

        // Channel-level toggle (O+)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = nm.getNotificationChannel(CH_ID)
            if (ch == null) {
                // create if missing
                createNotifChannel()
            } else if (ch.importance == NotificationManager.IMPORTANCE_NONE) {
                // Open channel settings
                val i = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CH_ID)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i)
                return false
            }
        }
        return true
    }

    private fun pruneOldRecordings(maxKeep: Int = 6) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.RELATIVE_PATH
                )
                // Keep only files we created in Movies/BusFlow with our prefix
                val selection = "(${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR " +
                        "${MediaStore.Video.Media.RELATIVE_PATH} = ?) AND " +
                        "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
                val args = arrayOf("Movies/BusFlow%", "Movies/BusFlow/", "busflow_rec_%")
                val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} ASC" // oldest first

                val ids = mutableListOf<Long>()
                contentResolver.query(uri, projection, selection, args, sortOrder)?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    while (c.moveToNext()) ids += c.getLong(idIdx)
                }

                val extras = (ids.size - maxKeep).coerceAtLeast(0)
                for (i in 0 until extras) {
                    val delUri = ContentUris.withAppendedId(uri, ids[i])
                    runCatching { contentResolver.delete(delUri, null, null) }
                        .onFailure { e -> Log.w("SRService", "Delete failed: $delUri", e) }
                }
            } else {
                // Pre-Q fallback: delete from /Movies/BusFlow by file API
                val base = Environment
                    .getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val dir = File(base, "BusFlow")
                if (dir.exists()) {
                    val files = dir.listFiles { f ->
                        f.isFile && f.name.startsWith("busflow_rec_") && f.name.endsWith(".mp4")
                    }?.sortedBy { it.lastModified() } ?: emptyList()

                    val extras = (files.size - maxKeep).coerceAtLeast(0)
                    for (i in 0 until extras) {
                        runCatching { files[i].delete() }
                            .onFailure { e -> Log.w("SRService", "Delete failed: ${files[i]}", e) }
                    }
                }
            }
        }.onFailure { e ->
            Log.w("SRService", "Prune failed", e)
        }
    }
}
