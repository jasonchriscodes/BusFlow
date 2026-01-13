package com.jason.publisher.main.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.jason.publisher.R
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable

/**
 * Object containing helper functions used throughout the application.
 */
object Helper {

    /**
     * Creates a custom drawable with the bus stop number.
     *
     * @param context The application context.
     * @param busStopIndex The bus stop number.
     * @param totalStops The maximum bus stop number.
     * @return A drawable with the bus stop symbol and number.
     */
    fun createBusStopSymbol(context: Context, busStopIndex: Int, totalStops: Int, isRed: Boolean): Drawable {
        val adjustedNumber = when (busStopIndex) {
            0 -> "S"
            totalStops - 1 -> "E"
            else -> busStopIndex.toString() // Numbered stops
        }

        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_bus_stop) as BitmapDrawable
        val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        val textSize = 30f
        val paint = Paint().apply {
            color = if (isRed) Color.RED else Color.CYAN  // Use red for scheduled stops
            isFakeBoldText = true
            typeface = Typeface.DEFAULT_BOLD
            this.textSize = textSize
        }

        val x = (canvas.width - paint.measureText(adjustedNumber)) / 2
        val y = canvas.height - 10f

        canvas.drawText(adjustedNumber, x, y, paint)

        return bitmap.toDrawable(context.resources)
    }
}