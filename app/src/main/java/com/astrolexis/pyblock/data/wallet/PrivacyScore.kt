package com.astrolexis.pyblock.data.wallet

/**
 * A pre-send privacy heuristic ("Boltzmann-lite") for PyBLØCK's single-key P2PKH
 * wallets. It doesn't touch funds — it reads the pending send's shape and scores the
 * on-chain footprint it will leave, so the user can improve it before broadcasting.
 * Pure + testable (no BDK, no UI). Android mirror of iOS PrivacyScore.swift.
 *
 * The weak point of a single-key wallet is CHANGE: change returns to the same (reused)
 * address, so a change output links this spend to your identity. The other big leak is
 * MERGING addresses in one tx (common-input-ownership). The app's privacy tools — MAX
 * (no change), single-address spends, PayNym payments — are exactly what raise the score.
 */
data class PrivacyScore(val score: Int, val factors: List<Factor>) {

    data class Factor(val good: Boolean, val text: String)

    enum class Band { GOOD, FAIR, POOR }

    val band: Band get() = if (score >= 80) Band.GOOD else if (score >= 50) Band.FAIR else Band.POOR
    val label: String get() = when (band) { Band.GOOD -> "GOOD"; Band.FAIR -> "FAIR"; Band.POOR -> "WEAK" }

    companion object {
        /**
         * Evaluate the pending send. `addressCount` is how many distinct owned addresses
         * the inputs come from (1 = no identity merge); `inputCount` is only meaningful for
         * an explicit coin selection.
         */
        fun evaluate(
            amountSats: Long,
            sendMax: Boolean,
            spendable: Long,
            recipientIsPaynym: Boolean,
            usingSelection: Boolean,
            addressCount: Int,
            inputCount: Int,
        ): PrivacyScore {
            var score = 100
            val factors = ArrayList<Factor>()

            // 1) Change output — the single-key wallet's main leak (reused-address change).
            val createsChange = !sendMax && amountSats > 0 && amountSats < spendable
            if (sendMax) {
                factors.add(Factor(true, "No change output (MAX) — nothing routes back to a reused address."))
            } else if (createsChange) {
                score -= 25
                factors.add(Factor(false, "Creates change to your reused address — links this spend to you. MAX avoids it."))
                if (isRound(amountSats)) {
                    score -= 10
                    factors.add(Factor(false, "Round amount makes the change output easy to pick out."))
                }
            }

            // 2) Merging addresses (common-input-ownership) — the other big leak.
            val addrs = if (usingSelection) maxOf(1, addressCount) else 1
            if (addrs > 1) {
                score -= if (addrs >= 3) 40 else 25
                factors.add(Factor(false, "Merges $addrs of your addresses in one tx — ties them to one owner."))
            } else {
                factors.add(Factor(true, "Spends from a single address — no identities merged."))
            }

            // 3) Recipient hygiene — a PayNym is a fresh, unlinkable address.
            if (recipientIsPaynym) {
                factors.add(Factor(true, "Paying a PayNym — a fresh address, unlinkable to past payments."))
            } else {
                score -= 6
                factors.add(Factor(false, "Paying a static address — reuse by the payee can link it."))
            }

            // 4) Input fingerprint (only when the selection is explicit + known).
            if (usingSelection && inputCount > 3) {
                score -= 5
                factors.add(Factor(false, "$inputCount inputs — a larger, more identifiable fingerprint."))
            }

            return PrivacyScore(score.coerceIn(0, 100), factors)
        }

        /** A "round" amount (≥1k sats and a clean multiple of 10k) — the tell that the
         *  OTHER output is change. */
        private fun isRound(sats: Long): Boolean = sats >= 1000 && sats % 10_000L == 0L
    }
}
