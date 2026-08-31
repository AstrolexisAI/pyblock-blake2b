package com.astrolexis.pyblock.data.net

import com.astrolexis.pyblock.data.model.Entitlements
import com.astrolexis.pyblock.data.model.LessonResp
import com.astrolexis.pyblock.data.model.AccountStatus
import com.astrolexis.pyblock.data.model.LinkClaimReq
import com.astrolexis.pyblock.data.model.UnlinkReq
import com.astrolexis.pyblock.data.model.UnlinkResp
import com.astrolexis.pyblock.data.model.LinkClaimResp
import com.astrolexis.pyblock.data.model.LinkStartResp
import com.astrolexis.pyblock.data.model.LivesConsumeReq
import com.astrolexis.pyblock.data.model.LivesConsumeResult
import com.astrolexis.pyblock.data.model.PurchaseReq
import com.astrolexis.pyblock.data.model.PurchaseResponse
import com.astrolexis.pyblock.data.store.AuthStore
import com.astrolexis.pyblock.data.store.DeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Entitlements + digital-product purchase (Pro/Whale/lives/skins).
 *
 *  Auth model: the account is the **anonymous device** (HMAC) by default, so
 *  ANY Lightning wallet can pay a BOLT11 — LNURL-auth is NOT required to buy.
 *  If the user linked a wallet (LNURL-auth), the Bearer session is preferred
 *  and the server merges device ↔ pubkey server-side. Mirrors [BuyRepo]. */
object ProRepo {
    private const val BASE = "https://pyblock.xyz:8443"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val mediaType = "application/json".toMediaType()
    private val EMPTY = ByteArray(0)

    suspend fun entitlements(): Entitlements? = withContext(Dispatchers.IO) {
        val txt = authedCall("GET", "/api/app/entitlements.php", null) ?: return@withContext null
        runCatching { json.decodeFromString<Entitlements>(txt) }.getOrNull()
    }

    /** Cross-device link: ask for a short pairing code (this device shares its
     *  account/entitlements with whoever claims the code). */
    suspend fun linkStart(): LinkStartResp? = withContext(Dispatchers.IO) {
        val txt = authedCall("POST", "/api/app/account/link_start.php", "{}".toByteArray()) ?: return@withContext null
        runCatching { json.decodeFromString<LinkStartResp>(txt) }.getOrNull()
    }

    /** Cross-device link: claim a code shown on another device. */
    suspend fun linkClaim(code: String): LinkClaimResp? = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(LinkClaimReq(code)).toByteArray()
        val txt = authedCall("POST", "/api/app/account/link_claim.php", bytes) ?: return@withContext null
        runCatching { json.decodeFromString<LinkClaimResp>(txt) }.getOrNull()
    }

    /** Devices sharing this account (for the LINKED DEVICES list). Null if the
     *  endpoint isn't deployed yet — the UI falls back to self-unlink. */
    suspend fun accountStatus(): AccountStatus? = withContext(Dispatchers.IO) {
        val txt = authedCall("GET", "/api/app/account/status.php", null) ?: return@withContext null
        runCatching { json.decodeFromString<AccountStatus>(txt) }.getOrNull()
    }

    /** Remove a device from the group — self (null) or another group member. */
    suspend fun unlinkDevice(deviceId: Int?): UnlinkResp? = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(UnlinkReq(deviceId)).toByteArray()
        val txt = authedCall("POST", "/api/app/account/unlink.php", bytes) ?: return@withContext null
        runCatching { json.decodeFromString<UnlinkResp>(txt) }.getOrNull()
    }

    /** Notify the developer that a user blocked a peer or reported a message
     *  (store-policy UGC moderation). Fire-and-forget. */
    suspend fun moderationReport(kind: String, targetPubkey: String, messageId: String?) = withContext(Dispatchers.IO) {
        val obj = buildJsonObject {
            put("kind", kind); put("target_pubkey", targetPubkey)
            if (messageId != null) put("message_id", messageId)
        }
        val bytes = obj.toString().toByteArray()
        authedCall("POST", "/api/app/moderation_report.php", bytes)
    }

    suspend fun purchase(productId: String): PurchaseResponse? = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(PurchaseReq(productId)).toByteArray()
        val txt = authedCall("POST", "/api/app/purchase.php", bytes) ?: return@withContext null
        runCatching { json.decodeFromString<PurchaseResponse>(txt) }.getOrNull()
    }

    /** Consume paid lives server-side (authoritative). Returns the new
     *  balance, or null on network failure / insufficient. */
    suspend fun consumeLives(n: Int): LivesConsumeResult? = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(LivesConsumeReq(n)).toByteArray()
        val txt = authedCall("POST", "/api/app/lives_consume.php", bytes) ?: return@withContext null
        runCatching { json.decodeFromString<LivesConsumeResult>(txt) }.getOrNull()
    }

    /** Premium Academy lesson body — server returns it only for Whale accounts.
     *  Returns the body text, or null if locked / offline / not found. */
    suspend fun lesson(id: Int): String? = withContext(Dispatchers.IO) {
        val txt = authedCall("GET", "/api/app/lesson.php?id=$id", null) ?: return@withContext null
        val r = runCatching { json.decodeFromString<LessonResp>(txt) }.getOrNull()
        if (r?.ok == true) r.body.takeIf { it.isNotBlank() } else null
    }

    /** Raw response body, or null. Bearer if a wallet is linked (falls back to
     *  device on 401); else device HMAC with one re-register on 401/403. */
    private suspend fun authedCall(method: String, path: String, body: ByteArray?): String? {
        AuthStore.bearer()?.let { bearer ->
            val req = newReq(method, path, body).addHeader("Authorization", bearer).build()
            val (code, txt) = exec(req)
            if (code != 401 && code != 403) return txt
            // Bearer stale — fall through to the device account.
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
        // Server signs the path WITHOUT the query (parse_url PATH) — the shared
        // convention across all HMAC endpoints. Strip ?query before signing.
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
            // DNS failure, timeout, no route, captive portal — treat as offline so
            // callers return null gracefully instead of an uncaught crash.
            -1 to null
        }

    private fun newReq(method: String, path: String, body: ByteArray?): Request.Builder {
        val b = Request.Builder().url(BASE + path).addHeader("Content-Type", "application/json")
        return if (method == "POST") b.post((body ?: EMPTY).toRequestBody(mediaType)) else b.get()
    }
}
