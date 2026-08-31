package com.astrolexis.pyblock.data.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Reliable mainnet transaction propagation. PyBLØCK broadcasts through its own single
 * CBF peer (sovereign), but that node doesn't always relay to the wider network — real
 * sends were getting stuck in its mempool without reaching miners. This posts the raw tx
 * to public relays so it actually propagates. Used ALONGSIDE the CBF broadcast (which
 * stays for sovereignty); a send succeeds if EITHER path accepts it. Android mirror of
 * iOS `PublicBroadcast.swift`.
 */
object PublicBroadcast {
    /** Public raw-tx submission endpoints (raw hex in the POST body → 200 + txid). */
    private val endpoints = listOf(
        "https://mempool.space/api/tx",
        "https://blockstream.info/api/tx",
    )

    // 10 s so a slow/unreachable relay can't stall a send (matches iOS).
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val plain = "text/plain".toMediaType()

    /** POST the raw tx hex to public relays; true if any accepted it (200 + a 64-hex txid,
     *  or an "already in mempool/known" reply). Tries each endpoint in order, stops at the
     *  first success. */
    suspend fun submit(rawHex: String): Boolean = withContext(Dispatchers.IO) {
        for (ep in endpoints) {
            val body = rawHex.toRequestBody(plain)
            val req = Request.Builder().url(ep).post(body).build()
            val ok = runCatching {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string()?.trim().orEmpty()
                    (resp.code == 200 && text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) ||
                        text.contains("already", ignoreCase = true)
                }
            }.getOrDefault(false)
            if (ok) return@withContext true
        }
        false
    }

    /** Raw hex of a serialized tx's bytes (BDK's `Transaction.serialize()` → ByteArray). */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
