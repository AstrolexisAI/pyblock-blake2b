package com.astrolexis.pyblock.data.nostr

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shared helpers for what a chat message may show. Mirrors iOS `ChatMedia`. */
object ChatMedia {
    /**
     * Hosts an image in a message is allowed to come from.
     *
     * The screen used to accept any URL after `pyblock:img?url=` and hand it straight to the image
     * loader. So anyone in the room could post a link to a server they control and collect the IP
     * address of every person who scrolled past it — a tracking pixel, in a chat whose whole point
     * is that it needs no account. Images posted from the apps go to our own host, so an allowlist
     * costs nothing. Anything else renders as a link the reader can choose to open.
     */
    private val allowedHosts = setOf("pyblock.xyz", "b.pyblock.xyz", "nostr.pyblock.xyz")

    private fun rawImageUrl(content: String): String? =
        if (content.startsWith("pyblock:img?")) Regex("url=([^&\\s]+)").find(content)?.groupValues?.get(1) else null

    /** The image URL if this message is an image marker we are willing to load, else null. */
    fun imageUrl(content: String): String? {
        val raw = rawImageUrl(content) ?: return null
        val u = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        return if (u.scheme == "https" && u.host?.lowercase() in allowedHosts) raw else null
    }

    /** An image marker we refused to load, so the screen can say so instead of showing nothing. */
    fun foreignImageUrl(content: String): String? =
        rawImageUrl(content)?.takeIf { imageUrl(content) == null }

    /** Time of day for today, day and time before that. One formatter each, not one per row. */
    private val today = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val older = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val dayFmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
    private val dayYearFmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    fun timeOnly(ts: Long): String = synchronized(this) { timeFmt.format(Date(ts * 1000)) }
    fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { time = Date(a * 1000) }; val cb = Calendar.getInstance().apply { time = Date(b * 1000) }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
    /** TODAY / YESTERDAY / a date, for the separators between days in a transcript. */
    fun dayLabel(ts: Long): String {
        val now = System.currentTimeMillis() / 1000
        if (sameDay(ts, now)) return com.astrolexis.pyblock.data.store.AppStrings.get(com.astrolexis.pyblock.R.string.blk_today)
        if (sameDay(ts, now - 86_400)) return com.astrolexis.pyblock.data.store.AppStrings.get(com.astrolexis.pyblock.R.string.blk_yesterday)
        val d = Date(ts * 1000); val thisYear = Calendar.getInstance().get(Calendar.YEAR) == Calendar.getInstance().apply { time = d }.get(Calendar.YEAR)
        return synchronized(this) { (if (thisYear) dayFmt else dayYearFmt).format(d).uppercase() }
    }

    fun clock(ts: Long): String {
        val d = Date(ts * 1000)
        val now = Calendar.getInstance(); val then = Calendar.getInstance().apply { time = d }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return synchronized(this) { (if (sameDay) today else older).format(d) }
    }
}
