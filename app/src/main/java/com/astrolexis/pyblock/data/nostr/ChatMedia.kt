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
    fun clock(ts: Long): String {
        val d = Date(ts * 1000)
        val now = Calendar.getInstance(); val then = Calendar.getInstance().apply { time = d }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return synchronized(this) { (if (sameDay) today else older).format(d) }
    }
}
