package com.jason.publisher.modules.schedule.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.jason.publisher.R
import com.jason.publisher.main.model.BusScheduleInfo
import com.jason.publisher.main.model.ScheduleItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var isDarkMode = false
    private var singleLineMode = true

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16, 16, 16, 16)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun setSingleLineMode(singleLine: Boolean) {
        singleLineMode = singleLine
        renderTimeline()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun setBusStops(busStops: List<BusScheduleInfo>) {
        this.busStops = busStops
        renderTimeline() // Redraw with markers
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun setDarkMode(isDark: Boolean) {
        isDarkMode = isDark
        renderTimeline()
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
    @SuppressLint("LongLogTag")
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
            val dutyName = dutyNames.getOrNull(i) ?:"Duty"
            val runName = item?.runName ?: dutyName

            val isRep = dutyName.equals("REP", ignoreCase =true)
            val isBreak = dutyName.equals("Break", ignoreCase =true)

// ✅ Signing detection (Sign On / Sign Off)
            val isSigning = runName.contains("sign", ignoreCase =true)
            val isSignOff = isSigning && (
                    runName.contains("off", ignoreCase =true) ||
                            runName.contains("out", ignoreCase =true)
                    )

            val startTime = workIntervals[i].first
            val firstStopAbbr = item?.busStops?.firstOrNull()?.abbreviation ?:"?"
            val lastStopAbbr = item?.busStops?.lastOrNull()?.abbreviation ?: "?"
            // REP / Sign On / Sign Off boxes show where the bus ends up after this duty
            // (matching the rest-stop label that follows them), not where it started -
            // except for the very first box in the timeline, which has no "previous" stop
            // to show that starting point otherwise.
            val repOrSignStopAbbr = if (i == 0) firstStopAbbr else lastStopAbbr

            Log.d(
                "StyledMultiColorTimeline renderTimeline",
                "Duty box $i: " +
                        "dutyName=$dutyName, isRep=$isRep, isBreak=$isBreak, " +
                        "startTime=$startTime, " +
                        "firstStopAbbr=$firstStopAbbr, " +
                        "lastStopAbbr=${item?.busStops?.lastOrNull()?.abbreviation ?: "?"}, " +
                        "source ScheduleItem=${item?.runName} [busStops: ${item?.busStops?.map { it.abbreviation }}]"
            )

            val box = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                setPadding(12, 8, 12, 8)
                background = ContextCompat.getDrawable(
                    context,
                    when {
                        isSigning -> if (isDarkMode) R.drawable.dark_break_horizontal_style else R.drawable.break_horizontal_style
                        isRep     -> if (isDarkMode) R.drawable.dark_rep_vertical_style else R.drawable.rep_vertical_style
                        isBreak   -> if (isDarkMode) R.drawable.dark_break_horizontal_style else R.drawable.break_horizontal_style
                        else      -> if (isDarkMode) R.drawable.dark_route_rounded_style else R.drawable.route_rounded_style
                    }
                )

                // create timeLabel but only add it for REP or Break
                val timeLabel = TextView(context).apply {
                    text = startTime
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                }
                if (isRep || isBreak || isSigning) {
                    addView(timeLabel)
                }

                when {
                    isSigning -> {
                        val icon = ImageView(context).apply {
                            setImageResource(if (isSignOff) R.drawable.ic_sign_off else R.drawable.ic_sign_on)
                            setColorFilter(if (isDarkMode) Color.GRAY else Color.WHITE)
                            layoutParams = LayoutParams(32, 32).apply {
                                gravity = Gravity.CENTER_HORIZONTAL
                            }
                        }

                        val signLabel = TextView(context).apply {
                            text = if (isSignOff) "Sign off" else "Sign on"
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                        }

                        val stopLabel = TextView(context).apply {
                            text = repOrSignStopAbbr
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                        }

                        addView(icon)
                        addView(signLabel)
                        addView(stopLabel)
                    }
                    isRep -> {
                        val repLabel = TextView(context).apply {
                            text = "REP"
                            textSize = 16f
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                        }
                        val stopLabel = TextView(context).apply {
                            text = repOrSignStopAbbr
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                        }
                        addView(repLabel)
                        addView(stopLabel)

                    }
                    isBreak -> {
                        val icon = ImageView(context).apply {
                            setImageResource(R.drawable.cup_break)
                            setColorFilter(if (isDarkMode) Color.GRAY else Color.WHITE)
                            layoutParams = LayoutParams(32, 32).apply {
                                gravity = Gravity.CENTER_HORIZONTAL
                            }
                        }
                        val stopLabel = TextView(context).apply {
                            text = firstStopAbbr
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                        }
                        addView(icon)
                        addView(stopLabel)

                    }
                    else -> {
                        if (singleLineMode) {
                            // 1-line:
                            addView(TextView(context).apply {
                                text = "$startTime $dutyName $firstStopAbbr → $lastStopAbbr"
                                textSize = 16f
                                gravity = Gravity.CENTER
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                setTextColor(Color.WHITE)
                            })
                        } else {
                            // 2-line:
                            // (1) time on its own line
                            addView(TextView(context).apply {
                                text = startTime
                                textSize = 12f
                                gravity = Gravity.CENTER
                                setTextColor(Color.WHITE)
                            })
                            // (2) duty+stops on new line
                            addView(TextView(context).apply {
                                text = "$dutyName $firstStopAbbr → $lastStopAbbr"
                                textSize = 16f
                                gravity = Gravity.CENTER
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                setTextColor(Color.WHITE)
                            })
                        }
                    }
                }
            }

            val fixedBoxWidth = (64 * context.resources.displayMetrics.density).toInt()
            val isFixed = isRep || isBreak || isSigning

            box.layoutParams = LayoutParams(
                if (isFixed) fixedBoxWidth else 0,
                LayoutParams.WRAP_CONTENT,
                if (isFixed) 0f else segmentDurations[i].toFloat() / totalDuration
            ).apply {
                marginStart = if (i > 0) 8 else 0
            }

            addView(box)

            if (i < workIntervals.size - 1) {
                val lastStopOfCurrent = item?.busStops?.lastOrNull()?.abbreviation ?: "?"

                val restBox = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundResource(
                        if (isDarkMode) R.drawable.dark_break_rounded_style else R.drawable.break_rounded_style
                    )

                    setPadding(1, 1, 1, 1)

                    val busIcon = ImageView(context).apply {
                        setImageResource(R.drawable.bus_timeline)
                        setColorFilter(if (isDarkMode) Color.GRAY else Color.WHITE)
                        layoutParams = LayoutParams(32, 32)
                    }

                    val restAbbr = TextView(context).apply {
                        text = lastStopOfCurrent
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(8, 0, 8, 0)
                        setTextColor(Color.WHITE)
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
            val s = fmt.parse(start) ?: Date()
            val e = fmt.parse(end) ?: Date()
            (e.time - s.time) / 60000
        } catch (_: Exception) {
            30L // fallback duration
        }
    }

}