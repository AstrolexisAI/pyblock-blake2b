package com.astrolexis.pyblock.data.net

import com.astrolexis.pyblock.data.model.AddressWorkers
import com.astrolexis.pyblock.data.model.AnonRegReq
import com.astrolexis.pyblock.data.model.BlockOddsResponse
import com.astrolexis.pyblock.data.model.AuthStatus
import com.astrolexis.pyblock.data.model.DeviceRegistration
import com.astrolexis.pyblock.data.model.Entitlements
import com.astrolexis.pyblock.data.model.LnurlAuth
import com.astrolexis.pyblock.data.model.NHQuoteResponse
import com.astrolexis.pyblock.data.model.OrderQuoteResponse
import com.astrolexis.pyblock.data.model.OrderStatusResponse
import com.astrolexis.pyblock.data.model.ProductsResponse
import com.astrolexis.pyblock.data.model.PurchaseReq
import com.astrolexis.pyblock.data.model.PurchaseResponse
import com.astrolexis.pyblock.data.model.PurchaseStatusResp
import com.astrolexis.pyblock.data.model.BlocksResponse
import com.astrolexis.pyblock.data.model.CarouselRing
import com.astrolexis.pyblock.data.model.CarouselStats
import com.astrolexis.pyblock.data.model.ChirpMiner
import com.astrolexis.pyblock.data.model.SuppliersRaw
import com.astrolexis.pyblock.data.model.CBBlocksResp
import com.astrolexis.pyblock.data.model.CBDetail
import com.astrolexis.pyblock.data.model.CBStats
import com.astrolexis.pyblock.data.model.ChirpStats
import com.astrolexis.pyblock.data.model.DatumStats
import com.astrolexis.pyblock.data.model.HashratePoint
import com.astrolexis.pyblock.data.model.LottoStats
import com.astrolexis.pyblock.data.model.NodeMapData
import com.astrolexis.pyblock.data.model.OceanData
import com.astrolexis.pyblock.data.model.OrdersListResponse
import com.astrolexis.pyblock.data.model.PoolHistoryRow
import com.astrolexis.pyblock.data.model.SV2Stats
import com.astrolexis.pyblock.data.model.TemplateAggregates
import com.astrolexis.pyblock.data.model.TemplateTx
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/** PyBLØCK public JSON API (Part A of the server contract). */
interface PyblockApi {
    // ---- Pool stats ----
    @GET("api.php")
    suspend fun lottoStats(@Query("mode") mode: String = "pool"): LottoStats

    @GET("api.php")
    suspend fun datumStats(@Query("mode") mode: String = "datum"): DatumStats

    @GET("sv2_api.php")
    suspend fun sv2Stats(@Query("mode") mode: String = "pool"): SV2Stats

    @GET("sv2_api.php")
    suspend fun sv2Workers(@Query("mode") mode: String = "workers"): com.astrolexis.pyblock.data.model.SV2WorkersResponse

    @GET("chirp_api.php")
    suspend fun chirpStats(@Query("mode") mode: String = "pool"): ChirpStats

    @GET("api.php")
    suspend fun carouselStats(@Query("mode") mode: String = "tmpl"): CarouselStats

    // ---- Pool detail (CHIRP leaderboard, CAROUSEL ring + suppliers) ----
    @GET("chirp_api.php")
    suspend fun chirpMiners(@Query("mode") mode: String = "miners"): List<ChirpMiner>

    @GET("templates.php")
    suspend fun carouselRing(@Query("carrousel") carrousel: Int = 1): CarouselRing

    @GET("templates.php")
    suspend fun carouselSuppliers(@Query("data") data: Int = 1): SuppliersRaw

    // ---- Odds + blocks ----
    @GET("api.php")
    suspend fun blockOdds(@Query("mode") mode: String = "block_odds"): BlockOddsResponse

    @GET("api.php")
    suspend fun recentBlocks(@Query("mode") mode: String = "blocks"): BlocksResponse

    // ---- Hashrate history ----
    @GET("api.php")
    suspend fun lottoHistory(
        @Query("range") range: String,
        @Query("mode") mode: String = "history",
    ): List<HashratePoint>

    @GET("api.php")
    suspend fun datumHistory(
        @Query("range") range: String,
        @Query("mode") mode: String = "datum_history",
    ): List<HashratePoint>

    @GET("api.php")
    suspend fun carouselHistory(
        @Query("range") range: String,
        @Query("mode") mode: String = "tmpl_history",
    ): List<HashratePoint>

    @GET("sv2_api.php")
    suspend fun sv2History(
        @Query("hours") hours: Int,
        @Query("mode") mode: String = "history",
    ): List<PoolHistoryRow>

    @GET("chirp_api.php")
    suspend fun chirpHistory(
        @Query("hours") hours: Int,
        @Query("mode") mode: String = "history",
    ): List<PoolHistoryRow>

    // ---- Per-address (My Address) ----
    @GET("api.php")
    suspend fun allWorkers(@Query("mode") mode: String = "workers"): Map<String, AddressWorkers>

    @GET("api/ocean_search_full.php")
    suspend fun ocean(@Query("address") address: String): OceanData

    @GET("api/app/orders_by_address.php")
    suspend fun ordersByAddress(@Query("address") address: String): OrdersListResponse

    // 0-conf: unconfirmed txs paying a wallet address, from PyBLØCK's own node.
    @GET("api/wallet_mempool.php")
    suspend fun walletMempool(@Query("address") address: String, @Query("chain") chain: String? = null): com.astrolexis.pyblock.data.model.WalletMempoolResp

    // CONFIRMED unspent outputs for one or more addresses (comma-separated), from
    // PyBLØCK's own node — lets the app see confirmed payments (incl. PayNym stealth
    // addresses) without waiting for the on-device CBF filter scan.
    @GET("api/wallet_utxos.php")
    suspend fun walletUtxos(@Query("addresses") addresses: String, @Query("chain") chain: String? = null): com.astrolexis.pyblock.data.model.WalletUtxosResp

    @GET("api/miner_history.php")
    suspend fun minerHistory(
        @Query("address") address: String,
        @Query("range") range: String = "1d",
    ): List<HashratePoint>

    // ---- Node map ----
    @GET("nodemap.php")
    suspend fun nodeMap(@Query("data") data: Int = 1): NodeMapData

    // ---- Clean Blocks ----
    @GET("cleanblocks.php")
    suspend fun cbRecent(@Query("data") data: Int = 1, @Query("token") token: String = ""): CBBlocksResp

    @GET("cleanblocks.php")
    suspend fun cbOlder(
        @Query("older") older: Int,
        @Query("count") count: Int = 15,
        @Query("token") token: String = "",
    ): CBBlocksResp

    @GET("cleanblocks.php")
    suspend fun cbPending(@Query("pending") pending: Int = 1): CBDetail

    @GET("cleanblocks.php")
    suspend fun cbDetail(@Query("detail") detail: Int): CBDetail

    /** Block detail located by TXID. `hl` highlights the tx within the block. */
    @GET("cleanblocks.php")
    suspend fun cbDetailByTxid(
        @Query("detail") txid: String,
        @Query("hl") hl: String,
    ): CBDetail

    @GET("cleanblocks.php")
    suspend fun cbStats(@Query("stats") stats: Int = 1, @Query("token") token: String = ""): CBStats

    // ---- Buy (hashrate orders) ----
    @POST("api/devices_anonymous.php")
    suspend fun registerAnonymous(@Body body: AnonRegReq): DeviceRegistration

    @GET("api/app/quote.php")
    suspend fun quote(
        @Query("port") port: Int,
        @Query("amount_sats") amountSats: Int,
        @Query("hashrate_phs") hashratePhs: Double,
    ): OrderQuoteResponse

    @GET("api/app/hashrate_quote.php")
    suspend fun packageQuote(
        @Query("hashrate_phs") hashratePhs: Double,
        @Query("hours") hours: Double,
    ): NHQuoteResponse

    @GET("api/app/status.php")
    suspend fun orderStatus(@Query("id") id: String): OrderStatusResponse

    // ---- Phase 2: Lightning monetization ----
    @GET("api/app/auth/lnurl.php")
    suspend fun authLnurl(): LnurlAuth

    @GET("api/app/auth/status.php")
    suspend fun authStatus(@Query("k1") k1: String): AuthStatus

    @GET("api/app/entitlements.php")
    suspend fun entitlements(@Header("Authorization") bearer: String): Entitlements

    @GET("api/app/products.php")
    suspend fun products(): ProductsResponse

    @POST("api/app/purchase.php")
    suspend fun purchase(@Header("Authorization") bearer: String, @Body body: PurchaseReq): PurchaseResponse

    @GET("api/app/purchase_status.php")
    suspend fun purchaseStatus(@Query("id") id: String): PurchaseStatusResp

    // ---- Sideload update check ----
    // MUST pass app=blake2b: this endpoint defaults to the SHA-256 app's manifest, so without
    // the param a BLAKE2b build is offered the wrong APK (pyblock-*.apk) as an "update".
    @GET("api/app/android_version.php")
    suspend fun androidVersion(@Query("app") app: String = "blake2b"): com.astrolexis.pyblock.data.model.AndroidVersion

    // ---- Node Defender leaderboard ----
    @GET("api/app/defender_scores.php")
    suspend fun defenderBoard(@Query("diff") diff: String? = null): com.astrolexis.pyblock.data.model.DefenderBoardResult

    @POST("api/app/defender_scores.php")
    suspend fun defenderSubmit(@Body body: com.astrolexis.pyblock.data.model.DefenderSubmitReq): com.astrolexis.pyblock.data.model.DefenderSubmitResult

    // ---- Block template ----
    @GET("template_data.php")
    suspend fun templateData(): TemplateAggregates

    @GET("template_txs.php")
    suspend fun templateTxs(): List<TemplateTx>
}
