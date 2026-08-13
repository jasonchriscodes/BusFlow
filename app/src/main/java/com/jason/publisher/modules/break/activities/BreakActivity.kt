package com.jason.publisher.modules.`break`.activities

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jason.publisher.R
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.TripStateSnapshot
import com.jason.publisher.main.loggers.UserActionLogger
import com.jason.publisher.main.loggers.TripLog
import com.jason.publisher.main.model.RouteData
import com.jason.publisher.modules.battery.ui.hookBatteryToasts
import com.jason.publisher.modules.`break`.adapters.BreakUpcomingAdapter
import com.jason.publisher.modules.map.mqtt.helpers.MqttHelper
import com.jason.publisher.modules.map.mqtt.services.MqttManager
import com.varunest.sparkbutton.SparkButton
import com.varunest.sparkbutton.SparkEventListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

open class BreakActivity : AppCompatActivity() {

    private lateinit var timerText: TextView
    private lateinit var infoText: TextView
    private lateinit var endAtText: TextView
    private lateinit var doneBtn: Button

    private var cd: CountDownTimer? = null
    private lateinit var mqttManager: MqttManager

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private lateinit var breakLabel: String

    companion object {
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

        doneBtn.visibility = View.VISIBLE
        doneBtn.isEnabled = false
        doneBtn.text = "Back to Schedule" // or: "End break early"

        val token = intent.getStringExtra("ACCESS_TOKEN")
        if (token.isNullOrBlank()) {
            FileLogger.e("BreakActivity", "ACCESS_TOKEN missing")
            finish()
            return
        }

        breakLabel = intent.getStringExtra("BREAK_LABEL") ?: "Break"

        // ===== Hanover acknowledgement =====
        setUpAcknowledgement()

        val firstList =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM", ScheduleItem::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM")
            }
        val breakItem = firstList?.firstOrNull()

        if (breakItem == null) {
            FileLogger.e("BreakActivity", "FIRST_SCHEDULE_ITEM empty")
            finish()
            return
        }

        // ===== Upcoming list =====
        val fullRemaining =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA", ScheduleItem::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA")
            }
                ?: arrayListOf()

        if (fullRemaining.isEmpty()) {
            FileLogger.e("BreakActivity", "FULL_SCHEDULE_DATA EMPTY")
        }

        val idx = intent.getIntExtra("SELECTED_ROUTE_INDEX", -1)
        FileLogger.d("BreakActivity ROUTE","BreakActivity ROUTE | selectedIdx=$idx | (ROUTE IGNORED)")


        val busRouteData =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra("BUS_ROUTE_DATA", RouteData::class.java) ?: arrayListOf()
            }else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra("BUS_ROUTE_DATA") ?: arrayListOf()
            }

        val selectedRoute =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("SELECTED_ROUTE_DATA", RouteData::class.java)
            }else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("SELECTED_ROUTE_DATA")
            }

        FileLogger.d(
            "BreakActivity ROUTE",
            "BreakActivity ROUTE | selectedIdx=$idx | busRouteData.size=${busRouteData.size} | selectedStart=${selectedRoute?.startingPoint}"
        )

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
        TripLog.mark("driver break")

        // ===== MQTT =====
        mqttManager = MqttManager(username = token)

        val no = intent.getIntExtra("EXTRA_PANEL_DEBUG_NO", -1)

        FileLogger.d(
            "BreakActivity INTENT",
            "BreakActivity INTENT | no=$no | BREAK_LABEL=$breakLabel | breakItem=$breakItem | remaining.size=${fullRemaining.size}"
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
        val durationMs = computeTimerDurationMillis(breakItem)

        endAtText.text = getEndAtText(breakItem, durationMs)

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
            UserActionLogger.click("BreakActivity", "doneBtn", "breakLabel=$breakLabel")
            onDoneClicked(fullRemaining)
        }
    }

    /**
     * Requires the driver to tick a checklist item confirming Hanover is set to
     * "Out of Service" before "Back to Schedule" is enabled or back-press is
     * allowed. This holds even after the countdown reaches zero.
     */
    private fun setUpAcknowledgement() {
        val ackSparkButton = findViewById<SparkButton>(R.id.breakAckSparkButton)
        val ackRow = findViewById<View>(R.id.breakAckRow)

        ackRow.setOnClickListener {
            UserActionLogger.click("BreakActivity", "ackRow", "breakLabel=$breakLabel")
            ackSparkButton.performClick()
        }

        ackSparkButton.setEventListener(object : SparkEventListener {
            override fun onEvent(button: ImageView, buttonState: Boolean) {}
            override fun onEventAnimationStart(button: ImageView, buttonState: Boolean) {}
            override fun onEventAnimationEnd(button: ImageView, buttonState: Boolean) {
                if (buttonState) {
                    UserActionLogger.stateChanged("BreakActivity", "doneBtn.isEnabled", false, true)
                    doneBtn.isEnabled = true
                }
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                UserActionLogger.click("BreakActivity", "systemBackPressed")
                if (ackSparkButton.isChecked) {
                    finish()
                } else {
                    UserActionLogger.shown("BreakActivity", "toast", "Please confirm Hanover before continuing")
                    Toast.makeText(
                        this@BreakActivity,
                        "Please confirm Hanover before continuing",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    protected open fun onDoneClicked(fullRemaining: ArrayList<ScheduleItem>) {
        val remainingAfterBreak = ArrayList(fullRemaining.drop(1))
        val resultIntent = android.content.Intent().apply {
            putParcelableArrayListExtra("UPDATED_FULL_SCHEDULE_DATA", remainingAfterBreak)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
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
            mqttManager.publish(MqttHelper.Companion.ATTR_TOPIC, payload)
        } catch (e: Exception) {
            FileLogger.e("BreakActivity", "Publish failed | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
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
                    mqttManager.publish(MqttHelper.Companion.ATTR_TOPIC, payload)
                }

                // Ask server to re-publish shared data and force a quick attribute refresh
                // so other tablets observe the change promptly.
                try {
                    requestAdminMessage()
                } catch (e: Exception) {
                    FileLogger.w("BreakActivity", "publishActiveSegment follow-up failed | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
                }
            } catch (e: Exception) {
                FileLogger.w("BreakActivity", "publishActiveSegment(\"\") failed | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
            }

            // 2) ask ThingsBoard to re-broadcast and then force a poll/refresh
            try {
                // this triggers any admin broadcast side-effects you use
                try { requestAdminMessage() } catch (e: Exception) {
                    FileLogger.w("BreakActivity", "requestAdminMessage failed | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
                }
                return
            } catch (e: Exception) {
                FileLogger.w("BreakActivity", "mqttHelper interaction failed | ${e.javaClass.simpleName}: ${e.message}\n${Log.getStackTraceString(e)}")
            }

        } catch (e: Exception) {
            FileLogger.w("BreakActivity", "clearActiveSegmentAndRefresh failed | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
        }
    }

    /**
     * Requests admin messages periodically.
     */
    fun requestAdminMessage() {
        val jsonObject = JSONObject().apply {
            put("sharedKeys", "message,busRoute,busStop,config")
        }
        mqttManager.publish(MqttHelper.Companion.PUB_MSG_TOPIC, jsonObject.toString())
        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                mqttManager.publish(MqttHelper.Companion.PUB_MSG_TOPIC, jsonObject.toString())
                Handler(Looper.getMainLooper()).postDelayed(this,
                    MqttHelper.Companion.REQUEST_PERIODIC_TIME
                )
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

    protected open fun computeTimerDurationMillis(item: ScheduleItem): Long {
        return computeRemainingMillis(item.endTime)
            .takeIf { it > 0 } ?: (FALLBACK_SECONDS * 1000L)
    }

    protected open fun getEndAtText(item: ScheduleItem, durationMs: Long): String {
        val endAt = System.currentTimeMillis() + durationMs
        return "Break until ${SimpleDateFormat("HH:mm:ss").format(Date(endAt))}"
    }

    protected fun computeScheduledDurationMillis(startHHmm: String, endHHmm: String): Long {
        val startParts = startHHmm.split(":").mapNotNull { it.toIntOrNull() }
        val endParts = endHHmm.split(":").mapNotNull { it.toIntOrNull() }
        if (startParts.size < 2 || endParts.size < 2) return 0L

        val startCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startParts[0])
            set(Calendar.MINUTE, startParts[1])
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = startCal.clone() as Calendar
        endCal.set(Calendar.HOUR_OF_DAY, endParts[0])
        endCal.set(Calendar.MINUTE, endParts[1])
        endCal.set(Calendar.SECOND, 0)
        endCal.set(Calendar.MILLISECOND, 0)
        if (endCal.timeInMillis <= startCal.timeInMillis) {
            endCal.add(Calendar.DATE, 1)
        }

        return endCal.timeInMillis - startCal.timeInMillis
    }

    protected fun formatScheduleTime(time: String): String {
        val parts = time.split(":").mapNotNull { it.toIntOrNull() }
        return if (parts.size >= 2) "%02d:%02d".format(parts[0], parts[1]) else time
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
