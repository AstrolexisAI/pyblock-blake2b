package com.astrolexis.pyblock.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.astrolexis.pyblock.data.net.ProRepo
import com.astrolexis.pyblock.ui.screens.game.GameSkin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max

/** Node Defender lives economy.
 *
 *  Two pools: **free** (3 every 24h, local, gated on the server clock so the
 *  device clock can't farm them) + **paid** (the server's `lives_balance`,
 *  authoritative — bought lives, consumed via lives_consume.php). Total
 *  playable = free + paid. Selected skin persisted; premium skins gated on
 *  entitlements. Observable for Compose. */
object LivesStore {
    private const val PREFS = "pyblock.lives"
    private const val DAY_SEC = 24L * 60 * 60
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var free by mutableIntStateOf(3)
    private var paid by mutableIntStateOf(0)

    /** Total playable lives (free + paid). */
    val lives: Int get() = free + paid

    var selectedSkinId by mutableStateOf("orange")
        private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        free = if (prefs.contains("free")) prefs.getInt("free", 3) else 3
        selectedSkinId = prefs.getString("skin", "orange") ?: "orange"
    }

    /** Reconcile paid lives against the server's authoritative balance, and
     *  refill free lives using the *server* clock (anti clock-manipulation).
     *  Call from entitlements refresh with `lives_balance` + `server_time`. */
    fun reconcile(serverLivesBalance: Int, serverTimeSec: Long) {
        paid = max(0, serverLivesBalance)
        if (serverTimeSec > 0) {
            val next = prefs.getLong("freeNextResetSec", 0L)
            if (serverTimeSec >= next) {
                free = max(free, 3)
                prefs.edit().putLong("freeNextResetSec", serverTimeSec + DAY_SEC).putInt("free", free).apply()
            }
        }
    }

    /** Consume one life: free first (local), then paid (server-authoritative,
     *  optimistic local decrement reconciled by the server response). */
    fun consumeLife() {
        if (free > 0) {
            free -= 1; prefs.edit().putInt("free", free).apply()
        } else if (paid > 0) {
            paid -= 1   // optimistic; server response reconciles the exact balance
            scope.launch { ProRepo.consumeLives(1)?.let { if (it.ok) paid = max(0, it.livesBalance) } }
        }
    }

    val skinsUnlocked: Boolean
        get() = EntitlementsStore.isWhale || (EntitlementsStore.ent?.skins?.isNotEmpty() == true)

    val selectedSkin: GameSkin
        get() = GameSkin.byId(selectedSkinId).let { if (it.free || skinsUnlocked) it else GameSkin.all[0] }

    fun selectSkin(id: String) {
        val s = GameSkin.byId(id)
        if (!s.free && !skinsUnlocked) return
        selectedSkinId = id
        prefs.edit().putString("skin", id).apply()
    }
}
