package com.dutu007.paceapp.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.dutu007.paceapp.data.DailyMetricPoint

fun computeNormalizedBarRatios(values: List<Double>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val max = values.maxOrNull()?.takeIf { it > 0.0 } ?: return List(values.size) { 0f }
    return values.map { value ->
        if (value <= 0.0) 0f else (value / max).toFloat().coerceIn(0.10f, 1f)
    }
}

object WidgetChartRenderer {
    fun renderSevenDayBarChart(
        context: Context,
        points: List<DailyMetricPoint>,
        accentColor: Int,
        emptyLabel: String,
        valueLabelFormatter: (Double) -> String,
    ): Bitmap {
        val scale = context.resources.displayMetrics.density.coerceIn(1.5f, 2.5f)
        val widthPx = (310f * scale).toInt().coerceAtLeast(320)
        val heightPx = (82f * scale).toInt().coerceAtLeast(100)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val recent = points.takeLast(7)
        val hasAnyPositive = recent.any { it.value > 0.0 }

        if (recent.isEmpty() || !hasAnyPositive) {
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF3F6F8.toInt()
                style = Paint.Style.FILL
            }
            val radius = 10f * scale
            canvas.drawRoundRect(
                RectF(0f, 4f * scale, widthPx.toFloat(), heightPx - 4f * scale),
                radius,
                radius,
                boxPaint,
            )
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF5A6B78.toInt()
                textSize = 11f * scale
                textAlign = Paint.Align.CENTER
            }
            val fm = textPaint.fontMetrics
            val centerY = heightPx / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(emptyLabel, widthPx / 2f, centerY, textPaint)
            return bitmap
        }

        val topPadding = 16f * scale
        val bottomLabelHeight = 16f * scale
        val sidePad = 4f * scale
        val chartBottom = heightPx - bottomLabelHeight
        val chartHeight = (chartBottom - topPadding).coerceAtLeast(16f * scale)
        val chartWidth = (widthPx - sidePad * 2f).coerceAtLeast(60f)

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE1E8EE.toInt()
            strokeWidth = 1f * scale
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(5f * scale, 4f * scale), 0f)
        }
        val midY = topPadding + chartHeight * 0.5f
        canvas.drawLine(sidePad, midY, widthPx - sidePad, midY, gridPaint)

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE1E8EE.toInt()
            strokeWidth = 1f * scale
        }
        canvas.drawLine(sidePad, chartBottom, widthPx - sidePad, chartBottom, basePaint)

        val ratios = computeNormalizedBarRatios(recent.map { it.value })
        val highlightIdx = recent.indexOfLast { it.value > 0.0 }.let { idx ->
            if (idx >= 0) idx else recent.lastIndex
        }

        val softAccent = (accentColor and 0x00FFFFFF) or (0x6B shl 24)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 9.5f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9.5f * scale
            textAlign = Paint.Align.CENTER
        }

        val slotW = chartWidth / recent.size.toFloat()
        val barW = (slotW * 0.44f).coerceIn(8f * scale, 22f * scale)
        val barRadius = 4f * scale

        recent.forEachIndexed { idx, pt ->
            val ratio = ratios[idx]
            val centerX = sidePad + idx * slotW + slotW / 2f
            val left = centerX - barW / 2f
            val right = centerX + barW / 2f
            val barH = if (pt.value > 0.0) (chartHeight * ratio).coerceAtLeast(4f * scale) else 2f * scale
            val top = chartBottom - barH

            barPaint.color = when {
                pt.value <= 0.0 -> 0xFFDCE4EA.toInt()
                idx == highlightIdx -> accentColor
                else -> softAccent
            }
            canvas.drawRoundRect(RectF(left, top, right, chartBottom), barRadius, barRadius, barPaint)

            if (idx == highlightIdx && pt.value > 0.0) {
                canvas.drawText(
                    valueLabelFormatter(pt.value),
                    centerX,
                    (top - 4f * scale).coerceAtLeast(10f * scale),
                    valuePaint,
                )
            }

            val shortDay = pt.weekday.removePrefix("周").ifBlank { pt.label }
            dayPaint.color = if (idx == highlightIdx) accentColor else 0xFF5A6B78.toInt()
            dayPaint.typeface = if (idx == highlightIdx) {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else {
                Typeface.DEFAULT
            }
            canvas.drawText(shortDay, centerX, heightPx - 3f * scale, dayPaint)
        }

        return bitmap
    }
}
