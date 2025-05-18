package com.jason.publisher.main

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.jason.publisher.R
import com.jason.publisher.main.model.BusScheduleInfo
import java.text.SimpleDateFormat
import java.util.*

/**
 * A custom LinearLayout that visually renders work intervals (duties) and breaks
 * as a horizontal, color-coded timeline. Each segment scales proportionally
 * based on the duration in minutes.
 */
class StyledMultiColorTimeline @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var workIntervals: List<Pair<String, String>> = emptyList()
    private var dutyNames: List<String> = emptyList()
    private var busStops: List<BusScheduleInfo> = emptyList()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16, 16, 16, 16)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun setBusStops(busStops: List<BusScheduleInfo>) {
        this.busStops = busStops
        renderTimeline() // Redraw with markers
    }

    /**
     * Sets the timeline data and triggers rendering.
     *
     * @param intervals List of time intervals (start, end) in HH:mm format.
     * @param dutyNames Corresponding labels to display in each work segment.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun setTimelineData(intervals: List<Pair<String, String>>, dutyNames: List<String>) {
        this.workIntervals = intervals
        this.dutyNames = dutyNames
        renderTimeline()
    }

    /**
     * Renders the timeline by:
     * - Calculating duration-based weights for each duty and break.
     * - Creating TextViews for duties with route styles.
     * - Inserting break segments between duties with break styles and delta text.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun renderTimeline() {
        removeAllViews()

        if (workIntervals.isEmpty()) return

        // Calculate duration in minutes for duties and breaks
        val segmentDurations = mutableListOf<Long>()
        val breakDurations = mutableListOf<Long>()

        for (i in workIntervals.indices) {
            segmentDurations.add(calculateDurationInMinutes(workIntervals[i].first, workIntervals[i].second))
            if (i < workIntervals.size - 1) {
                breakDurations.add(calculateDurationInMinutes(workIntervals[i].second, workIntervals[i + 1].first))
            }
        }

        val totalDuration = segmentDurations.sum() + breakDurations.sum()

        // Add views proportionally
        for (i in workIntervals.indices) {
            val workDuration = segmentDurations[i]
            val weight = workDuration.toFloat() / totalDuration

            // Create the work (duty) box
            val dutyName = dutyNames.getOrNull(i) ?: "Duty"
            val isRep = dutyName.equals("REP", ignoreCase = true)
            val isBreak = dutyName.equals("Break", ignoreCase = true)

            val dutyView: View = when {
                isRep -> {
                    val box = LinearLayout(context).apply {
                        orientation = VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(8, 8, 8, 8)
                        background = ContextCompat.getDrawable(context, R.drawable.rep_vertical_style)

                        val repText = TextView(context).apply {
                            text = "R\nE\nP"
                            setTextColor(Color.WHITE)
                            textSize = 16f
                            gravity = Gravity.CENTER
                        }

                        addView(repText)
                    }

                    val fixedWidthPx = (40 * context.resources.displayMetrics.density).toInt()
                    box.layoutParams = LayoutParams(fixedWidthPx, LayoutParams.MATCH_PARENT).apply {
                        marginStart = if (i > 0) 8 else 0
                    }

                    box
                }

                isBreak -> {
                    val box = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(8, 8, 8, 8)
                        background = ContextCompat.getDrawable(context, R.drawable.break_horizontal_style)

                        val icon = ImageView(context).apply {
                            setImageResource(R.drawable.cup_break)
                            layoutParams = LinearLayout.LayoutParams(32, 32)
                        }

                        addView(icon)
                    }

                    val fixedWidthPx = (120 * context.resources.displayMetrics.density).toInt()
                    box.layoutParams = LayoutParams(fixedWidthPx, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = if (i > 0) 8 else 0
                    }

                    box
                }

                else -> {
                    TextView(context).apply {
                        text = dutyName
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        gravity = Gravity.CENTER
                        setPadding(24, 12, 24, 12)
                        background = ContextCompat.getDrawable(context, R.drawable.route_rounded_style)

                        val params = LayoutParams(0, LayoutParams.WRAP_CONTENT, weight)
                        params.marginStart = if (i > 0) 8 else 0
                        layoutParams = params
                    }
                }
            }

            addView(dutyView)

            // Add break after this duty
            if (i < workIntervals.size - 1) {
                val breakDuration = breakDurations[i]
                val breakWeight = breakDuration.toFloat() / totalDuration
                val delta = "${breakDuration} min"

                val restBox = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.break_rounded_style)
                    setPadding(8, 8, 8, 8)

                    val minPx = (40 * context.resources.displayMetrics.density).toInt()
                    val breakWidthPx = (breakWeight * width).toInt().coerceAtLeast(minPx)
                    layoutParams = LayoutParams(breakWidthPx, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = 8
                    }
                }

                val busIcon = ImageView(context).apply {
                    setImageResource(R.drawable.bus_timeline)
                    layoutParams = LinearLayout.LayoutParams(32, 32).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                }

                val deltaText = TextView(context).apply {
                    text = delta
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                }

                restBox.addView(busIcon)
                restBox.addView(deltaText)
                addView(restBox)
            }
        }

        // Insert bus stop markers dynamically along the timeline
        if (busStops.isNotEmpty()) {
            val totalStart = convertToMinutes(workIntervals.first().first)
            val totalEnd = convertToMinutes(workIntervals.last().second)

            for (stop in busStops) {
                val stopMinute = convertToMinutes(stop.time)
                val relativePosition = (stopMinute - totalStart).toFloat() / (totalEnd - totalStart)

                val marker = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                }

                val abbrev = TextView(context).apply {
                    text = stop.abbreviation
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                }

                val delta = TextView(context).apply {
                    val mins = stopMinute - totalStart
                    text = if (mins >= 60) "${mins / 60}:${String.format("%02d", mins % 60)}" else "$mins"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                }

                marker.addView(abbrev)
                marker.addView(delta)

                val insertionIndex = (childCount * relativePosition).toInt().coerceIn(0, childCount)
                addView(marker, insertionIndex)
            }
        }
    }

    private fun convertToMinutes(time: String): Int {
        val parts = time.split(":").map { it.toInt() }
        return parts[0] * 60 + parts[1]
    }

    /**
     * Calculates the duration between two times in minutes.
     * Used for scaling weight of timeline segments.
     *
     * @param start Start time in "HH:mm".
     * @param end End time in "HH:mm".
     * @return Duration in minutes.
     */
    private fun calculateDurationInMinutes(start: String, end: String): Long {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return try {
            val s = fmt.parse(start)
            val e = fmt.parse(end)
            (e.time - s.time) / 60000
        } catch (e: Exception) {
            30L // fallback duration
        }
    }

    /**
     * Returns a formatted delta time string between two times.
     *
     * @param start Start time in "HH:mm".
     * @param end End time in "HH:mm".
     * @return A string like "15 min" or "?" if parsing fails.
     */
    private fun getDeltaTime(start: String, end: String): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return try {
            val s = fmt.parse(start)
            val e = fmt.parse(end)
            val diff = (e.time - s.time) / 60000
            "${diff} min"
        } catch (e: Exception) {
            "?"
        }
    }
}
