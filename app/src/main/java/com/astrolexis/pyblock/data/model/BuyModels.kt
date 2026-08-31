package com.astrolexis.pyblock.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OrderQuote(
    @SerialName("amount_sats") val amountSats: Int = 0,
    @SerialName("fee_sats") val feeSats: Int = 0,
    @SerialName("total_sats") val totalSats: Int = 0,
    @SerialName("duration_h") val durationH: Double = 0.0,
    @SerialName("fee_pct") val feePct: Double = 0.0,
    @SerialName("price_sats_phs_h") val priceSatsPhsH: Double = 0.0,
    @SerialName("price_source") val priceSource: String = "",
)

@Serializable
data class OrderQuoteResponse(
    val ok: Boolean = false,
    val quote: OrderQuote? = null,
    @SerialName("max_amount_sats") val maxAmountSats: Int? = null,
    val errors: List<String>? = null,
)

@Serializable
data class OrderReq(
    @SerialName("btc_address") val btcAddress: String,
    val port: Int,
    @SerialName("amount_sats") val amountSats: Int,
    @SerialName("hashrate_phs") val hashratePhs: Double,
)

@Serializable
data class OrderCreateResponse(
    val ok: Boolean = false,
    @SerialName("order_id") val orderId: String? = null,
    val invoice: String? = null,
    @SerialName("total_sats") val totalSats: Int? = null,
    @SerialName("expires_at") val expiresAt: Int? = null,
    val status: String? = null,
    val errors: List<String>? = null,
    // On-chain payment option (Lightning plan F1) — camelCase per server contract.
    val onchainAddress: String? = null,
    val onchainAmountSats: Int? = null,
    val onchainExpiresAt: Int? = null,
)

@Serializable
data class OrderStatusPayload(
    val id: String = "",
    @SerialName("btc_address") val btcAddress: String = "",
    val port: Int = 0,
    @SerialName("amount_sats") val amountSats: Int = 0,
    @SerialName("fee_sats") val feeSats: Int = 0,
    @SerialName("total_sats") val totalSats: Int = 0,
    @SerialName("hashrate_phs") val hashratePhs: Double = 0.0,
    @SerialName("duration_h") val durationH: Double = 0.0,
    @SerialName("ln_invoice") val lnInvoice: String = "",
    val status: String = "",
    @SerialName("consumed_sat") val consumedSat: Int = 0,
    @SerialName("expires_at") val expiresAt: Int = 0,
    @SerialName("error_msg") val errorMsg: String? = null,
    // On-chain payment option (Lightning plan F1) — camelCase per server contract.
    val onchainAddress: String? = null,
    val onchainAmountSats: Int? = null,
    val onchainExpiresAt: Int? = null,
    val onchainConfirmations: Int? = null,
    val onchainTxid: String? = null,
)

@Serializable
data class OrderStatusResponse(
    val ok: Boolean = false,
    val order: OrderStatusPayload? = null,
    val errors: List<String>? = null,
)

// ---- Package model (provider hidden) — same shape as nicehash_quote.php ----

@Serializable
data class NHQuote(
    @SerialName("hashrate_phs") val hashratePhs: Double = 0.0,
    @SerialName("duration_h") val durationH: Double = 0.0,
    @SerialName("price_sats_phs_h") val priceSatsPhsH: Double = 0.0,
    @SerialName("amount_sats") val amountSats: Int = 0,
    @SerialName("fee_pct") val feePct: Double = 0.0,
    @SerialName("fee_sats") val feeSats: Int = 0,
    @SerialName("total_sats") val totalSats: Int = 0,
    // Pro/Whale fee discount (server-applied). Optional — present once the backend ships it.
    @SerialName("discount_sats") val discountSats: Int? = null,
    val tier: String? = null,
    @SerialName("pro_save_sats") val proSaveSats: Int? = null,
    @SerialName("whale_save_sats") val whaleSaveSats: Int? = null,
)

@Serializable
data class NHQuoteResponse(
    val ok: Boolean = false,
    val quote: NHQuote? = null,
    @SerialName("max_sats") val maxSats: Int? = null,
    val errors: List<String>? = null,
)

@Serializable
data class NHOrderReq(
    @SerialName("btc_address") val btcAddress: String,
    val port: Int,
    @SerialName("hashrate_phs") val hashratePhs: Double,
    val hours: Double,
)

/** A preset hashrate package. Power × hours fixed; sat price is market-quoted.
 *  Same 5 exponential packages as the web (purchase_hashrate.php). */
data class NHPackage(val id: String, val name: String, val ph: Double, val hours: Double) {
    companion object {
        val all = listOf(
            NHPackage("p1", "Spark", 1.0, 48.0),
            NHPackage("p2", "Pulse", 2.0, 48.0),
            NHPackage("p3", "Surge", 5.0, 24.0),
            NHPackage("p4", "Storm", 10.0, 18.0),
            NHPackage("p5", "Titan", 20.0, 12.0),
        )
    }
}

@Serializable
data class AnonRegReq(val platform: String = "android", val bundle: String = "com.astrolexis.pyblock")

@Serializable
data class DeviceRegistration(
    val ok: Boolean = false,
    @SerialName("device_id") val deviceId: Int = 0,
    val created: Boolean = false,
    val secret: String? = null,
)
