package com.astrolexis.pyblock.data.util

/** Ensures no hashrate-provider brand or provider order number ever reaches the
 *  UI. Every server error string shown to the user MUST pass through [clean].
 *  Normalises away spaces/punctuation so "MyRig", "mining-rig", "M.R.R" etc.
 *  are caught, not just exact tokens. */
object BrandGuard {
    private val LEAKS = listOf(
        "nicehash", "miningrigrentals", "miningrig", "myrig",
        "mrr", "rigrental", "braiins", "provider",
    )
    private const val GENERIC = "Something went wrong — try again."

    fun clean(s: String?, fallback: String = GENERIC): String {
        val msg = s?.takeIf { it.isNotBlank() } ?: return fallback
        val norm = msg.lowercase().replace(Regex("[^a-z0-9]"), "")
        return if (LEAKS.any { norm.contains(it) }) fallback else msg
    }
}
