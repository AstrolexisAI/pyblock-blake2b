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
    const val RICOCHET_ENABLED = false  // DISABLED 2026-09-08 (auditoría C2): una falla de broadcast a mitad de cadena destruye las hop keys → pérdida total. Reactivar recién con las claves persistidas ANTES del primer broadcast.
}
