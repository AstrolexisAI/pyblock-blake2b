package com.astrolexis.pyblock.data.crypto

import android.content.Context
import com.astrolexis.pyblock.data.model.Utxo
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.data.net.ConfirmedUtxos
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.data.wallet.WalletStore
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Receiving to your OWN PayNym from EXTERNAL BIP-47 wallets (Samourai/Sparrow): they send a tiny
 * notification tx to our notification address with an OP_RETURN carrying their (blinded) payment
 * code. We watch that address on PyBLØCK's own node (wallet_mempool.php), unblind the sender's
 * code, then import any of their payments that have arrived — reusing the peer receive derivation.
 * Never spends the (linkable) notification output. Mirrors the iOS PaynymNotifications.
 */
object PaynymNotifications {
    private const val PREFS = "pyblock_paynym_extsenders"
    private const val KEY = "senders_v1"
    /** Set once the post-upgrade dual-scheme sweep has run. Before that, every pass is a full one,
     *  so a coin paid under the pre-0.2.4 derivation is found without the user doing anything. */
    private const val KEY_FULLSWEEP = "fullsweep_v2_done"
    /** Look-ahead window. Matches iOS: a shorter window on one platform loses payments the other
     *  would have found. */
    const val GAP = 20

    /** The chain this app's wallet lives on.
     *
     *  This file arrived with the fork from the SHA-256 app, where `chain` was left unset and the
     *  server answered for Bitcoin. Here the keys in [WalletStore] hold BLAKE2b coins, so every
     *  unset call was asking the wrong chain whether a payment had arrived — and being told no.
     *  A PayNym payment on this chain was undiscoverable, confirmed or not. */
    const val CHAIN = "blake2b"

    /** How many indices past the frontier to probe in the mempool. BIP-47 fills indices in order,
     *  so a payment being made right now lands at or just past the frontier — but "just past" is
     *  real: a sender who paid twice before we swept, or reinstalled, is already ahead of us. */
    private const val MEMPOOL_LOOKAHEAD = 5

    /** Internal: the chat sweep widens its own peer windows in the same pass. */
    fun fullSweepPending(ctx: Context) =
        !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FULLSWEEP, false)
    private fun markFullSweepDone(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FULLSWEEP, true).apply()

    private fun load(ctx: Context): MutableSet<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun save(ctx: Context, s: Set<String>) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, s).apply()

    /** Saved PayNym contacts are ALSO watched as senders — so a payment from a contact is
     *  discovered even without a notification tx or a chat peer relationship (recovers a
     *  "cold" PayNym send: add the sender as a contact and their payments to us appear). */
    private fun contactCodes(ctx: Context): List<String> =
        PaynymBook.all(ctx).map { it.code }.filter { PaymentCode.decode(it) != null }

    /** Every code we watch for incoming payments: notification-tx-discovered senders ∪ saved contacts. */
    private fun knownSenders(ctx: Context): Set<String> = load(ctx) + contactCodes(ctx)

    /** Look-ahead candidates (index → address) for every known sender (ext senders + contacts)
     *  — exposed so the app-wide sweep can batch them into ONE wallet_utxos call. */
    fun candidates(ctx: Context, gap: Int = GAP, full: Boolean = false): Map<String, List<PaymentCode.Candidate>> {
        val wide = full || fullSweepPending(ctx)
        return knownSenders(ctx).associateWith { code -> PaymentCode.candidates(ctx, code, gap, wide) }
    }

    @Volatile private var lastScanMs = 0L

    /** One full pass: (1) discover sender codes from notification txs to our notification address,
     *  (2) import any funded derived receive addresses from every known sender. TTL-guarded: the
     *  RECEIVE sheet fires this on every open, and the background sweep already covers the same
     *  window — rapid re-opens must not each trigger a server-side UTXO-set scan. */
    suspend fun scan(ctx: Context, gap: Int = GAP, full: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        // An explicit user check must never be swallowed by the background pass's TTL.
        if (!full && now - lastScanMs < 45_000) return
        lastScanMs = now
        sweep(ctx, gap, full)
    }

    /** One pass over the notification address and every known sender's look-ahead window.
     *
     *  Checks BOTH 0-conf (wallet_mempool) and CONFIRMED (wallet_utxos): a payment that confirmed
     *  and left the mempool is otherwise invisible to the app, and one that has not confirmed yet
     *  is invisible without the mempool. Fetches its own [CHAIN] batch — the caller's confirmed-UTXO
     *  map, where there is one, belongs to the Bitcoin wallet and says nothing about this chain. */
    suspend fun sweep(ctx: Context, gap: Int = GAP, full: Boolean = false) {
        val wide = full || fullSweepPending(ctx)
        val notifAddr = PaymentCode.notificationAddress(ctx) ?: return
        val discovered = load(ctx)            // notification-tx-discovered senders

        // 1) discover senders — from 0-conf notification txs AND confirmed ones. A
        //    BIP-47 notification output is never spent, so it sits in the UTXO set
        //    forever and wallet_utxos hands us the raw tx to unblind.
        val notifConfirmed = ConfirmedUtxos.fetch(listOf(notifAddr), CHAIN).byAddress
        val notifHexes =
            (try { ApiClient.api.walletMempool(notifAddr, CHAIN).txs.map { it.hex } } catch (e: Exception) { emptyList() }) +
            notifConfirmed[notifAddr].orEmpty().map { it.hex }
        for (hex in notifHexes) {
            val ntx = PaymentCode.parseNotificationTx(hex) ?: continue
            val code = PaymentCode.unblindNotification(ctx, ntx.designatedPubkey, ntx.outpoint, ntx.payload) ?: continue
            discovered.add(code)
        }
        save(ctx, discovered)

        // Claim from EVERY known sender: notif-discovered ∪ saved contacts. One batched call for
        // the whole set — wallet_utxos costs the server a UTXO-set scan per CALL, not per address.
        val known = discovered + contactCodes(ctx)
        val windows = known.associateWith { PaymentCode.candidates(ctx, it, gap, wide) }
        val utxos = ConfirmedUtxos.fetch(windows.values.flatten().map { it.address }, CHAIN).byAddress

        // 2) import funded payments from each known sender — confirmed vs the batch over the whole
        //    window; 0-conf mempool probe near the frontier only. Claims are check-then-act over
        //    shared state, so the whole step runs under the app-wide claim lock.
        WalletStore.ensureLoaded(ctx)
        PaynymClaims.mutex.withLock {
            for (code in known) {
                val frontier = PaymentCode.receivedCount(ctx, code)
                for (cand in windows[code].orEmpty()) {
                    val nearFrontier = cand.scheme == PaymentCode.Scheme.BIP47 &&
                        cand.index >= frontier && cand.index < frontier + MEMPOOL_LOOKAHEAD
                    val funded = utxos[cand.address].orEmpty().isNotEmpty() ||
                        (nearFrontier && mempoolPays(cand.address))
                    if (!funded) continue
                    val k = PaymentCode.receiveKeyAt(ctx, code, cand.index, cand.scheme) ?: continue
                    // The receive counter tracks the CURRENT scheme only. A legacy hit at a high
                    // index must not push the frontier past unclaimed spec-scheme indices below it:
                    // that would hide real coins from every later narrow sweep.
                    val advance = cand.scheme == PaymentCode.Scheme.BIP47
                    if (WalletStore.wallets.value.any { it.address == k.address }) {
                        if (advance) PaymentCode.didReceive(ctx, code, cand.index)
                    } else {
                        val label = if (cand.scheme == PaymentCode.Scheme.LEGACY) "PayNym ← external (legacy)" else "PayNym ← external"
                        val w = VanityWallet(UUID.randomUUID().toString(), label, k.address, true, PaymentCode.RECEIVE_BIRTHDAY)
                        if (WalletStore.add(ctx, w, k.wif) && advance) PaymentCode.didReceive(ctx, code, cand.index)
                    }
                }
            }
        }
        // A pass that got all the way here has covered both schemes from index 0 for every known
        // sender, so later passes can go back to the narrow window.
        if (wide) markFullSweepDone(ctx)
    }

    /** Does an unconfirmed tx actually PAY this address? `wallet_mempool` answers for every tx that
     *  touches the address, spends included, so a non-empty list is not by itself a payment. */
    private suspend fun mempoolPays(address: String): Boolean =
        try {
            ApiClient.api.walletMempool(address, CHAIN).txs.any {
                com.astrolexis.pyblock.data.wallet.MempoolParse.incomingSats(address, it.hex) > 0L
            }
        } catch (e: Exception) { false }
}
