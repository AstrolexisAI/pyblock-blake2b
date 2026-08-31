package com.astrolexis.pyblock.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Minimal Phase-0 models — enough to render the POOL STATS mini cards.
// Field names/renames match EXISTING_ENDPOINTS.md. All fields defaulted so
// partial server payloads never break decoding (Json ignoreUnknownKeys).

@Serializable
data class LottoStats(
    @SerialName("Workers") val workers: Int = 0,
    val hashrate1m: Double = 0.0,
)

@Serializable
data class DatumStats(
    @SerialName("Workers") val workers: Int = 0,
    val hashrate1m: Double = 0.0,
)

@Serializable
data class SV2Stats(
    val hashrate: Double = 0.0,
    val workers: Int = 0,
    val blocks: Int = 0,
    val bestdiff: Double? = null,
)

/** sv2_api.php?mode=workers — the SV2 worker leaderboard (mirrors iOS SV2Worker/SV2Channel). */
@Serializable
data class SV2WorkersResponse(
    val miners: List<SV2Worker> = emptyList(),
)

@Serializable
data class SV2Worker(
    val address: String = "",
    val hashrate: Double = 0.0,
    val workers: Int = 0,
    val channels: List<SV2Channel>? = null,
    @SerialName("best_diff") val bestDiff: Double? = null,
    @SerialName("share_pct") val sharePct: Double? = null,
)

@Serializable
data class SV2Channel(
    val worker: String = "",
    val hashrate: Double? = null,
    val shares: Int? = null,
)

@Serializable
data class ChirpStats(
    val hashrate: Double = 0.0,
    val workers: Int = 0,
    val blocks: Int = 0,
    val candidates: Int = 0,
    val bestdiff: Double? = null,
    // Eligibility floors for the coinbase lottery. Defaults mirror the
    // published gates so the detail UI degrades sanely on partial payloads.
    @SerialName("min_days") val minDays: Double = 7.0,
    @SerialName("min_power") val minPower: Double = 500_000.0,
)

/** One row of the CHIRP weight leaderboard — chirp_api.php?mode=miners.
 *  `weight` is the published post-normalisation mean of (days, power), so
 *  the UI renders it directly as the miner's share of the next lottery. */
@Serializable
data class ChirpMiner(
    val address: String = "",
    val days: Double = 0.0,
    val power: Double = 0.0,
    val weight: Double = 0.0,
    val eligible: Boolean = false,
)

@Serializable
data class CarouselStats(
    @SerialName("hashrate_th") val hashrateTh: Double = 0.0,
    val miners: Int = 0,
    val suppliers: Int = 0,
)
