package com.astrolexis.pyblock.data.wallet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoindevkit.Address
import org.bitcoindevkit.Network
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.SignOptions
import org.bitcoindevkit.Wallet

/**
 * Air-gap PSBT signer core — turns the phone into an offline signing device. It PARSES
 * an unsigned PSBT (from a QR or paste), lets the UI show EXACTLY what it would sign
 * (destinations, amounts, change, fee), then signs with a wallet key held on THIS
 * device. It never broadcasts and never touches the network — the signed PSBT goes back
 * out as a QR for an online device to broadcast. Android mirror of iOS AirGapSigner.swift.
 *
 * Money-safety: the caller MUST display [summarize] before [sign] so the user consents to
 * the real outputs. Signing only adds signatures to inputs the key actually owns (BDK),
 * and v1 only returns a PSBT it could FULLY sign.
 */
object AirGapSigner {

    /** One output in the PSBT preview. [isSelf] = back to one of your own addresses (change). */
    data class Out(val address: String, val sats: Long, val isSelf: Boolean)

    data class Summary(
        val outputs: List<Out>,
        val feeSats: Long,
        val toOthersSats: Long,   // leaving your wallets
        val toSelfSats: Long,     // change back to you
    )

    enum class Err { BAD_PSBT, LOCKED, NO_OWNED_INPUTS }
    class AirGapException(val err: Err) : Exception(err.name) {
        val userMessage: String get() = when (err) {
            Err.BAD_PSBT -> "That isn't a valid PSBT."
            Err.LOCKED -> "Unlock your wallet first, then sign."
            Err.NO_OWNED_INPUTS -> "None of your wallets can sign this PSBT."
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun signOptions() = SignOptions(
        trustWitnessUtxo = false, assumeHeight = null, allowAllSighashes = false,
        tryFinalize = true, signWithTapInternalKey = true, allowGrinding = true,
    )

    /** Read-only preview of what the PSBT pays — NO key touched. Throws BAD_PSBT if it
     *  can't be parsed/previewed (so the UI never asks to sign a blob it couldn't show). */
    suspend fun summarize(ctx: Context, base64: String): Summary = withContext(Dispatchers.IO) {
        val psbt = runCatching { Psbt(base64.trim()) }.getOrElse { throw AirGapException(Err.BAD_PSBT) }
        val tx = runCatching { psbt.extractTx() }.getOrElse { throw AirGapException(Err.BAD_PSBT) }
        val owned = ownedScriptHexes(ctx)
        val list = ArrayList<Out>()
        var toSelf = 0L; var toOthers = 0L
        for (o in tx.output()) {
            val sats = o.value.toSat().toLong()
            val hex = o.scriptPubkey.toBytes().hex()
            val addr = runCatching { Address.fromScript(o.scriptPubkey, Network.BITCOIN).toString() }.getOrDefault("unknown script")
            val mine = hex in owned
            list.add(Out(addr, sats, mine))
            if (mine) toSelf += sats else toOthers += sats
        }
        val fee = runCatching { psbt.fee().toLong() }.getOrDefault(0L)
        Summary(list, fee, toOthers, toSelf)
    }

    /** Sign the PSBT with whichever of the user's wallet keys owns its inputs. Returns the
     *  signed PSBT (base64). Requires the vault unlocked (WIFs). Tries each wallet on a fresh
     *  copy and returns the first that FULLY signs. */
    suspend fun sign(ctx: Context, base64: String): String = withContext(Dispatchers.IO) {
        val b64 = base64.trim()
        if (runCatching { Psbt(b64) }.getOrNull() == null) throw AirGapException(Err.BAD_PSBT)
        var sawKey = false
        for (w in WalletStore.wallets.value) {
            val (ext, chg) = WalletDescriptors.signing(ctx, w) ?: continue   // null ⇒ vault locked / no key
            sawKey = true
            val psbt = runCatching { Psbt(b64) }.getOrNull() ?: continue
            val signer = runCatching { Wallet(ext, chg, Network.BITCOIN, Persister.newInMemory()) }.getOrNull() ?: continue
            // sign(...) → true when the PSBT is now fully finalizable (single-key P2PKH
            // owned by this key). Only then do we hand back a signed PSBT.
            val ok = runCatching { signer.sign(psbt, signOptions()) }.getOrDefault(false)
            if (ok) return@withContext psbt.serialize()
        }
        throw AirGapException(if (sawKey) Err.NO_OWNED_INPUTS else Err.LOCKED)
    }

    /** Air-gap phase 3: relay an already-signed PSBT to the network. Focuses a wallet node so
     *  its CBF client connects, waits (bounded) for it, then broadcasts. Returns the txid. */
    suspend fun broadcast(ctx: Context, signedBase64: String): String {
        val meta = WalletStore.wallets.value.firstOrNull() ?: throw AirGapException(Err.NO_OWNED_INPUTS)
        WalletSyncManager.focus(ctx, meta)                 // start a node so we have a client to relay through
        val node = WalletSyncManager.getNode(ctx, meta)
        node.awaitBroadcastReady(20_000)                   // up to ~20 s for the client to come up
        return node.broadcastSignedPSBT(signedBase64.trim())
    }

    /** Script hexes of every wallet address the user owns — to flag change outputs. */
    private fun ownedScriptHexes(ctx: Context): Set<String> {
        WalletStore.ensureLoaded(ctx)
        return WalletStore.wallets.value.mapNotNull { w ->
            runCatching { Address(w.address, Network.BITCOIN).scriptPubkey().toBytes().hex() }.getOrNull()
        }.toSet()
    }
}
