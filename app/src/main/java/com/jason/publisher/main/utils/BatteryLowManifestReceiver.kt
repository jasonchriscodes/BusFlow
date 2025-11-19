package com.jason.publisher.main.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.RequiresApi
import com.jason.publisher.main.sos.BatteryLowWatcher
import com.jason.publisher.main.utils.FileLogger
import android.os.BatteryManager

class BatteryLowManifestReceiver : BroadcastReceiver() {
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onReceive(context: Context, intent: Intent) {
        BatteryLowWatcher.ensureChannel(context)
        FileLogger.d("BatteryLowManifestReceiver", "ACTION_BATTERY_LOW (manifest) received")
        val tele = BatteryLowWatcher.collectTelemetry(context)
        val result = BatteryLowWatcher.sendLowBatteryPing(context, tele)
        val pct = tele.batteryPct.takeIf { it >= 0 } ?: 15
        val bucket = BatteryLowWatcher.bucketFor(pct)
        BatteryLowWatcher.showConfirmationNotification(context, result, pct, bucket)
    }
}


