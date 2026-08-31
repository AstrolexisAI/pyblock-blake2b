package com.astrolexis.pyblock.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- Blocks found ----

@Serializable
data class BlocksResponse(
    val lotto: Int = 0,
    val datum: Int = 0,
    @SerialName("last_update") val lastUpdate: Int = 0,
    val blocks: List<FoundBlock> = emptyList(),
)

@Serializable
data class FoundBlock(
    val height: Int = 0,
    val hash: String = "",
    val tag: String = "",
    @SerialName("protocol") val protocolName: String = "",
    val relay: String? = null,
    val confirmed: Boolean = false,
    val timestamp: Int = 0,
)

// ---- Block odds ----

@Serializable
data class BlockOddsResponse(
    val difficulty: Double = 0.0,
    val height: Int = 0,
    val updated: Int = 0,
    val pools: Map<String, PoolOdds> = emptyMap(),
    /** Server-computed difficulty-adjustment for NEXT DIFF ADJ; null when the
     *  server omits it (the app then falls back to the mempool network value). */
    @SerialName("diffadj") val diffAdj: DifficultyAdjustment? = null,
)

@Serializable
data class PoolOdds(
    @SerialName("hashrate_ths") val hashrateThs: Double = 0.0,
    val workers: Int = 0,
    @SerialName("seconds_per_block") val secondsPerBlock: Double = 0.0,
    val odds: Odds = Odds(),
)

@Serializable
data class Odds(
    @SerialName("24h") val d1: Double = 0.0,
    @SerialName("7d") val d7: Double = 0.0,
    @SerialName("30d") val d30: Double = 0.0,
    @SerialName("1y") val y1: Double = 0.0,
)

// ---- Network (mempool.guide) ----

@Serializable
data class DifficultyAdjustment(
    val progressPercent: Double = 0.0,
    val difficultyChange: Double = 0.0,
    val estimatedRetargetDate: Long = 0, // ms epoch
    val remainingBlocks: Int = 0,
    val remainingTime: Long = 0, // ms
)

/** BTC spot price — mempool.guide /api/v1/prices. */
@Serializable
data class MempoolPrices(@SerialName("USD") val usd: Double = 0.0)

// ---- Hashrate history ----

@Serializable
data class HashratePoint(
    val timestamp: Int = 0,
    val hashrate1m: Double = 0.0,
)


/** SV2 / CHIRP history rows use a different shape (`ts`, `hashrate_th`). */
@Serializable
data class PoolHistoryRow(
    val ts: Int = 0,
    @SerialName("hashrate_th") val hashrateTh: Double = 0.0,
    val workers: Int? = null,
)

enum class HistoryRange(
    val label: String,
    val serverRange: String,
    val hours: Int,
    val windowSeconds: Long,
) {
    H1("1h", "1d", 1, 3_600),
    D1("1d", "1d", 24, 86_400),
    D7("7d", "7d", 168, 604_800),
    D30("30d", "30d", 168, 2_592_000),
}
