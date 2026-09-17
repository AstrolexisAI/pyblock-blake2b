package com.astrolexis.pyblock.data.blake

import com.astrolexis.pyblock.R
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
    /** The BLAKE2b site. Most endpoints answer on either host with `chain=blake2b`; the lap
     *  (`carousel.php?carrousel=1`) and the Primes (`datum_modes_api.php`) exist only here — on the
     *  other host the first is an HTML page and the second a 404. */
    const val B_BASE = "https://b.pyblock.xyz:8443"

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
        @SerialName("network_hashrate_ths") val networkHashrateThs: Double? = null,  // LEGACY: only LOTTO gateways
        @SerialName("pool_hashrate_ths") val poolHashrateThs: Double? = null,        // whole pool, all stratums
        @SerialName("chain_hashrate_ths") val chainHashrateThs: Double? = null,      // entire BLAKE2b network
        @SerialName("hashrate_by_stratum_ths") val hashrateByStratum: Map<String, Double>? = null,
        val flagship: String? = null,   // "lotto" | "carousel" — AUTHORITATIVE (the gateways' own
                                        // `active`, not a height guess). Omitted if unknown.
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
        val difficulty: Double? = null,
        @SerialName("protocol") val protocolName: String? = null,
        val confirmed: Boolean? = null,
        val timestamp: Double? = null,
        /** "datum" when the finder's own gateway built the block, "stratum" through the house. Optional:
         *  the feed only starts carrying it after SERVER_ACTION_blocks_via_datum. */
        val via: String? = null,
        @SerialName("gateway_name") val gatewayName: String? = null,
    ) { val builtOnDatum get() = via?.lowercase() == "datum" }
    @Serializable private data class BlocksResp(val blocks: List<Block>? = null)

    /** One coinbase output — how the block reward was split (carousel/chirp share the coinbase
     *  across many miners). Populated by the server's per-block endpoint. */
    @Serializable
    data class CoinbaseOut(
        val address: String? = null,
        val sats: Long? = null,
        val share: Double? = null,   // 0..1 fraction, if the pool reports it
    )
    @Serializable
    data class BlockDetail(
        val height: Int? = null,
        val reward: Double? = null,
        val stratum: String? = null,
        val architect: String? = null,   // node-runner / template supplier (from the coinbase scriptsig)
        val coinbase: List<CoinbaseOut>? = null,
    )

    // ---- WAVICLES (DATUM pool) ----
    @Serializable data class WaviclesStats(
        val ok: Boolean? = null,
        val hashrate: WHashrate? = null,
        val gateways: Int? = null,
        val pool: WPool? = null,
        val wavicles: WWav? = null,
        val window: WWindow? = null,
        val blocks: List<WBlock> = emptyList(),
        /** The gateways connected to the Prime right now — the house one and every node-runner's. */
        val clients: List<WClient> = emptyList(),
    )
    @Serializable data class WClient(
        val gateway: String? = null, val generation: String? = null, val identity: String? = null, val name: String? = null,
        @SerialName("user_agent") val userAgent: String? = null, @SerialName("connected_s") val connectedS: Int? = null,
        @SerialName("last_share_s") val lastShareS: Int? = null, val accepted: Int? = null, @SerialName("fee_path") val feePath: String? = null,
        val payable: Boolean? = null,
    ) { val onDatum get() = feePath == "datum" }
    @Serializable data class WHashrate(@SerialName("pool_ghs") val poolGhs: Double? = null)
    @Serializable data class WDatum(val host: String? = null, val port: Int? = null, val pubkey: String? = null)
    @Serializable data class WPool(
        val address: String? = null,
        @SerialName("fee_bps") val feeBps: Int? = null,
        @SerialName("min_payout") val minPayout: Int? = null,
        @SerialName("window_multiple") val windowMultiple: Int? = null,
        /** The OTHER fee: a block reaching the pool through PyBLØCK's stratum pays this instead of
         *  [feeBps] (2.9% vs 0.4% with your own node). The screen showed 0.4% for both. */
        @SerialName("stratum_fee_bps") val stratumFeeBps: Int? = null,
        val datum: WDatum? = null,
    )
    @Serializable data class WWav(
        @SerialName("carry_total_sats") val carryTotalSats: Long? = null,
        @SerialName("last_snapshot") val lastSnapshot: String? = null,
        /** Whether the carry mechanism EXISTS. It is off on the pool: what a coinbase can't place is
         *  not owed — it stays in the split as BelowCut and is visible in the snapshot committed to
         *  the OP_RETURN. The flag says whether the rule is alive; the number only says how much
         *  there once was. */
        @SerialName("carry_forward") val carryForward: Boolean? = null,
    )
    @Serializable data class WWindow(
        @SerialName("fill_percent") val fillPercent: Double? = null,
        val identities: Int? = null,
        @SerialName("sample_value") val sampleValue: Long? = null,
        @SerialName("sample_fee_sats") val sampleFeeSats: Long? = null,
        @SerialName("sample_pool_sats") val samplePoolSats: Long? = null,
        val miners: List<WMiner> = emptyList(),
    )
    @Serializable data class WMiner(
        val identity: String? = null,
        @SerialName("share_percent") val sharePercent: Double? = null,
        @SerialName("payout_sats") val payoutSats: Long? = null,
        @SerialName("last_share_s") val lastShareS: Int? = null,
        val payable: Boolean? = null,
        @SerialName("fee_path") val feePath: String? = null,
    ) { val onDatum get() = feePath == "datum" }
    @Serializable data class WBlock(val height: Int? = null, val finder: String? = null, val gateway: String? = null, val ts: Double? = null,
                                    @SerialName("coinbase_value") val coinbaseValue: Long? = null, val kind: String? = null,
                                    val via: String? = null, @SerialName("gateway_name") val gatewayName: String? = null)

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
        val ok: Boolean? = null,
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
        /** How the block is divided, as the gateway reports it. Never hardcode this: CHIRP takes
         *  0.9% on SHA-256 and 1% + 1% here, and the header used to print the other chain's number. */
        val split: ChirpSplit? = null,
    )

    @Serializable data class ChirpSplitPct(
        val syndicate: Double? = null, val supplier: Double? = null, val pool: Double? = null,
    )
    @Serializable data class ChirpSplit(
        @SerialName("supplier_bps") val supplierBps: Int? = null,
        @SerialName("fee_bps") val feeBps: Int? = null,
        val pct: ChirpSplitPct? = null,
    )

    /** One connected participant on the CHIRP syndicate (server `mode=workers`). */
    @Serializable
    data class ChirpWorker(
        val name: String? = null,                                   // worker label or masked payout address
        @SerialName("hashrate_ths") val hashrateThs: Double? = null,
        val connected: Boolean = true,
        @SerialName("last_share") val lastShare: Long? = null,      // epoch seconds
        val eligible: Boolean? = null,                              // meets the split floor (loyalty + power); server-authoritative
        val share: Double? = null,                                  // reward-split weight 0..1 (white-paper weighted split)
        /** How this address mines NOW: "prime" (own gateway) · "stratum" (house) · "both". Server-side, by address. */
        val via: String? = null,
        @SerialName("prime_last_share_s") val primeLastShareS: Int? = null,
    ) { val onPrime get() = via == "prime" || via == "both" }

    @Serializable
    private data class ChirpWorkersResp(val workers: List<ChirpWorker> = emptyList())

    /** The syndicate registry (`mode=miners`): tenure and power per address — what the draw weighs.
     *  The workers list only knows who is on the house stratum right now. */
    @Serializable
    data class ChirpMiner(val address: String? = null, val days: Double? = null, val power: Double? = null,
                          val weight: Double? = null, val eligible: Boolean? = null, @SerialName("last_seen") val lastSeen: Long? = null,
                          val via: String? = null, @SerialName("prime_last_share_s") val primeLastShareS: Int? = null) {
        val onPrime get() = via == "prime" || via == "both"
    }


    // ---- Transaction status ----
    /** What Node B says about a transaction we broadcast (`api/tx_status.php`): in its mempool,
     *  confirmed, or never seen. The answer to "did my send land?" — not a clock. */
    @Serializable data class TxStatus(val ok: Boolean? = null, val status: String? = null, val confirmations: Int? = null, val height: Int? = null) {
        val inMempool get() = status == "mempool"; val confirmed get() = status == "confirmed"; val unknown get() = status == "unknown"
    }
    suspend fun txStatus(txid: String): TxStatus? {
        if (txid.length != 64 || !txid.all { it in "0123456789abcdefABCDEF" }) return null
        return get("/api/tx_status.php?chain=blake2b&txid=$txid") { runCatching { json.decodeFromString<TxStatus>(it) }.getOrNull() }
    }

    // ---- CAROUSEL (the lap) ----

    /** The rotation as the website shows it (`carousel.php?carrousel=1`): who is being mined now, the next
     *  turns, the size and rhythm of the lap. Names are what suppliers typed; addresses come masked. */
    @Serializable data class CarouselNext(val addr: String? = null, val name: String? = null)
    @Serializable
    data class CarouselLap(val live: Boolean? = null, val current: String? = null, val miners: Int? = null, val hashrate: Double? = null,
                           val next: List<CarouselNext> = emptyList(), @SerialName("ring_n") val ringN: Int? = null, @SerialName("ring_idx") val ringIdx: Int? = null,
                           @SerialName("block_stride") val blockStride: Int? = null, @SerialName("cycle_s") val cycleS: Int? = null, @SerialName("lap_s") val lapS: Int? = null)
    suspend fun carouselLap(): CarouselLap? = get(B_BASE + "/carousel.php?carrousel=1") { runCatching { json.decodeFromString<CarouselLap>(it) }.getOrNull() }

    /** Where one template stands in the lap (`carousel.php?me=<address>`). No amounts. */
    @Serializable
    data class CarouselMe(val ok: Boolean? = null, @SerialName("in_ring") val inRing: Boolean? = null, val n: Int? = null, val position: Int? = null,
                          @SerialName("turns_ahead") val turnsAhead: Int? = null, @SerialName("eta_s") val etaS: Int? = null,
                          @SerialName("picks_24h") val picks24h: Int? = null, val blocks: Int? = null, @SerialName("mining_now") val miningNow: Boolean? = null)
    suspend fun carouselMe(address: String): CarouselMe? =
        get(B_BASE + "/carousel.php?me=${URLEncoder.encode(address, "UTF-8")}") { runCatching { json.decodeFromString<CarouselMe>(it) }.getOrNull() }

    /** The live split of a product, from the gateway's bps (`api/split.php`). Never hardcoded. */
    @Serializable
    data class SplitProduct(@SerialName("node_runner_pct") val nodeRunnerPct: Double? = null, @SerialName("pool_pct") val poolPct: Double? = null,
                            @SerialName("miner_pct") val minerPct: Double? = null, val label: String? = null, val short: String? = null)
    @Serializable private data class SplitResp(val ok: Boolean? = null, val products: Map<String, SplitProduct> = emptyMap())
    suspend fun split(product: String): SplitProduct? =
        get("/api/split.php?product=$product&chain=blake2b") { runCatching { json.decodeFromString<SplitResp>(it).products[product] }.getOrNull() }

    /** A product's DATUM Prime: where a gateway connects and how that path splits the block. */
    @Serializable data class PrimeConnect(val host: String? = null, val port: Int? = null)
    @Serializable data class PrimeSplit(val label: String? = null, @SerialName("miners_bps") val minersBps: Int? = null,
                                        @SerialName("node_runner_bps") val nodeRunnerBps: Int? = null, @SerialName("pool_bps") val poolBps: Int? = null)
    @Serializable data class PrimeRunner(val gateway: String? = null, val generation: String? = null, val identity: String? = null, val name: String? = null,
                                         val accepted: Int? = null, val work: Double? = null, @SerialName("connected_s") val connectedS: Int? = null,
                                         @SerialName("last_share_s") val lastShareS: Int? = null)
    @Serializable data class PrimeInfo(val ok: Boolean? = null, val connect: PrimeConnect? = null, val split: PrimeSplit? = null,
                                       val gateways: Int? = null, @SerialName("hashrate_ghs") val hashrateGhs: Double? = null,
                                       val runners: List<PrimeRunner> = emptyList(), @SerialName("node_runners_registered") val nodeRunnersRegistered: Int? = null)
    suspend fun prime(product: String): PrimeInfo? = get(B_BASE + "/datum_modes_api.php?product=$product") { runCatching { json.decodeFromString<PrimeInfo>(it) }.getOrNull() }

    // ---- Plumbing ----

    private suspend inline fun <reified T> get(path: String, crossinline deserializer: (String) -> T?): T? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(if (path.startsWith("http")) path else BASE + path)
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

    /** Recent mined blocks, NEWEST FIRST. The endpoint returns them oldest-first, so a naive
     *  take(N) showed the fork's earliest blocks (all `lotto`) instead of recent activity — sort
     *  by height descending so the UI shows the latest blocks and their real stratum mix. */
    suspend fun blocks(): List<Block> =
        (get("/api.php?mode=blocks&chain=bip110") {
            runCatching { json.decodeFromString<BlocksResp>(it) }.getOrNull()
        }?.blocks ?: emptyList())
            .filter { it.confirmed != false }   // drop orphaned blocks (reorged out — not real rewards)
            .sortedByDescending { it.height }

    /** Per-block detail incl. the coinbase split. Returns null until the server exposes
     *  `mode=block` (then the block detail dialog shows the split). */
    suspend fun blockDetail(height: Int): BlockDetail? =
        get("/api.php?mode=block&chain=bip110&height=$height") {
            runCatching { json.decodeFromString<BlockDetail>(it) }.getOrNull()?.takeIf { d -> d.coinbase != null }
        }

    suspend fun chirpPool(): ChirpPool? =
        get("/chirp_api.php?chain=blake2b&mode=pool") { runCatching { json.decodeFromString<ChirpPool>(it) }.getOrNull() }

    /** WAVICLES (DATUM pool) live dashboard. null on failure; ok=false → pool offline. */
    suspend fun wavicles(): WaviclesStats? =
        get("/wavicles_api.php?mode=stats") { runCatching { json.decodeFromString<WaviclesStats>(it) }.getOrNull() }

    /** Connected CHIRP participants. null on a fetch failure (so callers keep last-good instead
     *  of blanking); empty list only on a genuine empty response. */
    suspend fun chirpWorkers(): List<ChirpWorker>? =
        get("/chirp_api.php?chain=blake2b&mode=workers") {
            runCatching { json.decodeFromString<ChirpWorkersResp>(it).workers }.getOrNull()
        }
    suspend fun chirpMiners(): List<ChirpMiner>? =
        get("/chirp_api.php?chain=blake2b&mode=miners") { runCatching { json.decodeFromString<List<ChirpMiner>>(it) }.getOrNull() }
    /** Who is on CHIRP-PRIME (their own gateway): identity masked `6…4` → seconds since last share. */
    suspend fun chirpPrimeRunners(): Map<String, Int>? = prime("chirp")?.runners?.mapNotNull { r -> r.identity?.takeIf { it.isNotEmpty() }?.let { it to (r.lastShareS ?: Int.MAX_VALUE) } }?.toMap()

    /** UTXOs for one address on blake2b. Returns null on failure/warming (retry — never a false 0). */
    suspend fun walletUtxos(address: String): Pair<List<Utxo>, Int>? {
        val r = get("/api/wallet_utxos.php?chain=blake2b&addresses=${enc(address)}") {
            runCatching { json.decodeFromString<UtxosResp>(it) }.getOrNull()
        } ?: return null
        // FUND-SAFETY: only an `ok:true` body is authoritative. The server returns ok:false for
        // warming, an oversized address, or an internal error; a hard error is HTTP 400. This used to
        // trust `utxos ?: emptyList()`, so a body WITHOUT the key — which is what a php-fpm fatal
        // produced, since the endpoint sends its headers before doing the work — became an empty list
        // and the wallet showed ZERO for every address (the 2026-09-08 incident). Both the missing key
        // and ok:false now fail closed, so the caller keeps its last-good balance.
        if (r.ok != true || r.warming == true) return null
        val utxos = r.utxos ?: return null
        return utxos to (r.tipHeight ?: 0)
    }

    // ---- Broadcast (send/ricochet — gated) ----

    sealed class PushErr(msg: String) : Exception(msg) {
        object Disabled : PushErr(com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_blake2b_broadcasting_isn_t_enabled))
        object BadResponse : PushErr(com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_the_blake2b_server_gave_an_unexpected_re))
        // Carry the node's actual reason instead of swallowing it (was surfacing a bare "Send failed").
        class Rejected(val errors: List<String>) : PushErr(
            if (errors.isEmpty()) "The BLAKE2b network rejected the transaction."
            else "Network rejected the transaction: " + errors.joinToString("; ")
        ) {
            /** The node rejected because these inputs are already spent / already in a pending tx —
             *  i.e. a PRIOR broadcast of this same send already landed and a network hiccup made the
             *  client think it failed. Treat as "already in flight" (mark spent, don't invite a resend),
             *  NOT a fresh failure. Covers txn-mempool-conflict, bad-txns-inputs-missingorspent and the
             *  "already known/in block chain" family. */
            val alreadyInFlight: Boolean get() = errors.any {
                val s = it.lowercase()
                "txn-mempool-conflict" in s || "missingorspent" in s ||
                    "already known" in s || "txn-already-known" in s || "already in block chain" in s
            }
        }
    }

    @Serializable private data class PushResult(val txid: String? = null, val accepted: Boolean? = null)
    @Serializable private data class PushResp(
        val ok: Boolean = false,
        val result: PushResult? = null,
        val errors: List<String> = emptyList(),
    )

    /** Broadcast a raw signed BLAKE2b tx via the server (→ Node B). Returns the txid. */
    suspend fun pushTx(rawHex: String): String = withContext(Dispatchers.IO) {
        // pushtx.php expects the key "rawtx" (iOS sends this). Sending "tx" made the server see an
        // empty rawtx → "invalid rawtx (expect even-length hex)" → every Android broadcast failed.
        val bodyJson = json.encodeToString(mapOf("rawtx" to rawHex, "chain" to "blake2b"))
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

    // ---- Marks (runes earned mining) ----

    /** A rune the server certifies for a chat key, with a tier. Never a number. */
    @Serializable data class Mark(val rune: String = "", val level: Int = 1)
    @Serializable private data class MarksResp(val ok: Boolean = false, val marks: Map<String, List<Mark>> = emptyMap())

    /** Marks for up to 100 chat keys in one call. A key that is unknown, or that said no, is simply
     *  absent. Degrades to nothing while the endpoint does not exist yet. */
    suspend fun marks(pubkeys: Collection<String>): Map<String, List<Mark>> {
        val list = pubkeys.filter { it.length == 64 }.distinct().take(100)
        if (list.isEmpty()) return emptyMap()
        val r = get("/api/app/marks.php?chain=blake2b&pubkeys=${list.joinToString(",")}") {
            runCatching { json.decodeFromString<MarksResp>(it) }.getOrNull()
        } ?: return emptyMap()
        return if (r.ok) r.marks else emptyMap()
    }

    // ---- Push registration (payment notifications) ----

    /** Register this device's UnifiedPush endpoint + wallet addresses so the server can push
     *  a notification when a watched address receives coins. Fire-and-forget. */
    suspend fun registerPush(endpoint: String, addresses: List<String>, rigQuiet: Boolean = false) {
        withContext(Dispatchers.IO) {
            try {
                val bodyJson = json.encodeToString(
                    PushReg(endpoint = endpoint, addresses = addresses, preferences = mapOf("rig_quiet" to rigQuiet))
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
        /** What this device wants pushed besides payments; the server decides "quiet" where the shares are. */
        val preferences: Map<String, Boolean> = emptyMap(),
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

    // ---- Mempool (0-conf pending in/out) ----

    @Serializable
    data class MemTx(val txid: String = "", val hex: String = "", val seen: Long? = null)
    @Serializable private data class MemResp(val txs: List<MemTx>? = null)

    /** Unconfirmed (mempool) txs touching [address] on blake2b. Empty on failure. */
    suspend fun walletMempool(address: String): List<MemTx> =
        get("/api/wallet_mempool.php?chain=blake2b&address=${enc(address)}") {
            runCatching { json.decodeFromString<MemResp>(it) }.getOrNull()
        }?.txs ?: emptyList()

    private fun hexBytes(s: String): ByteArray? =
        runCatching { ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() } }.getOrNull()

    /** Sats an unconfirmed tx pays to the P2PKH [address] (sum of matching outputs). */
    fun incomingSats(address: String, txHex: String): Long {
        val dec = com.astrolexis.pyblock.data.crypto.VanityCrypto.base58Decode(address) ?: return 0
        if (dec.size != 25) return 0
        val raw = hexBytes(txHex) ?: return 0
        val spk = byteArrayOf(0x76, 0xa9.toByte(), 0x14) + dec.copyOfRange(1, 21) + byteArrayOf(0x88.toByte(), 0xac.toByte())
        val p = TxParser(raw)
        if (p.take(4) == null) return 0
        p.skipSegwitMarker()
        val nin = p.varint() ?: return 0
        repeat(nin) { if (p.take(32) == null || p.take(4) == null) return 0; val sl = p.varint() ?: return 0; if (p.take(sl) == null || p.take(4) == null) return 0 }
        val nout = p.varint() ?: return 0
        var total = 0L
        repeat(nout) {
            val valB = p.take(8) ?: return total
            val sl = p.varint() ?: return total
            val script = p.take(sl) ?: return total
            if (script.contentEquals(spk)) { var v = 0L; for (k in 0..7) v = v or ((valB[k].toLong() and 0xff) shl (8 * k)); total += v }
        }
        return total
    }

    /** Outpoints ("txid:vout", display order) an unconfirmed tx SPENDS. */
    fun spentOutpoints(txHex: String): List<String> {
        val raw = hexBytes(txHex) ?: return emptyList()
        val p = TxParser(raw)
        if (p.take(4) == null) return emptyList()
        p.skipSegwitMarker()
        val nin = p.varint() ?: return emptyList()
        val out = ArrayList<String>()
        repeat(nin) {
            val prev = p.take(32) ?: return out
            val voutB = p.take(4) ?: return out
            val txid = prev.reversed().joinToString("") { "%02x".format(it) }
            val vout = (voutB[0].toLong() and 0xff) or ((voutB[1].toLong() and 0xff) shl 8) or ((voutB[2].toLong() and 0xff) shl 16) or ((voutB[3].toLong() and 0xff) shl 24)
            out.add("$txid:$vout")
            val sl = p.varint() ?: return out
            if (p.take(sl) == null || p.take(4) == null) return out
        }
        return out
    }

    /** Minimal cursor over a raw tx for the two parsers above. */
    private class TxParser(val raw: ByteArray) {
        var i = 0
        fun take(n: Int): ByteArray? { if (i + n > raw.size) return null; val r = raw.copyOfRange(i, i + n); i += n; return r }
        fun u8(): Int? { if (i >= raw.size) return null; return raw[i++].toInt() and 0xff }
        fun varint(): Int? {
            val n = u8() ?: return null
            return when {
                n < 0xfd -> n
                n == 0xfd -> take(2)?.let { (it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8) }
                n == 0xfe -> take(4)?.let { (it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8) or ((it[2].toInt() and 0xff) shl 16) or ((it[3].toInt() and 0xff) shl 24) }
                else -> take(8)?.let { var v = 0; for (k in 0..7) v = v or ((it[k].toInt() and 0xff) shl (8 * k)); v }
            }
        }
        fun skipSegwitMarker() { if (i + 1 < raw.size && raw[i].toInt() == 0x00 && raw[i + 1].toInt() == 0x01) i += 2 }
    }
}
