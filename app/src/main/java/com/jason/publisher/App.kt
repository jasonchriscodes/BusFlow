// App.kt
package com.jason.publisher

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.jason.publisher.main.services.ScreenRecordService
import com.jason.publisher.main.utils.FileLogger

class App : Application(), Application.ActivityLifecycleCallbacks {

    private var liveActivities = 0
    private val killHandler = Handler(Looper.getMainLooper())
    private val maybeStopRecording = Runnable {
        // If nothing came back (not a rotation), consider the app "closed"
        if (liveActivities == 0) {
            ScreenRecordService.stop(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        registerActivityLifecycleCallbacks(this)
    }

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

    // Keep-screen-on flags you already had:
    override fun onActivityResumed(activity: Activity) { /* ...as you have... */ }
    override fun onActivityPaused(activity: Activity)  { /* ...as you have... */ }

    // Unused:
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
}

