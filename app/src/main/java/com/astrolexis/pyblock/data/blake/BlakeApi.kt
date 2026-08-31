package com.astrolexis.pyblock.data.blake

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * PyBLØCK BLAKE2b backend (pyblock.xyz:8443). All reads are chain=blake2b (Node B).
 * Public dashboards need no auth. Lenient decoding (ignoreUnknownKeys). Self-contained
 * — its own OkHttp client, separate from the SHA-256 rail. Mirrors iOS `BlakeAPI`.
 */
object BlakeApi {
    const val BASE = "https://pyblock.xyz:8443"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json".toMediaType()

    // ---- Models ----

    @Serializable
    data class PoolStats(
        val ok: Boolean = false,
        val pool: String? = null,
        val miners: Int? = null,
        val connections: Int? = null,
        @SerialName("network_hashrate_ths") val networkHashrateThs: Double? = null,
        @SerialName("block_height") val blockHeight: Int? = null,
        @SerialName("shares_accepted") val sharesAccepted: Int? = null,
        @SerialName("shares_rejected") val sharesRejected: Int? = null,
        @SerialName("miner_latest") val minerLatest: String? = null,
    )

    @Serializable
    data class Status(
        val ok: Boolean = false,
        val operational: Boolean? = null,
        val rc: String? = null,
        @SerialName("block_height") val blockHeight: Int? = null,
    )

    @Serializable
    data class Block(
        val height: Int = 0,
        val hash: String = "",
        @SerialName("finder_masked") val finderMasked: String? = null,
        val reward: Double? = null,
        val stratum: String? = null,
        val timestamp: Double? = null,
    )
    @Serializable private data class BlocksResp(val blocks: List<Block>? = null)

    @Serializable
    data class Utxo(
        val address: String = "",
        val txid: String = "",
        val vout: Int = 0,
        val value: Long = 0,
        val height: Int = 0,
        val coinbase: Boolean = false,
        @SerialName("script_hex") val scriptHex: String = "",   // output scriptPubkey
        val hex: String = "",                                    // full prev-tx hex (nonWitnessUtxo)
    ) {
        val id: String get() = "$txid:$vout"
    }
    @Serializable private data class UtxosResp(
        val utxos: List<Utxo>? = null,
        @SerialName("tip_height") val tipHeight: Int? = null,
        val warming: Boolean? = null,
    )

    @Serializable
    data class ChirpPool(
        val hashrate: Double? = null,
        val workers: Int? = null,
        val blocks: Int? = null,
        val candidates: Int? = null,
        val bestdiff: Double? = null,
        @SerialName("min_days") val minDays: Int? = null,
        @SerialName("min_power") val minPower: Double? = null,
    )

    // ---- Plumbing ----

    private suspend inline fun <reified T> get(path: String, crossinline deserializer: (String) -> T?): T? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(BASE + path)
                    .header("Cache-Control", "no-cache").get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    deserializer(body)
                }
            } catch (e: Exception) { null }
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---- Reads ----

    suspend fun poolStats(): PoolStats? =
        get("/api/blake_stats.php") { runCatching { json.decodeFromString<PoolStats>(it) }.getOrNull() }

    suspend fun status(): Status? =
        get("/api/chain_status.php?chain=blake2b") { runCatching { json.decodeFromString<Status>(it) }.getOrNull() }

    suspend fun blocks(): List<Block> =
        get("/api.php?mode=blocks&chain=bip110") {
            runCatching { json.decodeFromString<BlocksResp>(it) }.getOrNull()
        }?.blocks ?: emptyList()

    suspend fun chirpPool(): ChirpPool? =
        get("/chirp_api.php?chain=blake2b&mode=pool") { runCatching { json.decodeFromString<ChirpPool>(it) }.getOrNull() }

    /** UTXOs for one address on blake2b. Returns null on failure/warming (retry — never a false 0). */
    suspend fun walletUtxos(address: String): Pair<List<Utxo>, Int>? {
        val r = get("/api/wallet_utxos.php?chain=blake2b&addresses=${enc(address)}") {
            runCatching { json.decodeFromString<UtxosResp>(it) }.getOrNull()
        } ?: return null
        if (r.warming == true) return null
        return (r.utxos ?: emptyList()) to (r.tipHeight ?: 0)
    }

    // ---- Broadcast (send/ricochet — gated) ----

    sealed class PushErr : Exception() {
        object Disabled : PushErr()
        object BadResponse : PushErr()
        data class Rejected(val errors: List<String>) : PushErr()
    }

    @Serializable private data class PushResult(val txid: String? = null, val accepted: Boolean? = null)
    @Serializable private data class PushResp(
        val ok: Boolean = false,
        val result: PushResult? = null,
        val errors: List<String> = emptyList(),
    )

    /** Broadcast a raw signed BLAKE2b tx via the server (→ Node B). Returns the txid. */
    suspend fun pushTx(rawHex: String): String = withContext(Dispatchers.IO) {
        val bodyJson = json.encodeToString(mapOf("tx" to rawHex))
        val req = Request.Builder().url("$BASE/api/pushtx.php?chain=blake2b")
            .post(bodyJson.toRequestBody(JSON_MEDIA)).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw PushErr.BadResponse
            val decoded = runCatching { json.decodeFromString<PushResp>(body) }.getOrNull() ?: throw PushErr.BadResponse
            if (!decoded.ok || decoded.result?.txid.isNullOrEmpty()) {
                throw PushErr.Rejected(decoded.errors)
            }
            decoded.result!!.txid!!
        }
    }

    // ---- Push registration (payment notifications) ----

    /** Register this device's UnifiedPush endpoint + wallet addresses so the server can push
     *  a notification when a watched address receives coins. Fire-and-forget. */
    suspend fun registerPush(endpoint: String, addresses: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                val bodyJson = json.encodeToString(
                    PushReg(endpoint = endpoint, addresses = addresses)
                )
                val req = Request.Builder().url("$BASE/api/app/push/register.php?chain=blake2b")
                    .post(bodyJson.toRequestBody(JSON_MEDIA)).build()
                client.newCall(req).execute().use { }
            } catch (e: Exception) { /* fire-and-forget */ }
        }
    }
    @Serializable private data class PushReg(
        val endpoint: String,
        val addresses: List<String>,
        val platform: String = "android",
        @SerialName("push_provider") val pushProvider: String = "unifiedpush",
        val bundle: String = "com.astrolexis.pyblockblake2b",
    )

    // ---- Chat image upload (shared community media) ----

    @Serializable private data class UploadResp(val ok: Boolean = false, val url: String = "")

    /** Upload a JPEG to the shared chat-media store; returns its public URL. Same store the
     *  SHA-256 app uses, so images post from either app appear in the one community. */
    suspend fun uploadChatImage(jpegBase64: String): String = withContext(Dispatchers.IO) {
        val bodyJson = json.encodeToString(mapOf("data" to jpegBase64, "mime" to "image/jpeg"))
        val req = Request.Builder().url("$BASE/api/app/chat/upload_public.php")
            .post(bodyJson.toRequestBody(JSON_MEDIA)).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw PushErr.BadResponse
            val decoded = runCatching { json.decodeFromString<UploadResp>(body) }.getOrNull()
            if (decoded == null || !decoded.ok || decoded.url.isEmpty()) throw PushErr.BadResponse
            decoded.url
        }
    }
}
