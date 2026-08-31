package com.astrolexis.pyblock.data.model

import java.util.Locale

/** Single source of truth for PyBLØCK stratum URLs + fees. Mirrors the
 *  iOS Stratum enum and pyblock.xyz docs. */
object Stratum {
    /** SV2 Noise authority pubkey embedded in the SV2/CHIRP URLs. */
    const val SV2_AUTHORITY_PUBKEY = "9anZZb1uaJDqubvJhekPiNRHA2tuShcNaugDmFxtnTq54sDvTf5"

    private fun host() = "pool.pyblock.xyz"

    /** Host+port URL for an arbitrary port (per-supplier CAROUSEL ports). */
    fun hostPortURL(port: Int): String = "stratum+tcp://${host()}:$port"

    enum class Pool(
        val displayName: String,
        val subtitle: String,
        val port: Int,
        /** Pool fee in basis points. DATUM is 0 (OCEAN/TIDES relay). */
        val feeBps: Int,
    ) {
        LOTTO("LOTTO", "Stratum V1 · Solo lottery", 4444, 40),
        DATUM("DATUM", "Stratum V1 · OCEAN relay", 23334, 0),
        SV2("SV2", "Stratum V2 · Solo", 5555, 90),
        CHIRP("CHIRP", "Stratum V2 · Shared coinbase lottery", 5554, 90),
        CAROUSEL("CAROUSEL", "Stratum V1 · Random supplier template each job", 30000, 200);

        val feeDisplay: String
            get() = if (feeBps == 0) "0%" else String.format(Locale.US, "%.1f%%", feeBps / 100.0)

        /** Stratum port. */
        val activePort: Int get() = port

        val hostPortURL: String get() = Stratum.hostPortURL(activePort)

        /** SV2 Noise pubkey for the firmware's separate pubkey field. */
        val authorityPubkey: String?
            get() = if (this == SV2 || this == CHIRP) SV2_AUTHORITY_PUBKEY else null

        /** Combined single-string URL (SV2 pools embed the pubkey). */
        val url: String
            get() = if (this == SV2 || this == CHIRP) {
                "stratum2+tcp://${Stratum.host()}:$activePort/$SV2_AUTHORITY_PUBKEY"
            } else {
                hostPortURL
            }

        val needsSV2: Boolean get() = this == SV2 || this == CHIRP
    }
}
