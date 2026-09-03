package com.astrolexis.pyblock.data.blake

import android.content.Context
import com.astrolexis.pyblock.data.crypto.VanityCrypto
import com.astrolexis.pyblock.data.wallet.RicochetOutcome
import com.astrolexis.pyblock.data.wallet.WalletStore
import kotlinx.coroutines.delay
import org.bitcoindevkit.Address
import org.bitcoindevkit.Amount
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.Input
import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.bitcoindevkit.OutPoint
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Script
import org.bitcoindevkit.SignOptions
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.TxOut
import org.bitcoindevkit.Txid
import org.bitcoindevkit.Wallet

/**
 * Spend / ricochet BLAKE2b (fork) coins. Android mirror of iOS `BlakeSpend`.
 *
 * MONEY-SAFETY (replay): the fork has NO replay protection, so this spends ONLY mature,
 * post-fork COINBASE outputs. A coinbase mined after the fork exists only on BLAKE2b
 * (Node B) — absent from the SHA-256 majority chain — so a SIGHASH_ALL tx spending it is
 * rejected on mainnet by `missing-inputs`: NON-replayable. Shared pre-fork coins are NEVER
 * selected. Coinbase maturity (100 confs) is enforced. Broadcast goes through the server
 * (`pushtx.php?chain=blake2b` → Node B); a CBF client can't follow BLAKE2b post-fork.
 *
 * Every path guards on [BlakeChains]. Keys are loaded from the shared [WalletStore] vault
 * only to sign; the secret never leaves this function.
 */
object BlakeSpend {
    private val forkHeight = BlakeFork.FORK_HEIGHT
    private val coinbaseMaturity = BlakeFork.COINBASE_MATURITY

    sealed class Err(msg: String) : Exception(msg) {
        object Disabled : Err("BLAKE2b sending isn't enabled yet.")
        object NoSpendable : Err("No spendable BLAKE2b coins: only mature (100-conf) mined coins can be sent; shared pre-fork coins are replay-locked.")
        object BadAddress : Err("That isn't a valid Bitcoin address.")
        object BuildFailed : Err("Couldn't build the BLAKE2b transaction.")
        object NotSigned : Err("Couldn't sign the BLAKE2b transaction.")
        object UnexpectedInput : Err("Safety check failed — a non-mature or shared coin entered the tx. Aborted.")
        object BroadcastFailed : Err("Couldn't broadcast to the BLAKE2b network.")
        data class FeeTooHigh(val fee: Long, val amount: Long) : Err("Fee ($fee sats) would equal or exceed the amount ($amount sats).")
    }

    /** One spendable fork coin: a mature post-fork coinbase output + the key that owns it. */
    private data class Coin(
        val outpoint: OutPoint,
        val prevTx: Transaction,
        val wif: String,
        val valueSats: Long,
        val key: String,        // "txid:vout"
    )

    private fun metaKey(txid: String, vout: Int) = "$txid:$vout"

    // ---- hex helpers ----
    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
    private fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** A PSBT input carrying only the previous transaction (non-witness UTXO) — a legacy
     *  P2PKH coinbase needs this to sign. */
    private fun legacyInput(prevTx: Transaction): Input = Input(
        nonWitnessUtxo = prevTx, witnessUtxo = null, partialSigs = emptyMap(), sighashType = null,
        redeemScript = null, witnessScript = null, bip32Derivation = emptyMap(), finalScriptSig = null,
        finalScriptWitness = null, ripemd160Preimages = emptyMap(), sha256Preimages = emptyMap(),
        hash160Preimages = emptyMap(), hash256Preimages = emptyMap(), tapKeySig = null,
        tapScriptSigs = emptyMap(), tapScripts = emptyMap(), tapKeyOrigins = emptyMap(),
        tapInternalKey = null, tapMerkleRoot = null, proprietary = emptyMap(), unknown = emptyMap(),
    )

    /** A PSBT input carrying only the witness UTXO (script + value) — a native SegWit hop. */
    private fun segwitInput(prevOut: TxOut): Input = Input(
        nonWitnessUtxo = null, witnessUtxo = prevOut, partialSigs = emptyMap(), sighashType = null,
        redeemScript = null, witnessScript = null, bip32Derivation = emptyMap(), finalScriptSig = null,
        finalScriptWitness = null, ripemd160Preimages = emptyMap(), sha256Preimages = emptyMap(),
        hash160Preimages = emptyMap(), hash256Preimages = emptyMap(), tapKeySig = null,
        tapScriptSigs = emptyMap(), tapScripts = emptyMap(), tapKeyOrigins = emptyMap(),
        tapInternalKey = null, tapMerkleRoot = null, proprietary = emptyMap(), unknown = emptyMap(),
    )

    private fun singleSigner(wif: String, kind: String): Wallet {
        val desc = Descriptor("$kind($wif)", NetworkKind.MAIN)
        return Wallet.createSingle(desc, Network.BITCOIN, Persister.newInMemory())
    }

    private val legacySignOpts = SignOptions(
        trustWitnessUtxo = false, assumeHeight = null, allowAllSighashes = false,
        tryFinalize = true, signWithTapInternalKey = true, allowGrinding = true,
    )
    private val witnessSignOpts = SignOptions(
        trustWitnessUtxo = true, assumeHeight = null, allowAllSighashes = false,
        tryFinalize = true, signWithTapInternalKey = true, allowGrinding = true,
    )

    /** Gather every MATURE POST-FORK COINBASE across the user's wallets, with its owning key. */
    private suspend fun gatherCoins(ctx: Context, tip: Int): List<Coin> {
        val coins = ArrayList<Coin>()
        for (w in WalletStore.wallets.value) {
            if (w.address.isBlank()) continue
            val wif = WalletStore.wif(ctx, w.id) ?: continue
            val r = BlakeApi.walletUtxos(w.address) ?: continue     // warming/fail → skip (safe subset only)
            for (u in r.first) {
                // Mature post-fork coinbase (safe) OR a replay-locked coin the user chose to unlock.
                if (!BlakeFork.isEffectivelySpendable(u, tip)) continue
                val prev = runCatching { Transaction(hexToBytes(u.hex)) }.getOrNull() ?: continue
                val outpoint = OutPoint(Txid.fromString(u.txid), u.vout.toUInt())
                coins.add(Coin(outpoint, prev, wif, u.value, metaKey(u.txid, u.vout)))
            }
        }
        return coins
    }

    /** Greedy largest-first coins covering `need` + a worst-case legacy fee, or all (max). */
    private fun select(coins: List<Coin>, need: Long, sendMax: Boolean, feeRateSatVb: Long): List<Coin>? {
        if (sendMax) return coins
        val sorted = coins.sortedByDescending { it.valueSats }
        fun estFee(n: Int): Long = maxOf(1, feeRateSatVb) * (n.toLong() * 148 + 34 + 10)
        val pick = ArrayList<Coin>(); var sum = 0L
        for (c in sorted) { pick.add(c); sum += c.valueSats; if (sum >= need + estFee(pick.size)) break }
        return if (sum >= need + estFee(pick.size)) pick else null
    }

    // ---- Single spend ----

    /** Spend mature post-fork coinbase to [toAddress]. Amount + change (or sweep). Returns txid. */
    suspend fun send(
        ctx: Context, toAddress: String, amountSats: Long, sendMax: Boolean, feeRateSatVb: Long,
        onlyCoins: Set<String>? = null,
    ): String {
        if (!BlakeChains.SEND_ENABLED) throw Err.Disabled

        val tip = BlakeApi.status()?.blockHeight ?: 0
        if (tip <= 0) throw Err.NoSpendable

        var coins = gatherCoins(ctx, tip)
        if (onlyCoins != null) coins = coins.filter { it.key in onlyCoins }
        if (coins.isEmpty()) throw Err.NoSpendable

        val recipient: Script = runCatching { Address(toAddress, Network.BITCOIN).scriptPubkey() }.getOrNull()
            ?: throw Err.BadAddress
        val feeRate = FeeRate.fromSatPerVb(maxOf(1, feeRateSatVb).toULong())

        val selected: List<Coin> = when {
            onlyCoins != null -> coins
            else -> select(coins, amountSats, sendMax, feeRateSatVb) ?: throw Err.NoSpendable
        }
        val selectedKeys = selected.map { it.key }.toSet()

        val hostWif = selected.maxByOrNull { it.valueSats }!!.wif
        val hostSigner = singleSigner(hostWif, "pkh")

        var builder = TxBuilder().feeRate(feeRate).manuallySelectedOnly()
        for (c in selected) builder = builder.addForeignUtxo(c.outpoint, legacyInput(c.prevTx), 428uL)
        builder = if (sendMax) builder.drainTo(recipient)
                  else builder.addRecipient(recipient, Amount.fromSat(amountSats.toULong()))
        val psbt = builder.finish(hostSigner)     // change (if any) → host's own address

        for (wif in selected.map { it.wif }.toSet()) {
            val signer = singleSigner(wif, "pkh")
            signer.sign(psbt, legacySignOpts)
        }
        val tx = runCatching { psbt.extractTx() }.getOrNull() ?: throw Err.NotSigned

        // HARD GUARD: every input MUST be one of the selected mature-coinbase coins.
        val txInKeys = tx.input().map { metaKey(it.previousOutput.txid.toString(), it.previousOutput.vout.toInt()) }.toSet()
        if (!selectedKeys.containsAll(txInKeys)) throw Err.UnexpectedInput

        // Fee must never eat the payment (AMOUNT path only).
        val inTotal = selected.sumOf { it.valueSats }
        val outTotal = tx.output().sumOf { it.value.toSat().toLong() }
        val fee = if (inTotal >= outTotal) inTotal - outTotal else 0L
        if (!sendMax && fee >= amountSats) throw Err.FeeTooHigh(fee, amountSats)

        return BlakeApi.pushTx(bytesToHex(tx.serialize()))
    }

    // ---- Ricochet (multi-hop sweep) ----

    /** Sweep mature post-fork coinbase through [hops] ephemeral wpkh self-spends before the
     *  recipient. Builds + signs the whole chain in memory, then broadcasts every tx in order
     *  via the server (Node B). Returns the chain + kept hop addresses/keys (provenance proof). */
    suspend fun ricochet(
        ctx: Context, toAddress: String, amountSats: Long, sendMax: Boolean,
        hops: Int, feeRateSatVb: Long, onlyCoins: Set<String>? = null,
    ): RicochetOutcome {
        if (!BlakeChains.RICOCHET_ENABLED) throw Err.Disabled
        val n = maxOf(1, minOf(4, hops))

        val tip = BlakeApi.status()?.blockHeight ?: 0
        if (tip <= 0) throw Err.NoSpendable

        var coins = gatherCoins(ctx, tip)
        if (onlyCoins != null) coins = coins.filter { it.key in onlyCoins }
        if (coins.isEmpty()) throw Err.NoSpendable

        val recipient: Script = runCatching { Address(toAddress, Network.BITCOIN).scriptPubkey() }.getOrNull()
            ?: throw Err.BadAddress
        val feeRate = FeeRate.fromSatPerVb(maxOf(1, feeRateSatVb).toULong())

        // Ephemeral wpkh hop keys → addresses + single-key signers (kept for provenance).
        data class Hop(val address: String, val wif: String, val script: Script, val signer: Wallet)
        val hopChain = ArrayList<Hop>()
        for (i in 0 until n) {
            val bytes = VanityCrypto.hardenedRandom32(ByteArray(0))
            val hopWif = VanityCrypto.wifCompressed(bytes)
            val w = singleSigner(hopWif, "wpkh")
            val addr = w.peekAddress(org.bitcoindevkit.KeychainKind.EXTERNAL, 0u).address
            hopChain.add(Hop(addr.toString(), hopWif, addr.scriptPubkey(), w))
        }

        val hopFee = maxOf(1, feeRateSatVb) * 115          // conservative 1-in-1-out P2WPKH
        val staged = amountSats + n.toLong() * hopFee
        val selected: List<Coin> = when {
            onlyCoins != null -> coins
            else -> select(coins, staged, sendMax, feeRateSatVb) ?: throw Err.NoSpendable
        }
        val selectedKeys = selected.map { it.key }.toSet()

        val hostWif = selected.maxByOrNull { it.valueSats }!!.wif
        val hostSigner = singleSigner(hostWif, "pkh")
        var builder = TxBuilder().feeRate(feeRate).manuallySelectedOnly()
        for (c in selected) builder = builder.addForeignUtxo(c.outpoint, legacyInput(c.prevTx), 428uL)
        builder = if (sendMax) builder.drainTo(hopChain[0].script)
                  else builder.addRecipient(hopChain[0].script, Amount.fromSat(staged.toULong()))
        val psbt0 = builder.finish(hostSigner)
        for (wif in selected.map { it.wif }.toSet()) singleSigner(wif, "pkh").sign(psbt0, legacySignOpts)
        val tx0 = runCatching { psbt0.extractTx() }.getOrNull() ?: throw Err.NotSigned

        // HARD GUARD: tx0 inputs must be a subset of the selected mature-coinbase coins.
        val txInKeys = tx0.input().map { metaKey(it.previousOutput.txid.toString(), it.previousOutput.vout.toInt()) }.toSet()
        if (!selectedKeys.containsAll(txInKeys)) throw Err.UnexpectedInput

        val selectedTotal = selected.sumOf { it.valueSats }
        var totalFee = selectedTotal - tx0.output().sumOf { it.value.toSat().toLong() }   // tx0 fee

        val hop0Bytes = hopChain[0].script.toBytes()
        val hop0Idx = tx0.output().indexOfFirst { it.scriptPubkey.toBytes() == hop0Bytes }
        if (hop0Idx < 0) throw Err.BuildFailed
        var prevTxid = tx0.computeTxid()
        var prevVout = hop0Idx.toUInt()
        var prevOut = tx0.output()[hop0Idx]
        val builtTxs = ArrayList<Transaction>().apply { add(tx0) }

        for (i in 0 until n) {
            val outpoint = OutPoint(prevTxid, prevVout)
            val dest = if (i == n - 1) recipient else hopChain[i + 1].script
            var hb = TxBuilder().feeRate(feeRate).manuallySelectedOnly().onlyWitnessUtxo()
            hb = hb.addForeignUtxo(outpoint, segwitInput(prevOut), 107uL)
            hb = hb.drainTo(dest)
            val psbt = hb.finish(hopChain[i].signer)
            hopChain[i].signer.sign(psbt, witnessSignOpts)
            val tx = runCatching { psbt.extractTx() }.getOrNull() ?: throw Err.NotSigned
            val out0 = tx.output().firstOrNull() ?: throw Err.BuildFailed
            totalFee += prevOut.value.toSat().toLong() - out0.value.toSat().toLong()
            builtTxs.add(tx)
            prevOut = out0; prevTxid = tx.computeTxid(); prevVout = 0u
        }

        // FUND-SAFETY: abort (before ANY broadcast) if the fee would eat the payment (AMOUNT only).
        if (!sendMax && totalFee >= amountSats) throw Err.FeeTooHigh(totalFee, amountSats)

        for ((idx, tx) in builtTxs.withIndex()) {
            BlakeApi.pushTx(bytesToHex(tx.serialize()))
            if (idx < builtTxs.size - 1) delay(1_500)
        }

        return RicochetOutcome(
            txids = builtTxs.map { it.computeTxid().toString() },
            hopAddresses = hopChain.map { it.address },
            hopWifs = hopChain.map { it.wif },
        )
    }
}
