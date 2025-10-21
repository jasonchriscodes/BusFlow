package com.jason.publisher.main.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jason.publisher.R
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.services.MqttManager
import java.text.SimpleDateFormat
import java.util.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jason.publisher.main.ui.BreakUpcomingAdapter

class BreakActivity : AppCompatActivity() {

    private lateinit var timerText: TextView
    private lateinit var infoText: TextView
    private lateinit var endAtText: TextView
    private lateinit var doneBtn: Button
    private var cd: CountDownTimer? = null

    // Toggle this later when you want to use the ScheduleItem endTime
    private val USE_DYNAMIC = false
    private val FALLBACK_SECONDS = 30L
    private lateinit var mqttManager: MqttManager

    companion object {
        const val SERVER_URI = MapActivity.SERVER_URI
        const val CLIENT_ID  = MapActivity.CLIENT_ID
        private const val ATTR_TOPIC = "v1/devices/me/attributes"
    }

    @SuppressLint("SimpleDateFormat")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_break)

        timerText = findViewById(R.id.breakTimerText)
        infoText  = findViewById(R.id.breakInfoText)
        endAtText = findViewById(R.id.breakEndAtText)
        doneBtn   = findViewById(R.id.breakDoneBtn)

        val firstList = intent.getSerializableExtra("FIRST_SCHEDULE_ITEM") as? ArrayList<ScheduleItem>
        val token      = intent.getStringExtra("ACCESS_TOKEN") ?: return
        val breakLabel = intent.getStringExtra("BREAK_LABEL") ?: "Break"
        val breakItem = firstList?.firstOrNull()
        if (breakItem == null) {
            finish()
            return
        }

        // Grab the remaining day schedule (already passed into the Intent by ScheduleActivity)
        val fullRemaining = intent.getSerializableExtra("FULL_SCHEDULE_DATA") as? ArrayList<ScheduleItem>
        val upNextHeader = findViewById<TextView>(R.id.upNextHeader)
        val upNextRecycler = findViewById<RecyclerView>(R.id.upNextRecycler)

        // Build the slice: items until the *next* Break (exclusive)
        val untilNextBreak: List<ScheduleItem> = buildList {
            fullRemaining?.forEach { item ->
                if (item.runName.equals("break", ignoreCase = true)) return@forEach
                add(item)
            }
        }.let { raw ->
            val idx = fullRemaining?.indexOfFirst { it.runName.equals("break", true) } ?: -1
            if (idx in 0..raw.lastIndex) raw.take(idx) else raw
        }

        // If there’s something to show, reveal the header + list
        if (!untilNextBreak.isNullOrEmpty()) {
            upNextHeader.visibility = View.VISIBLE
            upNextRecycler.apply {
                visibility = View.VISIBLE
                layoutManager = LinearLayoutManager(this@BreakActivity)
                adapter = BreakUpcomingAdapter(untilNextBreak) // <-- new adapter
                setHasFixedSize(true)
                addItemDecoration(
                    androidx.recyclerview.widget.DividerItemDecoration(
                        this@BreakActivity,
                        androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
                    ).apply {
                        val d = object : android.graphics.drawable.ColorDrawable(
                            android.graphics.Color.parseColor("#33FFFFFF") // ← light white
                        ) {
                            override fun getIntrinsicHeight(): Int =
                                (resources.displayMetrics.density).toInt().coerceAtLeast(1)
                        }
                        setDrawable(d)
                    }
                )
            }
        } else {
            // Nothing before the next Break — keep it hidden.
            upNextHeader.visibility = View.GONE
            upNextRecycler.visibility = View.GONE
        }

        // Build a lightweight MQTT client just for attributes
        mqttManager = MqttManager(
            serverUri = SERVER_URI,
            clientId  = "$CLIENT_ID-break",
            username  = token
        )

        // 1) Hardcoded 30s for testing, 2) later switch to dynamic endTime
        val durationMs: Long = if (!USE_DYNAMIC) {
            FALLBACK_SECONDS * 1000L
        } else {
            computeRemainingMillis(breakItem.endTime).takeIf { it > 0 } ?: (FALLBACK_SECONDS * 1000L)
        }

        // Show "Break until" based on now + duration we’re actually using
        val endAt = System.currentTimeMillis() + durationMs
        val fmt = SimpleDateFormat("HH:mm:ss")
        endAtText.text = "Break until ${fmt.format(Date(endAt))}"

        if (durationMs <= 0L) {
            showFinished()
        } else {
            cd = object : CountDownTimer(durationMs, 1000L) {
                override fun onTick(ms: Long) { timerText.text = formatHMS(ms) }
                override fun onFinish()      { showFinished() }
            }.start()
        }

        // Simply pop back to ScheduleActivity on click
        doneBtn.setOnClickListener { finish() }
        mqttManager.connect { ok ->
            if (ok) {
                publishBreakAttributes(breakLabel)

                // (Optional) nudge once more after a short delay to defeat race conditions
                Handler(Looper.getMainLooper()).postDelayed({
                    publishBreakAttributes(breakLabel)
                }, 300)
            }
        }
    }

    private fun publishBreakAttributes(label: String) {
        // Make sure MqttHelper is requesting "currentTripLabel" key (it is).
        val payload = "{\"currentTripLabel\":\"${label.replace("\"", "\\\"")}\"}"
        try {
            mqttManager.publish(ATTR_TOPIC, payload)
        } catch (_: Exception) { /* ignore */ }
    }

    private fun showFinished() {
        timerText.text = "00:00"
        infoText.text = "Your break is over"
        doneBtn.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        cd?.cancel()
        super.onDestroy()
        try { mqttManager.disconnect() } catch (_: Exception) {}
    }

    private fun formatHMS(ms: Long): String {
        var sec = ms / 1000
        val h = sec / 3600; sec %= 3600
        val m = sec / 60
        val s = sec % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    // Dynamic path (when USE_DYNAMIC = true)
    private fun computeRemainingMillis(endHHmm: String): Long {
        val now = Calendar.getInstance()
        val endCal = now.clone() as Calendar
        val parts = endHHmm.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return 0L
        endCal.set(Calendar.HOUR_OF_DAY, parts[0])
        endCal.set(Calendar.MINUTE, parts[1])
        endCal.set(Calendar.SECOND, 0)
        endCal.set(Calendar.MILLISECOND, 0)
        val diff = endCal.timeInMillis - now.timeInMillis
        return if (diff > 0) diff else 0L
    }
}
