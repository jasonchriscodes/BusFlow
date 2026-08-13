package com.jason.publisher.modules.map.viewmodels

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.loggers.FileLogger
import com.jason.publisher.main.loggers.LifecycleLogger
import com.jason.publisher.main.loggers.TripStateSnapshot
import com.jason.publisher.main.utils.getDeltaNextSec
import com.jason.publisher.main.utils.parseTimeToday
import com.jason.publisher.modules.map.activities.MapActivity
import com.jason.publisher.modules.map.utils.calculateDistance
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class ScheduleStatusManager(
    private val activity: MapActivity,
    private val binding: ActivityMapBinding
) {

    enum class StopPassCategory {
        ON_TIME,     // green
        EARLY,       // red (timing points only)
        LATE_5,      // blue  (>= 5 min late)
        LATE_10,     // purple(>= 10 min late)
        PENDING      // orange (non-timing until next timing point)
    }

    data class StopVisualStatus(
        val category: StopPassCategory,
        val isTimingPoint: Boolean
    )

    fun toZoneColor(category: StopPassCategory): Int = when (category) {
        StopPassCategory.ON_TIME -> Color.parseColor("#2E7D32") // green
        StopPassCategory.EARLY   -> Color.parseColor("#E53935") // red
        StopPassCategory.LATE_5  -> Color.parseColor("#1E88E5") // blue
        StopPassCategory.LATE_10 -> Color.parseColor("#8E24AA") // purple
        StopPassCategory.PENDING -> Color.parseColor("#FB8C00") // orange
    }

    var lastCategory: StopPassCategory = StopPassCategory.ON_TIME
        private set

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var cachedRedStopIndex: Int = -1
    private var cachedRouteSize: Int = 0
    private var cachedD2: Double = -1.0

    private fun categorizeTimingDelta(deltaSec: Int): StopPassCategory {
        // deltaSec = scheduled - predicted
        // positive => predicted before scheduled (EARLY)
        // negative => predicted after scheduled (LATE)
        return when {
            deltaSec > 0          -> StopPassCategory.EARLY
            deltaSec <= -600      -> StopPassCategory.LATE_10
            deltaSec <= -300      -> StopPassCategory.LATE_5
            else                  -> StopPassCategory.ON_TIME
        }
    }

    /**
     * Checks and updates the bus schedule status for the upcoming red timing point.
     *
     * The function calculates the predicted arrival time based on the current simulation speed
     * and the distance from the bus’s current location to the next red timing point, then compares
     * this value against the expected timing point arrival time shown in the UI.
     *
     * Key Steps:
     * 1. **Initial Validation and Base Time Determination:**
     *    - If in force-ahead mode and the first stop has not yet been passed, displays "Please wait...".
     *    - Sets a base time:
     *         - If forceAheadStatus is true, uses a custom time.
     *         - Otherwise, uses the start time from the first schedule.
     *
     * 2. **Extracting and Adjusting Timing Values:**
     *    - Retrieves the scheduled timing point from the UI (via `timingPointValueTextView`).
     *    - Extracts the API-based scheduled arrival time from `ApiTimeValueTextView` and, when
     *      necessary (i.e. if the upcoming stop is the first bus stop and additional duration applies),
     *      adjusts it to account for the initial stop duration.
     *
     * 3. **Determining the Next Red Timing Point:**
     *    - Searches for the next bus stop designated as a “red” timing point (used as a key reference).
     *    - If none is found after the current stop, falls back to using the final bus stop.
     *
     * 4. **Distance and Time Calculations:**
     *    - **d1:** Computes the distance (in meters) from the current location to the identified red timing point.
     *    - **d2:** Sums the distances along the route from the start to the red timing point.
     *         - If d2 equals zero, the function logs an error and exits.
     *    - **t2:** Calculates the total scheduled travel time (in seconds) from the base time until the red timing point,
     *         using the API-provided schedule.
     *
     * 5. **Predicted Arrival Estimation:**
     *    - Determines the effective speed (in m/s) from the current smoothed speed, applying a minimum threshold
     *      (to avoid division by zero).
     *    - **t1:** Estimates the remaining travel time to the red timing point as `t1 = d1 / effectiveSpeed`.
     *    - Computes the predicted arrival time by adding t1 (in seconds) to the simulation start time.
     *
     * 6. **Status Comparison and UI Update:**
     *    - **deltaSec:** Calculates the time difference (in seconds) between the timing point’s displayed time
     *      (parsed from `timingPointValueTextView`) and the predicted arrival time.
     *    - Determines the schedule status based on deltaSec:
     *         - ≥120 sec → "Very Ahead"
     *         - 1 to 119 sec → "Slightly Ahead"
     *         - -179 to 0 sec → "On Time"
     *         - -299 to -180 sec → "Slightly Behind"
     *         - ≤ -300 sec → "Very Behind"
     *    - Updates the UI:
     *         - Sets the appropriate status text.
     *         - Changes text color and icon based on how early or late the bus is predicted to be.
     *
     * **Notes:**
     * - The API-based scheduled arrival time (apiTime) is used to calculate the overall journey time (t2),
     *   while the timing point value from the UI is used for the final status comparison.
     * - Lower speeds (or a speed near zero) extend t1, making the bus appear behind schedule; higher speeds reduce t1,
     *   showing the bus as ahead.
     * - If the upcoming stop is the first bus stop and additional duration applies, the API time is adjusted accordingly.
     */
    @SuppressLint("LongLogTag")
    fun checkScheduleStatus() {
        binding.scheduleStatusValueTextView.text = "Calculating..."
        // If using mock data and first stop hasn't been passed, show "Please wait..."
        if (activity.viewModel.forceAheadStatus && !activity.viewModel.hasPassedFirstStop) {
            try {
                binding.scheduleStatusValueTextView.text = "Please wait..."
                Log.d("ScheduleStatusManager", "✅ UI updated: Please wait...")
            } catch (e: Exception) {
                FileLogger.e("ScheduleStatusManager", "Error updating 'Please wait' status | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
                activity.runOnUiThread {
                    try {
                        binding.scheduleStatusValueTextView.text = "Please wait..."
                    } catch (e2: Exception) {
                        FileLogger.e("ScheduleStatusManager", "Error in fallback | ${e2.javaClass.simpleName}: ${e2.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e2)}")
                    }
                }
            }
            return
        }

        if (activity.viewModel.scheduleList.isEmpty()) return

        if (activity.viewModel.latitude == 0.0 && activity.viewModel.longitude == 0.0) {
            Log.w("checkScheduleStatus", "Skipping status check: Invalid location (0.0, 0.0)")
            return
        }

        try {
            val scheduledTimeStr = binding.timingPointValueTextView.text.toString()
            val timingPointTime = scheduledTimeStr.parseTimeToday()

            val baseTime = if (activity.viewModel.forceAheadStatus) {
                "22:15:00" // dummy actual-time string
            } else {
                activity.viewModel.scheduleList.first().startTime + ":00"
            }.parseTimeToday()

            var apiTimeStr = binding.ApiTimeValueTextView.text.toString()
            val firstAddress = activity.viewModel.scheduleList.firstOrNull()?.busStops?.firstOrNull()?.address

            if (activity.viewModel.upcomingStopName.value == firstAddress
                && activity.viewModel.currentStopIndex > 0
                && activity.viewModel.currentStopIndex - 1 < activity.viewModel.durationBetweenStops.size
            ) {
                val durationToFirstTimingPoint = activity.viewModel.durationBetweenStops[activity.viewModel.currentStopIndex - 1]
                val firstSchedule = activity.viewModel.scheduleList.first()
                val startTimeParts = firstSchedule.startTime.split(":")
                if (startTimeParts.size != 2) return
                val adjustedCalendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, startTimeParts[0].toInt())
                    set(Calendar.MINUTE, startTimeParts[1].toInt())
                    set(Calendar.SECOND, 0)
                    add(Calendar.SECOND, (durationToFirstTimingPoint * 60).toInt())
                }

                val adjustedApiTime = timeFormat.format(adjustedCalendar.time)
                activity.runOnUiThread {
                    binding.ApiTimeValueTextView.text = adjustedApiTime
                }
                apiTimeStr = adjustedApiTime
            }

            val apiTime = apiTimeStr.parseTimeToday()

            // ✅ Find next stop that is a red timing point
            var redStopIndex = -1
            val stops = activity.viewModel.stops
            val startIdx = activity.viewModel.currentStopIndex
            for (i in startIdx until stops.size) {
                if (activity.viewModel.redBusStops.contains(stops[i].address ?: "")) {
                    redStopIndex = i
                    break
                }
            }

            // 🔁 Fallback: If no red timing point found, use last stop instead
            if (redStopIndex == -1) {
                Log.d("checkScheduleStatus", "⚠️ No red timing point found after index ${activity.viewModel.currentStopIndex}, using final stop as fallback.")
                redStopIndex = activity.viewModel.stops.lastIndex
            }

            val redStop = activity.viewModel.stops[redStopIndex]
            val stopLat = redStop.latitude!!
            val stopLon = redStop.longitude!!

            // --- 1. Distance from current location to red timing point (d1) ---
            val d1 = calculateDistance(activity.viewModel.latitude, activity.viewModel.longitude, stopLat, stopLon)

            // --- 2. Total distance from route start to this red timing point (d2) ---
            val routeSize = activity.viewModel.route.size
            if (cachedRedStopIndex != redStopIndex || cachedRouteSize != routeSize) {
                val upcomingIndex = activity.viewModel.route.indexOfLast {
                    calculateDistance(it.latitude!!, it.longitude!!, stopLat, stopLon) < 30.0
                }.coerceAtLeast(1)
                cachedD2 = (0 until upcomingIndex).sumOf { i ->
                    val p1 = activity.viewModel.route[i]
                    val p2 = activity.viewModel.route[i + 1]
                    calculateDistance(p1.latitude!!, p1.longitude!!, p2.latitude!!, p2.longitude!!)
                }
                cachedRedStopIndex = redStopIndex
                cachedRouteSize = routeSize
            }
            val d2 = cachedD2

            if (d2 == 0.0) {
                Log.e("checkScheduleStatus", "❌ d2 (total distance) is 0. Cannot compute estimated time.")
                return
            }

            // --- 3. Total time from start to red timing point in seconds (t2) ---
            val t2 = ((apiTime.time - baseTime.time) / 1000).toDouble()

            // --- 4. Estimate time to arrival from current position (t1) ---
            // Use actual speed if available and reasonable, otherwise use average speed from schedule
            val minSpeedMps = 0.5  // Minimum speed (0.5 m/s = 1.8 km/h) to avoid division by zero
            val maxSpeedMps = 30.0 // Maximum speed (30 m/s = 108 km/h) for urban bus

            // Calculate average speed from schedule if available
            val avgSpeedFromSchedule = if (d2 > 0 && t2 > 0) {
                (d2 / t2).coerceIn(minSpeedMps, maxSpeedMps)
            } else {
                minSpeedMps
            }

            // Use smoothed speed if available and reasonable, otherwise fall back to schedule average
            val rawSpeedMps = activity.viewModel.smoothedSpeed / 3.6
            val effectiveSpeed = when {
                rawSpeedMps >= minSpeedMps && rawSpeedMps <= maxSpeedMps -> rawSpeedMps
                rawSpeedMps < minSpeedMps -> avgSpeedFromSchedule // Use schedule average if too slow
                else -> avgSpeedFromSchedule.coerceAtMost(maxSpeedMps) // Cap at max if too fast
            }

            val t1 = d1 / effectiveSpeed  // d1 is the distance to the red stop in meters

            val predictedArrival = Calendar.getInstance().apply {
                timeInMillis = activity.getScheduleStatusNowMillis()
                add(Calendar.SECOND, t1.toInt())
            }

            val predictedArrivalStr = timeFormat.format(predictedArrival.time)

            // --- 5. Compare predicted arrival with Timing Point ---
            val deltaSec = ((timingPointTime.time - predictedArrival.time.time) / 1000).toInt()
            lastCategory = categorizeTimingDelta(deltaSec)

            // convert to minutes only (drop seconds)
            val deltaMin = deltaSec / 60               // signed minutes
            val absMin   = abs(deltaMin)   // absolute value

            fun minutesLabel(m: Int) = if (m == 1) "1 min" else "$m min"
            val timeDiff = minutesLabel(absMin)

            // On Time is an exact, symmetric +/-60s window (matches how Google Maps shows
            // live transit status); only outside that window do we fall back to minute labels.
            val statusText = when {
                deltaSec > 30         -> "Early (~$timeDiff early)"
                deltaSec >= -30       -> "On Time"
                deltaSec >= -300      -> "Slightly Behind (~$timeDiff late)"
                else                  -> "Very Behind (~$timeDiff late)"
            }

            val symbolRes = when {
                deltaSec > 30         -> R.drawable.ic_schedule_very_ahead
                deltaSec >= -30       -> R.drawable.ic_schedule_on_time
                deltaSec >= -300      -> R.drawable.ic_schedule_slightly_behind
                else                  -> R.drawable.ic_schedule_very_behind
            }

            val statusColor = when {
                deltaSec > 30         -> ContextCompat.getColor(activity, R.color.blind_red)    // Very Ahead, red
                deltaSec >= -30       -> ContextCompat.getColor(activity, R.color.blind_green)  // On Time, green
                deltaSec >= -300      -> ContextCompat.getColor(activity, R.color.blind_blue)   // Slightly Behind, blue
                else                  -> ContextCompat.getColor(activity, R.color.blind_purple) // Very Behind, purple
            }

            // Update UI directly (already on main thread from MapActivity)
            try {
                // Always use runOnUiThread to ensure we're on the main thread
                activity.runOnUiThread {
                    try {
                        binding.scheduleStatusValueTextView.text = statusText
                        binding.scheduleStatusValueTextView.setTextColor(statusColor)

                        // Try to find icon using findViewById
                        val iconView = activity.findViewById<ImageView>(R.id.scheduleAheadIcon)
                        if (iconView != null) {
                            iconView.setImageResource(symbolRes)
                            iconView.setColorFilter(statusColor)
                        } else {
                            // Fallback: try to access via binding if available
                            try {
                                val bindingIcon = binding.root.findViewById<ImageView>(R.id.scheduleAheadIcon)
                                bindingIcon?.setImageResource(symbolRes)
                                bindingIcon?.setColorFilter(statusColor)
                            } catch (e3: Exception) {
                                FileLogger.e("ScheduleStatusManager", "Error accessing icon | ${e3.javaClass.simpleName}: ${e3.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e3)}")
                            }
                        }
                    } catch (e2: Exception) {
                        FileLogger.e("ScheduleStatusManager", "Error in UI update | ${e2.javaClass.simpleName}: ${e2.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e2)}")
                    }
                }
            } catch (e: Exception) {
                FileLogger.e("ScheduleStatusManager", "Error updating UI | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
            }

            // Store ETA calculation data for logging
            activity.viewModel.latestETAData = mapOf(
                "d1" to d1,
                "d2" to d2,
                "t1" to t1,
                "t2" to t2,
                "effectiveSpeed" to effectiveSpeed,
                "scheduleStatusText" to statusText,
                "timingPointTime" to scheduledTimeStr,
                "predictedArrival" to predictedArrivalStr,
                "deltaSec" to deltaSec
            )

            // Log detailed schedule status every 5 seconds
            LifecycleLogger.logScheduleStatus(
                d1 = d1,
                d2 = d2,
                t1 = t1,
                t2 = t2,
                effectiveSpeed = effectiveSpeed,
                predictedArrival = predictedArrivalStr,
                deltaSec = deltaSec,
                scheduleStatusText = statusText,
                timingPointTime = scheduledTimeStr
            )

            overrideLateStatusForNextSchedule()
        } catch (e: Exception) {
            FileLogger.e("MapActivity checkScheduleStatus", "Error computing schedule status | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
        }
    }

    /**
     * Override the schedule status text with a late-for-next-run message if the difference between
     * the next schedule's start time and the predicted arrival time at the final bus stop (predictedArrivalLastStop)
     * is within the range [-86400, 300] seconds.
     *
     * Calculation details:
     * - First, the predicted arrival at the final bus stop is computed (using variable names ending with LastStop).
     * - Then, the next schedule start time (converted to full HH:mm:ss) is parsed.
     * - The difference deltaNextSec = nextScheduleStartTime - predictedArrival is obtained in seconds.
     * - If deltaNextSec is negative, then the override value is computed as (-deltaNextSec) + 300.
     * - The status text is then overridden with:
     *      "Late for next run by <overrideValue>s"
     */
    @SuppressLint("LongLogTag")
    private fun overrideLateStatusForNextSchedule() {
        val logTag = "TestMapActivity checkScheduleStatus"

        val t1 = activity.viewModel.getExpectedDurationForNextSchedule() ?: return

        val deltaNextSec = activity.viewModel.scheduleData.getDeltaNextSec(
            t1,
            activity.getScheduleStatusNowMillis()
        ) ?: return

        if (deltaNextSec in -86400..300) {
            val overrideValue = if (deltaNextSec < 0) (-deltaNextSec) + 300 else deltaNextSec

            // Format time as "xx mins" only (no seconds) if >= 60 seconds
            val overrideStatusText = if (overrideValue >= 60) {
                val mins = overrideValue / 60
                "Late for next run by $mins mins"
            } else {
                "Late for next run by ${overrideValue}s"
            }

            // Format log message with "xx mins" only (no seconds)
            val logFormattedValue = if (overrideValue >= 60) {
                val mins = overrideValue / 60
                "$mins mins"
            } else {
                "${overrideValue}s"
            }

            activity.runOnUiThread {
                try {
                    binding.scheduleStatusValueTextView.text = overrideStatusText
                    binding.scheduleStatusValueTextView.setTextColor(
                        ContextCompat.getColor(activity,
                            R.color.blind_red
                        ))
                    val iconView = activity.findViewById<ImageView>(R.id.scheduleAheadIcon)
                    if (iconView != null) {
                        iconView.setImageResource(R.drawable.ic_schedule_late)
                        iconView.setColorFilter(ContextCompat.getColor(activity, R.color.blind_red))
                        Log.d(logTag, "✅ Overridden status: \"$overrideStatusText\" ($logFormattedValue)")
                    } else {
                        Log.w(logTag, "⚠️ scheduleAheadIcon not found when updating override status")
                    }
                } catch (e: Exception) {
                    FileLogger.e(logTag, "Error updating override status | ${e.javaClass.simpleName}: ${e.message} | ${TripStateSnapshot.describe()}\n${Log.getStackTraceString(e)}")
                }
            }
        } else {
            Log.d(logTag, "Delta not within override range; no status override applied.")
        }
    }
}
