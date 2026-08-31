package com.astrolexis.pyblock.data.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.astrolexis.pyblock.data.model.Entitlements
import com.astrolexis.pyblock.data.net.ProRepo
// LivesStore is in the same package (data.store)

/** Global, observable entitlement state — the source of truth for premium
 *  gating (themes, academy) across the app. */
object EntitlementsStore {
    var ent by mutableStateOf<Entitlements?>(null)
        private set

    val tier: String get() = ent?.tier ?: "free"
    val isWhale: Boolean get() = tier == "whale"
    val isPro: Boolean get() = tier == "pro" || tier == "whale"
    /** Short flair tag broadcast to the community ("whale"/"pro"), or null for free. */
    val tierTag: String? get() = tier.takeIf { it != "free" }

    fun set(e: Entitlements?) { ent = e }
    fun clear() { ent = null }

    /** Loads entitlements for the current account — the linked wallet if any,
     *  else the anonymous device account. Always safe to call. */
    suspend fun refresh() {
        ProRepo.entitlements()?.let {
            ent = it
            LivesStore.reconcile(it.livesBalance, it.serverTime)
        }
    }
}
