package com.astrolexis.pyblock.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A BTC address the user tracks (persisted locally). */
@Serializable
data class TrackedAddress(val address: String, val label: String? = null)

// ---- Workers (/api.php?mode=workers → { [address]: AddressWorkers }) ----

@Serializable
data class AddressWorkers(
    val workers: Int = 0,
    val worker: List<WorkerInfo> = emptyList(),
)

@Serializable
data class WorkerInfo(
    val workername: String = "",
    val hashrate1m: String = "0",
    val hashrate5m: String = "0",
    val hashrate1hr: String = "0",
    val hashrate1d: String = "0",
    val hashrate7d: String = "0",
    val lastshare: Int = 0,
    val shares: Double? = null,
    val bestshare: Double? = null,
    val bestever: Double? = null,
)

// ---- OCEAN fallback (/api/ocean_search_full.php) — lenient, earnings only ----

@Serializable
data class OceanData(
    val address: String = "",
    val lifetime: OceanLifetime = OceanLifetime(),
    val unpaid: OceanUnpaid = OceanUnpaid(),
    val workers: OceanWorkers = OceanWorkers(),
) {
    val hasData: Boolean
        get() = unpaid.unpaid != null || lifetime.lifetime != null || workers.count > 0
}

@Serializable
data class OceanLifetime(
    @SerialName("estimated_earnings_per_day") val estPerDay: String? = null,
    @SerialName("lifetime_earnings") val lifetime: String? = null,
)

@Serializable
data class OceanUnpaid(
    @SerialName("unpaid_earnings") val unpaid: String? = null,
    @SerialName("blocks_found") val blocksFound: Int? = null,
)

@Serializable
data class OceanWorkers(val count: Int = 0, val active: Int = 0)

// ---- Orders to an address (/api/app/orders_by_address.php) ----

@Serializable
data class OrdersListResponse(
    val ok: Boolean = false,
    val orders: List<AddressOrder> = emptyList(),
    val count: Int = 0,
)

@Serializable
data class AddressOrder(
    val id: String = "",
    val port: Int = 0,
    @SerialName("total_sats") val totalSats: Long = 0,
    @SerialName("amount_sats") val amountSats: Long = 0,
    @SerialName("consumed_sat") val consumedSat: Long = 0,
    @SerialName("hashrate_phs") val hashratePhs: Double = 0.0,
    @SerialName("duration_h") val durationH: Double = 0.0,
    val status: String = "",
    @SerialName("error_msg") val errorMsg: String? = null,
) {
    /** Progress is always relative to the TOTAL paid (never the pre-fee
     *  amount) — the fee split is never surfaced in the app. */
    val pctConsumed: Int
        get() = if (totalSats > 0) ((consumedSat.toDouble() / totalSats) * 100).toInt().coerceIn(0, 100) else 0
}

/** Port → pool label. */
fun poolNameForPort(port: Int): String = when (port) {
    4444 -> "LOTTO"
    23334 -> "DATUM"
    5555 -> "SV2"
    5554 -> "CHIRP"
    30000 -> "CAROUSEL"
    else -> "POOL"
}
