package com.astrolexis.pyblock.data.net

import com.astrolexis.pyblock.data.model.DifficultyAdjustment
import com.astrolexis.pyblock.data.model.MempoolPrices
import retrofit2.http.GET

/** mempool.guide (Knots-aligned) for network-level context. */
interface MempoolApi {
    @GET("api/v1/difficulty-adjustment")
    suspend fun difficulty(): DifficultyAdjustment

    @GET("api/v1/prices")
    suspend fun prices(): MempoolPrices
}
