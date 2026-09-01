package com.astrolexis.pyblock.data.blake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared BLAKE2b timechain status (chain_status.php). Drives the RC "testing on mainnet"
 * banner (auto-clears when `operational` flips true). Mirrors iOS `BlakeStatus`.
 */
object BlakeStatus {
    private val _operational = MutableStateFlow(false)
    val operational: StateFlow<Boolean> = _operational.asStateFlow()
    private val _rc = MutableStateFlow<String?>(null)
    val rc: StateFlow<String?> = _rc.asStateFlow()
    private val _blockHeight = MutableStateFlow(0)
    val blockHeight: StateFlow<Int> = _blockHeight.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun refresh() {
        val s = BlakeApi.status() ?: return
        _operational.value = s.operational ?: false
        _rc.value = s.rc
        _blockHeight.value = s.blockHeight ?: 0
        _loaded.value = true
    }
}
