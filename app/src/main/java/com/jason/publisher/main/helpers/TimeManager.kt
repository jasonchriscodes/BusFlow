package com.jason.publisher.main.helpers

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import com.jason.publisher.main.activity.MapActivity
import com.jason.publisher.main.model.ScheduleItem
import java.text.SimpleDateFormat
import java.util.*

class TimeManager(private val owner: MapActivity, private val scheduleStatusManager: ScheduleStatusManager) {

    var simulatedStartTime: Calendar = Calendar.getInstance()
    private var currentTimeHandler: Handler? = null
    private var currentTimeRunnable: Runnable? = null
    private var nextTripHandler: Handler? = null
    private var nextTripRunnable: Runnable? = null

    /**
     * Starts a custom time from a hardcoded string and counts up from there.
     * Example: startCustomTime("08:11:00")
     */
    @SuppressLint("LongLogTag")
    fun startCustomTime(customTime: String) {
        val timeParts = customTime.split(":")
        if (timeParts.size != 3) {
            Log.e("MapActivity startCustomTime", "❌ Invalid time format: $customTime. Expected HH:mm:ss")
            return
        }

        // Initialize simulatedStartTime from the custom string
        simulatedStartTime.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
        simulatedStartTime.set(Calendar.MINUTE, timeParts[1].toInt())
        simulatedStartTime.set(Calendar.SECOND, timeParts[2].toInt())

        // Stop any existing timer first
        stopCurrentTime()

        currentTimeHandler = Handler(Looper.getMainLooper())
        currentTimeRunnable = object : Runnable {
            override fun run() {
                try {
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    owner.currentTimeTextView.text = timeFormat.format(simulatedStartTime.time)
                    Log.d("MapActivity startCustomTime", "currentTimeTextView.text: ${owner.currentTimeTextView.text}")

                    // Advance time by 1 second per tick
                    simulatedStartTime.add(Calendar.SECOND, 1)

                    // Update schedule status based on the new simulated time
                    owner.scheduleStatusValueTextView.text = "Calculating..."
                    scheduleStatusManager.checkScheduleStatus()

                    // Schedule next update only if handler is still valid
                    currentTimeHandler?.postDelayed(this, 1000) // Update every second
                } catch (e: Exception) {
                    Log.e("TimeManager", "Error in timer runnable: ${e.message}", e)
                }
            }
        }

        currentTimeHandler?.post(currentTimeRunnable!!)
    }

    /**
     * Starts the simulated clock using the startTime of the first ScheduleItem in scheduleList.
     */
    fun startStartTime() {
        val scheduleList = owner.scheduleList
        if (scheduleList.isEmpty()) {
            Log.e("MapActivity", "❌ scheduleList is empty. Cannot start start time updater.")
            return
        }

        // Extract the first schedule start time (e.g., "11:15")
        val startTimeStr = scheduleList.first().startTime
        val timeParts = startTimeStr.split(":")
        if (timeParts.size != 2) {
            Log.e("MapActivity", "❌ Invalid start time format in scheduleList: $startTimeStr")
            return
        }

        simulatedStartTime.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
        simulatedStartTime.set(Calendar.MINUTE, timeParts[1].toInt())
        simulatedStartTime.set(Calendar.SECOND, 0)

        // Stop any existing timer first
        stopCurrentTime()

        currentTimeHandler = Handler(Looper.getMainLooper())
        currentTimeRunnable = object : Runnable {
            @SuppressLint("LongLogTag")
            override fun run() {
                try {
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    owner.currentTimeTextView.text = timeFormat.format(simulatedStartTime.time)
                    Log.d("MapActivity startStartTime", "Current simulated time: ${owner.currentTimeTextView.text}")

                    simulatedStartTime.add(Calendar.SECOND, 1)
                    // Schedule next update only if handler is still valid
                    currentTimeHandler?.postDelayed(this, 1000)
                } catch (e: Exception) {
                    Log.e("TimeManager", "Error in timer runnable: ${e.message}", e)
                }
            }
        }

        currentTimeHandler?.post(currentTimeRunnable!!)
    }

    /**
     * function to calculate and display the remaining time until the next scheduled run
     */
    fun startNextTripCountdownUpdater() {
        owner.scheduleList
        owner.scheduleData

        // Stop any existing countdown timer first
        stopNextTripCountdown()

        nextTripHandler = Handler(Looper.getMainLooper())
        nextTripRunnable = object : Runnable {
            override fun run() {
                try {
                    val currentTime = simulatedStartTime.clone() as Calendar
                    val nextTripStartTime = getNextScheduleStartTime()

                    if (nextTripStartTime != null) {
                        val timeParts = nextTripStartTime.split(":").map { it.toInt() }
                        val nextTripCalendar = Calendar.getInstance().apply {
                            set(Calendar.YEAR, currentTime.get(Calendar.YEAR))
                            set(Calendar.MONTH, currentTime.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, currentTime.get(Calendar.DAY_OF_MONTH))
                            set(Calendar.HOUR_OF_DAY, timeParts[0])
                            set(Calendar.MINUTE, timeParts[1])
                            set(Calendar.SECOND, 0)
                            if (timeInMillis <= currentTime.timeInMillis) add(Calendar.DATE, 1)
                        }
                        val diff = nextTripCalendar.timeInMillis - currentTime.timeInMillis
                        if (diff > 0) {
                            val mins = (diff / 1000 / 60).toInt()
                            val secs = ((diff / 1000) % 60).toInt()
                            owner.nextTripCountdownTextView.text = "Next run in: $mins mins $secs seconds"
                        } else {
                            owner.nextTripCountdownTextView.text = "You are late for the next run"
                        }
                    } else {
                        owner.nextTripCountdownTextView.text = "No more scheduled trips for today"
                    }
                    // Schedule next update only if handler is still valid
                    nextTripHandler?.postDelayed(this, 1000)
                } catch (e: Exception) {
                    Log.e("TimeManager", "Error in countdown runnable: ${e.message}", e)
                }
            }
        }
        nextTripHandler?.post(nextTripRunnable!!)
    }

    /**
     * Stop the next trip countdown updater
     */
    fun stopNextTripCountdown() {
        nextTripHandler?.removeCallbacksAndMessages(null)
        nextTripRunnable = null
    }

    /**
     * function to update the currentTimeTextView
     */
    fun startCurrentTimeUpdater() {
        // Stop any existing timer first
        stopCurrentTime()

        currentTimeHandler = Handler(Looper.getMainLooper())
        currentTimeRunnable = object : Runnable {
            override fun run() {
                try {
                    val currentTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    val nowStr = currentTimeFormat.format(Date())
                    owner.currentTimeTextView.text = nowStr
                    // Schedule next update only if handler is still valid
                    currentTimeHandler?.postDelayed(this, 1000)
                } catch (e: Exception) {
                    Log.e("TimeManager", "Error in current time runnable: ${e.message}", e)
                }
            }
        }
        currentTimeHandler?.post(currentTimeRunnable!!)
    }

    /**
     * Function to remove current time call back
     */
    fun stopCurrentTime() {
        currentTimeHandler?.removeCallbacksAndMessages(null)
        currentTimeRunnable = null
    }

    /**
     * Cleanup all handlers
     */
    fun cleanup() {
        stopCurrentTime()
        stopNextTripCountdown()
    }

    /**
     * Add this helper function to convert a time string (e.g. "08:11") to minutes since midnight.
     */
    fun convertTimeToMinutes(time: String): Int {
        val parts = time.split(":").map { it.toInt() }
        return parts[0] * 60 + parts[1]
    }

    /**
     * Returns the start time for the next schedule.
     * Assumes that the scheduleData list is sorted chronologically.
     */
    fun getNextScheduleStartTime(): String? {
        val flat = (owner.scheduleData as? List<Any> ?: emptyList()).flatMap {
            when (it) {
                is ScheduleItem -> listOf(it)
                is List<*>      -> it.filterIsInstance<ScheduleItem>()
                else            -> emptyList()
            }
        }
        return if (flat.size > 1) flat[1].startTime else null
    }

    /**
     * Set the same base date for all Date
     */
    fun parseTimeToday(timeStr: String): Date {
        val parts = timeStr.split(":")
        if (parts.size != 3) return Date()
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, parts[0].toInt())
            set(Calendar.MINUTE, parts[1].toInt())
            set(Calendar.SECOND, parts[2].toInt())
            set(Calendar.MILLISECOND, 0)
        }.time
    }

    /** 🔹 Reset actual time when the bus reaches a stop or upcoming stop changes */
    fun resetActualTime() {
        owner.simulationStartTime = System.currentTimeMillis()
        Log.d("MapActivity", "✅ Actual time reset to current time.")
    }
}