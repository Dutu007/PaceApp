package com.dutu007.paceapp

import java.util.Locale
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
