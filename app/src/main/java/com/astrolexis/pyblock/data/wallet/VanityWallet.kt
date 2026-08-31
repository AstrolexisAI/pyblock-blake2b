package com.astrolexis.pyblock.data.wallet

import kotlinx.serialization.Serializable

/**
 * A saved single-key vanity wallet. The private key (WIF) is NEVER stored here —
 * it lives only in Keystore-backed EncryptedSharedPreferences, keyed by [id].
 * Cached balance/tip let the list render instantly before the node syncs.
 */
@Serializable
data class VanityWallet(
    val id: String,
    val label: String,
    val address: String,
    val compressed: Boolean,          // false = "5" uncompressed, true = K/L
    val birthday: Int,                // block height when created (scan floor)
    val cachedBalanceSats: Long = 0L,
    val cachedTip: Int = 0,
    // Public key hex (in [compressed]'s format) for the WATCH-ONLY descriptor.
    // Lets the node sync with only the public key — the WIF is loaded ONLY to sign
    // a spend, never for syncing — so the wallet follows the chain even while the
    // vault is locked. Null on legacy wallets until backfilled on the next unlock.
    val pubkeyHex: String? = null,
)
