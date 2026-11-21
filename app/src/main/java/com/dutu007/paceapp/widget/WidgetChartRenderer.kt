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
import com.dutu007.paceapp.formatBarDurationLines
import com.dutu007.paceapp.formatCompactDuration
import com.dutu007.paceapp.parseClockMinutes
import com.dutu007.paceapp.sleepAxisOffsetMinutes
import com.dutu007.paceapp.sleepScoreColorInt

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
     * Renders a 7-night sleep schedule chart for desktop widgets:
     * score row uniformly at top, duration centered inside each floating sleep bar,
     * bedtime/wake times at top/bottom bar caps, and MM-DD date on the X-axis.
     */
    fun renderSleepTimelineChart(
        context: Context,
        nights: List<WidgetSleepNight>,
        emptyLabel: String,
        heightDp: Float = 118f,
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

        val sidePad = 4f * scale
        val colsWidth = (widthPx - 2f * sidePad).coerceAtLeast(120f)
        val slotW = colsWidth / recent.size.toFloat()
        val barW = (slotW * 0.72f).coerceIn(22f * scale, 34f * scale)
        val barRadius = (if (compactMode) 6f else 7.5f) * scale
        val highlightIdx = recent.indexOfLast { it.durationMinutes > 0 }.coerceAtLeast(0)

        val scoreY = (if (compactMode) 9.5f else 11f) * scale
        val plotTop = (if (compactMode) 13f else 15f) * scale
        val dateY = heightPx - (if (compactMode) 2f else 2.5f) * scale
        val plotBottom = heightPx - (if (compactMode) 12f else 14f) * scale
        val topLabelSpace = (if (compactMode) 10.5f else 12f) * scale
        val botLabelSpace = topLabelSpace
        val barZoneTop = plotTop + topLabelSpace
        val barZoneBottom = (plotBottom - botLabelSpace).coerceAtLeast(barZoneTop + 24f * scale)
        val barZoneH = (barZoneBottom - barZoneTop).coerceAtLeast(24f * scale)
        val minBarH = (if (compactMode) 18f else 22f) * scale

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
        val minAxis = run {
            val rawMin = windows.filterNotNull().minOfOrNull { it.first } ?: (22 * 60)
            ((rawMin / 30) * 30).coerceAtLeast(18 * 60)
        }
        val maxAxis = run {
            val rawMax = windows.filterNotNull().maxOfOrNull { it.second } ?: (32 * 60)
            maxOf(minAxis + 4 * 60, ((rawMax + 29) / 30) * 30)
        }
        val axisSpan = (maxAxis - minAxis).coerceAtLeast(1).toFloat()

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD9E1E8.toInt()
            strokeWidth = 1f * scale
            pathEffect = DashPathEffect(floatArrayOf(4f * scale, 4f * scale), 0f)
        }
        listOf(0f, 0.5f, 1f).forEach { frac ->
            val lineY = barZoneTop + barZoneH * frac
            canvas.drawLine(sidePad, lineY, widthPx - sidePad, lineY, gridPaint)
        }

        val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (if (compactMode) 8.0f else 8.8f) * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val scorePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val barStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFC5C7F8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1f * scale
        }
        val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (if (compactMode) 7.0f else 7.8f) * scale
            textAlign = Paint.Align.CENTER
        }
        val line1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (if (compactMode) 7.6f else 8.4f) * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val line2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (if (compactMode) 6.8f else 7.4f) * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val singleDurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (if (compactMode) 7.4f else 8.0f) * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (if (compactMode) 7.6f else 8.4f) * scale
            textAlign = Paint.Align.CENTER
        }

        val maxDuration = recent.maxOfOrNull { it.durationMinutes }?.coerceAtLeast(60) ?: 480

        recent.forEachIndexed { idx, night ->
            val centerX = sidePad + idx * slotW + slotW / 2f
            val isHi = idx == highlightIdx
            val left = centerX - barW / 2f
            val right = centerX + barW / 2f

            // Top band: Score uniformly on top
            val sc = night.score?.takeIf { it > 0 }
            val scColor = sc?.let(::sleepScoreColorInt) ?: 0xFF9AA7B2.toInt()
            if (isHi && sc != null) {
                scorePillPaint.color = (scColor and 0x00FFFFFF) or 0x1F000000
                canvas.drawRoundRect(
                    RectF(
                        centerX - 14f * scale,
                        scoreY + scorePaint.ascent() - 1.5f * scale,
                        centerX + 14f * scale,
                        scoreY + scorePaint.descent() + 1.5f * scale,
                    ),
                    8f * scale,
                    8f * scale,
                    scorePillPaint,
                )
            }
            scorePaint.color = scColor
            canvas.drawText(sc?.let { "${it}分" } ?: "--", centerX, scoreY, scorePaint)

            // Bottom band: MM-DD X-axis label
            val shortDate = if (night.date.length >= 10) night.date.substring(5) else night.date
            datePaint.color = if (isHi) 0xFF5B5CE2.toInt() else 0xFF5A6B78.toInt()
            datePaint.typeface = if (isHi) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            canvas.drawText(shortDate.ifBlank { "--" }, centerX, dateY, datePaint)

            if (night.durationMinutes <= 0) {
                barFillPaint.color = 0xFFD0D9E0.toInt()
                canvas.drawCircle(centerX, (barZoneTop + barZoneBottom) / 2f, 2f * scale, barFillPaint)
                return@forEachIndexed
            }

            // Middle band: floating bar with bedtime at top cap, wake at bottom cap, duration inside
            val win = windows[idx]
            val (topY, botY) = if (win != null) {
                val rawTop = barZoneTop + ((win.first - minAxis) / axisSpan).coerceIn(0f, 1f) * barZoneH
                val rawBot = barZoneTop + ((win.second - minAxis) / axisSpan).coerceIn(0f, 1f) * barZoneH
                if (rawBot - rawTop < minBarH) {
                    val mid = (rawTop + rawBot) / 2f
                    val clampedTop = (mid - minBarH / 2f).coerceIn(barZoneTop, barZoneBottom - minBarH)
                    clampedTop to (clampedTop + minBarH)
                } else {
                    rawTop to rawBot
                }
            } else {
                val ratio = (night.durationMinutes.toFloat() / maxDuration.toFloat()).coerceIn(0.15f, 1f)
                val barH = (barZoneH * ratio).coerceAtLeast(minBarH)
                (barZoneBottom - barH) to barZoneBottom
            }
            val barRect = RectF(left, topY, right, botY)

            if (isHi) {
                barFillPaint.color = 0xFF5B5CE2.toInt()
                canvas.drawRoundRect(barRect, barRadius, barRadius, barFillPaint)
            } else {
                barFillPaint.color = 0xFFE8E9FF.toInt()
                canvas.drawRoundRect(barRect, barRadius, barRadius, barFillPaint)
                canvas.drawRoundRect(barRect, barRadius, barRadius, barStrokePaint)
            }

            val bedClock = extractClockHHMM(night.bedtime)
            val wakeClock = extractClockHHMM(night.wakeTime)
            clockPaint.color = if (isHi) 0xFF5B5CE2.toInt() else 0xFF5A6B78.toInt()
            clockPaint.typeface = if (isHi) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            if (bedClock.isNotBlank()) {
                val bedBaseline = (topY - 2.8f * scale).coerceAtLeast(plotTop - clockPaint.ascent())
                canvas.drawText(bedClock, centerX, bedBaseline, clockPaint)
            }
            if (wakeClock.isNotBlank()) {
                val wakeBaseline = (botY + 2.8f * scale - clockPaint.ascent())
                    .coerceAtMost(plotBottom - clockPaint.descent())
                canvas.drawText(wakeClock, centerX, wakeBaseline, clockPaint)
            }

            val insideColor = if (isHi) 0xFFFFFFFF.toInt() else 0xFF3B3DC0.toInt()
            val centerY = (topY + botY) / 2f
            val barH = botY - topY
            val (line1, line2) = formatBarDurationLines(night.durationMinutes)
            val twoLineMinH = (if (compactMode) 21f else 24f) * scale
            if (line2 != null && barH >= twoLineMinH) {
                line1Paint.color = insideColor
                line2Paint.color = (insideColor and 0x00FFFFFF) or (0xE5 shl 24)
                val gap = (if (compactMode) 1f else 1.5f) * scale
                val b1 = centerY - gap / 2f - line1Paint.descent() * 0.45f
                val b2 = b1 + gap - line2Paint.ascent()
                canvas.drawText(line1, centerX, b1, line1Paint)
                canvas.drawText(line2, centerX, b2, line2Paint)
            } else {
                singleDurPaint.color = insideColor
                val baseY = centerY - (singleDurPaint.ascent() + singleDurPaint.descent()) / 2f
                canvas.drawText(formatCompactDuration(night.durationMinutes), centerX, baseY, singleDurPaint)
            }
        }

        return bitmap
    }
}
