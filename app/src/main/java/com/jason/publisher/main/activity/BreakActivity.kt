package com.jason.publisher.main.activity

import android.annotation.SuppressLint
import android.os.*
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jason.publisher.R
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.services.MqttManager
import com.jason.publisher.main.ui.BreakUpcomingAdapter
import com.jason.publisher.main.utils.FileLogger
import com.jason.publisher.main.utils.TripLog
import com.jason.publisher.main.utils.hookBatteryToasts
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class BreakActivity : AppCompatActivity() {

    private lateinit var timerText: TextView
    private lateinit var infoText: TextView
    private lateinit var endAtText: TextView
    private lateinit var doneBtn: Button

    private var cd: CountDownTimer? = null
    private lateinit var mqttManager: MqttManager

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private lateinit var breakLabel: String

    companion object {
        const val SERVER_URI = MapActivity.SERVER_URI
        const val CLIENT_ID  = MapActivity.CLIENT_ID
        private const val ATTR_TOPIC = "v1/devices/me/attributes"
        private const val USE_DYNAMIC = false
        private const val FALLBACK_SECONDS = 30L
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("SimpleDateFormat")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_break)

        FileLogger.d("BreakActivity", "onCreate")
        hookBatteryToasts()

        timerText = findViewById(R.id.breakTimerText)
        infoText  = findViewById(R.id.breakInfoText)
        endAtText = findViewById(R.id.breakEndAtText)
        doneBtn   = findViewById(R.id.breakDoneBtn)

        val token = intent.getStringExtra("ACCESS_TOKEN")
        if (token.isNullOrBlank()) {
            FileLogger.e("BreakActivity", "ACCESS_TOKEN missing")
            finish()
            return
        }

        breakLabel = intent.getStringExtra("BREAK_LABEL") ?: "Break"

        val firstList =
            intent.getSerializableExtra("FIRST_SCHEDULE_ITEM") as? ArrayList<ScheduleItem>
        val breakItem = firstList?.firstOrNull()

        if (breakItem == null) {
            FileLogger.e("BreakActivity", "FIRST_SCHEDULE_ITEM empty")
            finish()
            return
        }

        // ===== Upcoming list =====
        val fullRemaining =
            intent.getSerializableExtra("FULL_SCHEDULE_DATA") as? ArrayList<ScheduleItem>
                ?: arrayListOf()

        if (fullRemaining.isEmpty()) {
            FileLogger.e("BreakActivity", "FULL_SCHEDULE_DATA EMPTY")
        }

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
                layoutManager = LinearLayoutManager(this@BreakActivity)
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
                startedAt = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.getDefault()
                ).format(Date()),
                type = "break",
                label = breakLabel,
                aid = intent.getStringExtra("AID"),
                runNo = breakItem.runNo,
                runName = breakItem.runName,
                startTime = breakItem.startTime,
                endTime = breakItem.endTime,
                fromStop = breakItem.busStops.firstOrNull()?.name,
                toStop = breakItem.busStops.lastOrNull()?.name,
                scheduleSize = fullRemaining.size,
                routeDataSize = 0
            )
        )
        TripLog.mark(this, "driver break")

        // ===== MQTT =====
        mqttManager = MqttManager(
            serverUri = SERVER_URI,
            clientId  = CLIENT_ID,
            username  = token
        )

        mqttManager.connect { ok ->
            if (ok) {
                FileLogger.d("BreakActivity", "MQTT connected")
                publishBreakAttributes()
                heartbeatHandler.post(heartbeatRunnable)
            } else {
                FileLogger.e("BreakActivity", "MQTT connect failed")
            }
        }

        // ===== Timer =====
        val durationMs = if (!USE_DYNAMIC) {
            FALLBACK_SECONDS * 1000L
        } else {
            computeRemainingMillis(breakItem.endTime)
                .takeIf { it > 0 } ?: (FALLBACK_SECONDS * 1000L)
        }

        val endAt = System.currentTimeMillis() + durationMs
        endAtText.text = "Break until ${
            SimpleDateFormat("HH:mm:ss").format(Date(endAt))
        }"

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

        doneBtn.setOnClickListener {
            finish()
        }
    }

    // ===== MQTT payload =====
    private fun publishBreakAttributes() {
        val payload = """
            {
              "currentTripLabel": "${breakLabel.replace("\"", "\\\"")}",
              "activityState": "BREAK",
              "updatedAt": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        try {
            mqttManager.publish(ATTR_TOPIC, payload)
        } catch (e: Exception) {
            FileLogger.e("BreakActivity", "Publish failed: ${e.message}")
        }
    }

    /**
     * Clear the shared currentTripLabel on the server and coerce other tablets to refresh.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun clearActiveSegmentAndRefresh() {
        try {
            // 1) publish empty label to clear server-side client attribute
            try {
                val payload = "{\"currentTripLabel\":\"\", \"activityState\":\"\"}"
                if (::mqttManager.isInitialized) {
                    mqttManager.publish(ATTR_TOPIC, payload)
                }

                // Ask server to re-publish shared data and force a quick attribute refresh
                // so other tablets observe the change promptly.
                try {
                    requestAdminMessage()
                } catch (e: Exception) {
                    Log.w("BreakActivity", "publishActiveSegment follow-up failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w("BreakActivity", "publishActiveSegment(\"\") failed: ${e.message}")
            }

            // 2) ask ThingsBoard to re-broadcast and then force a poll/refresh
            try {
                // this triggers any admin broadcast side-effects you use
                try { requestAdminMessage() } catch (e: Exception) {
                    Log.w("BreakActivity", "requestAdminMessage failed: ${e.message}")
                }
                return
            } catch (e: Exception) {
                Log.w("BreakActivity", "mqttHelper interaction failed: ${e.message}")
            }

        } catch (e: Exception) {
            Log.w("BreakActivity", "clearActiveSegmentAndRefresh failed: ${e.message}")
        }
    }

    /**
     * Requests admin messages periodically.
     */
    fun requestAdminMessage() {
        val jsonObject = JSONObject().apply {
            put("sharedKeys", "message,busRoute,busStop,config")
        }
        mqttManager.publish(MapActivity.PUB_MSG_TOPIC, jsonObject.toString())
        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                mqttManager.publish(MapActivity.PUB_MSG_TOPIC, jsonObject.toString())
                Handler(Looper.getMainLooper()).postDelayed(this, MapActivity.REQUEST_PERIODIC_TIME)
            }
        })
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            publishBreakAttributes()
            heartbeatHandler.postDelayed(this, 15_000)
        }
    }

    private fun showFinished() {
        timerText.text = "00:00"
        infoText.text = "Your break is over"
        doneBtn.visibility = View.VISIBLE
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onDestroy() {
        cd?.cancel()
        heartbeatHandler.removeCallbacksAndMessages(null)
        // Update the segment's attribute post-break
        clearActiveSegmentAndRefresh()
        super.onDestroy()
        // ❗ DO NOT DISCONNECT MQTT HERE
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
        return (endCal.timeInMillis - now.timeInMillis).coerceAtLeast(0)
    }
}