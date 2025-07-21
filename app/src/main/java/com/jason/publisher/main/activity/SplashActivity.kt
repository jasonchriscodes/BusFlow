package com.jason.publisher.main.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.R
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.GifImageView

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // apply transparent theme
        setTheme(R.style.Theme_NavTrack_Splash)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // hide system UI (full‑screen)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )

        val gifView = findViewById<GifImageView>(R.id.openerGif)
        val drawable = gifView.drawable as GifDrawable

        // 1️⃣ Play the GIF **only once**
        drawable.loopCount = 1

        // listen for animation end
        drawable.addAnimationListener {
            // once the GIF has looped through once, go to ScheduleActivity
            startActivity(Intent(this, ScheduleActivity::class.java))
            finish()
        }
    }
}