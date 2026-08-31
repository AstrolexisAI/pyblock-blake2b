package com.astrolexis.pyblock.data.net

import com.astrolexis.pyblock.data.store.AuthStore
import com.astrolexis.pyblock.data.store.DeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One armed worker-alert target. Server is the tier authority (degrades premium
 *  fields for non-Whale rather than rejecting). */
@Serializable
data class RwTarget(
    val id: Int,
    @SerialName("btc_address") val btcAddress: String,
    @SerialName("worker_name") val workerName: String? = null,
    val chain: String = "legacy",
    val kind: String = "offline",
    @SerialName("grace_s") val graceS: Int = 600,
    @SerialName("drop_pct") val dropPct: Int = 0,
    val channels: Int = 1,
    val armed: Boolean = true,
)

@Serializable
data class RwFlag(
    val ok: Boolean = false,
    val enabled: Boolean = false,
    val config: RwConfig? = null,
) {
    /** Server default grace (s) — prefer whale's, fall back to free's, then 1800. */
    val graceDefault: Int get() = config?.whale?.graceDefault ?: config?.free?.graceDefault ?: 1800
}

@Serializable data class RwConfig(val free: RwTierCfg? = null, val whale: RwTierCfg? = null)
@Serializable data class RwTierCfg(
    @SerialName("grace_default") val graceDefault: Int? = null,
    @SerialName("grace_presets") val gracePresets: List<Int>? = null,
)

@Serializable
data class RwState(
    val ok: Boolean = false,
    val enabled: Boolean = false,
    @SerialName("is_whale") val isWhale: Boolean = false,
    val targets: List<RwTarget> = emptyList(),
)

@Serializable
data class RwArmResp(
    val ok: Boolean = false,
    val target: RwTarget? = null,
    val degraded: List<String>? = null,
    val warning: String? = null,
    val errors: List<String>? = null,
)

/** Client for /api/app/rigwatch_*.php — worker-down alerting. HMAC-authed
 *  per-device, mirroring [ProRepo]. Basic offline alerts are free; premium is
 *  gated to Whale server-side. */
object RigWatchRepo {
    private const val BASE = "https://pyblock.xyz:8443"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val mediaType = "application/json".toMediaType()
    private val EMPTY = ByteArray(0)

    /** Public kill-switch — no auth. enabled=false → hide the whole surface. */
    suspend fun flag(): RwFlag? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/api/app/rigwatch_flag.php").get().build()
        val (_, txt) = exec(req)
        txt?.let { runCatching { json.decodeFromString<RwFlag>(it) }.getOrNull() }
    }

    suspend fun state(): RwState? = withContext(Dispatchers.IO) {
        val txt = authedCall("GET", "/api/app/rigwatch_state.php", null) ?: return@withContext null
        runCatching { json.decodeFromString<RwState>(txt) }.getOrNull()
    }

    suspend fun arm(
        btcAddress: String, workerName: String?, chain: String,
        kind: String = "offline", graceS: Int = 600, dropPct: Int = 0, channels: Int = 1,
    ): RwArmResp? = withContext(Dispatchers.IO) {
        val obj = buildJsonObject {
            put("btc_address", btcAddress); put("chain", chain); put("kind", kind)
            put("grace_s", graceS); put("drop_pct", dropPct); put("channels", channels)
            if (workerName != null) put("worker_name", workerName)
        }
        val txt = authedCall("POST", "/api/app/rigwatch_target.php", obj.toString().toByteArray())
            ?: return@withContext null
        runCatching { json.decodeFromString<RwArmResp>(txt) }.getOrNull()
    }

    suspend fun disarm(id: Int) = withContext(Dispatchers.IO) {
        authedCall("DELETE", "/api/app/rigwatch_target.php?id=$id", null)
        Unit
    }

    // ---- HMAC plumbing (mirrors ProRepo) ----

    private suspend fun authedCall(method: String, path: String, body: ByteArray?): String? {
        AuthStore.bearer()?.let { bearer ->
            val req = newReq(method, path, body).addHeader("Authorization", bearer).build()
            val (code, txt) = exec(req)
            if (code != 401 && code != 403) return txt
        }
        var creds = DeviceStore.credentials() ?: return null
        var (code, txt) = execHmac(method, path, body, creds)
        if (code == 401 || code == 403) {
            creds = DeviceStore.credentials(forceNew = true) ?: return null
            execHmac(method, path, body, creds).let { code = it.first; txt = it.second }
        }
        return txt
    }

    private fun execHmac(method: String, path: String, body: ByteArray?, creds: Pair<Int, String>): Pair<Int, String?> {
        // Sign the path WITHOUT the query (parse_url PATH) — the shared HMAC convention.
        val s = HmacSigner.sign(method, path.substringBefore("?"), body ?: EMPTY, creds.second)
        val req = newReq(method, path, body)
            .addHeader("X-PyBLOCK-Device-Id", creds.first.toString())
            .addHeader("X-PyBLOCK-Timestamp", s.ts)
            .addHeader("X-PyBLOCK-Nonce", s.nonce)
            .addHeader("X-PyBLOCK-Signature", s.sig)
            .build()
        return exec(req)
    }

    private fun exec(req: Request): Pair<Int, String?> =
        try {
            client.newCall(req).execute().use { r -> r.code to r.body?.string() }
        } catch (e: java.io.IOException) {
            -1 to null
        }

    private fun newReq(method: String, path: String, body: ByteArray?): Request.Builder {
        val b = Request.Builder().url(BASE + path).addHeader("Content-Type", "application/json")
        return when (method) {
            "POST" -> b.post((body ?: EMPTY).toRequestBody(mediaType))
            "DELETE" -> b.delete((body ?: EMPTY).toRequestBody(mediaType))
            else -> b.get()
        }
    }
}
