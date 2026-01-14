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
import com.jason.publisher.main.utils.getNextScheduleStartTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TimeManager(): ViewModel() {
    companion object {
        fun provideFactory(): AbstractSavedStateViewModelFactory = object :
            AbstractSavedStateViewModelFactory() {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel?> create(
                key: String,
                modelClass: Class<T?>,
                handle: SavedStateHandle
            ): T & Any {
                return (TimeManager() as T)!!
            }
        }
    }

    val currentTime: MutableLiveData<String> by lazy {
        val systemCurrentMillis = System.currentTimeMillis()
        val systemTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(systemCurrentMillis)
        MutableLiveData<String>(systemTimeStr)
    }

    fun updateCurrentTime(newValue: String) {
        currentTime.postValue(newValue)
    }

    private var currentTimeHandler: Handler? = null
    private var currentTimeRunnable: Runnable? = null
    private var nextTripHandler: Handler? = null
    private var nextTripRunnable: Runnable? = null

    /**
     * Starts the simulated clock which will update [currentTime] every second based on the system's clock.
     */
    fun startCurrentTimeUpdater(onUpdateCallback: () -> Unit) {
        // Stop any existing timer first
        stopCurrentTime()

        currentTimeHandler = Handler(Looper.getMainLooper())
        currentTimeRunnable = object : Runnable {
            @SuppressLint("LongLogTag")
            override fun run() {
                try {
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    updateCurrentTime(timeFormat.format(System.currentTimeMillis()))
                    onUpdateCallback()

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
                    val currentTime = Calendar.getInstance().apply {
                        timeInMillis = System.currentTimeMillis()
                    }
                    val nextTripStartTime = scheduleData.getNextScheduleStartTime()

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
}