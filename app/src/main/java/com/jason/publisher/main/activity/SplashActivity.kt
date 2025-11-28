package com.jason.publisher.main.activity

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.R
import com.jason.publisher.main.services.ScreenRecordService
import com.jason.publisher.main.utils.FileLogger
import com.jason.publisher.main.utils.hookBatteryToasts
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.GifImageView

// Optimize: Default audio to false to reduce CPU/GPU load during screen recording
// Audio encoding adds significant overhead, so make it opt-in
private var wantAudio: Boolean = false

// SharedPreferences keys for screen recording configuration
private const val PREFS_SCREEN_RECORDING = "screen_recording_prefs"
private const val KEY_ENABLE_BUILTIN_RECORDING = "enable_builtin_recording"
private const val KEY_ENABLE_3RD_PARTY_RECORDING = "enable_3rd_party_recording"
private const val KEY_3RD_PARTY_PACKAGE = "3rd_party_package_name"

// Popular 3rd party screen recording apps (user can configure)
private const val AZ_SCREEN_RECORDER_PACKAGE = "com.hecorat.screenrecorder.free"
private const val MOBIZEN_PACKAGE = "com.rsupport.mvagent"
private const val DU_RECORDER_PACKAGE = "com.duapps.recorder"

class SplashActivity : AppCompatActivity() {
    private lateinit var mpm: MediaProjectionManager
    override fun onCreate(savedInstanceState: Bundle?) {
        // apply transparent splash theme
        setTheme(R.style.Theme_NavTrack_Splash)
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* granted -> ignore; denied -> you can show a tip */ }
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Initialize BEFORE any FileLogger.d/i/w/e
        com.jason.publisher.main.utils.FileLogger.init(applicationContext)
        com.jason.publisher.main.utils.FileLogger.d("SplashActivity", "onCreate")

        FileLogger.d("SplashActivity", "onCreate")
        hookBatteryToasts()

        if (savedInstanceState == null) { // cold start, not a rotation
            getSharedPreferences("panel_debug_pref", MODE_PRIVATE)
                .edit()
                .putInt("panel_debug_no", 0)
                .apply()
        }

        // ✅ OPTIMIZED: Screen recording permission tetap ditampilkan seperti awal
        // Tapi dengan optimasi di ScreenRecordService agar tidak lag
        // Optimasi: audio disabled by default, lower bitrate, reduced frame rate, dll
        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            val captureIntent = mpm.createScreenCaptureIntent()
            projectionLauncher.launch(captureIntent)
            Log.d("SplashActivity", "✅ Requesting screen recording permission (optimized for performance)")
            FileLogger.d("SplashActivity", "Screen recording permission requested - service is optimized to prevent lag")
        } catch (e: SecurityException) {
            // Handle security exception (e.g., if permission is denied)
            FileLogger.e("SplashActivity", "Security exception requesting screen capture: ${e.message}")
            Log.e("SplashActivity", "Security exception requesting screen capture", e)
        } catch (e: Exception) {
            // If permission request fails for any other reason, log but don't crash
            FileLogger.e("SplashActivity", "Failed to request screen capture permission: ${e.message}")
            Log.e("SplashActivity", "Failed to request screen capture permission", e)
        }

        setContentView(R.layout.activity_splash)

        // full-screen immersive mode
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )

        val gifView       = findViewById<GifImageView>(R.id.openerGif)
        val choiceLayout  = findViewById<LinearLayout>(R.id.choiceLayout)
        val btnFetch      = findViewById<Button>(R.id.btnFetchRoster)
        val btnUseCache   = findViewById<Button>(R.id.btnUseCache)

        // play GIF only once
        val drawable = gifView.drawable as GifDrawable
        drawable.loopCount = 1

        // when the GIF finishes, show the two choice buttons
        drawable.addAnimationListener {
            choiceLayout.visibility = View.VISIBLE
            choiceLayout.bringToFront()
        }

        // “Fetch Roster Data” → fresh fetch
        btnFetch.setOnClickListener {
            startScheduleActivity(fetch = true)
        }

        // “Use Cached Data” → offline
        btnUseCache.setOnClickListener {
            startScheduleActivity(fetch = false)
        }
    }

    /**
     * Launch ScheduleActivity with the user’s choice.
     *
     * @param fetch true to fetch fresh roster data, false to use cached data
     */
    private fun startScheduleActivity(fetch: Boolean) {
        Intent(this, ScheduleActivity::class.java).also {
            it.putExtra("EXTRA_FETCH_ROSTER", fetch)
            startActivity(it)
            finish()
        }
    }

    // Screen-capture consent result
    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            // Pass the audio decision to the service
            ScreenRecordService.start(
                this,
                res.resultCode,
                res.data!!,
                withAudio = wantAudio
            )
        }
    }

    /**
     * Launches a 3rd party screen recording app (e.g., AZ Screen Recorder)
     * This is recommended over built-in recording for better performance
     *
     * @param packageName The package name of the screen recording app to launch
     */
    private fun launchThirdPartyScreenRecorder(packageName: String) {
        try {
            // Try to launch the app by package name
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d("SplashActivity", "✅ Launched 3rd party screen recorder: $packageName")
                FileLogger.d("SplashActivity", "Launched 3rd party screen recorder: $packageName")
            } else {
                // App not installed, try to open Play Store
                Log.w("SplashActivity", "⚠️ Screen recorder app not found: $packageName")
                try {
                    val marketIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("market://details?id=$packageName")
                        setPackage("com.android.vending")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(marketIntent)
                    Log.d("SplashActivity", "Opened Play Store for screen recorder app")
                } catch (e: Exception) {
                    // Play Store not available, try web browser
                    try {
                        val webIntent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(webIntent)
                        Log.d("SplashActivity", "Opened browser for screen recorder app")
                    } catch (e2: Exception) {
                        Log.e("SplashActivity", "Failed to open screen recorder app or store: ${e2.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SplashActivity", "Error launching 3rd party screen recorder: ${e.message}", e)
            FileLogger.e("SplashActivity", "Error launching 3rd party screen recorder: ${e.message}")
        }
    }

    companion object {
        /**
         * Helper method to enable/disable built-in screen recording
         * Call this from anywhere in the app to configure recording behavior
         *
         * @param context Application context
         * @param enable true to enable built-in recording, false to disable (recommended)
         */
        fun setBuiltinRecordingEnabled(context: Context, enable: Boolean) {
            context.getSharedPreferences(PREFS_SCREEN_RECORDING, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLE_BUILTIN_RECORDING, enable)
                .apply()
            Log.d("SplashActivity", "Built-in screen recording ${if (enable) "ENABLED" else "DISABLED"}")
        }

        /**
         * Helper method to enable/disable 3rd party screen recording
         *
         * @param context Application context
         * @param enable true to auto-launch 3rd party app, false to disable
         * @param packageName Optional: package name of the screen recorder app (default: AZ Screen Recorder)
         */
        fun setThirdPartyRecordingEnabled(context: Context, enable: Boolean, packageName: String = AZ_SCREEN_RECORDER_PACKAGE) {
            context.getSharedPreferences(PREFS_SCREEN_RECORDING, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLE_3RD_PARTY_RECORDING, enable)
                .putString(KEY_3RD_PARTY_PACKAGE, packageName)
                .apply()
            Log.d("SplashActivity", "3rd party screen recording ${if (enable) "ENABLED" else "DISABLED"} with package: $packageName")
        }

        /**
         * Check if built-in recording is enabled
         */
        fun isBuiltinRecordingEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_SCREEN_RECORDING, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLE_BUILTIN_RECORDING, false)
        }

        /**
         * Check if 3rd party recording is enabled
         */
        fun isThirdPartyRecordingEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_SCREEN_RECORDING, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLE_3RD_PARTY_RECORDING, false)
        }
    }
}