package com.astrolexis.pyblock.data.wallet

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.astrolexis.pyblock.data.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bitcoindevkit.Address
import org.bitcoindevkit.Amount
import org.bitcoindevkit.BlockHash
import org.bitcoindevkit.BlockId
import org.bitcoindevkit.CbfBuilder
import org.bitcoindevkit.CbfClient
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.Info
import org.bitcoindevkit.IpAddress
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.bitcoindevkit.Input
import org.bitcoindevkit.OutPoint
import org.bitcoindevkit.Peer
import org.bitcoindevkit.Script
import org.bitcoindevkit.TxOut
import org.bitcoindevkit.Persister
import org.bitcoindevkit.RecoveryPoint
import org.bitcoindevkit.ScanType
import org.bitcoindevkit.SignOptions
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.UnconfirmedTx
import org.bitcoindevkit.Wallet
import java.io.File

/**
 * One on-device Kyoto CBF node driving one single-key BDK wallet. Connects ONLY
 * to PyBLØCK's own full node (compact block filters — the node never learns which
 * addresses are ours). Mirrors the iOS `WalletNode`. Compose-observable.
 *
 * BDK's Wallet + SQLite Persister are NOT thread-safe, so every access (sync,
 * mempool, send) is serialized through [walletMutex].
 */
private const val LOG = "PyBLOCKcbf"

class BdkNode(
    private val appCtx: Context,
    val meta: VanityWallet,
    /** Retained for node-key stability; always the legacy (Bitcoin) chain. */
    val chain: String = com.astrolexis.pyblock.data.store.ChainStore.LEGACY,
) {

    var balanceSats by mutableLongStateOf(meta.cachedBalanceSats); private set
    var pendingSats by mutableLongStateOf(0L); private set
    var tipHeight by mutableIntStateOf(meta.cachedTip); private set
    var synced by mutableStateOf(false); private set
    var status by mutableStateOf("idle"); private set
    var filtersPct by androidx.compose.runtime.mutableFloatStateOf(0f); private set
    val running get() = wantRun
    var txStatus by mutableStateOf<Map<String, TxConf>>(emptyMap()); private set   // txid → on-chain status
    /** Bumped every time the activity cache is (re)written — send / receive / sync
     *  — so the ACTIVITY view recomposes instantly instead of waiting for its poll. */
    var txCacheVersion by mutableIntStateOf(0); private set

    /** Client-side on-chain status of a tx this wallet tracked. */
    data class TxConf(val confirmed: Boolean, val height: Int?)
    fun confStatus(txid: String): TxConf? = txStatus[txid]

    val id get() = meta.id

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = mutableListOf<Job>()
    private val walletMutex = Mutex()          // serializes all Wallet/Persister access
    private var bdk: Wallet? = null
    private var persister: Persister? = null
    private var client: CbfClient? = null
    @Volatile private var wantRun = false      // supervisor keeps the CBF node alive/reconnecting while true
    @Volatile private var walletExisted = false // DB persisted from a prior sync → resume via fast ScanType.Sync

    // PyBLØCK's own full node — the only peer (sovereign, CBF privacy).
    private val peerIp get() = IpAddress.fromIpv4(179.toUByte(), 27.toUByte(), 118.toUByte(), 130.toUByte())
    private val peerPort: UShort = 8333u

    private fun dbPath(): String =
        File(appCtx.filesDir, "wallets").apply { mkdirs() }.resolve("${meta.id}.sqlite").absolutePath
    private fun nodeDir(): String =
        File(appCtx.filesDir, "cbf/${meta.id}").apply { mkdirs() }.absolutePath

    /**
     * Recovery anchor for kyoto (bdk 3.0.0 needs a checkpoint, not a bare height).
     * A fixed recent mainnet block (all vanity wallets are newer), matching iOS
     * `vanityCheckpoint`. Kyoto caches filters in [nodeDir] so the resume stays
     * cheap after the first scan.
     */
    private fun recoveryPoint(): RecoveryPoint =
        RecoveryPoint.Other(BlockId(955000u,
            BlockHash.fromString("0000000000000000000055222909c19ed96cd371013861337214a3c39a63d828")))

    /**
     * WATCH-ONLY (public-key) descriptors for the single vanity key — the node
     * syncs from these with NO private key, so it follows the chain even while the
     * vault is locked. External == the saved-format pubkey (address == meta.address);
     * change == the OTHER-format pubkey (bdk rejects identical ext==chg), still
     * spendable by the same key. The WIF is loaded only to sign a spend (see [send]).
     */
    private fun descriptors(): Pair<Descriptor, Descriptor> =
        WalletDescriptors.watch(appCtx, meta)
            ?: error("watch key unavailable — unlock the vault once to enable sync")

    private fun openWallet(): Wallet {
        val (ext, chg) = descriptors()
        val dbFile = File(dbPath())
        walletExisted = dbFile.exists()           // a persisted DB → previously synced → resume with Sync
        var p = Persister.newSqlite(dbPath()); persister = p
        val w = try {
            Wallet.load(ext, chg, p)
        } catch (e: Exception) {
            // Persisted DB doesn't match these descriptors (e.g. built by an older
            // build with private-key descriptors, or corrupt). Chain state is
            // disposable — the KEY is untouched in Keystore prefs — so wipe the
            // SQLite and recover cleanly from the birthday.
            android.util.Log.w(LOG, "[${meta.id.take(6)}] load failed (${e.message?.take(60)}); rebuilding chain state")
            runCatching { p.close() }
            runCatching { dbFile.delete() }
            walletExisted = false
            p = Persister.newSqlite(dbPath()); persister = p
            Wallet(ext, chg, Network.BITCOIN, p)
        }
        // A Recovery scan (no persisted wallet) starts kyoto from a CLEAN data dir so
        // a stale header cache can't fight the recovery anchor. (When the wallet
        // loaded — resume — we KEEP the node dir for a fast, filter-cached resume.)
        if (!walletExisted) runCatching { File(nodeDir()).deleteRecursively() }
        android.util.Log.i(LOG, "[${meta.id.take(6)}] openWallet existed=$walletExisted")
        return w
    }

    /**
     * One-time (per rescan version) reset of persisted CHAIN state — the wallet
     * SQLite + kyoto data dir. Wallets that "synced empty" in older builds (the
     * scan floor was the cached tip, past the blocks holding their UTXOs) left a
     * checkpoint AHEAD of their funds; bdk only extends forward, so the funds were
     * never re-scanned and the balance stayed 0. Wiping forces one clean Recovery
     * from the birthday. The private KEY is untouched (Keystore-backed prefs).
     */
    private fun maybeRescanReset() {
        // v3: the watch-only refactor changed descriptors from private (pkh(wif)) to
        // public (pkh(pubkey)). An OLD build's persisted wallet DB + kyoto node dir
        // are inconsistent with the new build. One clean wipe rebuilds both
        // consistently from the birthday. The private KEY is never touched.
        val marker = File(appCtx.filesDir, "cbf_markers/${meta.id}.rescan_v3")
        if (marker.exists()) return
        runCatching {
            File(dbPath()).delete()
            File(nodeDir()).deleteRecursively()
            marker.parentFile?.mkdirs()
            marker.createNewFile()
        }
    }

    fun start() {
        if (wantRun) return
        wantRun = true
        status = "starting"
        try {
            maybeRescanReset()          // one-time: clear stale "synced empty" chain state so UTXOs are re-found
            // synchronized(this): the offline UTXO seeding (seedConfirmed on a
            // CLOSED node) opens the same SQLite briefly — never both at once.
            val w = synchronized(this) { openWallet() }; bdk = w
            // A fresh single-key wallet must reveal its script or the node won't watch it.
            w.revealAddressesTo(KeychainKind.EXTERNAL, 0u)
            // FUND-SAFETY: the descriptor's derived address MUST equal the saved
            // vanity address, else we'd watch/spend the wrong script (e.g. a
            // compressed/uncompressed WIF mismatch). Fail closed if it doesn't.
            val derived = runCatching { w.peekAddress(KeychainKind.EXTERNAL, 0u).address.toString() }.getOrNull()
            if (derived != null && derived != meta.address) {
                status = "error: address mismatch — key/format does not match ${meta.address.take(10)}…"
                bdk = null; runCatching { persister?.close() }; persister = null; wantRun = false
                return
            }
            runCatching { balanceSats = w.balance().total.toSat().toLong() }
            jobs += scope.launch { superviseCbf(w) }
            jobs += scope.launch { while (scope.isActive && wantRun) { refreshMempool(); delay(20_000) } }
        } catch (e: Throwable) {   // Throwable: a missing/incompatible native .so throws UnsatisfiedLinkError (an Error)
            status = "error: ${e.message?.take(40)}"; wantRun = false
        }
    }

    /**
     * Keeps a CBF node alive. `ScanType.Recovery` is a ONE-SHOT scan: when it
     * finishes (or the single peer drops), `node.run()` returns — previously that
     * left the wallet "stopped" forever. Here we supervise: (re)build the client +
     * node, run until it stops, then reconnect with backoff.
     */
    private suspend fun superviseCbf(w0: Wallet) {
        val w = w0
        var everSynced = false
        var backoff = 8_000L        // gentle reconnects — hammering the single peer gets the IP throttled/evicted
        while (scope.isActive && wantRun) {
            var infoJob: Job? = null
            var warnJob: Job? = null
            try {
                val peer = Peer(peerIp, peerPort, true)
                // Recovery from the chain checkpoint (bdk 3.0.0). kyoto reuses the
                // cached filters in the node dir on a resume, so this stays cheap.
                val comps = CbfBuilder()
                    .peers(listOf(peer))
                    .connections(1u.toUByte())
                    .scanType(ScanType.Recovery(1u, recoveryPoint()))
                    .dataDir(nodeDir())
                    .build(w)
                val cl = comps.client
                client = cl
                val node = comps.node
                status = if (everSynced) "syncing" else "recovering"
                android.util.Log.i(LOG, "[${meta.id.take(6)}] built everSynced=$everSynced walletExisted=$walletExisted scan=Recovery")
                // bdk 3.0.0: node.run() spins up the node's internal workers and returns
                // quickly. It MUST be called from a coroutine context (the UniFFI async
                // runtime that client.update()/nextInfo() share) — NOT a raw Thread, or
                // the node never drives its peer connection and sits at NeedConnections.
                // Matches the official devkit-wallet Kyoto.kt.
                runCatching { node.run() }.onFailure { android.util.Log.e(LOG, "node.run() failed", it) }
                android.util.Log.i(LOG, "[${meta.id.take(6)}] node.run() started")
                // Drain the info + warning channels (kyoto backpressure) while the
                // update loop applies scanned chain state to the wallet.
                infoJob = scope.launch {
                    while (isActive && client === cl) {
                        val info = try { cl.nextInfo() } catch (e: Exception) { break }
                        when (info) {
                            is Info.Progress -> { filtersPct = info.filtersDownloadedPercent; tipHeight = info.chainHeight.toInt(); status = "syncing ${(info.filtersDownloadedPercent * 100).toInt()}%" }
                            is Info.ConnectionsMet -> if (status.startsWith("connecting") || status.startsWith("recovering")) status = "syncing…"
                            else -> {}
                        }
                    }
                }
                warnJob = scope.launch {
                    while (isActive && client === cl) {
                        val warn = try { cl.nextWarning() } catch (e: Exception) { break }
                        android.util.Log.w(LOG, "[${meta.id.take(6)}] warning: $warn")
                        when (warn) {
                            is org.bitcoindevkit.Warning.CouldNotConnect,
                            is org.bitcoindevkit.Warning.NeedConnections -> status = "connecting to node…"
                            is org.bitcoindevkit.Warning.NoCompactFilters -> status = "peer has no compact filters"
                            is org.bitcoindevkit.Warning.PeerTimedOut -> status = "peer timed out"
                            else -> {}
                        }
                    }
                }
                if (syncLoop(w)) { everSynced = true; backoff = 2_000L }   // applied ≥1 update
            } catch (e: Throwable) {
                android.util.Log.e(LOG, "supervise error", e)
                status = "error: ${e.message?.take(40)}"
            } finally {
                warnJob?.cancel()
                infoJob?.cancel()
                val old = client; client = null
                runCatching { old?.shutdown() }   // stops the node → update()/nextInfo() throw and the loops end
            }
            if (!scope.isActive || !wantRun) break
            status = "reconnecting…"
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(120_000L)
        }
        status = if (wantRun) "stopped" else "idle"
    }

    private fun computeTxStatus(w: Wallet): Map<String, TxConf> = runCatching {
        w.transactions().associate { ctx ->
            val txid = ctx.transaction.computeTxid().toString()
            val conf = when (val pos = ctx.chainPosition) {
                is org.bitcoindevkit.ChainPosition.Confirmed ->
                    TxConf(true, pos.confirmationBlockTime.blockId.height.toInt())
                else -> TxConf(false, null)
            }
            txid to conf
        }
    }.getOrDefault(emptyMap())

    /** One row of wallet activity (received + sent). */
    data class ActivityTx(val txid: String, val net: Long, val confirmed: Boolean, val height: Int?, val timestamp: Long?)

    private fun computeActivity(w: Wallet): List<ActivityTx> = runCatching {
        w.transactions().map { ctx ->
            val sr = w.sentAndReceived(ctx.transaction)
            val net = sr.received.toSat().toLong() - sr.sent.toSat().toLong()
            val pos = ctx.chainPosition
            if (pos is org.bitcoindevkit.ChainPosition.Confirmed)
                ActivityTx(ctx.transaction.computeTxid().toString(), net, true,
                    pos.confirmationBlockTime.blockId.height.toInt(), pos.confirmationBlockTime.confirmationTime.toLong())
            else ActivityTx(ctx.transaction.computeTxid().toString(), net, false, null, null)
        }
    }.getOrDefault(emptyList())

    private fun cacheActivity(w: Wallet) = runCatching {
        WalletActivityCache.write(appCtx, meta.id, computeActivity(w))
        txCacheVersion++   // signal the ACTIVITY view to re-read the cache now
    }

    /** Incoming (net-received) txs for the received/confirmed notifier. */
    private fun computeIncoming(w: Wallet): List<WalletTxNotifier.Incoming> = runCatching {
        w.transactions().mapNotNull { ctx ->
            val sr = w.sentAndReceived(ctx.transaction)
            val net = sr.received.toSat().toLong() - sr.sent.toSat().toLong()
            if (net <= 0) return@mapNotNull null
            val confirmed = ctx.chainPosition is org.bitcoindevkit.ChainPosition.Confirmed
            WalletTxNotifier.Incoming(ctx.transaction.computeTxid().toString(), net, confirmed)
        }
    }.getOrDefault(emptyList())

    private fun notifyIncoming(w: Wallet) =
        runCatching { WalletTxNotifier.reconcile(appCtx, meta.id, meta.label.ifEmpty { "wallet" }, computeIncoming(w)) }

    /** Applies node updates to the wallet until the client stops or errors.
     *  Returns true if it applied at least one update (→ supervisor switches to
     *  continuous Sync mode). In bdk 3.0.0 `update()` suspends until an update is
     *  ready and throws once the client is shut down. */
    private suspend fun syncLoop(w: Wallet): Boolean {
        val c = client ?: return false
        var applied = false
        while (scope.isActive && wantRun) {
            val update = try { c.update() } catch (e: Exception) { android.util.Log.w(LOG, "update() threw: ${e.message}"); break }
            android.util.Log.i(LOG, "[${meta.id.take(6)}] update applied")
            runCatching {
                walletMutex.withLock {
                    w.applyUpdate(update)
                    persister?.let { w.persist(it) }
                    val b = w.balance()
                    balanceSats = b.total.toSat().toLong()
                    pendingSats = b.untrustedPending.toSat().toLong()   // incoming-unconfirmed only (iOS parity)
                    txStatus = computeTxStatus(w)
                }
                notifyIncoming(w)   // received / confirmed local notifications
                cacheActivity(w)    // so ACTIVITY shows history without a live node
                synced = true
                applied = true
                status = "synced"
                // A ZERO balance mid-recovery is meaningless (the scan simply hasn't
                // reached the funding blocks yet) — persisting it would wipe a
                // server-seeded cache. Zero only counts once the scan is complete.
                if (balanceSats > 0 || filtersPct >= 0.99f)
                    WalletStore.updateCache(appCtx, meta.id, balanceSats, tipHeight)
            }
            if (!(try { c.isRunning() } catch (e: Exception) { false })) break
        }
        return applied
    }

    /**
     * Broadcast a tx with reliable propagation: public relays PRIMARY, then PyBLØCK's own
     * CBF peer only if the relays reject. PyBLØCK's single CBF peer accepts a tx into its
     * mempool but doesn't reliably relay it to miners, so a CBF-only broadcast can leave a
     * send stuck (unpropagated). Mirrors iOS WalletNode. A send succeeds if EITHER accepts.
     */
    private suspend fun broadcastTx(tx: Transaction) {
        val publicOk = PublicBroadcast.submit(PublicBroadcast.hex(tx.serialize()))
        if (!publicOk) (client ?: error("node not started")).broadcast(tx)
    }

    /**
     * Build (watch-only) → sign (ephemeral WIF wallet) → broadcast → reflect 0-conf.
     * Returns the txid (not the wtxid). The private key is loaded ONLY here, into a
     * throwaway in-memory wallet, and never enters the long-running watch wallet.
     */
    suspend fun send(
        toAddress: String, sats: Long, feeSatPerVb: Long, sendMax: Boolean = false,
        /** Coin control: exact "txid:vout" keys to spend; null ⇒ BDK default selection. */
        selectedKeys: List<String>? = null,
    ): String {
        client ?: error("node not started")   // readiness gate; broadcastTx handles propagation
        // Spendable = what the BDK wallet actually holds (CBF-scanned AND/OR
        // server-seeded via seedConfirmed — real UTXOs with prev-tx data either
        // way). Only block un-synced sends when the wallet is genuinely empty.
        if (!synced) {
            val held = walletMutex.withLock { runCatching { bdk?.balance()?.total?.toSat()?.toLong() }.getOrNull() ?: 0L }
            if (held <= 0L) error("wallet still syncing — wait until it finishes before sending")
        }
        val addr = Address(toAddress, Network.BITCOIN)   // throws on invalid/wrong-network
        // Ephemeral signing descriptors (the WIF pair). Null ⇒ vault locked.
        val (sExt, sChg) = WalletDescriptors.signing(appCtx, meta)
            ?: error("vault locked — unlock to sign")
        val signOpts = SignOptions(
            trustWitnessUtxo = false,
            assumeHeight = null,
            allowAllSighashes = false,
            tryFinalize = true,
            signWithTapInternalKey = true,
            allowGrinding = true,
        )
        val frozen = UtxoMetaStore.frozenKeys(appCtx)    // do-not-spend set (persisted)
        val selected = selectedKeys?.toSet()
        val tx = walletMutex.withLock {
            val w = bdk ?: error("wallet not open")
            val live = w.listUnspent()
            fun k(u: org.bitcoindevkit.LocalOutput) = UtxoMetaStore.key(u.outpoint.txid.toString(), u.outpoint.vout.toInt())

            var builder = TxBuilder().feeRate(FeeRate.fromSatPerVb(feeSatPerVb.toULong()))
            if (selected != null) {
                // COIN CONTROL: spend EXACTLY the chosen coins. Fund safety: every requested
                // key must resolve to a live, non-frozen UTXO, else abort (never substitute).
                require(selected.isNotEmpty()) { "no coins selected" }
                require(selected.none { it in frozen }) { "a frozen coin is in the selection" }
                val chosen = live.filter { k(it) in selected }
                require(chosen.size == selected.size) { "selection out of date — refresh coins and try again" }
                builder = builder.manuallySelectedOnly()
                chosen.forEach { builder = builder.addUtxo(it.outpoint) }
            } else if (frozen.isNotEmpty()) {
                // DEFAULT selection: just exclude frozen coins.
                val frozenOutpoints = live.filter { k(it) in frozen }.map { it.outpoint }
                if (frozenOutpoints.isNotEmpty()) builder = builder.unspendable(frozenOutpoints)
            }
            // MAX drains the selected set (or whole wallet minus frozen) to the recipient;
            // otherwise send the exact amount.
            builder = if (sendMax) builder.drainWallet().drainTo(addr.scriptPubkey())
                      else builder.addRecipient(addr.scriptPubkey(), Amount.fromSat(sats.toULong()))
            val psbt = builder.finish(w)                 // built from the WATCH-ONLY UTXOs
            // Sign with a throwaway in-memory wallet holding the SAME descriptors WITH
            // the private key. The PSBT already carries each input's prev-tx data, so
            // signing needs no chain sync — the secret never touches the watch wallet.
            val signer = Wallet(sExt, sChg, Network.BITCOIN, Persister.newInMemory())
            if (!signer.sign(psbt, signOpts)) error("signing failed")
            val t = psbt.extractTx()
            // Belt-and-suspenders (mirrors iOS): the FINAL tx must spend NO frozen coin, and
            // — when coin-control — EXACTLY the chosen set. Abort before broadcast otherwise.
            val txInPoints = t.input().map { "${it.previousOutput.txid}:${it.previousOutput.vout}" }.toSet()
            require(frozen.none { it in txInPoints }) { "aborted: a frozen coin entered the tx" }
            if (selected != null) require(txInPoints == selected) { "aborted: tx inputs != selected coins" }
            t
        }
        broadcastTx(tx)                                  // public relays first, CBF fallback — outside the lock
        // 0-conf: reflect the spend in the watch wallet right away (balance drops,
        // tx shows in history) without waiting for a mempool poll or block. (iOS.)
        runCatching {
            val nowSec = (System.currentTimeMillis() / 1000L).toULong()
            walletMutex.withLock {
                val w = bdk ?: return@withLock
                w.applyUnconfirmedTxs(listOf(UnconfirmedTx(tx, nowSec)))
                persister?.let { w.persist(it) }
                val b = w.balance()
                balanceSats = b.total.toSat().toLong()
                pendingSats = b.untrustedPending.toSat().toLong()
                txStatus = computeTxStatus(w)
                cacheActivity(w)
            }
        }
        return tx.computeTxid().toString()
    }

    /**
     * RICOCHET (mainnet): spend the selected coins forward through N ephemeral wpkh hop
     * keys — coordinator-free. tx0 is built from the WATCH-ONLY wallet (coin-control or
     * whole-wallet-minus-frozen) → hop0, signed by the throwaway WIF wallet; AMOUNT mode
     * stages `amount + N·hopFee` and keeps the CHANGE, MAX sweeps. Each hop then sweeps
     * its predecessor's output forward (in memory), and the whole chain is broadcast in
     * order — public relays PRIMARY (reliable propagation), the sovereign CBF peer as a
     * best-effort fallback. Hop keys are 256-bit and KEPT (returned in the outcome) so the
     * user can PROVE the hops — e.g. the sender address a recipient/exchange saw. Fund-
     * safety: aborts BEFORE any broadcast if fees would eat the payment. Android mirror of
     * iOS `CoinSpend.ricochet`.
     */
    suspend fun ricochet(
        toAddress: String, amountSats: Long, sendMax: Boolean, hops: Int, feeSatPerVb: Long,
        /** Coin control: exact "txid:vout" keys to spend; null ⇒ whole wallet minus frozen. */
        selectedKeys: List<String>? = null,
    ): RicochetOutcome {
        if (client == null) error("node not started")   // wallet must be live; broadcast is public-relay only
        if (!synced) {
            val held = walletMutex.withLock { runCatching { bdk?.balance()?.total?.toSat()?.toLong() }.getOrNull() ?: 0L }
            if (held <= 0L) error("wallet still syncing — wait until it finishes before sending")
        }
        val n = maxOf(1, minOf(4, hops))
        val recipient = Address(toAddress, Network.BITCOIN).scriptPubkey()   // throws on invalid
        val (sExt, sChg) = WalletDescriptors.signing(appCtx, meta) ?: error("vault locked — unlock to sign")
        val signOpts = SignOptions(
            trustWitnessUtxo = false, assumeHeight = null, allowAllSighashes = false,
            tryFinalize = true, signWithTapInternalKey = true, allowGrinding = true,
        )
        // Hops sign foreign SegWit inputs from the witnessUtxo alone (no prev-tx attached).
        val witnessSignOpts = SignOptions(
            trustWitnessUtxo = true, assumeHeight = null, allowAllSighashes = false,
            tryFinalize = true, signWithTapInternalKey = true, allowGrinding = true,
        )
        val frozen = UtxoMetaStore.frozenKeys(appCtx)
        val selected = selectedKeys?.toSet()

        val feeRate = FeeRate.fromSatPerVb(feeSatPerVb.toULong())
        val hopFee = feeSatPerVb * 115                     // conservative 1-in-1-out P2WPKH
        val staged = amountSats + n.toLong() * hopFee

        // 256-bit hop keys → mainnet wpkh addresses + single-key signers (KEPT).
        data class Hop(val address: String, val wif: String, val script: Script, val signer: Wallet)
        val hopChain = ArrayList<Hop>()
        for (i in 0 until n) {
            val bytes = com.astrolexis.pyblock.data.crypto.VanityCrypto.hardenedRandom32(ByteArray(0))
            val hopWif = com.astrolexis.pyblock.data.crypto.VanityCrypto.wifCompressed(bytes)
            val w = Wallet.createSingle(Descriptor("wpkh($hopWif)", NetworkKind.MAIN), Network.BITCOIN, Persister.newInMemory())
            val addr = w.peekAddress(KeychainKind.EXTERNAL, 0u).address
            hopChain.add(Hop(addr.toString(), hopWif, addr.scriptPubkey(), w))
        }

        val builtTxs = ArrayList<Transaction>()
        var totalFee = 0L

        // tx0: selected/whole-wallet coins → hop0 (+ change in AMOUNT mode). Built under the
        // wallet lock; signed by the ephemeral WIF wallet. Same fund-safety guards as send().
        val tx0 = walletMutex.withLock {
            val w = bdk ?: error("wallet not open")
            val live = w.listUnspent()
            fun k(u: org.bitcoindevkit.LocalOutput) = UtxoMetaStore.key(u.outpoint.txid.toString(), u.outpoint.vout.toInt())
            var builder = TxBuilder().feeRate(feeRate)
            if (selected != null) {
                require(selected.isNotEmpty()) { "no coins selected" }
                require(selected.none { it in frozen }) { "a frozen coin is in the selection" }
                val chosen = live.filter { k(it) in selected }
                require(chosen.size == selected.size) { "selection out of date — refresh coins and try again" }
                builder = builder.manuallySelectedOnly()
                chosen.forEach { builder = builder.addUtxo(it.outpoint) }
            } else if (frozen.isNotEmpty()) {
                val frozenOutpoints = live.filter { k(it) in frozen }.map { it.outpoint }
                if (frozenOutpoints.isNotEmpty()) builder = builder.unspendable(frozenOutpoints)
            }
            builder = if (sendMax) builder.drainWallet().drainTo(hopChain[0].script)
                      else builder.addRecipient(hopChain[0].script, Amount.fromSat(staged.toULong()))
            val psbt = builder.finish(w)
            val signer = Wallet(sExt, sChg, Network.BITCOIN, Persister.newInMemory())
            if (!signer.sign(psbt, signOpts)) error("signing failed")
            val t = psbt.extractTx()
            val txInPoints = t.input().map { "${it.previousOutput.txid}:${it.previousOutput.vout}" }.toSet()
            require(frozen.none { it in txInPoints }) { "aborted: a frozen coin entered the tx" }
            if (selected != null) require(txInPoints == selected) { "aborted: tx inputs != selected coins" }
            // tx0 fee = inputs − outputs. NEVER default to 0: if it can't be verified we can't
            // enforce the fee-safety guard, so abort (mirrors iOS, which computes it deterministically).
            totalFee = runCatching { psbt.fee().toLong() }.getOrElse { error("could not verify the ricochet fee — aborted, nothing sent") }
            t
        }
        builtTxs.add(tx0)

        // Find the output paying hop0 (AMOUNT mode also has a change output → not always [0]).
        val hop0Bytes = hopChain[0].script.toBytes()
        val hop0Idx = tx0.output().indexOfFirst { it.scriptPubkey.toBytes().contentEquals(hop0Bytes) }
        if (hop0Idx < 0) error("could not locate hop0 output")
        var prevTxid = tx0.computeTxid()
        var prevVout = hop0Idx.toUInt()
        var prevOut = tx0.output()[hop0Idx]

        // Hops: each spends the previous carried output → next hop / recipient (sweep, 1 out).
        for (i in 0 until n) {
            val outpoint = OutPoint(prevTxid, prevVout)
            val dest = if (i == n - 1) recipient else hopChain[i + 1].script
            var hb = TxBuilder().feeRate(feeRate).manuallySelectedOnly().onlyWitnessUtxo()
            hb = hb.addForeignUtxo(outpoint, segwitInput(prevOut), 107uL)
            hb = hb.drainTo(dest)
            val psbt = hb.finish(hopChain[i].signer)
            if (!hopChain[i].signer.sign(psbt, witnessSignOpts)) error("hop signing failed")
            val fin = psbt.finalize()
            if (!fin.couldFinalize) error("hop not finalized")
            val tx = fin.psbt.extractTx()
            builtTxs.add(tx)
            val out0 = tx.output().firstOrNull() ?: error("hop has no output")
            totalFee += prevOut.value.toSat().toLong() - out0.value.toSat().toLong()   // this hop's fee
            prevOut = out0; prevTxid = tx.computeTxid(); prevVout = 0u
        }

        // FUND-SAFETY: abort BEFORE any broadcast if the fee would eat the payment.
        if (!sendMax && totalFee >= amountSats)
            error("Ricochet fees ($totalFee sats) would equal or exceed the $amountSats sats you're sending. Use fewer hops or a lower fee rate.")

        // Broadcast the whole chain IN ORDER via public relays (child after its parent) so the
        // chain actually propagates to miners. We deliberately do NOT fall back to PyBLØCK's
        // single CBF peer here (mirrors iOS broadcastChain): that peer doesn't reliably relay a
        // chain — which is the exact propagation gap PublicBroadcast exists to close. Public
        // failure is fatal: earlier txs may already be out, but every hop key is kept so the
        // funds stay recoverable (the chain can be rebroadcast).
        for ((idx, tx) in builtTxs.withIndex()) {
            if (!PublicBroadcast.submit(PublicBroadcast.hex(tx.serialize()))) error("broadcast failed at hop $idx — nothing further sent")
            if (idx < builtTxs.size - 1) delay(1500)   // let the relay see the parent first
        }

        // Reflect tx0 on the watch wallet so the spend shows as pending right away.
        runCatching {
            val nowSec = (System.currentTimeMillis() / 1000L).toULong()
            walletMutex.withLock {
                val w = bdk ?: return@withLock
                w.applyUnconfirmedTxs(listOf(UnconfirmedTx(tx0, nowSec)))
                persister?.let { w.persist(it) }
                val b = w.balance()
                balanceSats = b.total.toSat().toLong()
                pendingSats = b.untrustedPending.toSat().toLong()
                txStatus = computeTxStatus(w)
                cacheActivity(w)
            }
        }

        return RicochetOutcome(
            txids = builtTxs.map { it.computeTxid().toString() },
            hopAddresses = hopChain.map { it.address },
            hopWifs = hopChain.map { it.wif },
        )
    }

    /** A PSBT input carrying only the witness UTXO (script + value) — all a native SegWit
     *  input (the ephemeral wpkh ricochet hops) needs to sign. */
    private fun segwitInput(txout: TxOut): Input = Input(
        nonWitnessUtxo = null, witnessUtxo = txout, partialSigs = emptyMap(), sighashType = null,
        redeemScript = null, witnessScript = null, bip32Derivation = emptyMap(),
        finalScriptSig = null, finalScriptWitness = emptyList(),
        ripemd160Preimages = emptyMap(), sha256Preimages = emptyMap(),
        hash160Preimages = emptyMap(), hash256Preimages = emptyMap(),
        tapKeySig = null, tapScriptSigs = emptyMap(), tapScripts = emptyMap(),
        tapKeyOrigins = emptyMap(), tapInternalKey = null, tapMerkleRoot = null,
        proprietary = emptyMap(), unknown = emptyMap(),
    )

    /**
     * Air-gap phase 1: build an UNSIGNED PSBT from the WATCH-ONLY wallet — no key touched,
     * never broadcast. Serialized base64 for an offline signer. Mirrors iOS
     * WalletNode.buildUnsignedPSBT (excludes frozen coins; MAX drains).
     */
    suspend fun buildUnsignedPSBT(toAddress: String, sats: Long, sendMax: Boolean, feeSatPerVb: Long): String {
        if (!synced) {
            val held = walletMutex.withLock { runCatching { bdk?.balance()?.total?.toSat()?.toLong() }.getOrNull() ?: 0L }
            if (held <= 0L) error("wallet still syncing — wait until it finishes before exporting")
        }
        val addr = Address(toAddress, Network.BITCOIN)   // throws on invalid/wrong-network
        val frozen = UtxoMetaStore.frozenKeys(appCtx)
        return walletMutex.withLock {
            val w = bdk ?: error("wallet not open")
            fun k(u: org.bitcoindevkit.LocalOutput) = UtxoMetaStore.key(u.outpoint.txid.toString(), u.outpoint.vout.toInt())
            var builder = TxBuilder().feeRate(FeeRate.fromSatPerVb(feeSatPerVb.toULong()))
            if (frozen.isNotEmpty()) {
                val frozenOutpoints = w.listUnspent().filter { k(it) in frozen }.map { it.outpoint }
                if (frozenOutpoints.isNotEmpty()) builder = builder.unspendable(frozenOutpoints)
            }
            builder = if (sendMax) builder.drainWallet().drainTo(addr.scriptPubkey())
                      else builder.addRecipient(addr.scriptPubkey(), Amount.fromSat(sats.toULong()))
            builder.finish(w).serialize()                // built from the WATCH-ONLY UTXOs; unsigned
        }
    }

    /**
     * Air-gap phase 3: finalize a fully-signed PSBT and relay it via the running client.
     * Reflects 0-conf. Mirrors iOS WalletNode.broadcastSignedPSBT.
     */
    suspend fun broadcastSignedPSBT(signedBase64: String): String {
        client ?: error("node not started")   // readiness gate; broadcastTx handles propagation
        val psbt = runCatching { Psbt(signedBase64.trim()) }.getOrElse { error("that isn't a valid PSBT") }
        val fin = psbt.finalize()
        if (!fin.couldFinalize) error("PSBT isn't fully signed")   // not finalizable
        val tx = fin.psbt.extractTx()
        broadcastTx(tx)                                  // public relays first, CBF fallback — outside the lock
        runCatching {
            val nowSec = (System.currentTimeMillis() / 1000L).toULong()
            walletMutex.withLock {
                val w = bdk ?: return@withLock
                w.applyUnconfirmedTxs(listOf(UnconfirmedTx(tx, nowSec)))
                persister?.let { w.persist(it) }
                val b = w.balance()
                balanceSats = b.total.toSat().toLong()
                pendingSats = b.untrustedPending.toSat().toLong()
                txStatus = computeTxStatus(w)
                cacheActivity(w)
            }
        }
        return tx.computeTxid().toString()
    }

    // ---- PayJoin (Collaborative Send) bridge — all fund-safety guards live in PayJoinTx;
    // these just supply the wallet (under the mutex) + the running client for broadcast.
    // Gated by PayJoinFeature (no callers until the coordinator/UI land). ----

    /** Sender: build the ORIGINAL payment (under the wallet lock). */
    suspend fun pjBuildOriginal(
        sessionId: String, selected: List<UtxoInfo>, toAddress: String,
        amountSats: Long, feeRateSatVb: Long, frozenKeys: Set<String>,
    ): PayJoinTx.Original = walletMutex.withLock {
        val w = bdk ?: error("wallet not open")
        val o = PayJoinTx.buildOriginal(
            appCtx, meta, w, selected, toAddress, amountSats, feeRateSatVb,
            com.astrolexis.pyblock.data.nostr.PayJoin.MAX_ADDITIONAL_FEE_SATS.toLong(), frozenKeys,
        )
        o.copy(ctx = o.ctx.copy(sessionId = sessionId))
    }

    /** Receiver: co-sign the proposal (under the wallet lock). */
    suspend fun pjBuildProposal(
        originalPsbtB64: String, coin: PayJoinTx.ReceiverCoin, receiveAddress: String,
        amountSats: Long, feeRateSatVb: Long,
    ): String = walletMutex.withLock {
        val w = bdk ?: error("wallet not open")
        PayJoinTx.buildProposal(appCtx, meta, originalPsbtB64, w, coin, receiveAddress, amountSats, feeRateSatVb)
    }

    /** Sender: verify + broadcast the co-signed proposal via the running client; reflect 0-conf. */
    suspend fun pjFinalizeAndBroadcast(proposalPsbtB64: String, original: PayJoinTx.Original): String {
        val c = client ?: error("node not started")
        return PayJoinTx.finalizeAndBroadcast(appCtx, meta, proposalPsbtB64, original) { tx ->
            c.broadcast(tx); pjReflect(tx)
        }
    }

    /** Sender fallback: broadcast the plain original (Defect-C interlocked in PayJoinTx). */
    suspend fun pjBroadcastFallback(original: PayJoinTx.Original): String {
        val c = client ?: error("node not started")
        return PayJoinTx.broadcastFallback(appCtx, original) { tx -> c.broadcast(tx); pjReflect(tx) }
    }

    /** True once the CBF client is connected and a tx can be broadcast. */
    val canBroadcast get() = client != null

    /** Suspend until this node can broadcast (client up), or [timeoutMs] elapses. Starts
     *  nothing — the caller focuses the node first; a PayJoin round-trip gives it ample
     *  time to connect before the sender's broadcast step. */
    suspend fun awaitBroadcastReady(timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (client == null && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(250)
        return client != null
    }

    private suspend fun pjReflect(tx: Transaction) {
        runCatching {
            val nowSec = (System.currentTimeMillis() / 1000L).toULong()
            walletMutex.withLock {
                val w = bdk ?: return@withLock
                w.applyUnconfirmedTxs(listOf(UnconfirmedTx(tx, nowSec)))
                persister?.let { w.persist(it) }
                balanceSats = w.balance().total.toSat().toLong()
            }
        }
    }

    /** Live spendable UTXOs decorated with persisted freeze/label metadata (for the COINS
     *  hub + the SEND coin picker). Uses the open node when running, else reads the persisted
     *  SQLite read-only so the hub works with nodes closed. Single-key wallet ⇒ every UTXO is
     *  on [meta.address]. */
    suspend fun unspentOutputs(): List<UtxoInfo> = walletMutex.withLock {
        val w = bdk
        if (w != null) return@withLock w.listUnspent().map { toUtxoInfo(it) }
        // Node closed — open the persisted store read-only (watch-only descriptors), best-effort.
        runCatching {
            val dbPath = File(File(appCtx.filesDir, "wallets"), "${meta.id}.sqlite").absolutePath
            if (!File(dbPath).exists()) return@runCatching emptyList<UtxoInfo>()
            val (ext, chg) = WalletDescriptors.watch(appCtx, meta) ?: return@runCatching emptyList<UtxoInfo>()
            val pw = Wallet.load(ext, chg, Persister.newSqlite(dbPath))
            pw.listUnspent().map { toUtxoInfo(it) }
        }.getOrDefault(emptyList())
    }

    private fun toUtxoInfo(u: org.bitcoindevkit.LocalOutput): UtxoInfo {
        val txid = u.outpoint.txid.toString()
        val vout = u.outpoint.vout.toInt()
        val key = UtxoMetaStore.key(txid, vout)
        return UtxoInfo(
            key = key, txid = txid, vout = vout,
            valueSats = u.txout.value.toSat().toLong(),
            confirmations = 0,
            walletId = meta.id, walletLabel = meta.label, address = meta.address,
            label = UtxoMetaStore.label(appCtx, key),
            frozen = UtxoMetaStore.isFrozen(appCtx, key),
        )
    }

    /**
     * BIP-47 notification transaction: announce MY payment code to [toPeerCode] so the
     * receiver can discover the stealth payments I make to them (a "cold" PayNym send
     * from the wallet screen carries no chat/DM signal). A tiny tx paying the peer's
     * notification address + an OP_RETURN with my blinded code. Uses a SINGLE designated
     * input (the largest UTXO) so it is unambiguously input[0], whose outpoint keys the
     * blinding — matching what the receiver reads. One-time per peer. Returns the txid.
     * Mirrors iOS WalletNode.sendNotification.
     */
    suspend fun sendNotification(toPeerCode: String, feeSatPerVb: Long): String {
        client ?: error("node not started")   // readiness gate; broadcastTx handles propagation
        val notifAddr = com.astrolexis.pyblock.data.crypto.PaymentCode.notificationAddressForPeer(toPeerCode)
            ?: error("bad payment code")
        val wif = WalletStore.wif(appCtx, meta.id) ?: error("vault locked — unlock to sign")
        val (priv, _) = com.astrolexis.pyblock.data.crypto.VanityCrypto.decodeWif(wif) ?: error("bad key")
        val (sExt, sChg) = WalletDescriptors.signing(appCtx, meta) ?: error("vault locked — unlock to sign")
        val signOpts = SignOptions(
            trustWitnessUtxo = false, assumeHeight = null, allowAllSighashes = false,
            tryFinalize = true, signWithTapInternalKey = true, allowGrinding = true,
        )
        val addr = Address(notifAddr, Network.BITCOIN)
        val tx = walletMutex.withLock {
            val w = bdk ?: error("wallet not open")
            // Designated input = largest UTXO → single input → unambiguously input[0].
            val designated = w.listUnspent().maxByOrNull { it.txout.value.toSat() }
                ?: error("no spendable coins for the notification")
            val vout = designated.outpoint.vout
            // Outpoint bytes as serialized: txid INTERNAL order (reverse of display) ‖ vout LE.
            val txidInternal = designated.outpoint.txid.toString()
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray().reversedArray()
            val outpointBytes = txidInternal + byteArrayOf(
                (vout and 0xFFu).toByte(), ((vout shr 8) and 0xFFu).toByte(),
                ((vout shr 16) and 0xFFu).toByte(), ((vout shr 24) and 0xFFu).toByte(),
            )
            val blinded = com.astrolexis.pyblock.data.crypto.PaymentCode
                .blindedNotificationPayload(appCtx, toPeerCode, priv, outpointBytes)
                ?: error("notification build failed")
            val psbt = TxBuilder()
                .feeRate(FeeRate.fromSatPerVb(feeSatPerVb.toULong()))
                .manuallySelectedOnly()
                .addUtxo(designated.outpoint)
                .addRecipient(addr.scriptPubkey(), Amount.fromSat(600uL))   // notif output, above dust
                .addData(blinded)
                .finish(w)
            val signer = Wallet(sExt, sChg, Network.BITCOIN, Persister.newInMemory())
            if (!signer.sign(psbt, signOpts)) error("signing failed")
            val t = psbt.extractTx()
            // Sanity: our designated coin must be input[0] (single input → always true);
            // guards against a coin-selection change reordering inputs, which would break
            // the receiver's outpoint-keyed unblind.
            val fin = t.input().firstOrNull() ?: error("no input")
            if (fin.previousOutput.txid.toString() != designated.outpoint.txid.toString() ||
                fin.previousOutput.vout != designated.outpoint.vout) error("input reordered")
            t
        }
        broadcastTx(tx)                                  // public relays first, CBF fallback
        // Reflect the spend right away so the follow-up payment sees the updated UTXO set.
        runCatching {
            val nowSec = (System.currentTimeMillis() / 1000L).toULong()
            walletMutex.withLock {
                val w = bdk ?: return@withLock
                w.applyUnconfirmedTxs(listOf(UnconfirmedTx(tx, nowSec)))
                persister?.let { w.persist(it) }
                val b = w.balance()
                balanceSats = b.total.toSat().toLong()
                pendingSats = b.untrustedPending.toSat().toLong()
                txStatus = computeTxStatus(w)
                cacheActivity(w)
            }
        }
        return tx.computeTxid().toString()
    }

    /**
     * Server-assisted instant balance: credit CONFIRMED UTXOs (from PyBLØCK's own
     * node via wallet_utxos.php) found by the app-wide sweep. If the BDK wallet is
     * open, inject the raw funding txs — they show as PENDING until the CBF scan
     * anchors them (the trustless verifier stays authoritative). If the node isn't
     * open, reflect the confirmed sum in the display/cached balance so the list is
     * right without a scan. Only ever RAISES a balance — absence proves nothing.
     */
    suspend fun seedConfirmed(utxos: List<com.astrolexis.pyblock.data.model.Utxo>) {
        if (utxos.isEmpty()) return
        val nowSec = (System.currentTimeMillis() / 1000L).toULong()
        val txs = utxos.mapNotNull { u ->
            runCatching {
                val h = u.hex.trim()
                require(h.isNotEmpty() && h.length % 2 == 0) { "bad hex length ${h.length}" }
                UnconfirmedTx(Transaction(h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()), nowSec)
            }.onFailure {
                android.util.Log.w(LOG, "[${meta.id.take(6)}] seedConfirmed: unparsable hex ${u.txid.take(12)}:${u.vout}: ${it.message?.take(60)}")
            }.getOrNull()
        }
        val applied = injectTxs(txs)
        // Display floor also after a PARTIAL apply (some hex unparsable): the
        // dropped UTXO's value must not silently vanish — the floor only ever
        // raises, and the CBF scan supersedes it once it anchors the real txs.
        if (!applied || txs.size < utxos.size) {
            val sum = utxos.sumOf { it.value }
            if (sum > balanceSats) {
                balanceSats = sum
                WalletStore.updateCache(appCtx, meta.id, sum, tipHeight)
            }
        }
        if (!applied) {
            // Stopgap ACTIVITY entries from the same server data (real txid/amount/
            // height), so the receive is visible before the node's first run — the
            // node's own history replaces them once it opens and applies.
            runCatching {
                val existing = WalletActivityCache.cached(appCtx, meta.id)
                val have = existing.map { it.txid }.toSet()
                val seeded = utxos.groupBy { it.txid }
                    .filterKeys { it.isNotEmpty() && it !in have }
                    .map { (txid, us) ->
                        ActivityTx(txid, us.sumOf { it.value }, true,
                            us.first().height.takeIf { h -> h > 0 }, null)
                    }
                if (seeded.isNotEmpty()) WalletActivityCache.write(appCtx, meta.id, existing + seeded)
            }
        }
    }

    /** 0-conf mempool pump entry: raw txs paying this address (server's 0-conf
     *  feed, cheap endpoint) — an incoming payment becomes visible within one
     *  pump cycle (~30 s) even while the node is closed. The CBF scan later
     *  anchors (or a reorg evicts) them — the trustless verifier stays in charge. */
    suspend fun seedMempool(txsHexSeen: List<Pair<String, Long>>) {
        if (txsHexSeen.isEmpty()) return
        val nowSec = System.currentTimeMillis() / 1000
        val txs = txsHexSeen.take(50).mapNotNull { (hex, seen) ->
            runCatching {
                val h = hex.trim()
                require(h.isNotEmpty() && h.length % 2 == 0) { "bad hex length ${h.length}" }
                UnconfirmedTx(Transaction(h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()),
                    (if (seen in 1..4_000_000_000L) seen else nowSec).toULong())
            }.getOrNull()
        }
        injectTxs(txs)
    }

    /** Inject raw txs into the wallet — open node (under walletMutex) or, when the
     *  node is CLOSED, offline into its DB (open → apply → persist → close;
     *  synchronized(this) excludes a concurrent start()'s openWallet). The funds
     *  become real BDK state: they survive restarts and the SEND flow can build a
     *  PSBT from them before the CBF scan finishes. No-op when every txid is
     *  already known (avoids re-opening the DB each sweep for the same txs). */
    private suspend fun injectTxs(txs: List<UnconfirmedTx>): Boolean {
        if (txs.isEmpty()) return false
        if (txs.all { t ->
                val id = runCatching { t.tx.computeTxid().toString() }.getOrNull()
                id != null && txStatus.containsKey(id)
            }) return true
        var applied = false
        runCatching {
            walletMutex.withLock {
                val w = bdk ?: return@withLock
                w.applyUnconfirmedTxs(txs)
                persister?.let { w.persist(it) }
                refreshDerived(w)
                applied = true
            }
        }
        if (!applied) runCatching {
            synchronized(this) {
                if (bdk != null || wantRun) return@synchronized      // node came up meanwhile — the open path covers it next pass
                val (ext, chg) = WalletDescriptors.watch(appCtx, meta) ?: return@synchronized
                val dbFile = File(dbPath())
                val p = Persister.newSqlite(dbPath())
                try {
                    // Existing DB that fails to load is corrupt — that's start()'s
                    // wipe-and-rebuild job, not ours; skip and let the next sweep
                    // seed the rebuilt DB. A missing DB (claimed wallet whose node
                    // never ran) is simply created.
                    val w = if (dbFile.exists()) Wallet.load(ext, chg, p)
                            else Wallet(ext, chg, Network.BITCOIN, p)
                    w.revealAddressesTo(KeychainKind.EXTERNAL, 0u)
                    w.applyUnconfirmedTxs(txs)
                    w.persist(p)
                    refreshDerived(w)
                    applied = true
                    android.util.Log.i(LOG, "[${meta.id.take(6)}] injected ${txs.size} tx(s) offline, balance=$balanceSats")
                } finally { runCatching { p.close() } }
            }
        }.onFailure { android.util.Log.w(LOG, "[${meta.id.take(6)}] offline inject failed: ${it.message?.take(60)}") }
        return applied
    }

    /** Post-apply state refresh shared by every injection path. */
    private fun refreshDerived(w: Wallet) {
        val b = w.balance()
        balanceSats = b.total.toSat().toLong()
        pendingSats = b.untrustedPending.toSat().toLong()
        txStatus = computeTxStatus(w)
        notifyIncoming(w)
        cacheActivity(w)
        WalletStore.updateCache(appCtx, meta.id, balanceSats, tipHeight)
    }

    /** 0-conf: pull unconfirmed txs paying this address from PyBLØCK's node.
     *  BDK only credits outputs matching our script, so unrelated txs can't
     *  inflate the balance; a non-confirming tx to us is the only (own-node) caveat. */
    private suspend fun refreshMempool() {
        val resp = try { ApiClient.api.walletMempool(meta.address) } catch (e: Exception) { return }
        if (resp.txs.isEmpty()) return
        val nowSec = System.currentTimeMillis() / 1000
        val unconfirmed = resp.txs.take(50).mapNotNull { t ->
            runCatching {
                val bytes = t.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val seen = if (t.seen in 1..4_000_000_000L) t.seen else nowSec
                UnconfirmedTx(Transaction(bytes), seen.toULong())
            }.getOrNull()
        }
        if (unconfirmed.isEmpty()) return
        runCatching {
            walletMutex.withLock {
                val w = bdk ?: return@withLock
                w.applyUnconfirmedTxs(unconfirmed)
                val b = w.balance()
                balanceSats = b.total.toSat().toLong()
                pendingSats = b.untrustedPending.toSat().toLong()
                txStatus = computeTxStatus(w)
                notifyIncoming(w)   // fire 'received · pending' as soon as it hits the mempool
                cacheActivity(w)
            }
        }
    }

    fun stop() {
        android.util.Log.w(LOG, "[${meta.id.take(6)}] stop() called by:", Throwable("stop-trace"))
        wantRun = false
        val c = client; val p = persister
        client = null; persister = null; bdk = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        synced = false
        status = "idle"
        // Shut down + close on a detached scope so we don't race the cancel. The
        // Persister close waits on walletMutex — an in-flight applyUpdate/persist
        // holds it in blocking native code that cancel() can't interrupt, and
        // closing the SQLite under it would be a native use-after-close.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { c?.shutdown() }
            walletMutex.withLock { runCatching { p?.close() } }
        }
    }
}
