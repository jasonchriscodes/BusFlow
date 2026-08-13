// App.kt
package com.jason.publisher

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import com.jason.publisher.main.services.background.ScreenRecordService
import com.jason.publisher.modules.battery.services.BatteryLowWatcher
import com.jason.publisher.main.loggers.FetchSessionStore
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.TripStateSnapshot
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

            // The app is judged "fully closed" here - forget that Fetch Roster was already
            // chosen this open, so the next open shows the normal Fetch/Use Cache choice again
            // instead of silently treating it like a crash-restart.
            FetchSessionStore.clear(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        installCrashHandler()
        registerActivityLifecycleCallbacks(this)

        // 🔋 Start battery watcher (sends GPS when battery is about to die)
        BatteryLowWatcher.start(this)
    }

    /**
     * Catches any exception that would otherwise crash the app silently (from the log's
     * perspective). Writes the full exception type/message/stack trace plus a snapshot of
     * what the driver was looking at (current page, current/upcoming stop, position) to the
     * persistent file log, then hands off to the previous handler so normal OS crash
     * behavior (system dialog, process death, any crash-reporting SDK) is unaffected.
     *
     * Exception: MQTT (Paho) runs its own background threads that Android will kill the whole
     * app for if one of them ever throws uncaught - e.g. a plain network drop while the bus is
     * moving through a dead zone. That's a routine, recoverable event (Paho's automatic
     * reconnect handles it), not a reason to end the driver's shift. Those are logged as a
     * warning and swallowed instead of being handed to the OS default handler.
     */
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val isMqttThreadFailure = originatesFromPaho(throwable)
            try {
                FileLogger.e(
                    if (isMqttThreadFailure) "MQTT_THREAD_FAILURE" else "FATAL_CRASH",
                    "thread=${thread.name} | ${throwable.javaClass.name}: ${throwable.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(throwable)}"
                )
                // Uncaught exceptions kill the process right after this handler returns, so the
                // async write above must be forced to disk now or it may never land.
                FileLogger.flushBlocking()
            } catch (loggingFailure: Throwable) {
                Log.e("App", "Failed to log fatal crash: ${loggingFailure.message}")
            }

            if (isMqttThreadFailure) return@setDefaultUncaughtExceptionHandler

            previousHandler?.uncaughtException(thread, throwable)
                ?: run {
                    Process.killProcess(Process.myPid())
                    kotlin.system.exitProcess(10)
                }
        }
    }

    /** True if [throwable] or anything in its cause chain was raised by the Paho MQTT library. */
    private fun originatesFromPaho(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 10) {
            if (current.stackTrace.any { it.className.startsWith("org.eclipse.paho.") }) return true
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return false
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
        // Fallback page tracker for the crash handler in case an activity never called
        // TripStateSnapshot.onActivityEntered() itself (e.g. a screen we haven't wired up yet).
        TripStateSnapshot.currentActivity = activity.javaClass.simpleName

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