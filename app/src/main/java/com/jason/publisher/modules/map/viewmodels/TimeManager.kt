package com.jason.publisher.modules.map.viewmodels

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.jason.publisher.main.model.ScheduleItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TimeManager(private val savedStateHandle: SavedStateHandle): ViewModel() {
    companion object {
        fun provideFactory(): AbstractSavedStateViewModelFactory = object :
            AbstractSavedStateViewModelFactory() {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel?> create(
                key: String,
                modelClass: Class<T?>,
                handle: SavedStateHandle
            ): T & Any {
                return (TimeManager(handle) as T)!!
            }
        }

        private const val SAVED_CURRENT_TIME_KEY = "currentTime"
    }

    val currentTime: MutableLiveData<String> by lazy {
        val savedCurrentTime = savedStateHandle.get<String>(SAVED_CURRENT_TIME_KEY)
        MutableLiveData<String>(savedCurrentTime)
    }

    fun updateCurrentTime(newValue: String) {
        currentTime.postValue(newValue)
        savedStateHandle[SAVED_CURRENT_TIME_KEY] = newValue
    }

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
    fun startCustomTime(customTime: String, updateTextViewCallback: (String) -> Unit) {
        if (savedStateHandle.get<String>(SAVED_CURRENT_TIME_KEY) == null) {
            val timeParts = customTime.split(":")
            if (timeParts.size != 3) {
                Log.e("MapActivity startCustomTime", "❌ Invalid time format: $customTime. Expected HH:mm:ss")
                return
            }

            // Initialize simulatedStartTime from the custom string
            simulatedStartTime.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
            simulatedStartTime.set(Calendar.MINUTE, timeParts[1].toInt())
            simulatedStartTime.set(Calendar.SECOND, timeParts[2].toInt())
        }

        // Stop any existing timer first
        stopCurrentTime()

        currentTimeHandler = Handler(Looper.getMainLooper())
        currentTimeRunnable = object : Runnable {
            override fun run() {
                try {
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    val newCurrentTime = timeFormat.format(simulatedStartTime.time)
                    updateCurrentTime(newCurrentTime)
                    updateTextViewCallback(timeFormat.format(simulatedStartTime.time))

                    // Advance time by 1 second per tick
                    simulatedStartTime.add(Calendar.SECOND, 1)

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
    fun startStartTime(scheduleList: List<ScheduleItem>) {
        if (savedStateHandle.get<String>(SAVED_CURRENT_TIME_KEY) == null) {
            if (scheduleList.isEmpty()) {
                Log.e("MapActivity", "❌ scheduleList is empty. Cannot start start time updater.")
                return
            }

            // Initialize simulatedStartTime with schedule start time (original behavior)
            val firstSchedule = scheduleList.first()
            val startTimeParts = firstSchedule.startTime.split(":")
            if (startTimeParts.size != 2) {
                Log.e("MapActivity", "❌ Invalid start time format: ${firstSchedule.startTime}")
                return
            }

            simulatedStartTime.set(Calendar.HOUR_OF_DAY, startTimeParts[0].toInt())
            simulatedStartTime.set(Calendar.MINUTE, startTimeParts[1].toInt())
            simulatedStartTime.set(Calendar.SECOND, 0)
        }

        // Stop any existing timer first
        stopCurrentTime()

        currentTimeHandler = Handler(Looper.getMainLooper())
        currentTimeRunnable = object : Runnable {
            @SuppressLint("LongLogTag")
            override fun run() {
                try {
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    updateCurrentTime(timeFormat.format(simulatedStartTime.time))

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
    fun startNextTripCountdownUpdater(
        scheduleData: List<ScheduleItem>,
        updateTextViewCallback: (String) -> Unit,
    ) {
        // Stop any existing countdown timer first
        stopNextTripCountdown()

        nextTripHandler = Handler(Looper.getMainLooper())
        nextTripRunnable = object : Runnable {
            override fun run() {
                try {
                    val currentTime = simulatedStartTime.clone() as Calendar
                    val nextTripStartTime = getNextScheduleStartTime(scheduleData)

                    val newNextTripText: String
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
                            newNextTripText = "Next run in: $mins mins $secs seconds"
                        } else {
                            newNextTripText = "You are late for the next run"
                        }
                    } else {
                        newNextTripText = "No more scheduled trips for today"
                    }
                    updateTextViewCallback(newNextTripText)
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
     * ✅ REVERT: Back to original implementation before plan changes
     */
    fun getNextScheduleStartTime(scheduleData: List<ScheduleItem>): String? {
        val flat = (scheduleData as? List<Any> ?: emptyList()).flatMap {
            when (it) {
                is ScheduleItem -> listOf(it)
                is List<*>      -> it.filterIsInstance<ScheduleItem>()
                else            -> emptyList()
            }
        }
        // Return second item if available
        return if (flat.size > 1) flat[1].startTime else null
    }

}