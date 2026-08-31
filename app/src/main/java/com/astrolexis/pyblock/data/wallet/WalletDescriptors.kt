package com.astrolexis.pyblock.data.wallet

import android.content.Context
import com.astrolexis.pyblock.data.crypto.VanityCrypto
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.NetworkKind

/**
 * Single source of truth for a single-key vanity wallet's BDK descriptors.
 *
 * The long-running node syncs from the WATCH-ONLY (public-key) descriptors — it
 * never needs the WIF, so the wallet follows the chain even while the vault is
 * locked, and the secret spends zero time in the sync path. The WIF is loaded
 * ONLY to build an ephemeral SIGNING wallet for a spend (see [signing]) and is
 * discarded right after. This mirrors iOS's watch-only + ephemeral-signer model.
 *
 * bdk-android 1.2.0 has no single-descriptor wallet (no createSingle/loadSingle)
 * and rejects identical external==change, so both forms use a distinct change
 * descriptor: the SAME key in the OTHER compression format (spendable by the same
 * key, so change is never lost, just parked on the sibling-format address).
 */
object WalletDescriptors {

    /** Public-only (watch) descriptors, or null if the pubkey can't be resolved
     *  (legacy wallet whose vault has never been unlocked since the upgrade). */
    fun watch(ctx: Context, meta: VanityWallet): Pair<Descriptor, Descriptor>? {
        val pub = resolvePub(ctx, meta) ?: return null
        val other = VanityCrypto.otherFormatPubkeyHex(pub) ?: return null
        return Descriptor("pkh($pub)", NetworkKind.MAIN) to Descriptor("pkh($other)", NetworkKind.MAIN)
    }

    /** Private (signing) descriptors — the WIF pair. Null while the vault is
     *  locked (the fund-critical chokepoint hands out no key). Used only to sign. */
    fun signing(ctx: Context, meta: VanityWallet): Pair<Descriptor, Descriptor>? {
        val wif = WalletStore.wif(ctx, meta.id) ?: return null
        val other = VanityCrypto.otherFormatWif(wif) ?: return null
        return Descriptor("pkh($wif)", NetworkKind.MAIN) to Descriptor("pkh($other)", NetworkKind.MAIN)
    }

    /** The wallet's public key hex: the cached one, else derived-and-validated from
     *  the WIF (when accessible) and cached for future locked syncs. Null if neither
     *  is available. Never returns an unvalidated key (guards against watching the
     *  wrong script). */
    fun resolvePub(ctx: Context, meta: VanityWallet): String? {
        meta.pubkeyHex?.let { return it }
        val wif = WalletStore.wif(ctx, meta.id) ?: return null
        val hex = VanityCrypto.validatedPubkeyHex(wif, meta.address) ?: return null
        WalletStore.setPubkey(ctx, meta.id, hex)   // cache so the next LOCKED sync needs no WIF
        return hex
    }
}
