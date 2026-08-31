package com.astrolexis.pyblock.data.model

import kotlinx.serialization.Serializable

/** Mempool block-template aggregates — GET /template_data.php. */
@Serializable
data class TemplateAggregates(
    val height: Int = 0,
    val txCount: Int = 0,
    val blockUsage: Double = 0.0,
    val totalWeight: Int = 0,
    val totalVBytes: Int = 0,
    val totalFeeBTC: String = "0",
    val minFeeRate: Double = 0.0,
    val medianFeeRate: Double = 0.0,
    val maxFeeRate: Double = 0.0,
    val p10Fee: Double = 0.0,
    val p90Fee: Double = 0.0,
)

/** One template tx tile — GET /template_txs.php. */
@Serializable
data class TemplateTx(
    val txid: String = "",
    val vbytes: Double = 0.0,
    val feerate: Double = 0.0,
    val fee: Int = 0,
)
