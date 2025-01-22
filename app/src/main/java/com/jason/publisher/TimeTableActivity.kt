package com.jason.publisher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.databinding.ActivityTimetableBinding

class TimeTableActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimetableBinding
    private lateinit var aid: String // Declare a variable to store AID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimetableBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fetch AID from the device
        aid = getAndroidId()
        Log.d("TimeTableActivity", "Fetched AID: $aid")

        // Set up the "Start Route" button
        binding.startRouteButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("AID", aid) // Pass the AID to MainActivity
            startActivity(intent)
        }
    }

    /**
     * Fetches the Android ID (AID) of the device.
     * @return A string representing the Android ID.
     */
    private fun getAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }
}
