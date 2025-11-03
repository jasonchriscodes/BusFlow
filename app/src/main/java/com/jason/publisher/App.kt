// App.kt
package com.jason.publisher

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import com.jason.publisher.main.services.ScreenRecordService
import com.jason.publisher.main.sos.BatteryLowWatcher
import com.jason.publisher.main.utils.FileLogger
import android.Manifest

class App : Application(), Application.ActivityLifecycleCallbacks {

    private val REQ_NOTIF = 12345
    private val PREFS = "battery_watcher_prefs"
    private val KEY_NOTIF_ASKED = "notif_perm_asked_once"

    // Tracks visible activities to know when the app is effectively closed
    private var liveActivities = 0

    // Delay a bit to ignore config changes/rotations before stopping recorder
    private val killHandler = Handler(Looper.getMainLooper())
    private val maybeStopRecording = Runnable {
        if (liveActivities == 0) {
            ScreenRecordService.stop(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        registerActivityLifecycleCallbacks(this)

        // 🔋 Start battery watcher (sends GPS when battery is about to die)
        BatteryLowWatcher.start(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        // Stop battery watcher (note: onTerminate typically not called on real devices)
        BatteryLowWatcher.stop(this)
    }

    // --- ActivityLifecycleCallbacks ---

    override fun onActivityCreated(a: Activity, s: Bundle?) {
        liveActivities++
        killHandler.removeCallbacks(maybeStopRecording)
    }

    override fun onActivityDestroyed(a: Activity) {
        liveActivities--
        if (liveActivities <= 0) {
            // wait a beat to ignore rotation/config switches
            killHandler.removeCallbacks(maybeStopRecording)
            killHandler.postDelayed(maybeStopRecording, 800)
        }
    }

    // Apply to every Activity while it's visible
    override fun onActivityResumed(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 🔔 Ask POST_NOTIFICATIONS at first foreground Activity on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            val asked = activity
                .getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_NOTIF_ASKED, false)

            val granted = activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED

            if (!asked && !granted) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF
                )
                activity.getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
            }
        }

        // 🟢 Also force an immediate battery check on every resume
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            com.jason.publisher.main.sos.BatteryLowWatcher.ensureNow(activity)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onActivityPaused(activity: Activity) { /* no-op */ }

    // Unused but required overrides
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
}
