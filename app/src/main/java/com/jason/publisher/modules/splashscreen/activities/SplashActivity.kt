package com.jason.publisher.modules.splashscreen.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.jason.publisher.R
import com.jason.publisher.main.services.background.ClientAttributesService
import com.jason.publisher.main.services.background.ScreenRecordService
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.modules.battery.ui.hookBatteryToasts
import com.jason.publisher.modules.schedule.activities.ScheduleActivity
import com.jason.publisher.modules.schedule.helpers.OtaCheckResult
import com.jason.publisher.modules.schedule.helpers.OtaUpdateManager
import kotlinx.coroutines.launch
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.GifImageView

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    private lateinit var mpm: MediaProjectionManager
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        // apply transparent splash theme
        setTheme(R.style.Theme_NavTrack_Splash)
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* granted -> ignore; denied -> you can show a tip */ }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Initialize BEFORE any FileLogger.d/i/w/e
        FileLogger.init(applicationContext)
        FileLogger.d("SplashActivity", "onCreate")

        FileLogger.d("SplashActivity", "onCreate")
        hookBatteryToasts()

        lifecycleScope.launch {
            val tbHost = "https://thingsboard.cloud" // or from your config
            val deviceToken = "YOUR_DEVICE_TOKEN"    // from config / stored
            val started = OtaUpdateManager(this@SplashActivity, tbHost, deviceToken)
                .checkDownloadAndPromptInstall()

            if (started) {
                FileLogger.d("SplashActivity", "OTA install flow started")
                // Optionally return early or show “Updating…” UI
            }
        }

        if (savedInstanceState == null) { // cold start, not a rotation
            getSharedPreferences("panel_debug_pref", MODE_PRIVATE)
                .edit {
                    putInt("panel_debug_no", 0)
                }
        }

        // ✅ OPTIMIZED: Screen recording permission tetap ditampilkan seperti awal
        // Tapi dengan optimasi di ScreenRecordService agar tidak lag
        // Optimasi: audio disabled by default, lower bitrate, reduced frame rate, dll
        mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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

        // Start the ClientAttributesService to handle attributes-updating when the app is closed
        startService(Intent(baseContext, ClientAttributesService::class.java))

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
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            // Pass the audio decision to the service
            ScreenRecordService.Companion.start(
                this,
                res.resultCode,
                res.data!!,
                withAudio = false // Default to false to reduce CPU/GPU load during screen recording
            )
        }
    }
}