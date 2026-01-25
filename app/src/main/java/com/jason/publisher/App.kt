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
import com.jason.publisher.main.services.background.ScreenRecordService
import com.jason.publisher.modules.battery.services.BatteryLowWatcher
import com.jason.publisher.main.loggers.FileLogger
import android.Manifest
import android.util.Log
import androidx.core.content.edit

class App : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        private const val REQ_NOTIF = 12345
        private const val PREFS = "battery_watcher_prefs"
        private const val KEY_NOTIF_ASKED = "notif_perm_asked_once"
        private const val STOP_RECORDING_DELAY_MS = 2000L
    }

    // Tracks visible activities to know when the app is effectively closed
    private var liveActivities = 0

    private val killHandler = Handler(Looper.getMainLooper())
    private val maybeStopRecording = Runnable {
        if (liveActivities == 0) {
            // Only stop built-in recording if it was enabled
            // Check if built-in recording is enabled before stopping
            val prefs = applicationContext.getSharedPreferences("screen_recording_prefs", MODE_PRIVATE)
            val isBuiltinEnabled = prefs.getBoolean("enable_builtin_recording", false)
            if (isBuiltinEnabled) {
                Log.d("App", "No activities alive, stopping screen recording service")
                ScreenRecordService.stop(applicationContext)
            }
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
            // Optimize: Wait longer to ignore rotation/config switches
            // This prevents unnecessary service restarts during screen rotations
            killHandler.removeCallbacks(maybeStopRecording)
            killHandler.postDelayed(maybeStopRecording, STOP_RECORDING_DELAY_MS)
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
                    .edit { putBoolean(KEY_NOTIF_ASKED, true) }
            }
        }

        // 🟢 Also force an immediate battery check on every resume
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            BatteryLowWatcher.ensureNow(activity)
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