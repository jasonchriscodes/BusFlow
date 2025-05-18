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
    private val repBoxWidthPx = (80 * context.resources.displayMetrics.density).toInt()
    private val dutyBoxMinWidthPx = (100 * context.resources.displayMetrics.density).toInt()

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

        val segmentDurations = mutableListOf<Long>()
        val breakDurations = mutableListOf<Long>()

        for (i in workIntervals.indices) {
            segmentDurations.add(calculateDurationInMinutes(workIntervals[i].first, workIntervals[i].second))
            if (i < workIntervals.size - 1) {
                breakDurations.add(calculateDurationInMinutes(workIntervals[i].second, workIntervals[i + 1].first))
            }
        }

        val totalDuration = segmentDurations.sum() + breakDurations.sum()

        for (i in workIntervals.indices) {
            val item = allScheduleItems.getOrNull(i)
            val dutyName = dutyNames.getOrNull(i) ?: "Duty"
            val isRep = dutyName.equals("REP", ignoreCase = true)
            val isBreak = dutyName.equals("Break", ignoreCase = true)
            val startTime = workIntervals[i].first
            val firstStopAbbr = item?.busStops?.firstOrNull()?.abbreviation ?: "?"

            val box = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                setPadding(12, 8, 12, 8)
                background = ContextCompat.getDrawable(context,
                    when {
                        isRep -> R.drawable.rep_vertical_style
                        isBreak -> R.drawable.break_horizontal_style
                        else -> R.drawable.route_rounded_style
                    }
                )

                val timeLabel = TextView(context).apply {
                    text = startTime
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                }

                addView(timeLabel)

                if (isRep) {
                    val repLabel = TextView(context).apply {
                        text = "REP"
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        gravity = Gravity.CENTER
                    }

                    val stopLabel = TextView(context).apply {
                        text = firstStopAbbr
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        gravity = Gravity.CENTER
                    }

                    addView(repLabel)
                    addView(stopLabel)

                } else if (isBreak) {
                    val icon = ImageView(context).apply {
                        setImageResource(R.drawable.cup_break)
                        layoutParams = LinearLayout.LayoutParams(32, 32).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
                    }

                    val stopLabel = TextView(context).apply {
                        text = firstStopAbbr
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        gravity = Gravity.CENTER
                    }

                    addView(icon)
                    addView(stopLabel)

                } else {
                    val label = TextView(context).apply {
                        text = "$dutyName → $firstStopAbbr"
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }

                    addView(label)
                }
            }

            val fixedBoxWidth = (64 * context.resources.displayMetrics.density).toInt()
            box.layoutParams = LayoutParams(
                if (isRep || isBreak) fixedBoxWidth else 0,
                LayoutParams.WRAP_CONTENT,
                if (isRep || isBreak) 0f else segmentDurations[i].toFloat() / totalDuration
            ).apply {
                marginStart = if (i > 0) 8 else 0
            }

            addView(box)

            if (i < workIntervals.size - 1) {
                val lastStopOfCurrent = item?.busStops?.lastOrNull()?.abbreviation ?: "?"
                val nextItem = allScheduleItems.getOrNull(i + 1)
                val breakAbbr = nextItem?.busStops?.firstOrNull()?.abbreviation ?: "?"

                val restBox = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.break_rounded_style)
                    setPadding(1, 1, 1, 1)

                    val busIcon = ImageView(context).apply {
                        setImageResource(R.drawable.bus_timeline)
                        layoutParams = LinearLayout.LayoutParams(32, 32)
                    }

                    val restAbbr = TextView(context).apply {
                        text = lastStopOfCurrent
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(8, 0, 8, 0)
                    }

                    addView(busIcon)
                    addView(restAbbr)
                }

                val thinWidthPx = (48 * context.resources.displayMetrics.density).toInt()
                restBox.layoutParams = LayoutParams(thinWidthPx, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = 6
                }

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
