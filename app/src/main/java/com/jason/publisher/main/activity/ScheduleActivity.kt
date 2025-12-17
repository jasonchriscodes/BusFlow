package com.jason.publisher.main.activity

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityScheduleBinding
import com.jason.publisher.main.realtime.BusTelemetrySubscriber
import com.jason.publisher.main.realtime.LiveBusStore
import com.jason.publisher.main.realtime.LiveBusStatus
import com.jason.publisher.main.services.MqttManager
import com.jason.publisher.main.utils.NetworkStatusHelper

class ScheduleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduleBinding
    private lateinit var mqttManager: MqttManager
    private lateinit var telemetrySubscriber: BusTelemetrySubscriber

    private var aid: String = ""

    companion object {
        const val SERVER_URI = "ssl://mqtt.thingsboard.cloud:8883"
        const val CLIENT_ID = "jasonAndroidClientId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        aid = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )

        mqttManager = MqttManager(
            serverUri = SERVER_URI,
            clientId = CLIENT_ID
        )

        mqttManager.connect { ok ->
            if (!ok) return@connect

            telemetrySubscriber = BusTelemetrySubscriber(mqttManager) {
                runOnUiThread { refreshDetailPanel() }
            }
            telemetrySubscriber.subscribe()
        }
    }

    // =====================================================
    // PANEL DETAIL – BUS LAIN (ONLINE ONLY)
    // =====================================================
    private fun refreshDetailPanel() {
        val container = findViewById<LinearLayout>(R.id.detailIconsContainer)
        container.removeAllViews()

        if (!NetworkStatusHelper.isNetworkAvailable(this)) {
            addHint(container)
            return
        }

        val others = LiveBusStore.activeOthers(aid)

        if (others.isEmpty()) {
            addHint(container)
            return
        }

        others.forEach {
            container.addView(createBusRow(it))
        }
    }

    private fun addHint(container: LinearLayout) {
        val tv = TextView(this).apply {
            text = "Other bus tracking is not available in this mode"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            val p = (resources.displayMetrics.density * 8).toInt()
            setPadding(p, p, p, p)
        }
        container.addView(tv)
    }

    private fun createBusRow(bus: LiveBusStatus): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (resources.displayMetrics.density * 8).toInt()
            setPadding(p, p / 2, p, p / 2)
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_bus_symbol)
            layoutParams = LinearLayout.LayoutParams(32, 32)
        }

        val label = TextView(this).apply {
            text = bus.currentTripLabel ?: "Unknown trip"
            textSize = 14f
            setPadding(12, 0, 0, 0)
        }

        row.addView(icon)
        row.addView(label)
        return row
    }

    override fun onDestroy() {
        super.onDestroy()
        LiveBusStore.clear()
    }
}
