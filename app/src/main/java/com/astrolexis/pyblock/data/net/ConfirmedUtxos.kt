package com.astrolexis.pyblock.data.net

import com.astrolexis.pyblock.data.model.Utxo

/**
 * Batched lookup of CONFIRMED unspent outputs via `wallet_utxos.php` — served from
 * PyBLØCK's own node (sovereign, no third-party explorer). Complements
 * `wallet_mempool.php`, which is 0-conf only: once a payment confirms and leaves
 * the mempool, this is the app's only way to see it without a full CBF scan.
 *
 * The server scans the UTXO set per call (minutes, uncached), so callers MUST
 * batch every candidate address into ONE call per sweep. Requests are chunked to
 * the server's ≤50-address cap and use the long-timeout client. On the first
 * failed chunk the remaining chunks are ABANDONED — a saturated server must not
 * receive follow-up scans — and the failure is reported so the sweep can back
 * off. A missing address means "unknown", never "unfunded": callers may only ACT
 * on positive hits (claim/credit), never on absence.
 */
object ConfirmedUtxos {
    private const val CHUNK = 50

    /** One batched lookup. [failed] = true if any chunk errored (partial or no data). */
    data class Batch(val byAddress: Map<String, List<Utxo>>, val failed: Boolean)

    /** Confirmed UTXO sweep for the spendable (Bitcoin) wallet. */
    suspend fun fetch(addresses: Collection<String>, chain: String? = null): Batch {
        val unique = addresses.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return Batch(emptyMap(), failed = false)
        val found = ArrayList<Utxo>()
        var failed = false
        for (chunk in unique.chunked(CHUNK)) {
            val resp = try { ApiClient.apiSlow.walletUtxos(chunk.joinToString(","), chain) } catch (e: Exception) {
                android.util.Log.w("PyBLOCKutxo", "wallet_utxos failed for ${chunk.size} addr(s): ${e.message?.take(80)}")
                failed = true
                break        // don't hammer a server that just timed out with the remaining chunks
            }
            if (resp.warming) { failed = true; break }   // scan busy → retry, never a false 0
            found.addAll(resp.utxos)
        }
        return Batch(found.groupBy { it.address }, failed)
    }

    /** Convenience for callers that only need the map (absence == unknown). */
    suspend fun forAddresses(addresses: Collection<String>): Map<String, List<Utxo>> =
        fetch(addresses).byAddress
}
