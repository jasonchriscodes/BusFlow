package com.jason.publisher.modules.battery.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import com.jason.publisher.main.loggers.FileLogger

class BatteryLowManifestReceiver : BroadcastReceiver() {
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onReceive(context: Context, intent: Intent) {
        BatteryLowWatcher.ensureChannel(context)
        FileLogger.d("BatteryLowManifestReceiver", "ACTION_BATTERY_LOW (manifest) received")
        val tele = BatteryLowWatcher.collectTelemetry(context)
        val result = BatteryLowWatcher.sendLowBatteryPing(tele)
        val pct = tele.batteryPct.takeIf { it >= 0 } ?: 15
        val bucket = BatteryLowWatcher.bucketFor(pct)
        BatteryLowWatcher.showConfirmationNotification(context, result, pct, bucket)
    }
}

