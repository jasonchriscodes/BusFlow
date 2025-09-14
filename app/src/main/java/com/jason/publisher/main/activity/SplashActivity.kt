package com.jason.publisher.main.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.R
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.GifImageView

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // apply transparent splash theme
        setTheme(R.style.Theme_NavTrack_Splash)
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) { // cold start, not a rotation
            getSharedPreferences("panel_debug_pref", MODE_PRIVATE)
                .edit()
                .putInt("panel_debug_no", 0)
                .apply()
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
}
