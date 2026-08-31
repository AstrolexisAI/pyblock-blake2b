package com.astrolexis.pyblock.data.store

import com.astrolexis.pyblock.data.net.RigWatchRepo
import com.astrolexis.pyblock.data.net.RwState
import com.astrolexis.pyblock.data.net.RwTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-side state for Rig Watch (worker-down alerts). Holds the public kill-switch
 *  flag (drives whether the whole surface is shown) + the per-device armed targets.
 *  Basic offline alerts are free; premium is gated to Whale server-side. */
object RigWatchStore {
    private val _state = MutableStateFlow(RwState())
    val state: StateFlow<RwState> = _state.asStateFlow()
    private val _graceDefault = MutableStateFlow(1800)   // server-tunable (LOTTO gaps → 30 min)
    val graceDefault: StateFlow<Int> = _graceDefault.asStateFlow()
    @Volatile private var flagLoaded = false

    /** Load the public flag once (cheap, no auth). */
    suspend fun loadFlag() {
        RigWatchRepo.flag()?.let { f ->
            _state.value = _state.value.copy(enabled = f.enabled)
            _graceDefault.value = f.graceDefault
        }
        flagLoaded = true
    }

    /** Pull armed targets + tier. No-op while disabled (dry-run). */
    suspend fun refresh() {
        if (!flagLoaded) loadFlag()
        if (!_state.value.enabled) return
        RigWatchRepo.state()?.let { _state.value = it }
    }

    fun offlineTarget(address: String, worker: String?): RwTarget? =
        _state.value.targets.firstOrNull { it.btcAddress == address && it.workerName == worker && it.kind == "offline" }

    /** Arm an OFFLINE alert; returns the server warning (e.g. address not on LOTTO). */
    suspend fun armOffline(address: String, worker: String?, chain: String): String? {
        val res = RigWatchRepo.arm(address, worker, chain, kind = "offline", graceS = _graceDefault.value)
        refresh()
        return res?.warning
    }

    suspend fun disarm(target: RwTarget) {
        RigWatchRepo.disarm(target.id)
        refresh()
    }
}
