package com.astrolexis.pyblock.data.model

import kotlinx.serialization.Serializable

/** Node Defender global leaderboard row. */
@Serializable
data class DefenderScore(
    val name: String = "",
    val score: Int = 0,
    val wave: Int = 0,
    val difficulty: String = "",
    val ts: Long = 0,
)

@Serializable
data class DefenderSubmitReq(
    val name: String,
    val handle: String,
    val score: Int,
    val wave: Int,
    val difficulty: String,
)

@Serializable
data class DefenderSubmitResult(
    val ok: Boolean = false,
    val rank: Int? = null,
    val top: List<DefenderScore> = emptyList(),
)

@Serializable
data class DefenderBoardResult(
    val ok: Boolean = false,
    val top: List<DefenderScore> = emptyList(),
)

/** lives_consume.php response. */
@Serializable
data class LivesConsumeResult(
    val ok: Boolean = false,
    @kotlinx.serialization.SerialName("lives_balance") val livesBalance: Int = 0,
    val error: String? = null,
)

@Serializable
data class LivesConsumeReq(val n: Int = 1)
