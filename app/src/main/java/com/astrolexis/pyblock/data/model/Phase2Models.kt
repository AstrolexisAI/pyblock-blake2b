package com.astrolexis.pyblock.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- LNURL-auth ----

@Serializable
data class LnurlAuth(val ok: Boolean = false, val k1: String = "", val lnurl: String = "")

@Serializable
data class AuthStatus(
    val ok: Boolean = false,
    val authenticated: Boolean = false,
    @SerialName("session_token") val sessionToken: String? = null,
    val pubkey: String? = null,
    @SerialName("device_linked") val deviceLinked: Boolean? = null,
)

// ---- Entitlements ----

@Serializable
data class Entitlements(
    val ok: Boolean = false,
    val tier: String = "free",
    @SerialName("tier_expires_at") val tierExpiresAt: Long? = null,
    val skins: List<String> = emptyList(),
    @SerialName("lives_balance") val livesBalance: Int = 0,
    @SerialName("server_time") val serverTime: Long = 0,
)

// ---- Cross-device account link ----

@Serializable
data class LinkStartResp(
    val ok: Boolean = false,
    val code: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
    val error: String? = null,
)

@Serializable
data class LinkClaimReq(val code: String)

@Serializable
data class LinkClaimResp(
    val ok: Boolean = false,
    val account: String = "",
    val devices: Int = 0,
    val error: String? = null,
)

@Serializable
data class LinkedDevice(
    @SerialName("device_id") val id: Int = 0,
    val platform: String = "",
    @SerialName("last_seen_at") val lastSeen: Long = 0,
    @SerialName("is_self") val thisDevice: Boolean = false,
)

@Serializable
data class AccountStatus(
    val ok: Boolean = false,
    val account: String = "",          // "device:<anchorId>" — the anchor is derived from this
    val linked: Boolean = false,
    val devices: List<LinkedDevice> = emptyList(),
) {
    /** The group anchor (owner) device id, parsed from `account`. */
    val anchorId: Int? get() = account.removePrefix("device:").toIntOrNull()
}

@Serializable
data class UnlinkReq(@SerialName("device_id") val deviceId: Int? = null)

@Serializable
data class UnlinkResp(
    val ok: Boolean = false,
    val removed: Int? = null,
    @SerialName("devices_remaining") val devicesRemaining: Int? = null,
    val error: String? = null,
)

// ---- Products / paywall ----

@Serializable
data class Product(
    val id: String = "",
    val kind: String = "",
    @SerialName("period_days") val periodDays: Int? = null,
    @SerialName("grant_lives") val grantLives: Int? = null,
    @SerialName("grant_skins") val grantSkins: String? = null,
    @SerialName("price_usd") val priceUsd: Double = 0.0,
    @SerialName("price_sats") val priceSats: Int = 0,
    @SerialName("display_fiat") val displayFiat: String = "",
    @SerialName("grants_tier") val grantsTier: String? = null,
)

@Serializable
data class ProductsResponse(
    val ok: Boolean = false,
    @SerialName("btc_usd") val btcUsd: Double = 0.0,
    val products: List<Product> = emptyList(),
)

// ---- Purchase ----

@Serializable
data class PurchaseReq(@SerialName("product_id") val productId: String)

@Serializable
data class PurchaseResponse(
    val ok: Boolean = false,
    @SerialName("purchase_id") val purchaseId: String? = null,
    val invoice: String? = null,
    @SerialName("amount_sats") val amountSats: Int? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val errors: List<String>? = null,
)

@Serializable
data class PurchaseStatusResp(val ok: Boolean = false, val status: String = "")

// ---- Academy premium lesson (server-gated body) ----

@Serializable
data class LessonResp(
    val ok: Boolean = false,
    val id: Int = 0,
    val title: String = "",
    val body: String = "",
    val error: String? = null,
)
