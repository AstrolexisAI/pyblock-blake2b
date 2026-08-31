package com.astrolexis.pyblock.data.blake

/**
 * Compile-time feature gates for money-moving paths on the BLAKE2b fork.
 *
 * The fork has NO replay protection, so sending is only safe for mature post-fork
 * coinbase (non-replayable) and must go through the server (Node B). Flip to true only
 * on greenlight. Mirrors iOS `BlakeChains`.
 */
object BlakeChains {
    const val SEND_ENABLED = true       // ENABLED 2026-08-30 (Bruno greenlight) — single spend of mature mined coinbase
    const val RICOCHET_ENABLED = true   // ENABLED 2026-08-30 (Bruno greenlight) — multi-hop sweep of mature mined coinbase
}
