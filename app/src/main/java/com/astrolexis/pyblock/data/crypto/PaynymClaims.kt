package com.astrolexis.pyblock.data.crypto

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes every PayNym claim (receivedCount read → derive key → WalletStore.add
 * → didReceive) across ALL triggers: the Nostr WebSocket thread (kind-0 / paid
 * receipts), the 60 s background sweep, DM-open scans and the RECEIVE sheet. The
 * claim sequence is check-then-act over shared state — two concurrent claimers
 * would either import the same address twice (double-counted balance) or lose a
 * just-added wallet whose receivedCount already advanced (funds turn invisible).
 * NOT reentrant: never call withLock inside a section that already holds it.
 */
object PaynymClaims {
    val mutex = Mutex()
}
