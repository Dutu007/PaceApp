package com.dutu007.paceapp.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.dutu007.paceapp.data.DailyMetricPoint
import com.dutu007.paceapp.extractClockHHMM
import com.dutu007.paceapp.formatAxisOffsetClock
import com.dutu007.paceapp.formatCompactDuration
import com.dutu007.paceapp.parseClockMinutes
import com.dutu007.paceapp.sleepAxisOffsetMinutes
import com.dutu007.paceapp.sleepScoreColorInt
import kotlin.math.ceil
import kotlin.math.floor

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
        heightDp: Float = 82f,
        maxDensityScale: Float = 2.5f,
    ): Bitmap {
        val scale = context.resources.displayMetrics.density.coerceIn(1.5f, maxDensityScale)
        val widthPx = (310f * scale).toInt().coerceAtLeast(320)
        val heightPx = (heightDp * scale).toInt().coerceAtLeast(88)
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

    /**
     * Renders a multi-band 7-night sleep schedule chart for desktop widgets:
     * duration + score headers, floating bedtime-to-wake capsules with hour Y-axis,
     * and bedtime + wake + MM-DD footers without overlap.
     */
    fun renderSleepTimelineChart(
        context: Context,
        nights: List<WidgetSleepNight>,
        emptyLabel: String,
        heightDp: Float = 114f,
        widthDp: Float = 320f,
        maxDensityScale: Float = 2.15f,
        compactMode: Boolean = false,
    ): Bitmap {
        val scale = context.resources.displayMetrics.density.coerceIn(1.5f, maxDensityScale)
        val widthPx = (widthDp * scale).toInt().coerceAtLeast(320)
        val heightPx = (heightDp * scale).toInt().coerceAtLeast(108)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val recent = nights.takeLast(7)
        val hasAnyPositive = recent.any { it.durationMinutes > 0 }
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
                textSize = (if (compactMode) 10f else 11f) * scale
                textAlign = Paint.Align.CENTER
            }
            val y = heightPx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(emptyLabel, widthPx / 2f, y, textPaint)
            return bitmap
        }

        val axisWidth = (if (compactMode) 31f else 35f) * scale
        val rightPad = 4f * scale
        val colsWidth = (widthPx - axisWidth - rightPad).coerceAtLeast(100f)
        val slotW = colsWidth / recent.size.toFloat()
        val capsuleW = (slotW * 0.40f).coerceIn(7f * scale, 15f * scale)
        val highlightIdx = recent.indexOfLast { it.durationMinutes > 0 }.coerceAtLeast(0)

        val labelTextSize = (if (compactMode) 7.6f else 8.4f) * scale
        val railTextSize = (if (compactMode) 7.2f else 7.8f) * scale

        val durY = (if (compactMode) 9.5f else 11f) * scale
        val scoreY = (if (compactMode) 19f else 22f) * scale
        val plotTop = (if (compactMode) 23f else 27f) * scale
        val bedY = heightPx - (if (compactMode) 20f else 23.5f) * scale
        val wakeY = heightPx - (if (compactMode) 11f else 13f) * scale
        val dateY = heightPx - (if (compactMode) 2f else 2.5f) * scale
        val plotBottom = (heightPx - (if (compactMode) 29f else 34f) * scale).coerceAtLeast(plotTop + 26f * scale)

        val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7B8797.toInt()
            textSize = railTextSize
            textAlign = Paint.Align.RIGHT
        }
        val railX = axisWidth - 5f * scale
        canvas.drawText("时长", railX, durY, railPaint)
        canvas.drawText("分数", railX, scoreY, railPaint)
        canvas.drawText("入睡", railX, bedY, railPaint)
        canvas.drawText("醒来", railX, wakeY, railPaint)
        canvas.drawText("日期", railX, dateY, railPaint)

        val windows = recent.map { night ->
            val bedMin = parseClockMinutes(night.bedtime)?.let(::sleepAxisOffsetMinutes)
            val wakeRawMin = parseClockMinutes(night.wakeTime)?.let(::sleepAxisOffsetMinutes)
            if (bedMin != null && wakeRawMin != null) {
                val wakeMin = if (wakeRawMin <= bedMin) wakeRawMin + 24 * 60 else wakeRawMin
                bedMin to wakeMin
            } else {
                null
            }
        }
        val hasAnyWindow = windows.any { it != null }
        val minAxis = run {
            val rawMin = windows.filterNotNull().minOfOrNull { it.first } ?: (22 * 60)
            (floor(rawMin / 60.0).toInt() * 60 - 60).coerceAtLeast(18 * 60)
        }
        val maxAxis = run {
            val rawMax = windows.filterNotNull().maxOfOrNull { it.second } ?: (32 * 60)
            maxOf(minAxis + 4 * 60, ceil(rawMax / 60.0).toInt() * 60 + 60)
        }
        val axisSpan = (maxAxis - minAxis).coerceAtLeast(1).toFloat()
        val plotPadY = 4f * scale
        val usablePlotH = (plotBottom - plotTop - 2f * plotPadY).coerceAtLeast(16f * scale)

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD9E1E8.toInt()
            strokeWidth = 1f * scale
            pathEffect = DashPathEffect(floatArrayOf(4f * scale, 4f * scale), 0f)
        }
        val axisClockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A97A6.toInt()
            textSize = (if (compactMode) 7.0f else 7.5f) * scale
            textAlign = Paint.Align.RIGHT
        }

        val yFractions = listOf(0f, 0.5f, 1f)
        yFractions.forEach { frac ->
            val lineY = plotTop + plotPadY + usablePlotH * frac
            canvas.drawLine(axisWidth, lineY, widthPx - rightPad, lineY, gridPaint)
            if (hasAnyWindow) {
                val tickMin = (minAxis + axisSpan * frac).toInt()
                val textY = lineY - (axisClockPaint.ascent() + axisClockPaint.descent()) / 2f
                canvas.drawText(formatAxisOffsetClock(tickMin), railX, textY, axisClockPaint)
            }
        }

        val colPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelTextSize
            textAlign = Paint.Align.CENTER
        }
        val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val maxDuration = recent.maxOfOrNull { it.durationMinutes }?.coerceAtLeast(60) ?: 480

        recent.forEachIndexed { idx, night ->
            val centerX = axisWidth + idx * slotW + slotW / 2f
            val isHi = idx == highlightIdx
            val left = centerX - capsuleW / 2f
            val right = centerX + capsuleW / 2f

            // Duration row
            colPaint.typeface = if (isHi) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            colPaint.color = when {
                night.durationMinutes <= 0 -> 0xFF9AA7B2.toInt()
                isHi -> 0xFF5B5CE2.toInt()
                else -> 0xFF131C24.toInt()
            }
            canvas.drawText(formatCompactDuration(night.durationMinutes), centerX, durY, colPaint)

            // Score row
            val sc = night.score?.takeIf { it > 0 }
            colPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            colPaint.color = sc?.let(::sleepScoreColorInt) ?: 0xFF9AA7B2.toInt()
            canvas.drawText(sc?.let { "${it}分" } ?: "--", centerX, scoreY, colPaint)

            // Capsule plot
            val win = windows[idx]
            if (night.durationMinutes > 0 && win != null) {
                val topY = plotTop + plotPadY + ((win.first - minAxis) / axisSpan).coerceIn(0f, 1f) * usablePlotH
                val rawBotY = plotTop + plotPadY + ((win.second - minAxis) / axisSpan).coerceIn(0f, 1f) * usablePlotH
                val botY = maxOf(topY + 8f * scale, rawBotY)
                if (isHi) {
                    capsulePaint.color = 0xFFECECFF.toInt()
                    val haloPad = 2.5f * scale
                    canvas.drawRoundRect(
                        RectF(left - haloPad, topY - haloPad, right + haloPad, botY + haloPad),
                        capsuleW,
                        capsuleW,
                        capsulePaint,
                    )
                }
                capsulePaint.color = if (isHi) 0xFF5B5CE2.toInt() else 0xFF9CA0F0.toInt()
                canvas.drawRoundRect(RectF(left, topY, right, botY), capsuleW / 2f, capsuleW / 2f, capsulePaint)

                val dotR = (capsuleW * 0.22f).coerceIn(1.3f * scale, 2.2f * scale)
                capsulePaint.color = 0xEBFFFFFF.toInt()
                canvas.drawCircle(centerX, topY + dotR + 1.5f * scale, dotR, capsulePaint)
                canvas.drawCircle(centerX, botY - dotR - 1.5f * scale, dotR, capsulePaint)
            } else if (night.durationMinutes > 0) {
                val ratio = (night.durationMinutes.toFloat() / maxDuration.toFloat()).coerceIn(0.15f, 1f)
                val barH = (usablePlotH * ratio).coerceAtLeast(6f * scale)
                val topY = plotBottom - plotPadY - barH
                capsulePaint.color = if (isHi) 0xFF5B5CE2.toInt() else 0xFFA5A8F2.toInt()
                canvas.drawRoundRect(RectF(left, topY, right, plotBottom - plotPadY), capsuleW / 2f, capsuleW / 2f, capsulePaint)
            } else {
                capsulePaint.color = 0xFFD0D9E0.toInt()
                canvas.drawCircle(centerX, (plotTop + plotBottom) / 2f, 2f * scale, capsulePaint)
            }

            val bedClock = extractClockHHMM(night.bedtime)
            val wakeClock = extractClockHHMM(night.wakeTime)
            colPaint.typeface = if (isHi) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT

            // Bedtime row
            colPaint.color = when {
                bedClock.isEmpty() -> 0xFF9AA7B2.toInt()
                isHi -> 0xFF5B5CE2.toInt()
                else -> 0xFF2B3945.toInt()
            }
            canvas.drawText(bedClock.ifBlank { "--" }, centerX, bedY, colPaint)

            // Wake row
            colPaint.color = when {
                wakeClock.isEmpty() -> 0xFF9AA7B2.toInt()
                isHi -> 0xFF5B5CE2.toInt()
                else -> 0xFF2B3945.toInt()
            }
            canvas.drawText(wakeClock.ifBlank { "--" }, centerX, wakeY, colPaint)

            // Date row (MM-DD)
            val shortDate = if (night.date.length >= 10) night.date.substring(5) else night.date
            colPaint.color = if (isHi) 0xFF5B5CE2.toInt() else 0xFF5A6B78.toInt()
            canvas.drawText(shortDate.ifBlank { "--" }, centerX, dateY, colPaint)
        }

        return bitmap
    }
}
