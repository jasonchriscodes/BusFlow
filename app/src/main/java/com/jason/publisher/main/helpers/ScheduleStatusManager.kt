// ScheduleStatusManager.kt
package com.jason.publisher.main.helpers

import android.annotation.SuppressLint
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.jason.publisher.R
import com.jason.publisher.databinding.ActivityMapBinding
import com.jason.publisher.main.activity.MapActivity
import com.jason.publisher.main.utils.FileLogger
import java.text.SimpleDateFormat
import java.util.*

class ScheduleStatusManager(
    private val activity: MapActivity,
    private val binding: ActivityMapBinding
) {
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
        // If using mock data and first stop hasn't been passed, show "Please wait..."
        if (activity.forceAheadStatus && !activity.hasPassedFirstStop) {
            try {
                binding.scheduleStatusValueTextView.text = "Please wait..."
                Log.d("ScheduleStatusManager", "✅ UI updated: Please wait...")
            } catch (e: Exception) {
                Log.e("ScheduleStatusManager", "Error updating 'Please wait' status: ${e.message}", e)
                activity.runOnUiThread {
                    try {
                        binding.scheduleStatusValueTextView.text = "Please wait..."
                    } catch (e2: Exception) {
                        Log.e("ScheduleStatusManager", "Error in fallback: ${e2.message}", e2)
                    }
                }
            }
            return
        }

        if (activity.scheduleList.isEmpty()) return

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        if (activity.latitude == 0.0 && activity.longitude == 0.0) {
            Log.w("checkScheduleStatus", "Skipping status check: Invalid location (0.0, 0.0)")
            return
        }

        try {
            val scheduledTimeStr = binding.timingPointValueTextView.text.toString()
            val timingPointTime = activity.timeManager.parseTimeToday(scheduledTimeStr)

            if (activity.forceAheadStatus) {
                activity.baseTimeStr = activity.customTime
                // ✅ OPTIMIZED: Removed verbose logging
            } else {
                activity.baseTimeStr = activity.scheduleList.first().startTime + ":00"
                // ✅ OPTIMIZED: Removed verbose logging
            }
            val baseTime = activity.timeManager.parseTimeToday(activity.baseTimeStr)

            var apiTimeStr = binding.ApiTimeValueTextView.text.toString()
            val firstAddress = activity.scheduleList.firstOrNull()?.busStops?.firstOrNull()?.address

            if (activity.upcomingStopName == firstAddress
                && activity.currentStopIndex > 0
                && activity.currentStopIndex - 1 < activity.durationBetweenStops.size
            ) {
                val durationToFirstTimingPoint = activity.durationBetweenStops[activity.currentStopIndex - 1]
                val firstSchedule = activity.scheduleList.first()
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

            val apiTime = activity.timeManager.parseTimeToday(apiTimeStr)

            // ✅ Find next stop that is a red timing point
            var redStopIndex = activity.stops.indexOfFirst { stop ->
                activity.redBusStops.contains(stop.address ?: "") &&
                        activity.stops.indexOf(stop) >= activity.currentStopIndex
            }

            // 🔁 Fallback: If no red timing point found, use last stop instead
            if (redStopIndex == -1) {
                Log.d("checkScheduleStatus", "⚠️ No red timing point found after index ${activity.currentStopIndex}, using final stop as fallback.")
                redStopIndex = activity.stops.lastIndex
            }

            val redStop = activity.stops[redStopIndex]
            val stopLat = redStop.latitude!!
            val stopLon = redStop.longitude!!

            // --- 1. Distance from current location to red timing point (d1) ---
            val d1 = activity.mapController.calculateDistance(activity.latitude, activity.longitude, stopLat, stopLon)

            // --- 2. Total distance from route start to this red timing point (d2) ---
            val upcomingIndex = activity.route.indexOfLast {
                activity.mapController.calculateDistance(it.latitude!!, it.longitude!!, stopLat, stopLon) < 30.0
            }.coerceAtLeast(1)

            val d2 = (0 until upcomingIndex).sumOf { i ->
                val p1 = activity.route[i]
                val p2 = activity.route[i + 1]
                activity.mapController.calculateDistance(p1.latitude!!, p1.longitude!!, p2.latitude!!, p2.longitude!!)
            }

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
            val rawSpeedMps = activity.smoothedSpeed / 3.6
            val effectiveSpeed = when {
                rawSpeedMps >= minSpeedMps && rawSpeedMps <= maxSpeedMps -> rawSpeedMps
                rawSpeedMps < minSpeedMps -> avgSpeedFromSchedule // Use schedule average if too slow
                else -> avgSpeedFromSchedule.coerceAtMost(maxSpeedMps) // Cap at max if too fast
            }

            val t1 = d1 / effectiveSpeed  // d1 is the distance to the red stop in meters

            val predictedArrival = Calendar.getInstance().apply {
                time = activity.timeManager.simulatedStartTime.time
                add(Calendar.SECOND, t1.toInt())
            }

            val predictedArrivalStr = timeFormat.format(predictedArrival.time)
            val actualTimeStr       = timeFormat.format(activity.timeManager.simulatedStartTime.time)

            // --- 5. Compare predicted arrival with Timing Point ---
            val deltaSec = ((timingPointTime.time - predictedArrival.time.time) / 1000).toInt()

            // convert to minutes only (drop seconds)
            val deltaMin = deltaSec / 60               // signed minutes
            val absMin   = kotlin.math.abs(deltaMin)   // absolute value

            fun minutesLabel(m: Int) = if (m == 1) "1 min" else "$m min"
            val timeDiff = minutesLabel(absMin)

            val statusText = when {
                deltaMin >= 2    -> "Very Ahead (~$timeDiff early)"
                deltaMin == 1    -> "Slightly Ahead (~$timeDiff early)"
                deltaMin in -2..0-> "On Time (~$timeDiff on time)"
                deltaMin in -4..-3 -> "Slightly Behind (~$timeDiff late)"
                deltaMin <= -5   -> "Very Behind (~$timeDiff late)"
                else             -> "Unknown"
            }

            val symbolRes = when {
                deltaMin >= 2       -> R.drawable.ic_schedule_very_ahead
                deltaMin == 1       -> R.drawable.ic_schedule_slightly_ahead
                deltaMin in -2..0   -> R.drawable.ic_schedule_on_time
                deltaMin in -4..-3  -> R.drawable.ic_schedule_slightly_behind
                deltaMin <= -5      -> R.drawable.ic_schedule_very_behind
                else                -> R.drawable.ic_schedule_on_time
            }

            val colorRes = when {
                deltaMin >= 2       -> R.color.blind_red            // Very Ahead
                deltaMin == 1       -> R.color.blind_light_orange   // Slightly Ahead
                deltaMin in -2..0   -> R.color.blind_cyan           // On Time
                deltaMin in -4..-3  -> R.color.blind_orange         // Slightly Behind
                deltaMin <= -5      -> R.color.blind_orange         // Very Behind
                else                -> R.color.blind_cyan
            }

            // Update UI directly (already on main thread from MapActivity)
            try {
                // Always use runOnUiThread to ensure we're on the main thread
                activity.runOnUiThread {
                    try {
                        binding.scheduleStatusValueTextView.text = statusText
                        binding.scheduleStatusValueTextView.setTextColor(ContextCompat.getColor(activity, colorRes))

                        // Try to find icon using findViewById
                        val iconView = activity.findViewById<ImageView>(R.id.scheduleAheadIcon)
                        if (iconView != null) {
                            iconView.setImageResource(symbolRes)
                        } else {
                            // Fallback: try to access via binding if available
                            try {
                                val bindingIcon = binding.root.findViewById<ImageView>(R.id.scheduleAheadIcon)
                                if (bindingIcon != null) {
                                    bindingIcon.setImageResource(symbolRes)
                                }
                            } catch (e3: Exception) {
                                Log.e("ScheduleStatusManager", "Error accessing icon: ${e3.message}", e3)
                            }
                        }
                    } catch (e2: Exception) {
                        Log.e("ScheduleStatusManager", "Error in UI update: ${e2.message}", e2)
                        e2.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                Log.e("ScheduleStatusManager", "Error updating UI: ${e.message}", e)
                e.printStackTrace()
            }

            // ✅ OPTIMIZED: Simplified logging - only log essential status info
            // Detailed debug info removed to reduce log spam
            // Status changes are tracked via LifecycleLogger in MapActivity

            overrideLateStatusForNextSchedule()
        } catch (e: Exception) {
            Log.e("MapActivity checkScheduleStatus", "Error: ${e.localizedMessage}")
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
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val scheduledTimeForFinalStopStr = activity.scheduleList.first().endTime + ":00"
        val finalStopScheduledTime = activity.timeManager.parseTimeToday(scheduledTimeForFinalStopStr)

        val baseTimeStr = activity.scheduleList.first().startTime + ":00"
        val baseTime = activity.timeManager.parseTimeToday(baseTimeStr)

        val finalStop = activity.stops.last()
        val stopLat = finalStop.latitude!!
        val stopLon = finalStop.longitude!!

        val d1 = activity.mapController.calculateDistance(activity.latitude, activity.longitude, stopLat, stopLon)

        val finalStopRouteIndex = activity.route.indexOfLast {
            activity.mapController.calculateDistance(it.latitude!!, it.longitude!!, stopLat, stopLon) < 30.0
        }.coerceAtLeast(1)
        val d2 = (0 until finalStopRouteIndex).sumOf { i ->
            val p1 = activity.route[i]
            val p2 = activity.route[i + 1]
            activity.mapController.calculateDistance(p1.latitude!!, p1.longitude!!, p2.latitude!!, p2.longitude!!)
        }
        if (d2 == 0.0) {
            Log.e(logTag, "Total route distance is zero; cannot compute predicted arrival.")
            return
        }

        val t2 = ((finalStopScheduledTime.time - baseTime.time) / 1000).toDouble()

        // Use smoothed speed with fallback to schedule average
        val minSpeedMps = 0.5
        val maxSpeedMps = 30.0
        val avgSpeedFromSchedule = if (d2 > 0 && t2 > 0) {
            (d2 / t2).coerceIn(minSpeedMps, maxSpeedMps)
        } else {
            minSpeedMps
        }

        val rawSpeedMps = activity.smoothedSpeed / 3.6
        val speedMetersPerSec = when {
            rawSpeedMps >= minSpeedMps && rawSpeedMps <= maxSpeedMps -> rawSpeedMps
            rawSpeedMps < minSpeedMps -> avgSpeedFromSchedule
            else -> avgSpeedFromSchedule.coerceAtMost(maxSpeedMps)
        }

        val t1 = d1 / speedMetersPerSec

        val predictedArrival = Calendar.getInstance().apply {
            time = activity.timeManager.simulatedStartTime.time
            add(Calendar.SECOND, t1.toInt())
        }

        val predictedArrivalLastStop = timeFormat.format(predictedArrival.time)

        val nextScheduleStartRaw = activity.timeManager.getNextScheduleStartTime() ?: return
        val nextScheduleStartStr = nextScheduleStartRaw + ":00"
        val nextScheduleStartTime = activity.timeManager.parseTimeToday(nextScheduleStartStr)

        val deltaNextSec = ((nextScheduleStartTime.time - predictedArrival.time.time) / 1000).toInt()
        // ✅ OPTIMIZED: Removed all verbose logging - only log when status is overridden

        if (deltaNextSec in -86400..300) {
            val overrideValue = if (deltaNextSec < 0) (-deltaNextSec) + 300 else deltaNextSec

            // ✅ FIX: Format time as "xx mins" only (no seconds) if >= 60 seconds
            val overrideStatusText = if (overrideValue >= 60) {
                val mins = overrideValue / 60
                "Late for next run by $mins mins"
            } else {
                "Late for next run by ${overrideValue}s"
            }

            // ✅ FIX: Format log message with "xx mins" only (no seconds)
            val logFormattedValue = if (overrideValue >= 60) {
                val mins = overrideValue / 60
                "$mins mins"
            } else {
                "${overrideValue}s"
            }

            activity.runOnUiThread {
                try {
                    binding.scheduleStatusValueTextView.text = overrideStatusText
                    binding.scheduleStatusValueTextView.setTextColor(ContextCompat.getColor(activity,
                        R.color.blind_red
                    ))
                    val iconView = activity.findViewById<ImageView>(R.id.scheduleAheadIcon)
                    if (iconView != null) {
                        iconView.setImageResource(R.drawable.ic_schedule_late)
                        Log.d(logTag, "✅ Overridden status: \"$overrideStatusText\" ($logFormattedValue)")
                    } else {
                        Log.w(logTag, "⚠️ scheduleAheadIcon not found when updating override status")
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "Error updating override status: ${e.message}", e)
                    e.printStackTrace()
                }
            }
        } else {
            Log.d(logTag, "Delta not within override range; no status override applied.")
        }
    }
}