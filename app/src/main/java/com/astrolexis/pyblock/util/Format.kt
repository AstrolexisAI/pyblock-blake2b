package com.astrolexis.pyblock.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** TH/s → natural unit ("2.7 PH/s", "344 TH/s"). Mirrors iOS hashrateLabel. */
fun hashrateLabel(th: Double?): String {
    if (th == null) return "—"
    return when {
        th >= 1_000_000 -> String.format(Locale.US, "%.2f EH/s", th / 1_000_000)
        th >= 1_000 -> String.format(Locale.US, "%.1f PH/s", th / 1_000)
        th >= 1 -> String.format(Locale.US, "%.0f TH/s", th)
        else -> String.format(Locale.US, "%.0f GH/s", th * 1_000)
    }
}

/** Compact axis label for the chart (input TH/s). */
fun hashrateShort(th: Double): String = when {
    th >= 1_000_000 -> String.format(Locale.US, "%.1fEH", th / 1_000_000)
    th >= 1_000 -> String.format(Locale.US, "%.1fPH", th / 1_000)
    th >= 1 -> String.format(Locale.US, "%.1fTH", th)
    th >= 0.001 -> String.format(Locale.US, "%.1fGH", th * 1_000)
    else -> String.format(Locale.US, "%.0fMH", th * 1_000_000)
}

/** 0.019 → "1.9%" · 5.2e-05 → "1 in 18,957". Mirrors iOS formatOdds. */
fun formatOdds(p: Double): String {
    if (p <= 0) return "—"
    if (p >= 0.01) return String.format(Locale.US, "%.1f%%", p * 100)
    if (p >= 0.001) return String.format(Locale.US, "%.2f%%", p * 100)
    val oneIn = (1.0 / p).roundToLong()
    return "1 in " + String.format(Locale.US, "%,d", oneIn)
}

/** Seconds-per-block → human "~1 / X". Mirrors iOS expectedTime. */
fun expectedTime(seconds: Double): String {
    val days = seconds / 86_400
    return when {
        days >= 365 * 2 -> String.format(Locale.US, "%.0f years", days / 365)
        days >= 365 -> String.format(Locale.US, "%.1f years", days / 365)
        days >= 60 -> String.format(Locale.US, "%.0f months", days / 30.4)
        days >= 2 -> String.format(Locale.US, "%.0f days", days)
        else -> String.format(Locale.US, "%.1f hours", seconds / 3600)
    }
}

private val dtFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)

fun shortDateTime(epochSeconds: Int): String =
    Instant.ofEpochSecond(epochSeconds.toLong())
        .atZone(ZoneId.systemDefault())
        .format(dtFormatter)
