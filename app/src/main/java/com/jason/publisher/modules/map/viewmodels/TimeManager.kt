package com.jason.publisher.modules.map.viewmodels

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.TripStateSnapshot
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.utils.formatNextRunCountdown
import java.text.SimpleDateFormat
import java.util.Locale

class TimeManager(): ViewModel() {
    companion object {
        fun provideFactory(): ViewModelProvider.Factory = object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return (TimeManager() as T)
            }
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val currentTime: MutableLiveData<String> by lazy {
        val systemCurrentMillis = System.currentTimeMillis()
        val systemTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(systemCurrentMillis)
        MutableLiveData<String>(systemTimeStr)
    }

    val nextTripCountdown: MutableLiveData<String> by lazy { MutableLiveData<String>() }

    private var currentTimeHandler: Handler? = null
    private var currentTimeRunnable: Runnable? = null
    private var nextTripHandler: Handler? = null
    private var nextTripRunnable: Runnable? = null

    /**
     * Starts the simulated clock which will update [currentTime] every second based on the system's clock.
     */
    fun startCurrentTimeUpdater(
        timeProvider: () -> Long = { System.currentTimeMillis() },
        onUpdateCallback: () -> Unit
    ) {
        // Stop any existing timer first
        stopCurrentTime()

        currentTimeHandler = Handler(Looper.getMainLooper())
        currentTimeRunnable = object : Runnable {
            @SuppressLint("LongLogTag")
            override fun run() {
                try {
                    currentTime.postValue(timeFormat.format(timeProvider()))
                    onUpdateCallback()

                    // Schedule next update only if handler is still valid
                    currentTimeHandler?.postDelayed(this, 1000)
                } catch (e: Exception) {
                    FileLogger.e("TimeManager", "Error in timer runnable | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
                }
            }
        }

        currentTimeHandler?.post(currentTimeRunnable!!)
    }

    /**
     * function to calculate and display the remaining time until the next scheduled run
     */
    fun startNextTripCountdownUpdater(
        nextRun: ScheduleItem?,
        timeProvider: () -> Long = { System.currentTimeMillis() }
    ) {
        // Stop any existing countdown timer first
        stopNextTripCountdown()

        nextTripHandler = Handler(Looper.getMainLooper())
        nextTripRunnable = object : Runnable {
            override fun run() {
                try {
                    val newNextTripText = formatNextRunCountdown(nextRun?.startTime, timeProvider())
                    nextTripCountdown.postValue(newNextTripText)

                    // Schedule next update only if handler is still valid
                    nextTripHandler?.postDelayed(this, 1000)
                } catch (e: Exception) {
                    FileLogger.e("TimeManager", "Error in countdown runnable | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
                }
            }
        }
        nextTripHandler?.post(nextTripRunnable!!)
    }

    /**
     * Stop the next trip countdown updater
     */
    private fun stopNextTripCountdown() {
        nextTripHandler?.removeCallbacksAndMessages(null)
        nextTripRunnable = null
    }

    /**
     * Function to remove current time call back
     */
    private fun stopCurrentTime() {
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

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}