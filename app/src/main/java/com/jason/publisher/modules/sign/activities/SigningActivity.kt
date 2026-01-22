package com.jason.publisher.modules.signing.activities

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jason.publisher.R
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.TripLog
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.modules.battery.ui.hookBatteryToasts
import com.jason.publisher.modules.`break`.adapters.BreakUpcomingAdapter
import com.jason.publisher.modules.map.mqtt.helpers.MqttHelper
import com.jason.publisher.modules.map.mqtt.services.MqttManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SigningActivity : AppCompatActivity() {

    private lateinit var timerText: TextView
    private lateinit var infoText: TextView
    private lateinit var endAtText: TextView
    private lateinit var doneBtn: Button

    private var cd: CountDownTimer? = null
    private lateinit var mqttManager: MqttManager

    private val heartbeatHandler = Handler(Looper.getMainLooper())

    private lateinit var signingLabel: String
    private lateinit var signingAction: String // "IN" or "OFF"

    companion object {
        private const val USE_DYNAMIC = true      // for signing this makes more sense
        private const val FALLBACK_SECONDS = 30L  // safety fallback
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("SimpleDateFormat", "MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signing)

        FileLogger.d("SigningActivity", "onCreate")
        hookBatteryToasts()

        timerText = findViewById(R.id.signingTimerText)
        infoText  = findViewById(R.id.signingInfoText)
        endAtText = findViewById(R.id.signingEndAtText)
        doneBtn   = findViewById(R.id.signingDoneBtn)

        val token = intent.getStringExtra("ACCESS_TOKEN")
        if (token.isNullOrBlank()) {
            FileLogger.e("SigningActivity", "ACCESS_TOKEN missing")
            finish()
            return
        }

        signingLabel = intent.getStringExtra("SIGNING_LABEL") ?: "Signing"
        signingAction = intent.getStringExtra("SIGNING_ACTION")
            ?: inferSigningAction(intent.getStringExtra("SIGNING_RUN_NAME") ?: "")

        val firstList =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM", ScheduleItem::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM")
            }
        val signItem = firstList?.firstOrNull()

        if (signItem == null) {
            FileLogger.e("SigningActivity", "FIRST_SCHEDULE_ITEM empty")
            finish()
            return
        }

        // ===== Upcoming list (same as BreakActivity) =====
        val fullRemaining =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA", ScheduleItem::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA")
            } ?: arrayListOf()

        val upNextHeader = findViewById<TextView>(R.id.upNextHeader)
        val upNextRecycler = findViewById<RecyclerView>(R.id.upNextRecycler)

        val untilNextBreak = buildList {
            fullRemaining.forEach {
                if (it.runName.equals("break", true)) return@forEach
                add(it)
            }
        }

        if (untilNextBreak.isNotEmpty()) {
            upNextHeader.visibility = View.VISIBLE
            upNextRecycler.apply {
                visibility = View.VISIBLE
                layoutManager = LinearLayoutManager(this@SigningActivity)
                adapter = BreakUpcomingAdapter(untilNextBreak)
                setHasFixedSize(true)
            }
        } else {
            upNextHeader.visibility = View.GONE
            upNextRecycler.visibility = View.GONE
        }

        // ===== Trip Log =====
        TripLog.start(
            this,
            TripLog.ActiveTrip(
                startedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date()),
                type = "signing",
                label = signingLabel,
                aid = intent.getStringExtra("AID"),
                runNo = signItem.runNo,
                runName = signItem.runName,
                startTime = signItem.startTime,
                endTime = signItem.endTime,
                fromStop = signItem.busStops.firstOrNull()?.name,
                toStop = signItem.busStops.lastOrNull()?.name,
                scheduleSize = fullRemaining.size,
                routeDataSize = 0
            )
        )
        TripLog.mark("driver signing")

        // ===== MQTT =====
        mqttManager = MqttManager(username = token)
        mqttManager.connect { ok ->
            if (ok) {
                FileLogger.d("SigningActivity", "MQTT connected")
                publishSigningAttributes()
                heartbeatHandler.post(heartbeatRunnable)
            } else {
                FileLogger.e("SigningActivity", "MQTT connect failed")
            }
        }

        // ===== UI text =====
        infoText.text = if (signingAction == "OFF") "Please sign off" else "Please sign on"
        endAtText.text = "Before ${signItem.endTime}"

        // ===== Timer =====
        val durationMs = if (!USE_DYNAMIC) {
            FALLBACK_SECONDS * 1000L
        } else {
            computeRemainingMillis(signItem.endTime).takeIf { it > 0 } ?: (FALLBACK_SECONDS * 1000L)
        }

        if (durationMs > 0) {
            cd = object : CountDownTimer(durationMs, 1000) {
                override fun onTick(ms: Long) {
                    timerText.text = formatHMS(ms)
                }
                override fun onFinish() {
                    showFinished()
                }
            }.start()
        } else {
            showFinished()
        }

        doneBtn.setOnClickListener { finish() }
    }

    private fun inferSigningAction(runName: String): String {
        val rn = runName.lowercase(Locale.getDefault())
        return if (rn.contains("off") || rn.contains("out")) "OFF" else "IN" // "on"/"in" => IN
    }

    private fun publishSigningAttributes() {
        val payload = """
            {
              "currentTripLabel": "${signingLabel.replace("\"", "\\\"")}",
              "activityState": "SIGNING",
              "signingAction": "$signingAction",
              "updatedAt": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        try {
            mqttManager.publish(MqttHelper.Companion.ATTR_TOPIC, payload)
        } catch (e: Exception) {
            FileLogger.e("SigningActivity", "Publish failed: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun clearActiveSegmentAndRefresh() {
        try {
            try {
                val payload = "{\"currentTripLabel\":\"\", \"activityState\":\"\", \"signingAction\":\"\"}"
                if (::mqttManager.isInitialized) {
                    mqttManager.publish(MqttHelper.Companion.ATTR_TOPIC, payload)
                }
                try {
                    requestAdminMessage()
                } catch (e: Exception) {
                    Log.w("SigningActivity", "requestAdminMessage follow-up failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w("SigningActivity", "publishActiveSegment(\"\") failed: ${e.message}")
            }

            try {
                try { requestAdminMessage() } catch (e: Exception) {
                    Log.w("SigningActivity", "requestAdminMessage failed: ${e.message}")
                }
                return
            } catch (e: Exception) {
                Log.w("SigningActivity", "mqtt interaction failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w("SigningActivity", "clearActiveSegmentAndRefresh failed: ${e.message}")
        }
    }

    fun requestAdminMessage() {
        val jsonObject = JSONObject().apply {
            put("sharedKeys", "message,busRoute,busStop,config")
        }
        mqttManager.publish(MqttHelper.Companion.PUB_MSG_TOPIC, jsonObject.toString())
        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                mqttManager.publish(MqttHelper.Companion.PUB_MSG_TOPIC, jsonObject.toString())
                Handler(Looper.getMainLooper()).postDelayed(
                    this,
                    MqttHelper.Companion.REQUEST_PERIODIC_TIME
                )
            }
        })
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            publishSigningAttributes()
            heartbeatHandler.postDelayed(this, 15_000)
        }
    }

    private fun showFinished() {
        timerText.text = "00:00"

        infoText.text = if (signingAction == "OFF") {
            "You're late to sign off. Please sign off now."
        } else {
            "You're late to sign on. Please sign in now."
        }

        doneBtn.visibility = View.VISIBLE
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onDestroy() {
        cd?.cancel()
        heartbeatHandler.removeCallbacksAndMessages(null)
        clearActiveSegmentAndRefresh()
        super.onDestroy()
        // DO NOT DISCONNECT MQTT HERE (same as BreakActivity)
    }

    private fun formatHMS(ms: Long): String {
        var sec = ms / 1000
        val h = sec / 3600
        sec %= 3600
        val m = sec / 60
        val s = sec % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    private fun computeRemainingMillis(endHHmm: String): Long {
        val now = Calendar.getInstance()
        val endCal = now.clone() as Calendar
        val parts = endHHmm.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return 0L
        endCal.set(Calendar.HOUR_OF_DAY, parts[0])
        endCal.set(Calendar.MINUTE, parts[1])
        endCal.set(Calendar.SECOND, 0)
        endCal.set(Calendar.MILLISECOND, 0)

        // If the end time is already passed "today", treat as 0
        return (endCal.timeInMillis - now.timeInMillis).coerceAtLeast(0)
    }
}
