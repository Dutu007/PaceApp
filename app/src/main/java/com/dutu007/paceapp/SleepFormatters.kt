package com.dutu007.paceapp

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

private val SLEEP_CLOCK_REGEX = Regex("""(?:\b|[Tt\s])(\d{1,2}):(\d{2})(?::\d{2})?""")
private const val MINUTES_PER_DAY = 24 * 60
private const val SLEEP_EVENING_CUTOFF_MINUTES = 18 * 60

/**
 * Extracts normalized `HH:mm` clock time from timestamps like `2026-11-20 23:59`,
 * `2026-11-20T23:59:00+08:00`, or bare `23:59`. Returns empty string if unparseable.
 */
fun extractClockHHMM(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val match = SLEEP_CLOCK_REGEX.find(trimmed) ?: return ""
    val hour = match.groupValues[1].toIntOrNull() ?: return ""
    val minute = match.groupValues[2].toIntOrNull() ?: return ""
    if (hour !in 0..23 || minute !in 0..59) return ""
    return String.format(Locale.US, "%02d:%02d", hour, minute)
}

/**
 * Parses `HH:mm` (or timestamp containing `HH:mm`) into minutes since `00:00` (`0..1439`).
 */
fun parseClockMinutes(raw: String): Int? {
    val hhmm = extractClockHHMM(raw)
    if (hhmm.isEmpty()) return null
    val parts = hhmm.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return hour * 60 + minute
}

/**
 * Shifts midnight-crossing sleep times into a continuous axis (`18:00` = 1080 .. `17:59+1d` = 2519)
 * so evening bedtimes (`23:57`) sit directly before early-morning times (`00:58`).
 */
fun sleepAxisOffsetMinutes(clockMinutes: Int): Int {
    val normalized = ((clockMinutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    return if (normalized >= SLEEP_EVENING_CUTOFF_MINUTES) {
        normalized
    } else {
        normalized + MINUTES_PER_DAY
    }
}

/**
 * Formats an axis offset minute value back to 24-hour `HH:mm`.
 */
fun formatAxisOffsetClock(axisMinutes: Int): String {
    val normalized = ((axisMinutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    return String.format(Locale.US, "%02d:%02d", normalized / 60, normalized % 60)
}

/**
 * Computes mean bedtime across nights safely across the midnight boundary.
 */
fun averageBedtimeClock(bedtimes: List<String>): String {
    val axisValues = bedtimes.mapNotNull { parseClockMinutes(it)?.let(::sleepAxisOffsetMinutes) }
    if (axisValues.isEmpty()) return "--:--"
    return formatAxisOffsetClock(axisValues.average().roundToInt())
}

/**
 * Computes mean wake-up clock time across nights.
 */
fun averageWakeClock(wakeTimes: List<String>): String {
    val axisValues = wakeTimes.mapNotNull { parseClockMinutes(it)?.let(::sleepAxisOffsetMinutes) }
    if (axisValues.isEmpty()) return "--:--"
    return formatAxisOffsetClock(axisValues.average().roundToInt())
}

/**
 * Compact sleep duration formatting suitable for narrow 7-column charts and widgets (e.g. `8h40m`, `9h`).
 */
fun formatCompactDuration(minutes: Int): String {
    if (minutes <= 0) return "--"
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours == 0 -> "${mins}m"
        mins == 0 -> "${hours}h"
        else -> String.format(Locale.US, "%dh%02dm", hours, mins)
    }
}

/**
 * Splits sleep duration into 1 or 2 compact stacked lines (`"8h"` + `"03m"`, `"9h"`, `"45m"`)
 * for centering cleanly inside narrow floating sleep bars.
 */
fun formatBarDurationLines(minutes: Int): Pair<String, String?> {
    if (minutes <= 0) return "--" to null
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours == 0 -> "${mins}m" to null
        mins == 0 -> "${hours}h" to null
        else -> "${hours}h" to String.format(Locale.US, "%02dm", mins)
    }
}

/**
 * Computes a clean `[minScore, maxScore]` Y-axis range for plotting sleep score trend lines:
 * snaps to 5-point increments with breathing room and enforces a minimum 30-point span
 * so small fluctuations aren't visually exaggerated.
 */
fun computeSleepScoreRange(scores: List<Int>): Pair<Int, Int> {
    val valid = scores.filter { it > 0 }
    if (valid.isEmpty()) return 50 to 100
    val rawMin = valid.min()
    val rawMax = valid.max()
    var lo = (((rawMin - 8).coerceAtLeast(0)) / 5) * 5
    var hi = ((((rawMax + 6).coerceAtMost(100)) + 4) / 5) * 5
    if (hi - lo < 30) {
        lo = (hi - 30).coerceAtLeast(0)
        hi = (lo + 30).coerceAtMost(100)
    }
    return lo to hi
}

/**
 * Color-codes sleep quality scores for high readability on light surfaces.
 */
fun sleepScoreColorInt(score: Int): Int {
    return when {
        score <= 0 -> 0xFF7B8797.toInt()
        score >= 85 -> 0xFF0E9F6E.toInt()
        score >= 70 -> 0xFFD97706.toInt()
        else -> 0xFFDC2626.toInt()
    }
}

data class SleepBarColors(
    val fillArgb: Int,
    val strokeArgb: Int,
    val textArgb: Int,
)

private data class QuadFloat(val r: Float, val g: Float, val b: Float, val a: Float)

/**
 * Normalizes sleep duration onto `[0f, 1f]` between 3h (`180m`) and 10h (`600m`), clamping extremes.
 */
fun sleepDurationIntensity(minutes: Int): Float =
    ((minutes - 180f) / 420f).coerceIn(0f, 1f)

/**
 * Computes semi-translucent indigo fill, border, and high-contrast label colors directly from sleep duration
 * (`3h` lightest -> `10h` deepest), without highlighting any single day as darker by recency.
 */
fun sleepDurationBarColors(minutes: Int): SleepBarColors {
    val t = sleepDurationIntensity(minutes)
    val c = if (t <= 0.5f) {
        val k = t / 0.5f
        QuadFloat(
            r = 234f + (120f - 234f) * k,
            g = 236f + (123f - 236f) * k,
            b = 255f + (230f - 255f) * k,
            a = 0.60f + (0.71f - 0.60f) * k,
        )
    } else {
        val k = (t - 0.5f) / 0.5f
        QuadFloat(
            r = 120f + (37f - 120f) * k,
            g = 123f + (38f - 123f) * k,
            b = 230f + (172f - 230f) * k,
            a = 0.71f + (0.82f - 0.71f) * k,
        )
    }
    val br = (c.r * c.a + 255f * (1f - c.a)) / 255f
    val bg = (c.g * c.a + 255f * (1f - c.a)) / 255f
    val bb = (c.b * c.a + 255f * (1f - c.a)) / 255f
    val lum = 0.2126 * br.toDouble().pow(2.2) +
        0.7152 * bg.toDouble().pow(2.2) +
        0.0722 * bb.toDouble().pow(2.2)
    val textArgb = if (lum < 0.25) 0xFFFFFFFF.toInt() else 0xFF181A66.toInt()

    val aInt = (c.a * 255f).roundToInt().coerceIn(0, 255)
    val rInt = c.r.roundToInt().coerceIn(0, 255)
    val gInt = c.g.roundToInt().coerceIn(0, 255)
    val bInt = c.b.roundToInt().coerceIn(0, 255)
    val fillArgb = (aInt shl 24) or (rInt shl 16) or (gInt shl 8) or bInt

    val saInt = ((c.a + 0.14f).coerceAtMost(0.92f) * 255f).roundToInt().coerceIn(0, 255)
    val srInt = (c.r * 0.84f).roundToInt().coerceIn(0, 255)
    val sgInt = (c.g * 0.84f).roundToInt().coerceIn(0, 255)
    val sbInt = (c.b * 0.92f).roundToInt().coerceIn(0, 255)
    val strokeArgb = (saInt shl 24) or (srInt shl 16) or (sgInt shl 8) or sbInt

    return SleepBarColors(fillArgb = fillArgb, strokeArgb = strokeArgb, textArgb = textArgb)
}
