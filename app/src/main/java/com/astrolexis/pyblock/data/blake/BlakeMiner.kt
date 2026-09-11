package com.astrolexis.pyblock.data.blake

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A miner's own view: the payout address they use as stratum username, across every pool port,
 * plus the blocks that address was paid in and the connect info. Public read. Mirrors iOS.
 */
object BlakeMiner {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    @Serializable
    data class Worker(
        val name: String? = null,
        @SerialName("hashrate_th") val hashrateTh: Double? = null,
        val lastshare: Long? = null,
        @SerialName("shares_diff_acc") val sharesDiffAcc: Double? = null,
        @SerialName("shares_diff_rej") val sharesDiffRej: Double? = null,
        val vdiff: Double? = null,
        val agent: String? = null,
    )
    @Serializable
    data class PoolEntry(
        val pool: String = "",
        val label: String? = null,
        val port: Int? = null,
        @SerialName("port_highdiff") val portHighdiff: Int? = null,
        val hashrate1m: Double? = null,
        val hashrate5m: Double? = null,
        val hashrate1h: Double? = null,
        val hashrate1d: Double? = null,
        val workers: List<Worker> = emptyList(),
        @SerialName("workers_online") val workersOnline: Int? = null,
        @SerialName("share_pct") val sharePct: Double? = null,
        @SerialName("pool_hashrate_th") val poolHashrateTh: Double? = null,
        val lastshare: Long? = null,
        val live: Boolean? = null,
    )
    @Serializable
    data class MinerBlock(
        val height: Int = 0,
        val time: Long? = null,
        val pool: String? = null,
        val hash: String? = null,
        @SerialName("reward_sats") val rewardSats: Long? = null,
        @SerialName("block_value_sats") val blockValueSats: Long? = null,
        val role: String? = null,
    )
    @Serializable
    data class ConnectPool(
        val pool: String? = null, val label: String? = null, val port: Int? = null, val kind: String? = null,
        @SerialName("min_diff") val minDiff: Double? = null, val split: String? = null,
    )
    @Serializable
    data class Connect(
        val host: String? = null, val username: String? = null, val password: String? = null,
        val pools: List<ConnectPool> = emptyList(), val vardiff: String? = null, val miners: Map<String, String> = emptyMap(),
    )
    @Serializable
    data class Stats(
        val ok: Boolean? = null,
        val address: String? = null,
        val ts: Long? = null,
        val pools: List<PoolEntry> = emptyList(),
        val blocks: List<MinerBlock> = emptyList(),
        @SerialName("best_diff_ever") val bestDiffEver: Double? = null,
        val since: Long? = null,
        val connect: Connect? = null,
    ) {
        val isMining: Boolean get() = pools.isNotEmpty()
        val totalTh1m: Double get() = pools.sumOf { it.hashrate1m ?: 0.0 }
        val totalTh1d: Double get() = pools.sumOf { it.hashrate1d ?: 0.0 }
        val workersOnline: Int get() = pools.sumOf { it.workersOnline ?: 0 }
    }
    @Serializable
    data class Point(val ts: Long? = null, @SerialName("hashrate_th") val hashrateTh: Double? = null, val workers: Int? = null)
    @Serializable private data class ConnectResp(val connect: Connect? = null)

    private suspend inline fun <reified T> get(path: String): T? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(BlakeApi.BASE + path).header("Cache-Control", "no-cache").get().build()
            client.newCall(req).execute().use { r -> if (!r.isSuccessful) null else json.decodeFromString<T>(r.body?.string().orEmpty()) }
        }.getOrNull()
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun stats(address: String): Stats? = get("/api/blake2b_miner.php?address=${enc(address)}")
    suspend fun history(address: String, range: String): List<Point> =
        get<List<Point>>("/api/miner_history.php?address=${enc(address)}&range=$range&chain=blake2b") ?: emptyList()
    suspend fun connect(): Connect? = get<ConnectResp>("/api/blake2b_miner.php?connect=1")?.connect

    /** Probe a batch of addresses and keep the ones with any pool presence (auto-detect "my miners"). */
    suspend fun findMining(among: List<String>): List<String> = coroutineScope {
        among.map { a -> async { a to (stats(a)?.isMining == true) } }.awaitAll().filter { it.second }.map { it.first }
    }
}
