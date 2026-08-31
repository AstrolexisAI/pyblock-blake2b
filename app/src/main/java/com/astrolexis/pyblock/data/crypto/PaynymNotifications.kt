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
    fun candidates(ctx: Context, gap: Int = 8): Map<String, List<Pair<Int, String>>> =
        knownSenders(ctx).associateWith { code -> PaymentCode.lookaheadAddresses(ctx, code, gap) }

    @Volatile private var lastScanMs = 0L

    /** One full pass: (1) discover sender codes from notification txs to our notification address,
     *  (2) import any funded derived receive addresses from every known sender. Fetches its own
     *  confirmed-UTXO batch; prefer [scanWith] when the caller already has one. TTL-guarded: the
     *  RECEIVE sheet fires this on every open, and the background sweep already covers the same
     *  window — rapid re-opens must not each trigger a server-side UTXO-set scan. */
    suspend fun scan(ctx: Context, gap: Int = 8) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScanMs < 45_000) return
        lastScanMs = now
        val notifAddr = PaymentCode.notificationAddress(ctx) ?: return
        val addrs = listOf(notifAddr) + candidates(ctx, gap).values.flatten().map { it.second }
        scanWith(ctx, ConfirmedUtxos.forAddresses(addrs), gap)
    }

    /** Same pass, reusing an already-fetched confirmed-UTXO map (must cover the
     *  notification address + every known sender's look-ahead window). Checks BOTH
     *  0-conf (wallet_mempool) and CONFIRMED (wallet_utxos) — a payment that
     *  confirmed and left the mempool is otherwise invisible to the app. */
    suspend fun scanWith(ctx: Context, confirmed: Map<String, List<Utxo>>, gap: Int = 8) {
        val notifAddr = PaymentCode.notificationAddress(ctx) ?: return
        val discovered = load(ctx)            // notification-tx-discovered senders
        val before = discovered.toSet()

        // 1) discover senders — from 0-conf notification txs AND confirmed ones. A
        //    BIP-47 notification output is never spent, so it sits in the UTXO set
        //    forever and wallet_utxos hands us the raw tx to unblind.
        val notifHexes =
            (try { ApiClient.api.walletMempool(notifAddr).txs.map { it.hex } } catch (e: Exception) { emptyList() }) +
            confirmed[notifAddr].orEmpty().map { it.hex }
        for (hex in notifHexes) {
            val ntx = PaymentCode.parseNotificationTx(hex) ?: continue
            val code = PaymentCode.unblindNotification(ctx, ntx.designatedPubkey, ntx.outpoint, ntx.payload) ?: continue
            discovered.add(code)
        }
        save(ctx, discovered)

        // Claim from EVERY known sender: notif-discovered ∪ saved contacts.
        val known = discovered + contactCodes(ctx)
        // Codes not already covered by [confirmed] (contacts + this-pass discoveries) — fetch theirs.
        val newCodes = known - before
        val utxos = if (newCodes.isEmpty()) confirmed else confirmed +
            ConfirmedUtxos.forAddresses(newCodes.flatMap { c -> PaymentCode.lookaheadAddresses(ctx, c, gap).map { it.second } })

        // 2) import funded payments from each known sender — confirmed vs the batch
        //    over the whole window; 0-conf mempool probe only at the frontier index
        //    (BIP-47 fills indices in order). Claims are check-then-act over shared
        //    state, so the whole step runs under the app-wide claim lock.
        WalletStore.ensureLoaded(ctx)
        PaynymClaims.mutex.withLock {
            for (code in known) {
                val frontier = PaymentCode.receivedCount(ctx, code)
                for ((index, address) in PaymentCode.lookaheadAddresses(ctx, code, gap)) {
                    val funded = utxos[address].orEmpty().isNotEmpty() ||
                        (index == frontier &&
                            (try { ApiClient.api.walletMempool(address).txs.isNotEmpty() } catch (e: Exception) { false }))
                    if (!funded) continue
                    val k = PaymentCode.receiveKeyAt(ctx, code, index) ?: continue
                    if (WalletStore.wallets.value.any { it.address == k.address }) {
                        PaymentCode.didReceive(ctx, code, index)
                    } else {
                        val w = VanityWallet(UUID.randomUUID().toString(), "PayNym ← external", k.address, true, PaymentCode.RECEIVE_BIRTHDAY)
                        if (WalletStore.add(ctx, w, k.wif)) PaymentCode.didReceive(ctx, code, index)
                    }
                }
            }
        }
    }
}
