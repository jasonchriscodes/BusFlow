package com.jason.publisher.main.activity

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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

private lateinit var mpm: MediaProjectionManager
private var wantAudio: Boolean = true

class SplashActivity : AppCompatActivity() {
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

        // Trigger the consent dialog
        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())

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

    private lateinit var mpm: MediaProjectionManager


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
}
