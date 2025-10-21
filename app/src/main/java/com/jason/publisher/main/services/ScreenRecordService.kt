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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jason.publisher.R
import java.util.Locale
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat

class ScreenRecordService : Service() {

    companion object {
        private const val CH_ID = "screen_rec_ch_v2"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "STOP"

        fun start(ctx: Context, resultCode: Int, data: Intent, withAudio: Boolean = true) {
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
    private val ticker = object : Runnable {
        @RequiresApi(Build.VERSION_CODES.N)
        override fun run() {
            updateOngoingNotification()
            tickHandler.postDelayed(this, 1000L)
        }
    }

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

        // auto-stop service if user hits system “Stop sharing”
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

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
                try { r.setAudioSource(MediaRecorder.AudioSource.MIC) }
                catch (_: RuntimeException) {
                    // mic busy/blocked → retry silently without audio on a clean instance
                    runCatching { r.reset(); r.release() }
                    audioEnabled = false
                    r = MediaRecorder()
                }
            }

            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audioEnabled) r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            r.setVideoEncodingBitRate(8_000_000)
            r.setVideoFrameRate(30)
            r.setVideoSize(width, height)

            if (audioEnabled) {
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(44100)
            }

            r.setOutputFile(outputFd)
            r.prepare()
            return r to audioEnabled
        }

        val (rec, _) = buildRecorder(wantAudio)
        recorder = rec

        vDisplay = projection!!.createVirtualDisplay(
            "BusFlow-Recorder",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            rec.surface, null, null
        )

        rec.start()

        // start ticking the notification each second
        tickHandler.removeCallbacks(ticker)
        tickHandler.post(ticker)

        return START_STICKY
    }

    override fun onDestroy() {
        tickHandler.removeCallbacks(ticker)
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset(); recorder?.release() }
        runCatching { vDisplay?.release() }
        runCatching { projection?.stop() }

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        super.onDestroy()
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
        val elapsedSec = ((SystemClock.elapsedRealtime() - startedElapsedMs) / 1000L).coerceAtLeast(0)
        nm.notify(NOTIF_ID, buildNotification(elapsedSec))
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun buildNotification(elapsedSec: Long): Notification {
        val mm = elapsedSec / 60
        val ss = elapsedSec % 60
        val timeTxt = String.format(Locale.US, "%02d:%02d", mm, ss)

        val openPi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

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
            .setContentIntent(stopPi)
            // Keep explicit actions too (shown when expanded)
            .addAction(R.drawable.ic_open, "Open", openPi)
            .addAction(R.drawable.ic_close, "Stop", stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

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
}

