// com.jason.publisher.main.utils.BatteryToasts.kt
package com.jason.publisher.main.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.jason.publisher.main.sos.BatteryLowWatcher

/** Call this once in onCreate() of any Activity to get battery toasts automatically. */
fun AppCompatActivity.hookBatteryToasts(
    lowThresholdInclusive: Int = 15
) {
    val activity = this
    val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pct = i.getIntExtra(BatteryLowWatcher.EXTRA_PCT, -1)
            if (pct in 1..lowThresholdInclusive) {
                Toast.makeText(c, "Battery low: $pct%", Toast.LENGTH_LONG).show()
            }
        }
    }

    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            // 1) Listen first so we don't miss the priming broadcast
            registerReceiver(
                uiReceiver,
                IntentFilter(BatteryLowWatcher.ACTION_APP_BATTERY_BUCKET)
            )

            // 2) Start watcher (idempotent) – may emit in future on % changes
            BatteryLowWatcher.ensureNow(activity)

            // 3) Prime UI immediately with the current level (no need to wait)
            val now = BatteryLowWatcher.collectTelemetry(activity).batteryPct
            if (now in 1..lowThresholdInclusive) {
                Toast.makeText(activity, "Battery low: $now%", Toast.LENGTH_LONG).show()
            }
        }

        override fun onPause(owner: LifecycleOwner) {
            try { unregisterReceiver(uiReceiver) } catch (_: IllegalArgumentException) {}
        }
    })
}

