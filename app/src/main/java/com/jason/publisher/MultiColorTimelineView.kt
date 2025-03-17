package com.jason.publisher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class MultiColorTimelineView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var timelineRange = Pair("08:00", "11:10") // Default timeline range
    private val timelineMargin = 50f // Added margin on both sides of the timeline
    private var dutyNames: List<String> = emptyList() // Add this at class level

    private val timeLabelInterval = 30 // Show a label every 30 minutes
    // Load and resize the bus icon, make it white
    private val busBitmap: Bitmap? = ContextCompat.getDrawable(context, R.drawable.ic_bustimeline)?.let { drawable ->
        val originalWidth = drawable.intrinsicWidth
        val originalHeight = drawable.intrinsicHeight
        val newWidth = 50  // Set desired width
        val newHeight = 50 // Set desired height

        val bitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, newWidth, newHeight)
        drawable.draw(canvas)

        // Convert to white color
        val paint = Paint().apply {
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        val whiteBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val whiteCanvas = Canvas(whiteBitmap)
        whiteCanvas.drawBitmap(bitmap, 0f, 0f, paint)

        whiteBitmap
    }

    /** Updates the timeline range and refreshes the view */
    fun setTimelineRange(start: String, end: String) {
        timelineRange = Pair(start, end)
        invalidate() // Redraw the view when timeline range is updated
    }

    // Paint for work segments (Red)
    private val workPaint = Paint().apply {
        color = 0xFFFF0000.toInt() // Red for work
        strokeWidth = 20f
        style = Paint.Style.FILL
    }

    // Paint for rest segments (Green)
    private val restPaint = Paint().apply {
        color = 0xFF00FF00.toInt() // Green for rest
        strokeWidth = 20f
        style = Paint.Style.FILL
    }

    // Paint for text labels (White)
    private val textPaint = Paint().apply {
        color = 0xFF000000.toInt() // White text
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }

    private var timeIntervals: List<Pair<String, String>> = emptyList() // Work intervals
    private var totalDuration = 0 // Total duration of the timeline in minutes
    private var dutyName: String = "Work" // Default to "Work"
    private var showBusIcon: Boolean = true // Add this flag for control

    /** Sets the work intervals and total timeline range */
    fun setTimeIntervals(
        workIntervals: List<Pair<String, String>>,
        totalDayStart: String,
        totalDayEnd: String,
        dutyNames: List<String>,
        showBusIcon: Boolean
    )
    {
        this.timeIntervals = workIntervals
        this.dutyNames = dutyNames
        this.totalDuration = getMinutesDifference(totalDayStart, totalDayEnd)
        this.showBusIcon = showBusIcon // Set the flag to control bus icon drawing
        invalidate() // Redraw the view when data changes
    }

    /** Draws the timeline on the canvas */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (timeIntervals.isEmpty() || totalDuration == 0) return

        val totalWidth = width.toFloat()
        val totalStartMinute = convertToMinutes(timelineRange.first)
        val totalEndMinute = convertToMinutes(timelineRange.second)

        var lastEndMinute = totalStartMinute

        for ((index, interval) in timeIntervals.withIndex()) {
            val (startTime, endTime) = interval
            val startMinute = convertToMinutes(startTime)
            val endMinute = convertToMinutes(endTime)

            // Draw rest period before this work session
            if (startMinute > lastEndMinute) {
                drawSegment(canvas, lastEndMinute, startMinute, totalStartMinute, totalEndMinute, totalWidth, restPaint, "Break")
            }

            val dutyName = dutyNames.getOrNull(index) ?: "Unknown" // Display respective dutyName
            drawSegment(canvas, startMinute, endMinute, totalStartMinute, totalEndMinute, totalWidth, workPaint, dutyName)

            lastEndMinute = endMinute
        }

        if (lastEndMinute < totalEndMinute) {
            drawSegment(canvas, lastEndMinute, totalEndMinute, totalStartMinute, totalEndMinute, totalWidth, restPaint, "Break")
        }

        drawTimeLabels(canvas, totalStartMinute, totalEndMinute, totalWidth)
        drawBusIcon(canvas, totalStartMinute, totalEndMinute, totalWidth)
    }

    /**
     * Draw bus icon in Bitmap
     */
    private fun drawBusIcon(canvas: Canvas, totalStart: Int, totalEnd: Int, totalWidth: Float) {
        if (!showBusIcon) return // ✅ Skip drawing if not the first page

        busBitmap?.let { bitmap ->
            val availableWidth = totalWidth - (2 * timelineMargin)
            val busX = timelineMargin - (bitmap.width / 2) // Position at the start of the timeline
            val busY = height / 2f - 60f // Adjust height above timeline

            canvas.drawBitmap(bitmap, busX, busY, null)
        }
    }

    /** Draws time labels with 100% opacity for start/end and completely transparent for internal times */
    private fun drawTimeLabels(canvas: Canvas, totalStart: Int, totalEnd: Int, totalWidth: Float) {
        val availableWidth = totalWidth - (2 * timelineMargin) // Account for margins
        val textY = height - 5f  // Lower the text position to prevent clipping

        val uniqueTimes = mutableSetOf<Int>() // Avoid duplicate labels

        // Collect all start and end times from work intervals
        for ((startTime, endTime) in timeIntervals) {
            uniqueTimes.add(convertToMinutes(startTime))
            uniqueTimes.add(convertToMinutes(endTime))
        }

        // Ensure the timeline start and end are included
        uniqueTimes.add(totalStart)
        uniqueTimes.add(totalEnd)

        // Draw time labels at correct positions
        for (time in uniqueTimes.sorted()) {
            val xPosition = timelineMargin + ((time - totalStart).toFloat() / (totalEnd - totalStart)) * availableWidth
            val timeLabel = convertMinutesToTime(time)

            // Show only start and end labels; hide internal labels
            val isStartOrEnd = (time == totalStart || time == totalEnd)
            val textPaint = if (isStartOrEnd) timeTextPaint else transparentTextPaint

            canvas.drawText(timeLabel, xPosition, textY, textPaint)
        }
    }

    // Paint for fully visible text (Start/End times - 100% opacity)
    private val timeTextPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt() // Full white color
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    // Paint for completely transparent text (Internal intervals - 0% opacity)
    private val transparentTextPaint = Paint().apply {
        color = Color.TRANSPARENT // Fully transparent color
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    /** Converts minutes to HH:MM format */
    private fun convertMinutesToTime(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return String.format("%02d:%02d", hours, mins)
    }

    /** Draws a timeline segment with label (Work/Rest) */
    private fun drawSegment(
        canvas: Canvas, startMinute: Int, endMinute: Int,
        totalStart: Int, totalEnd: Int, totalWidth: Float,
        paint: Paint, label: String
    ) {
        val availableWidth = totalWidth - (2 * timelineMargin) // Adjust width to account for margins
        val startX = timelineMargin + ((startMinute - totalStart).toFloat() / (totalEnd - totalStart)) * availableWidth
        val endX = timelineMargin + ((endMinute - totalStart).toFloat() / (totalEnd - totalStart)) * availableWidth
        val rect = RectF(startX, height / 2f - 10, endX, height / 2f + 10)

        canvas.drawRect(rect, paint)

        val textX = (startX + endX) / 2
        val textY = height / 2f + 5
        canvas.drawText(label, textX, textY, textPaint)
    }

    /** Converts a time string (HH:MM) to minutes since midnight */
    private fun convertToMinutes(time: String): Int {
        val parts = time.split(":").map { it.toInt() }
        return parts[0] * 60 + parts[1]
    }

    /** Returns the difference in minutes between two time strings */
    private fun getMinutesDifference(startTime: String, endTime: String): Int {
        return convertToMinutes(endTime) - convertToMinutes(startTime)
    }
}
