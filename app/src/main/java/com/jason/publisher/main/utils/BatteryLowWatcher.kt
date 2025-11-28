package com.jason.publisher.main.sos

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jason.publisher.main.utils.FileLogger
import kotlin.math.roundToInt

object BatteryLowWatcher {
    /** App-local broadcast for UI pieces to subscribe to */
    const val ACTION_APP_BATTERY_BUCKET =
        "com.jason.publisher.action.APP_BATTERY_BUCKET"

    /** Intent extra names */
    const val EXTRA_PCT = "pct"
    const val EXTRA_IS_CRITICAL = "is_critical"

    /** Change if you want a different "low" threshold */
    private const val CRITICAL_PCT = 15

    @Volatile private var isRegistered = false
    @Volatile private var lastPct = -1
    private const val CHANNEL_ID = "battery_low_channel"
    private const val CHANNEL_NAME = "Battery & SOS"
    private const val CHANNEL_DESC = "Alerts when battery is low and confirms GPS ping."

    data class Telemetry(
        val batteryPct: Int,
        val timestamp: Long = System.currentTimeMillis()
        // Add lat/lng, accuracy, deviceId, etc., if needed
    )

    private val sysReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val lvl = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (lvl < 0 || scale <= 0) return

            val pct = ((lvl * 100f) / scale).roundToInt()
            if (pct == lastPct) return
            lastPct = pct

            val app = context.applicationContext
            // Fan-out a safe, app-local broadcast with the current percentage
            Intent(ACTION_APP_BATTERY_BUCKET).apply {
                putExtra(EXTRA_PCT, pct)
                putExtra(EXTRA_IS_CRITICAL, pct in 1..CRITICAL_PCT)
                // Keep it inside our app
                setPackage(app.packageName)
                app.sendBroadcast(this)
            }

            // Optional logging
            try {
                 FileLogger.i("BatteryLowWatcher", "Battery: $pct%")
            } catch (_: Throwable) { /* no-op if logger missing */ }

            // Hook: if you want to do something when critically low (e.g., GPS ping),
            // call your own service here when pct <= CRITICAL_PCT.
            if (pct in 1..CRITICAL_PCT) {
                // dispatchLowBatteryLocationPing(app, pct) // <- your implementation
            }
        }
    }

    /** Safe to call from any Activity/Service as often as you like. Registers once. */
    @Synchronized
    fun ensureNow(context: Context) {
        if (isRegistered) return
        val app = context.applicationContext

        // Start receiving ongoing battery updates
        app.registerReceiver(sysReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        isRegistered = true

        // Prime immediately with the sticky current battery state
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            sysReceiver.onReceive(app, it)
        }
    }

    fun start(context: Context) = ensureNow(context)

    @Synchronized
    fun stop(context: Context) {
        if (!isRegistered) return
        val app = context.applicationContext
        try {
            app.unregisterReceiver(sysReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered – ignore
        }
        isRegistered = false
        lastPct = -1
    }

    @JvmStatic
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            val existing = mgr.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                )
                ch.description = CHANNEL_DESC
                mgr.createNotificationChannel(ch)
                FileLogger.d("BatteryLowWatcher", "NotificationChannel created")
            }
        }
    }

    /** Replace with your real network/MQTT/ThingsBoard call. Returns success/failure. */
    @SuppressLint("MissingPermission")
    fun sendLowBatteryPing(context: Context, telemetry: Telemetry): Boolean {
        FileLogger.d(
            "BatteryLowWatcher",
            "sendLowBatteryPing(): battery=${telemetry.batteryPct}%"
        )
        // TODO: integrate with your existing publisher / MQTT
        return true
    }

    fun bucketFor(pct: Int): String = when {
        pct < 5  -> "CRITICAL"
        pct < 10 -> "VERY_LOW"
        pct < 16 -> "LOW"
        else     -> "OK"
    }

    fun showConfirmationNotification(
        context: Context,
        success: Boolean,
        pct: Int,
        bucket: String
    ) {
        ensureChannel(context)

        if (!canPostNotifications(context)) {
            FileLogger.w(
                "BatteryLowWatcher",
                "Notification not shown: missing POST_NOTIFICATIONS permission or disabled."
            )
            return
        }

        val title = if (success) "Battery low ping sent" else "Battery low ping failed"
        val text = "Battery $pct% ($bucket)"

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(1001, notif)
            FileLogger.d("BatteryLowWatcher", "Notification posted: $title - $text")
        } catch (se: SecurityException) {
            val details = se.message ?: se.javaClass.simpleName
            FileLogger.e(
                "BatteryLowWatcher",
                "SecurityException posting notification: $details"
            )
        }
    }

    fun collectTelemetry(context: Context): Telemetry {
        val pct = readBatteryPct(context)
        return Telemetry(batteryPct = pct)
    }


    private fun readBatteryPct(context: Context): Int {
        // Try the modern property first (0..100); fall back to sticky broadcast.
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val prop = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (prop in 1..100) return prop

        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = context.registerReceiver(null, ifilter)
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100f).toInt() else -1
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-13: no runtime permission; also check channel/app level
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}

