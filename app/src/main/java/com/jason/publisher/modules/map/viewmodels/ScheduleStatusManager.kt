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
import com.jason.publisher.main.utils.ScheduleAdherence
import com.jason.publisher.main.utils.ScheduleState
import com.jason.publisher.main.utils.calculateScheduleStatus
import com.jason.publisher.main.utils.estimateEffectiveSpeed
import com.jason.publisher.main.utils.nextRunAfterActive
import com.jason.publisher.main.utils.nextRunSlackSeconds
import com.jason.publisher.main.utils.resolveTimeOfDay
import com.jason.publisher.modules.map.activities.MapActivity
import com.jason.publisher.modules.map.utils.calculateDistance
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScheduleStatusManager(
    private val activity: MapActivity,
    private val binding: ActivityMapBinding
) {

    enum class StopPassCategory {
        ON_TIME,     // green
        EARLY,       // red (timing points only)
        LATE_5,      // blue  (Slightly Behind)
        LATE_10,     // purple(Very Behind)
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
    private var cachedRedRouteIndex: Int = -1

    /** Last shared status state; used for hysteresis so GPS noise does not flip the status. */
    private var lastState: ScheduleState? = null

    // Zone/marker category is derived from the same shared state as the text, icon and color.
    private fun categoryFor(state: ScheduleState): StopPassCategory = when (state) {
        ScheduleState.AHEAD           -> StopPassCategory.EARLY
        ScheduleState.ON_TIME         -> StopPassCategory.ON_TIME
        ScheduleState.SLIGHTLY_BEHIND -> StopPassCategory.LATE_5
        ScheduleState.VERY_BEHIND     -> StopPassCategory.LATE_10
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
            // One clock for every value in this calculation.
            val nowMillis = activity.getScheduleStatusNowMillis()
            val scheduledTimeStr = binding.timingPointValueTextView.text.toString()
            val timingPointMillis = resolveTimeOfDay(scheduledTimeStr, nowMillis) ?: run {
                Log.w("checkScheduleStatus", "Skipping status check: invalid timing point '$scheduledTimeStr'")
                return
            }

            val baseTimeStr = if (activity.viewModel.forceAheadStatus) {
                "22:15:00" // dummy actual-time string
            } else {
                activity.viewModel.scheduleList.first().startTime
            }
            val baseMillis = resolveTimeOfDay(baseTimeStr, nowMillis) ?: run {
                Log.w("checkScheduleStatus", "Skipping status check: invalid trip start '$baseTimeStr'")
                return
            }

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

            // Resolved against the trip start so a trip crossing midnight keeps a positive t2.
            val apiMillis = resolveTimeOfDay(apiTimeStr, baseMillis) ?: run {
                Log.w("checkScheduleStatus", "Skipping status check: invalid API time '$apiTimeStr'")
                return
            }

            if (activity.viewModel.stops.isEmpty()) return

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

            val redStop = activity.viewModel.stops.getOrNull(redStopIndex) ?: return
            val stopLat = redStop.latitude ?: return
            val stopLon = redStop.longitude ?: return

            // Straight-line distance; only a fallback, it under/over-estimates on winding routes.
            val straightLineM = calculateDistance(activity.viewModel.latitude, activity.viewModel.longitude, stopLat, stopLon)

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
                cachedRedRouteIndex = upcomingIndex
            }
            val d2 = cachedD2

            // --- 1. Remaining distance to the red timing point along the route polyline (d1) ---
            val route = activity.viewModel.route
            val nearestIdx = activity.viewModel.findNearestBusRoutePoint(activity.viewModel.latitude, activity.viewModel.longitude)
            val d1 = if (nearestIdx < cachedRedRouteIndex && cachedRedRouteIndex <= route.lastIndex) {
                val next = route[nearestIdx + 1]
                calculateDistance(activity.viewModel.latitude, activity.viewModel.longitude, next.latitude!!, next.longitude!!) +
                        (nearestIdx + 1 until cachedRedRouteIndex).sumOf { i ->
                            val p1 = route[i]
                            val p2 = route[i + 1]
                            calculateDistance(p1.latitude!!, p1.longitude!!, p2.latitude!!, p2.longitude!!)
                        }
            } else {
                straightLineM
            }

            if (d2 == 0.0) {
                Log.e("checkScheduleStatus", "❌ d2 (total distance) is 0. Cannot compute estimated time.")
                return
            }

            // --- 3. Scheduled time from trip start to this red timing point (t2), matching d2 ---
            val t2 = ((timingPointMillis - baseMillis) / 1000).toDouble()
                .takeIf { it > 0 } ?: ((apiMillis - baseMillis) / 1000).toDouble()

            // --- 4. Estimate time to arrival from current position (t1) ---
            val rawSpeedKmh = activity.viewModel.smoothedSpeed.toDouble()
            val speed = estimateEffectiveSpeed(rawSpeedKmh, if (d2 > 0 && t2 > 0) d2 / t2 else null)
            val effectiveSpeed = speed.mps
            val t1 = d1 / effectiveSpeed  // d1 is the distance to the red stop in meters

            val predictedArrivalMillis = nowMillis + (t1 * 1000).toLong()
            val predictedArrivalStr = timeFormat.format(predictedArrivalMillis)

            // --- 5. Compare predicted arrival with Timing Point (shared thresholds) ---
            val status = calculateScheduleStatus(timingPointMillis, predictedArrivalMillis, lastState)
            lastState = status.state
            lastCategory = categoryFor(status.state)
            val deltaSec = status.deltaSeconds.toInt()
            val statusText = status.displayText

            val symbolRes = when (status.state) {
                ScheduleState.AHEAD           -> R.drawable.ic_schedule_very_ahead
                ScheduleState.ON_TIME         -> R.drawable.ic_schedule_on_time
                ScheduleState.SLIGHTLY_BEHIND -> R.drawable.ic_schedule_slightly_behind
                ScheduleState.VERY_BEHIND     -> R.drawable.ic_schedule_very_behind
            }

            val statusColor = ContextCompat.getColor(activity, when (status.state) {
                ScheduleState.AHEAD           -> R.color.blind_red
                ScheduleState.ON_TIME         -> R.color.blind_green
                ScheduleState.SLIGHTLY_BEHIND -> R.color.blind_blue
                ScheduleState.VERY_BEHIND     -> R.color.blind_purple
            })

            Log.d("ScheduleAdherence",
                "timingPoint=${redStop.address} scheduled=$scheduledTimeStr now=${timeFormat.format(nowMillis)} " +
                        "lat=${activity.viewModel.latitude} lon=${activity.viewModel.longitude} " +
                        "distanceRemainingM=${d1.toInt()} straightLineM=${straightLineM.toInt()} routeDistanceM=${d2.toInt()} scheduledTravelSec=${t2.toLong()} " +
                        "smoothedSpeedKmh=$rawSpeedKmh effectiveSpeedKmh=${"%.1f".format(effectiveSpeed * 3.6)} " +
                        "speedSource=${speed.source} predictedArrival=$predictedArrivalStr deltaSec=$deltaSec status=${status.state}")

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
                "speedSource" to speed.source,
                "scheduleState" to status.state.name,
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
     * leaves less than [ScheduleAdherence.NEXT_RUN_MIN_LAYOVER_SECONDS] of layover.
     *
     * Calculation details:
     * - First, the predicted arrival at the final bus stop is computed (using variable names ending with LastStop).
     * - Then, the next schedule start time (converted to full HH:mm:ss) is parsed.
     * - The difference deltaNextSec = nextScheduleStartTime - predictedArrival is obtained in seconds.
     * - The override value is the layover shortfall: NEXT_RUN_MIN_LAYOVER_SECONDS - deltaNextSec.
     * - The status text is then overridden with:
     *      "Late for next run by <overrideValue>s"
     */
    @SuppressLint("LongLogTag")
    private fun overrideLateStatusForNextSchedule() {
        val logTag = "TestMapActivity checkScheduleStatus"

        val t1 = activity.viewModel.getExpectedDurationForNextSchedule() ?: return

        // Explicit next run (FULL_SCHEDULE_DATA may or may not still contain the active item).
        val nextRun = nextRunAfterActive(
            activity.viewModel.scheduleList.firstOrNull(),
            activity.viewModel.scheduleData
        ) ?: return

        // Implausible values (stale roster / wrong day) come back null and are never displayed.
        val deltaNextSec = nextRunSlackSeconds(
            nextRun.startTime,
            t1,
            activity.getScheduleStatusNowMillis()
        )?.toInt() ?: return

        val requiredSlack = ScheduleAdherence.NEXT_RUN_MIN_LAYOVER_SECONDS.toInt()
        if (deltaNextSec < requiredSlack) {
            // How far short of the minimum layover the driver will be.
            val overrideValue = requiredSlack - deltaNextSec

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