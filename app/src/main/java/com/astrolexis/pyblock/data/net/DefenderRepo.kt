package com.astrolexis.pyblock.data.net

import com.astrolexis.pyblock.data.model.DefenderScore
import com.astrolexis.pyblock.data.model.DefenderSubmitReq
import com.astrolexis.pyblock.data.store.DefenderStore

/** Node Defender global leaderboard — public endpoint, no auth. */
object DefenderRepo {
    suspend fun board(diff: String?): List<DefenderScore> =
        runCatching { ApiClient.api.defenderBoard(diff).top }.getOrDefault(emptyList())

    /** Submits a score; returns the global rank, or null on failure. */
    suspend fun submit(name: String, score: Int, wave: Int, difficulty: String): Int? {
        DefenderStore.setName(name)
        val req = DefenderSubmitReq(name, DefenderStore.handle, score, wave, difficulty)
        return runCatching { ApiClient.api.defenderSubmit(req) }.getOrNull()?.rank
    }
}
