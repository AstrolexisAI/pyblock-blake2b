package com.astrolexis.pyblock.data.wallet

import android.content.Context
import org.bitcoindevkit.Address
import org.bitcoindevkit.Amount
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.Input
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Network
import org.bitcoindevkit.OutPoint
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.SignOptions
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.Wallet

/**
 * PayJoin (BIP-78) "Collaborative Send" transaction core — **MONEY-CRITICAL**. Android port of the
 * audited + hardened iOS `PayJoinTx.swift` (workflow payjoin-money-safety-review). Two PyBLOCK
 * contacts co-sign ONE tx: the sender pays `amount` to the receiver and the receiver folds in one
 * of their own coins, breaking the common-input-ownership heuristic.
 *
 * ⚠️ ANDROID DIVERGENCE vs iOS: the watch/sign wallet here uses TWO descriptors (`pkh(pub)` +
 * `pkh(otherFormatPub)`) so it catches both the compressed and uncompressed P2PKH scripts of the
 * single key. So "sender-owned input" = prevout script ∈ {both keychain scripts}, NOT a single
 * script like iOS. Misclassifying = fund-safety hole, so we resolve BOTH scripts authoritatively.
 *
 * Gated by [PayJoinFeature]; NO caller until the coordinator/UI land and the feature is signed off.
 */
object PayJoinTx {

    enum class Err {
        EMPTY, FROZEN, MULTI_WALLET, INCOMPLETE_SELECTION, NO_CHANGE, BAD_ADDRESS,
        BUILD_FAILED, NOT_SIGNED, RECEIVER_SIGN_FAILED, ORIGINAL_NOT_SIGNED, NOT_PAID,
        PREV_TX_MISSING, MALFORMED_ORIGINAL, UNEXPECTED_INPUT, UNEXPECTED_OUTPUT,
        RECIPIENT_SHORT, CHANGE_SHORT, OVERPAY, FEE_TOO_LOW, PROBING_SIGHASH,
        BROADCAST_FAILED, BROADCAST_UNCERTAIN, ALREADY_BROADCAST,
    }

    class PayJoinException(val err: Err) : Exception(err.name)
    private fun fail(e: Err): Nothing = throw PayJoinException(e)

    /** Dust tolerance (sats): a rounding/change below this is treated as rolled into fee. */
    const val DUST = 546L

    /** Stable outpoint key, identical to UtxoMetaStore.key so guards compare in the freeze space. */
    fun key(op: OutPoint): String = UtxoMetaStore.key(op.txid.toString(), op.vout.toInt())

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** Idempotency interlock (Defect C): once a session's PayJoin broadcast has been ATTEMPTED,
     *  the fallback original must never also broadcast (a propagated-but-timed-out broadcast would
     *  otherwise double-spend the sender's coins). Persisted so it survives relaunch. */
    object BroadcastLog {
        private const val PREFS = "pyblock.payjoin"
        private const val KEY = "broadcastAttempted"
        fun markAttempted(ctx: Context, id: String) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val s = (p.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
            s.add(id); p.edit().putStringSet(KEY, s).apply()
        }
        fun attempted(ctx: Context, id: String): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.contains(id) == true
    }

    /** A PSBT input carrying only the previous transaction (non-witness UTXO) — every P2PKH input
     *  needs this for the receiver's addForeignUtxo; all other PSBT fields empty. Mirrors iOS
     *  CoinSpend.legacyInput. */
    fun legacyInput(prevTx: Transaction): Input = Input(
        nonWitnessUtxo = prevTx, witnessUtxo = null, partialSigs = emptyMap(), sighashType = null,
        redeemScript = null, witnessScript = null, bip32Derivation = emptyMap(),
        finalScriptSig = null, finalScriptWitness = emptyList(),
        ripemd160Preimages = emptyMap(), sha256Preimages = emptyMap(),
        hash160Preimages = emptyMap(), hash256Preimages = emptyMap(),
        tapKeySig = null, tapScriptSigs = emptyMap(), tapScripts = emptyMap(),
        tapKeyOrigins = emptyMap(), tapInternalKey = null, tapMerkleRoot = null,
        proprietary = emptyMap(), unknown = emptyMap(),
    )

    /** Sign `psbt` in place with a throwaway single-key wallet built from the wallet's SIGNING
     *  descriptors (the WIF pair). `finalizeNow=false` keeps it signed-but-not-finalized so
     *  nonWitnessUtxo survives serialization (the original on the wire). */
    private fun sign(ctx: Context, meta: VanityWallet, psbt: Psbt, finalizeNow: Boolean) {
        val (ext, chg) = WalletDescriptors.signing(ctx, meta) ?: fail(Err.NOT_SIGNED)
        val signer = Wallet(ext, chg, Network.BITCOIN, Persister.newInMemory())
        val opts = SignOptions(
            trustWitnessUtxo = false, assumeHeight = null, allowAllSighashes = false,
            tryFinalize = finalizeNow, signWithTapInternalKey = true, allowGrinding = true,
        )
        signer.sign(psbt, opts)
    }

    /** Both P2PKH scripts (compressed + uncompressed format) this single-key wallet owns — the
     *  ownership set for sender-input / change-output classification. */
    private fun ownerScripts(w: Wallet): Set<String> = setOf(
        w.peekAddress(KeychainKind.EXTERNAL, 0u).address.scriptPubkey().toBytes().hex(),
        w.peekAddress(KeychainKind.INTERNAL, 0u).address.scriptPubkey().toBytes().hex(),
    )

    // MARK: - Sender

    data class SenderContext(
        val sessionId: String,
        val recipientScriptHex: String,
        val ownerScriptsHex: Set<String>,
        val amountSats: Long,
        val originalChangeSats: Long,
        val originalFeeSats: Long,
        val feeRateSatVb: Long,
        val maxFeeSats: Long,
        val selectedKeys: Set<String>,
    )

    /** Signed original (on the wire, NOT finalized) + a local finalized fallback + guard context. */
    data class Original(val psbtB64: String, val fallbackTx: Transaction, val ctx: SenderContext)

    /** Sender step 1: build the ORIGINAL payment (sender coins → {recipient: amt, change}), sign it
     *  (NOT finalized, for transmit), and finalize a LOCAL copy as the fallback. Invariant S6: the
     *  original is a valid standalone payment. */
    fun buildOriginal(
        ctx: Context, meta: VanityWallet, senderWallet: Wallet,
        selected: List<UtxoInfo>, toAddress: String, amountSats: Long,
        feeRateSatVb: Long, maxFeeSats: Long, frozenKeys: Set<String>,
    ): Original {
        if (selected.isEmpty()) fail(Err.EMPTY)
        if (selected.map { it.walletId }.toSet().size != 1) fail(Err.MULTI_WALLET)
        val selectedKeys = selected.map { it.key }.toSet()
        if (selectedKeys.any { it in frozenKeys }) fail(Err.FROZEN)

        // The sender absorbs the receiver's added-input fee via drainTo; size the consent cap to
        // cover one extra (possibly uncompressed) P2PKH input at the chosen feerate.
        val feeCap = maxOf(maxFeeSats, feeRateSatVb * 250 + 300)

        // Resolve the exact live outpoints for the selected coins (never silently drop).
        val outpoints = senderWallet.listUnspent()
            .filter { key(it.outpoint) in selectedKeys }
            .map { it.outpoint }
        if (outpoints.size != selectedKeys.size) fail(Err.INCOMPLETE_SELECTION)

        val recipient = runCatching { Address(toAddress, Network.BITCOIN).scriptPubkey() }.getOrElse { fail(Err.BAD_ADDRESS) }
        val recipientHex = recipient.toBytes().hex()
        val owners = ownerScripts(senderWallet)
        val feeRate = FeeRate.fromSatPerVb(feeRateSatVb.toULong())

        var b = TxBuilder().feeRate(feeRate).manuallySelectedOnly().addUtxos(outpoints)
        b = b.addRecipient(recipient, Amount.fromSat(amountSats.toULong()))
        val psbt = b.finish(senderWallet)

        // Sign but DO NOT finalize — keep nonWitnessUtxo intact for the wire.
        sign(ctx, meta, psbt, finalizeNow = false)

        // A local finalized copy proves the original is a valid fallback + lets us read its outputs.
        val fin = psbt.finalize()
        if (!fin.couldFinalize) fail(Err.NOT_SIGNED)
        val otx = fin.psbt.extractTx()

        val outs = otx.output()
        val recvOut = outs.firstOrNull { it.scriptPubkey.toBytes().hex() == recipientHex }
            ?: fail(Err.MALFORMED_ORIGINAL)
        if (recvOut.value.toSat().toLong() < amountSats) fail(Err.MALFORMED_ORIGINAL)
        // Exactly one other output = the sender's change (PayJoin needs it to absorb the extra fee).
        val others = outs.filter { it.scriptPubkey.toBytes().hex() != recipientHex }
        if (others.size != 1) fail(Err.NO_CHANGE)
        val changeOut = others.first()
        val originalChangeSats = changeOut.value.toSat().toLong()

        val senderIn = selected.sumOf { it.valueSats }
        val outTotal = recvOut.value.toSat().toLong() + originalChangeSats
        if (senderIn < outTotal) fail(Err.MALFORMED_ORIGINAL)
        val originalFeeSats = senderIn - outTotal

        val sctx = SenderContext(
            sessionId = "", // set by caller
            recipientScriptHex = recipientHex, ownerScriptsHex = owners,
            amountSats = amountSats, originalChangeSats = originalChangeSats,
            originalFeeSats = originalFeeSats, feeRateSatVb = feeRateSatVb,
            maxFeeSats = feeCap, selectedKeys = selectedKeys,
        )
        return Original(psbt.serialize(), otx, sctx)
    }

    /** Re-sign the sender's inputs, finalize, and run every fund-safety guard — returns the
     *  finalized tx WITHOUT broadcasting (offline-testable heart of the sender's vetting). */
    fun verifyAndFinalize(ctx: Context, meta: VanityWallet, proposalPsbtB64: String, original: Original): Transaction {
        val c = original.ctx
        val prop = Psbt(proposalPsbtB64)

        // Re-sign the sender's own inputs on the received PSBT (single lineage — no combine).
        sign(ctx, meta, prop, finalizeNow = true)

        // S5 — must fully finalize before going near the network.
        val fin = prop.finalize()
        if (!fin.couldFinalize) fail(Err.NOT_SIGNED)
        val tx = fin.psbt.extractTx()

        // Classify every input by TRUE ownership from the proposal's nonWitnessUtxo (each bound to
        // its real outpoint by txid). The single key signs ANY of its own coins, so a malicious
        // receiver could smuggle in an UNSELECTED sender coin — classify by ownership, not count.
        val txInputs = tx.input()
        val propInputs = prop.input()
        if (txInputs.size != propInputs.size) fail(Err.MALFORMED_ORIGINAL)
        val owners = c.ownerScriptsHex
        val txInKeys = HashSet<String>()
        var senderIn = 0L
        var receiverContribution = 0L
        var foreignCount = 0
        for (i in txInputs.indices) {
            val txin = txInputs[i]
            val k = key(txin.previousOutput)
            txInKeys.add(k)
            val prevTx = propInputs[i].nonWitnessUtxo ?: fail(Err.PREV_TX_MISSING)
            if (prevTx.computeTxid().toString() != txin.previousOutput.txid.toString()) fail(Err.MALFORMED_ORIGINAL)
            val vout = txin.previousOutput.vout.toInt()
            val prevOuts = prevTx.output()
            if (vout >= prevOuts.size) fail(Err.MALFORMED_ORIGINAL)
            val prevScriptHex = prevOuts[vout].scriptPubkey.toBytes().hex()
            val prevValue = prevOuts[vout].value.toSat().toLong()
            if (prevScriptHex in owners) {
                if (k !in c.selectedKeys) fail(Err.UNEXPECTED_INPUT)   // Defect A: only selected sender coins
                senderIn += prevValue
            } else {
                foreignCount++
                receiverContribution += prevValue
            }
        }

        // S1 — every selected sender coin present, EXACTLY one foreign input, nothing CURRENTLY
        // frozen (Defect B: live freeze set, never a stale snapshot).
        val liveFrozen = UtxoMetaStore.frozenKeys(ctx)
        if (!txInKeys.containsAll(c.selectedKeys) || foreignCount != 1 || txInKeys.any { it in liveFrozen }) fail(Err.UNEXPECTED_INPUT)

        // Outputs must be EXACTLY the recipient and/or the sender's change (Defect E: no third,
        // receiver-controlled output can skim value).
        var recipientPaid = 0L
        var changeSats = 0L
        for (o in tx.output()) {
            val s = o.scriptPubkey.toBytes().hex()
            when {
                s == c.recipientScriptHex -> recipientPaid += o.value.toSat().toLong()
                s in owners -> changeSats += o.value.toSat().toLong()
                else -> fail(Err.UNEXPECTED_OUTPUT)
            }
        }

        // S2 — recipient paid the agreed amount, and not more than amount + the receiver's own
        // contribution (which folds back to the recipient=receiver address) + dust (Defect E).
        if (recipientPaid < c.amountSats || recipientPaid > c.amountSats + receiverContribution + DUST) fail(Err.RECIPIENT_SHORT)

        // S3 — the sender's change isn't raided beyond the consented fee cap.
        if (changeSats + c.maxFeeSats < c.originalChangeSats) fail(Err.CHANGE_SHORT)

        // Realized fee, computed independently (Defect D).
        val outTotal = recipientPaid + changeSats
        val totalIn = senderIn + receiverContribution
        if (totalIn < outTotal) fail(Err.OVERPAY)
        val realizedFee = totalIn - outTotal
        val vsize = tx.vsize().toLong()
        if (vsize <= 0 || realizedFee < vsize || realizedFee < c.originalFeeSats ||
            realizedFee > c.originalFeeSats + c.maxFeeSats) fail(Err.FEE_TOO_LOW)

        // S4 — total the sender pays (its inputs minus returned change) is capped.
        if (senderIn < changeSats) fail(Err.OVERPAY)
        if (senderIn - changeSats > c.amountSats + c.originalFeeSats + c.maxFeeSats) fail(Err.OVERPAY)

        return tx
    }

    /** Sender step 2: vet the proposal, then broadcast via [broadcast]. Records the attempt BEFORE
     *  the bytes can hit the wire (Defect C). Throws BROADCAST_UNCERTAIN if the broadcast might have
     *  propagated — the caller must NOT auto-fall-back. */
    suspend fun finalizeAndBroadcast(
        ctx: Context, meta: VanityWallet, proposalPsbtB64: String, original: Original,
        broadcast: suspend (Transaction) -> Unit,
    ): String {
        if (BroadcastLog.attempted(ctx, original.ctx.sessionId)) fail(Err.ALREADY_BROADCAST)
        val tx = verifyAndFinalize(ctx, meta, proposalPsbtB64, original)
        BroadcastLog.markAttempted(ctx, original.ctx.sessionId)
        try {
            broadcast(tx)
        } catch (e: Exception) {
            throw PayJoinException(Err.BROADCAST_UNCERTAIN)
        }
        return tx.computeTxid().toString()
    }

    /** Sender fallback (S6): broadcast the standalone signed original. Refuses if a PayJoin
     *  broadcast was ever attempted for this session (Defect C: never double-broadcast). */
    suspend fun broadcastFallback(ctx: Context, original: Original, broadcast: suspend (Transaction) -> Unit): String {
        if (BroadcastLog.attempted(ctx, original.ctx.sessionId)) fail(Err.ALREADY_BROADCAST)
        BroadcastLog.markAttempted(ctx, original.ctx.sessionId)
        broadcast(original.fallbackTx)
        return original.fallbackTx.computeTxid().toString()
    }

    // MARK: - Receiver

    /** One coin the receiver contributes, by its UtxoMetaStore outpoint key ("txid:vout"). */
    data class ReceiverCoin(val key: String)

    /** Receiver: from the sender's signed original, REBUILD the PayJoin tx (own coin as host +
     *  sender coins as unsigned foreign inputs + recipient bumped by contribution + residual drained
     *  to the sender's change), then sign ONLY the contributed coin. Returns the base64 proposal. */
    fun buildProposal(
        ctx: Context, meta: VanityWallet, originalPsbtB64: String, receiverWallet: Wallet,
        receiverCoin: ReceiverCoin, receiveAddress: String, amountSats: Long, feeRateSatVb: Long,
    ): String {
        // R1 — contributed coin must be a LIVE, NON-frozen coin of this wallet (resolve value from
        // the wallet, never trust a UI value; re-check the freeze set).
        val ownUtxo = receiverWallet.listUnspent().firstOrNull { key(it.outpoint) == receiverCoin.key }
            ?: fail(Err.INCOMPLETE_SELECTION)
        if (UtxoMetaStore.isFrozen(ctx, receiverCoin.key)) fail(Err.FROZEN)
        val ownOutpoint = ownUtxo.outpoint

        val orig = Psbt(originalPsbtB64)

        // R3 — the original must be fully signed by the sender (a finalizable fallback). finalize()
        // returns a NEW psbt; `orig` is untouched so we still read nonWitnessUtxo off it below.
        val finCheck = orig.finalize()
        if (!finCheck.couldFinalize) fail(Err.ORIGINAL_NOT_SIGNED)
        val otx = finCheck.psbt.extractTx()

        val recvScript = runCatching { Address(receiveAddress, Network.BITCOIN).scriptPubkey() }.getOrElse { fail(Err.BAD_ADDRESS) }
        val recvHex = recvScript.toBytes().hex()
        val paid = otx.output().firstOrNull { it.scriptPubkey.toBytes().hex() == recvHex } ?: fail(Err.NOT_PAID)
        if (paid.value.toSat().toLong() < amountSats) fail(Err.NOT_PAID)

        val changeOuts = otx.output().filter { it.scriptPubkey.toBytes().hex() != recvHex }
        if (changeOuts.size != 1) fail(Err.MALFORMED_ORIGINAL)
        val senderChangeScript = changeOuts.first().scriptPubkey

        // Sender inputs → unsigned foreign inputs (prevTx required for every P2PKH input).
        val otxInputs = otx.input()
        val psbtInputs = orig.input()
        if (otxInputs.size != psbtInputs.size) fail(Err.MALFORMED_ORIGINAL)
        data class Foreign(val op: OutPoint, val prevTx: Transaction)
        val senderForeign = ArrayList<Foreign>()
        for (i in otxInputs.indices) {
            val txin = otxInputs[i]
            val prevTx = psbtInputs[i].nonWitnessUtxo ?: fail(Err.PREV_TX_MISSING)
            // R3 anti-probing: the payer must commit to outputs with SIGHASH_ALL (null = default ALL).
            val sh = psbtInputs[i].sighashType
            if (sh != null && !sh.lowercase().contains("all")) fail(Err.PROBING_SIGHASH)
            if (prevTx.computeTxid().toString() != txin.previousOutput.txid.toString()) fail(Err.MALFORMED_ORIGINAL)
            val vout = txin.previousOutput.vout.toInt()
            if (vout >= prevTx.output().size) fail(Err.MALFORMED_ORIGINAL)
            senderForeign.add(Foreign(txin.previousOutput, prevTx))
        }
        if (senderForeign.isEmpty()) fail(Err.MALFORMED_ORIGINAL)

        val ownValue = ownUtxo.txout.value.toSat().toLong()
        val recvAmount = amountSats + ownValue   // recipient (= our receive addr) bumped by our contribution
        if (recvAmount < amountSats) fail(Err.MALFORMED_ORIGINAL)   // overflow guard

        val feeRate = FeeRate.fromSatPerVb(feeRateSatVb.toULong())
        var b = TxBuilder().feeRate(feeRate).manuallySelectedOnly().addUtxos(listOf(ownOutpoint))
        for (f in senderForeign) {
            b = b.addForeignUtxo(f.op, legacyInput(f.prevTx), 428uL)
        }
        b = b.addRecipient(recvScript, Amount.fromSat(recvAmount.toULong()))
        b = b.drainTo(senderChangeScript)
        val pj = b.finish(receiverWallet)

        // Don't co-sign a griefing over-fee tx — MANDATORY (fail-closed if the fee can't be read).
        val pjFee = runCatching { pj.fee().toLong() }.getOrElse { fail(Err.FEE_TOO_LOW) }
        val originalFee = run {
            val inTot = senderForeign.sumOf { f -> f.prevTx.output()[f.op.vout.toInt()].value.toSat().toLong() }
            val outTot = otx.output().sumOf { it.value.toSat().toLong() }
            (inTot - outTot).coerceAtLeast(0L)
        }
        val feeCap = maxOf(feeRateSatVb * 250 + 300, feeRateSatVb * 250 + 300)
        if (pjFee > originalFee + feeCap) fail(Err.FEE_TOO_LOW)

        // R4 — sign ONLY the receiver's own input, leaving a partial signature for the sender.
        sign(ctx, meta, pj, finalizeNow = false)
        if (pj.input().none { it.partialSigs.isNotEmpty() }) fail(Err.RECEIVER_SIGN_FAILED)

        return pj.serialize()
    }
}
