package com.astrolexis.pyblock.data.blake

import com.astrolexis.pyblock.data.net.HmacSigner
import com.astrolexis.pyblock.data.store.DeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Hashrate rentals on BLAKE2b — rent hash delivered to YOUR payout address on one of the pool's
 * stratums. Packages are fixed size × duration at a live market price; `total_sats` is everything
 * the buyer pays (fee inside; the upstream provider and cost are never exposed). Mirrors iOS.
 */
object BlakeRentals {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val JSON_MEDIA = "application/json".toMediaType()

    @Serializable
    data class Rig(
        val id: String,
        val label: String? = null,
        val th: Double? = null,
        val ph: Double? = null,
        val hours: Int? = null,
        @SerialName("total_sats") val totalSats: Long? = null,
        @SerialName("sat_th_h") val satThH: Double? = null,
        val available: Boolean? = true,
    )
    @Serializable
    data class PoolInfo(val port: Int? = null, val label: String? = null, val split: String? = null, val note: String? = null)
    @Serializable
    data class Rigs(
        val ok: Boolean? = null,
        val rigs: List<Rig> = emptyList(),
        val pools: Map<String, PoolInfo> = emptyMap(),
        val durations: List<Int> = emptyList(),
        val notice: String? = null,
    )
    @Serializable
    data class Quote(
        val ok: Boolean? = null,
        @SerialName("total_sats") val totalSats: Long? = null,
        val th: Double? = null,
        val hours: Int? = null,
        val pool: String? = null,
        val port: Int? = null,
        val expires: Long? = null,
        @SerialName("fee_pct") val feePct: Double? = null,
    )
    @Serializable
    data class Order(
        val ok: Boolean? = null,
        @SerialName("order_id") val orderId: String? = null,
        val invoice: String? = null,
        @SerialName("total_sats") val totalSats: Long? = null,
        @SerialName("expires_at") val expiresAt: Long? = null,
        val status: String? = null,
        val pool: String? = null,
        val port: Int? = null,
        val th: Double? = null,
        val hours: Int? = null,
    )
    /** Public tracking record (`hashrate_status.php?id=`). Address comes masked on purpose. */
    @Serializable
    data class OrderStatus(
        val id: String,
        val status: String? = null,
        @SerialName("amount_sats") val amountSats: Long? = null,
        @SerialName("fee_sats") val feeSats: Long? = null,
        @SerialName("total_sats") val totalSats: Long? = null,
        @SerialName("btc_address") val btcAddress: String? = null,
        val port: Int? = null,
        @SerialName("hashrate_phs") val hashratePhs: Double? = null,
        @SerialName("live_hashrate_phs") val liveHashratePhs: Double? = null,
        @SerialName("hr_series") val hrSeries: JsonArray? = null,
        @SerialName("duration_h") val durationH: Double? = null,
        val invoice: String? = null,
        @SerialName("still_payable") val stillPayable: Boolean? = null,
        @SerialName("error_msg") val errorMsg: String? = null,
        @SerialName("created_at") val createdAt: Long? = null,
        @SerialName("updated_at") val updatedAt: Long? = null,
        @SerialName("expires_at") val expiresAt: Long? = null,
        @SerialName("seconds_left") val secondsLeft: Long? = null,
        @SerialName("consumed_sat") val consumedSat: Long? = null,
        @SerialName("percent_done") val percentDone: Double? = null,
    ) {
        /** Delivered hashrate samples in TH/s (`[[ts, phs], …]`). */
        fun seriesTh(): List<Double> = hrSeries?.mapNotNull { e: JsonElement ->
            runCatching { e.jsonArray.getOrNull(1)?.jsonPrimitive?.doubleOrNull?.times(1000) }.getOrNull()
        } ?: emptyList()
    }
    @Serializable private data class StatusResp(val ok: Boolean? = null, val order: OrderStatus? = null)
    /** An order placed by THIS device (signed read). */
    @Serializable
    data class DeviceOrder(
        val id: String,
        val chain: String? = null,
        val provider: String? = null,
        val pool: String? = null,
        @SerialName("btc_address") val btcAddress: String? = null,
        val port: Int? = null,
        @SerialName("total_sats") val totalSats: Long? = null,
        @SerialName("hashrate_phs") val hashratePhs: Double? = null,
        val th: Double? = null,
        @SerialName("duration_h") val durationH: Double? = null,
        @SerialName("ln_invoice") val lnInvoice: String? = null,
        val status: String? = null,
        @SerialName("created_at") val createdAt: Long? = null,
        @SerialName("expires_at") val expiresAt: Long? = null,
        @SerialName("error_msg") val errorMsg: String? = null,
    )
    @Serializable private data class DeviceOrders(val ok: Boolean? = null, val orders: List<DeviceOrder> = emptyList())
    @Serializable private data class ErrResp(val ok: Boolean? = null, val errors: List<String>? = null, val error: String? = null, val message: String? = null, val notice: String? = null)

    class Failure(msg: String) : Exception(msg)

    private fun serverMessage(body: String, status: Int): String {
        runCatching { json.decodeFromString<ErrResp>(body) }.getOrNull()?.let { e ->
            (e.errors?.firstOrNull() ?: e.error ?: e.message ?: e.notice)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return if (status == 404) "Rentals are paused right now." else "Server error ($status)."
    }

    /** Public: live package list + delivery pools. Refresh ≤ 60 s (prices move). */
    suspend fun rigs(): Rigs? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(BlakeApi.BASE + "/api/app/blake2b_rigs.php").header("Cache-Control", "no-cache").get().build()
            client.newCall(req).execute().use { r -> if (!r.isSuccessful) null else json.decodeFromString<Rigs>(r.body?.string().orEmpty()) }
        }.getOrNull()
    }

    /** Signed request with one self-heal (403 → re-register → retry). Returns body + status, null on transport failure. */
    private suspend fun signed(method: String, path: String, query: String = "", body: ByteArray = ByteArray(0)): Pair<String, Int>? = withContext(Dispatchers.IO) {
        var creds = DeviceStore.credentials() ?: return@withContext null
        repeat(2) { attempt ->
            val s = HmacSigner.sign(method, path, body, creds.second)
            val b = Request.Builder().url(BlakeApi.BASE + path + query)
                .addHeader("X-PyBLOCK-Device-Id", creds.first.toString())
                .addHeader("X-PyBLOCK-Timestamp", s.ts)
                .addHeader("X-PyBLOCK-Nonce", s.nonce)
                .addHeader("X-PyBLOCK-Signature", s.sig)
            if (method == "POST") b.post(body.toRequestBody(JSON_MEDIA)).addHeader("Content-Type", "application/json") else b.get()
            val resp = try { client.newCall(b.build()).execute() } catch (e: java.io.IOException) { return@withContext null }
            resp.use { r ->
                if (r.code == 403 && attempt == 0) {
                    creds = DeviceStore.credentials(forceNew = true) ?: return@withContext null
                } else return@withContext (r.body?.string().orEmpty() to r.code)
            }
        }
        null
    }

    private fun bodyOf(vararg kv: Pair<String, String>): ByteArray =
        buildJsonObject { kv.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString().toByteArray()

    suspend fun quote(rigId: String, address: String, pool: String): Quote {
        val r = signed("POST", "/api/app/blake2b_quote.php", body = bodyOf("rig_id" to rigId, "address" to address, "pool" to pool))
            ?: throw Failure(if (DeviceStore.peek() == null) "Couldn't register this device." else "Can't reach the server.")
        val q = if (r.second == 200) runCatching { json.decodeFromString<Quote>(r.first) }.getOrNull() else null
        if (q?.ok != true || (q.totalSats ?: 0) <= 0) throw Failure(serverMessage(r.first, r.second))
        return q
    }

    /** Sats a BOLT11 invoice asks for, read from its human-readable part (`lnbc<amount><mult>1…`).
     *  null when the invoice carries no amount or can't be parsed — both of which we refuse to pay. */
    fun invoiceSats(invoice: String): Long? {
        val s = invoice.lowercase()
        if (!s.startsWith("lnbc")) return null
        val sep = s.lastIndexOf('1')
        if (sep <= 4) return null
        var amount = s.substring(4, sep)
        if (amount.isEmpty()) return null                       // amountless invoice
        var multiplier = 1.0
        val last = amount.last()
        if (last in "munp") {
            multiplier = mapOf('m' to 1e-3, 'u' to 1e-6, 'n' to 1e-9, 'p' to 1e-12)[last] ?: 1.0
            amount = amount.dropLast(1)
        }
        val v = amount.toDoubleOrNull() ?: return null
        if (v < 0) return null
        val sats = v * multiplier * 100_000_000
        if (!sats.isFinite() || sats < 0 || sats >= 21_000_000.0 * 100_000_000) return null
        return Math.round(sats)
    }

    /** Places the order; the server re-quotes at pay time. `clientNonce` makes a retry idempotent. */
    suspend fun order(rigId: String, address: String, pool: String, clientNonce: String): Order {
        val r = signed("POST", "/api/app/blake2b_order.php",
            body = bodyOf("rig_id" to rigId, "address" to address, "pool" to pool, "client_nonce" to clientNonce))
            ?: throw Failure(if (DeviceStore.peek() == null) "Couldn't register this device." else "Can't reach the server.")
        val o = if (r.second == 200) runCatching { json.decodeFromString<Order>(r.first) }.getOrNull() else null
        if (o?.ok != true || o.invoice.isNullOrBlank() || o.orderId.isNullOrBlank()) throw Failure(serverMessage(r.first, r.second))
        // FUND-SAFETY: the screen shows a total and a QR that come from two different fields of the
        // same response. Never display an invoice asking for something other than the total shown —
        // a wrong (or hostile) server would otherwise collect a silent overpay.
        val asked = invoiceSats(o.invoice)
        if (asked == null || asked != (o.totalSats ?: -1L)) {
            throw Failure("The invoice asks for ${asked ?: "an unreadable amount"} sats but the order says ${o.totalSats ?: 0}. Not showing it — nothing was paid.")
        }
        return o
    }

    suspend fun status(id: String): OrderStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(BlakeApi.BASE + "/api/hashrate_status.php?id=" + URLEncoder.encode(id, "UTF-8"))
                .header("Cache-Control", "no-cache").get().build()
            client.newCall(req).execute().use { r -> if (!r.isSuccessful) null else json.decodeFromString<StatusResp>(r.body?.string().orEmpty()).order }
        }.getOrNull()
    }

    /** Orders placed by this device on BLAKE2b, newest first. */
    suspend fun myOrders(): List<DeviceOrder> {
        val r = signed("GET", "/api/app/orders_by_device.php", query = "?chain=blake2b") ?: return emptyList()
        if (r.second != 200) return emptyList()
        val d = runCatching { json.decodeFromString<DeviceOrders>(r.first) }.getOrNull() ?: return emptyList()
        return d.orders.filter { (it.chain ?: "blake2b") == "blake2b" }.sortedByDescending { it.createdAt ?: 0 }
    }

    // ---- Presentation ----

    fun th(v: Double?): String = when {
        v == null -> "—"
        v >= 1000 -> "%.2f PH/s".format(java.util.Locale.US, v / 1000)
        v >= 1 -> (if (v == Math.rint(v)) "%.0f TH/s" else "%.1f TH/s").format(java.util.Locale.US, v)
        else -> "%.0f GH/s".format(java.util.Locale.US, v * 1000)
    }
    fun sats(v: Long?): String = v?.let { "%,d sats".format(java.util.Locale.US, it) } ?: "—"

    val lifecycle = listOf("pending_payment", "paid", "active", "completed")
    fun statusLabel(s: String?): String = when (s) {
        "pending_payment" -> "AWAITING PAYMENT"
        "paid" -> "PAID · STARTING"
        "active" -> "DELIVERING"
        "completed" -> "COMPLETED"
        "expired" -> "EXPIRED"
        "failed" -> "FAILED"
        null -> "—"
        else -> s.replace('_', ' ').uppercase()
    }
    fun statusIsFinal(s: String?): Boolean = s in listOf("completed", "expired", "failed") || (s ?: "").startsWith("ai_") || (s ?: "").startsWith("admin_")
}
