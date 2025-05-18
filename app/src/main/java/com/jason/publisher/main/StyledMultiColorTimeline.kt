package com.jason.publisher.main

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.jason.publisher.R
import com.jason.publisher.main.model.BusScheduleInfo
import com.jason.publisher.main.model.ScheduleItem
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
    private var allScheduleItems: List<ScheduleItem> = emptyList()

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
                    val firstBusStopAbbreviation = allScheduleItems.getOrNull(i)?.busStops?.firstOrNull()?.abbreviation ?: "?"

                    val repBox = LinearLayout(context).apply {
                        orientation = VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(12, 8, 12, 8)
                        background = ContextCompat.getDrawable(context, R.drawable.rep_vertical_style)

                        val repLabel = TextView(context).apply {
                            text = "REP"
                            setTextColor(Color.WHITE)
                            textSize = 16f
                            gravity = Gravity.CENTER
                        }

                        val stopAbbrev = TextView(context).apply {
                            text = firstBusStopAbbreviation
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            gravity = Gravity.CENTER
                        }

                        addView(repLabel)
                        addView(stopAbbrev)
                    }

                    repBox.layoutParams = LayoutParams(
                        (64 * context.resources.displayMetrics.density).toInt(), // fixed width in dp
                        LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = if (i > 0) 8 else 0
                    }
                    repBox
                }

                isBreak -> {
                    val firstBusStopAbbreviation = allScheduleItems.getOrNull(i)?.busStops?.firstOrNull()?.abbreviation ?: "?"
                    val box = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(8, 8, 8, 8)
                        background = ContextCompat.getDrawable(context, R.drawable.break_horizontal_style)

                        val icon = ImageView(context).apply {
                            setImageResource(R.drawable.cup_break)
                            layoutParams = LinearLayout.LayoutParams(32, 32)
                        }

                        val abbrev = TextView(context).apply {
                            text = "→ $firstBusStopAbbreviation"
                            setTextColor(Color.WHITE)
                            textSize = 16f
                            gravity = Gravity.CENTER
                            setPadding(8, 0, 0, 0)
                        }

                        addView(icon)
                        addView(abbrev)
                    }
                    box
                }

                else -> {
                    val firstBusStopAbbreviation = allScheduleItems.getOrNull(i)?.busStops?.firstOrNull()?.abbreviation ?: "?"
                    val displayText = if (isRep) "REP → $firstBusStopAbbreviation" else "$dutyName → $firstBusStopAbbreviation"

                    LinearLayout(context).apply {
                        orientation = VERTICAL
                        gravity = Gravity.CENTER
                        background = ContextCompat.getDrawable(context,
                            if (isRep) R.drawable.rep_vertical_style else R.drawable.route_rounded_style)
                        setPadding(12, 8, 12, 8)

                        val label = TextView(context).apply {
                            text = displayText
                            setTextColor(Color.WHITE)
                            textSize = 16f
                            gravity = Gravity.CENTER
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }

                        addView(label)
                        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weight).apply {
                            marginStart = if (i > 0) 8 else 0
                        }
                    }
                }
            }

            addView(dutyView)

            // Add break after this duty
            if (i < workIntervals.size - 1) {
                val breakDuration = breakDurations[i]
                val breakWeight = breakDuration.toFloat() / totalDuration
                val previousRouteIndex = i
                val lastBusStopAbbreviation = allScheduleItems
                    .getOrNull(previousRouteIndex)
                    ?.busStops
                    ?.lastOrNull()
                    ?.abbreviation ?: "?"

                val restBox = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.break_rounded_style)
                    setPadding(1, 1, 1, 1)

                    val thinWidthPx = (48 * context.resources.displayMetrics.density).toInt()
                    layoutParams = LayoutParams(thinWidthPx, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = 6 // slightly tighter margin
                    }
                }

                val busIcon = ImageView(context).apply {
                    setImageResource(R.drawable.bus_timeline)
                    layoutParams = LinearLayout.LayoutParams(32, 32).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                }

                val abbrevText = TextView(context).apply {
                    text = lastBusStopAbbreviation
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    isSingleLine = true
                    setPadding(8, 0, 8, 0) // ← wider padding

                    // ✅ Increase min width slightly to fit 3-letter abbreviations comfortably
                    val minWidthPx = (60 * context.resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        width = minWidthPx
                    }
                }

                restBox.addView(busIcon)
                restBox.addView(abbrevText)
                addView(restBox)
            }
        }
    }

    fun setScheduleData(scheduleItems: List<ScheduleItem>) {
        this.allScheduleItems = scheduleItems
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
