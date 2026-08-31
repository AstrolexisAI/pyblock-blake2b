package com.astrolexis.pyblock.ui.screens.academy

/** A Bitcoin Academy lesson. Free lessons ship their `body` in the APK;
 *  `premium` bodies are NOT bundled — they're fetched from lesson.php only for
 *  Whale accounts (server-gated), so the gate protects real content. */
data class AcademyLesson(
    val id: Int,
    val title: String,
    val summary: String,
    val premium: Boolean,
    val body: String,   // "" for premium — loaded on demand via lesson.php
)

object Academy {
    val lessons: List<AcademyLesson> = listOf(
        AcademyLesson(1, "What is Bitcoin?", "Money without a middleman", false,
            "Bitcoin is a peer-to-peer digital money. No company, bank, or government issues it or controls it. A public ledger — the timechain — records every transaction, and thousands of independent nodes around the world each keep a full copy and enforce the same rules.\n\nOnly 21 million bitcoin will ever exist. New coins enter circulation only as a reward to miners who secure the network, and that reward is cut in half roughly every four years (the \"halving\"). This fixed, transparent supply is what makes bitcoin scarce by design — nobody can print more.\n\nBecause the rules are enforced by everyone running the software, you don't have to trust any single party. You verify."),
        AcademyLesson(2, "How Mining Works", "Turning energy into security", false,
            "Mining is how new blocks of transactions get added to the timechain. Miners collect pending transactions, bundle them into a candidate block, and then race to find a number (the \"nonce\") that makes the block's hash fall below a target. There's no shortcut — it takes trillions of guesses, i.e. real computation and real energy.\n\nThe first miner to find a valid hash broadcasts the block; every node checks it instantly and, if valid, builds on top of it. The winner earns the block subsidy plus the fees of the transactions inside.\n\nThis \"proof-of-work\" is what makes rewriting history astronomically expensive — an attacker would have to out-compute the entire honest network."),
        AcademyLesson(3, "Pools vs Solo Mining", "Steady drips or rare jackpots", false,
            "A single miner's odds of finding a block alone are tiny, so most miners join a pool: they combine hashrate and share the rewards proportionally to the work each contributed. You get small, steady payouts instead of waiting years for one big hit.\n\nSolo mining is the opposite — you keep the entire block reward if you get lucky, but you might never find one. It's a lottery.\n\nPyBLØCK lets you point hashrate at different pools: LOTTO (solo-style lottery), DATUM/CHIRP (shared coinbase, non-custodial), SV2 and more. Payouts go straight to your own address — the pool never holds your coins."),
        AcademyLesson(4, "Reading Your Hashrate", "TH/s, PH/s and what they mean", true, ""),
        AcademyLesson(5, "Clean Blocks", "Mining money, not spam", true, ""),
        AcademyLesson(6, "Difficulty & the Halving", "Why the network self-adjusts", true, ""),
        AcademyLesson(7, "Non-custodial Payouts", "Not your keys, not your coins", true, ""),
        AcademyLesson(8, "Stratum V1 vs V2", "Who chooses what you mine", true, ""),
        AcademyLesson(9, "Fees, sat/vB & the Mempool", "The market for blockspace", true, ""),
        AcademyLesson(10, "Run Your Own Node", "Verify, don't trust", true, ""),
    )
}
