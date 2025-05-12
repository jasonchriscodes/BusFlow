package com.jason.publisher.main

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.jason.publisher.main.model.BusScheduleInfo
import java.text.SimpleDateFormat
import java.util.*

class OverlayBusStopMarkerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var busStops: List<BusScheduleInfo> = emptyList()
    var startTime: String = "08:00"
    var endTime: String = "11:00"

    private val linePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }

    fun setData(stops: List<BusScheduleInfo>, start: String, end: String) {
        busStops = stops
        startTime = start
        endTime = end
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val startMins = convertToMinutes(startTime)
        val endMins = convertToMinutes(endTime)
        val fullDuration = (endMins - startMins).toFloat()

        val centerY = height / 2f

        for (stop in busStops) {
            val stopMins = convertToMinutes(stop.time)
            val ratio = (stopMins - startMins) / fullDuration
            val x = (width * ratio).coerceIn(0f, width.toFloat())

            canvas.drawLine(x, centerY - 40f, x, centerY + 40f, linePaint)
            canvas.drawText(stop.abbreviation, x, centerY - 50f, textPaint)

            val delta = stopMins - startMins
            val deltaText = if (delta >= 60) "${delta / 60}:${"%02d".format(delta % 60)}" else "$delta"
            canvas.drawText(deltaText, x, centerY + 70f, textPaint)
        }
    }

    private fun convertToMinutes(time: String): Int {
        val parts = time.split(":").map { it.toIntOrNull() ?: 0 }
        return parts[0] * 60 + parts[1]
    }
}
