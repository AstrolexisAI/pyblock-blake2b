package com.astrolexis.pyblock.data.net

import android.util.Base64
import com.astrolexis.pyblock.data.store.DeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Community-chat image uploads. HMAC device auth (no wallet link required),
 *  mirroring iOS `PyBLOCKAPI.uploadChatImage` and the [ProRepo] plumbing.
 *  Contract: POST base64 JPEG → `{ok, url}` hosted media URL. */
object ChatMediaRepo {
    private const val BASE = "https://pyblock.xyz:8443"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json".toMediaType()
    private val EMPTY = ByteArray(0)

    @Serializable private data class UploadResp(val ok: Boolean = false, val url: String = "")

    /** Upload an already-compressed JPEG; returns the hosted URL or null on any
     *  failure (offline, unauth, malformed response). One re-register on 401/403. */
    suspend fun uploadImage(jpeg: ByteArray): String? = withContext(Dispatchers.IO) {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val body = buildJsonObject { put("data", b64); put("mime", "image/jpeg") }.toString().toByteArray()
        val txt = authedCall("POST", "/api/app/chat/upload.php", body) ?: return@withContext null
        val r = runCatching { json.decodeFromString<UploadResp>(txt) }.getOrNull()
        if (r?.ok == true && r.url.startsWith("http")) r.url else null
    }

    /** Device-HMAC call with one re-register on 401/403. Mirrors [ProRepo.authedCall]. */
    private suspend fun authedCall(method: String, path: String, body: ByteArray?): String? {
        var creds = DeviceStore.credentials() ?: return null
        var (code, txt) = execHmac(method, path, body, creds)
        if (code == 401 || code == 403) {
            creds = DeviceStore.credentials(forceNew = true) ?: return null
            execHmac(method, path, body, creds).let { code = it.first; txt = it.second }
        }
        return txt
    }

    private fun execHmac(method: String, path: String, body: ByteArray?, creds: Pair<Int, String>): Pair<Int, String?> {
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
        return if (method == "POST") b.post((body ?: EMPTY).toRequestBody(mediaType)) else b.get()
    }
}
