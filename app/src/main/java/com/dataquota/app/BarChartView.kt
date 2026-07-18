package com.dataquota.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A minimal bar chart drawn by hand with Canvas - avoids pulling in an
 * external charting library (fewer things that can break the build).
 * Feed it a list of (label, value) pairs and it draws vertical bars
 * scaled to the tallest one, with labels underneath.
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: List<Pair<String, Float>> = emptyList()

    private val barPaint = Paint().apply {
        color = Color.parseColor("#6200EE")
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val valuePaint = Paint().apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    fun setData(points: List<Pair<String, Float>>) {
        data = points
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val maxValue = data.maxOf { it.second }.coerceAtLeast(0.01f)
        val chartWidth = width.toFloat()
        val chartHeight = height.toFloat()
        val bottomMargin = 60f
        val topMargin = 40f
        val usableHeight = chartHeight - bottomMargin - topMargin

        val slotWidth = chartWidth / data.size
        val barWidth = slotWidth * 0.5f

        data.forEachIndexed { index, (label, value) ->
            val centerX = slotWidth * index + slotWidth / 2f
            val barHeight = if (maxValue > 0) (value / maxValue) * usableHeight else 0f
            val top = topMargin + (usableHeight - barHeight)
            val bottom = topMargin + usableHeight

            canvas.drawRect(
                centerX - barWidth / 2f, top,
                centerX + barWidth / 2f, bottom,
                barPaint
            )

            if (value > 0) {
                canvas.drawText(
                    String.format("%.2f", value),
                    centerX, top - 8f,
                    valuePaint
                )
            }

            canvas.drawText(label, centerX, chartHeight - 15f, labelPaint)
        }
    }
}
