package com.astrolexis.pyblock.data.nostr

import android.content.Context
import com.astrolexis.pyblock.data.store.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted, on-device store for direct messages.
 *
 * Until now conversations lived nowhere. Every launch asked the relay to replay hundreds of DMs and
 * decrypted them all again, which is both slow and the reason the relay cannot forget anything: the
 * history on the server IS the user's history, so trimming it would take their messages away.
 * Keeping a local copy is what makes a retention window on the relay possible.
 *
 * AES-GCM under a key held in the app's encrypted preferences, which are Keystore-backed and never
 * leave the device — the same posture as the Nostr identity itself. An identity that does not
 * survive a reinstall makes old DMs meaningless anyway. Mirrors iOS `DMArchive`.
 */
object DMArchive {
    /** Per conversation. Enough to be a history, bounded enough that the file stays small. */
    const val PER_PEER_LIMIT = 1_000

    private const val FILE = "dms.bin"
    private const val KEY_NAME = "dm_archive_key_v1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun prefs(ctx: Context) =
        SecurePrefs.open(ctx, "pyblock_dm_archive_enc", "pyblock_dm_archive", resetOnCorruption = false)

    private fun key(ctx: Context): SecretKeySpec? = try {
        val p = prefs(ctx)
        val existing = p.getString(KEY_NAME, null)
        val bytes = if (existing != null) android.util.Base64.decode(existing, android.util.Base64.NO_WRAP)
        else ByteArray(32).also {
            SecureRandom().nextBytes(it)
            p.edit().putString(KEY_NAME, android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)).commit()
        }
        if (bytes.size == 32) SecretKeySpec(bytes, "AES") else null
    } catch (e: Exception) { null }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    // MARK: - Load / save

    fun load(ctx: Context): Map<String, List<DMMessage>> = try {
        val f = file(ctx)
        val k = key(ctx)
        if (!f.exists() || k == null) emptyMap() else {
            val blob = f.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_BYTES)))
            val plain = cipher.doFinal(blob.copyOfRange(IV_BYTES, blob.size))
            decode(String(plain, Charsets.UTF_8))
        }
    } catch (e: Exception) { emptyMap() }

    fun save(ctx: Context, conversations: Map<String, List<DMMessage>>) {
        try {
            val k = key(ctx) ?: return
            val json = encode(conversations).toByteArray(Charsets.UTF_8)
            val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            val sealed = iv + cipher.doFinal(json)
            // Write beside it and rename, so an interrupted write can't leave a truncated archive.
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(file(ctx))) { file(ctx).writeBytes(sealed); tmp.delete() }
        } catch (e: Exception) { /* the relay still has the recent window */ }
    }

    /** Everything is gone: the user asked, or the identity was reset. */
    fun wipe(ctx: Context) { runCatching { file(ctx).delete() } }

    // MARK: - JSON

    private fun encode(conversations: Map<String, List<DMMessage>>): String {
        val root = JSONObject()
        for ((peer, list) in conversations) {
            val arr = JSONArray()
            for (m in list.takeLast(PER_PEER_LIMIT)) {
                arr.put(JSONObject()
                    .put("id", m.id).put("peer", m.peer).put("mine", m.mine)
                    .put("text", m.text).put("ts", m.createdAt))
            }
            root.put(peer, arr)
        }
        return root.toString()
    }

    private fun decode(json: String): Map<String, List<DMMessage>> {
        val root = JSONObject(json)
        val out = HashMap<String, List<DMMessage>>()
        for (peer in root.keys()) {
            val arr = root.optJSONArray(peer) ?: continue
            val list = ArrayList<DMMessage>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(DMMessage(o.optString("id"), o.optString("peer", peer),
                    o.optBoolean("mine"), o.optString("text"), o.optLong("ts")))
            }
            out[peer] = list
        }
        return out
    }

    /**
     * Seal, write, read back, decode. Worth checking at startup: an archive that silently fails to
     * round-trip loses the user's conversations, and a file that was never written looks exactly
     * like a working one with nothing in it yet.
     */
    fun selfTest(ctx: Context): Boolean = try {
        val k = key(ctx)
        if (k == null) false else {
            val sample = mapOf("peer" to listOf(DMMessage("abc", "peer", true, "round trip / ✓", 1_700_000_000L)))
            val json = encode(sample).toByteArray(Charsets.UTF_8)
            val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val enc = Cipher.getInstance("AES/GCM/NoPadding")
            enc.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            val sealed = iv + enc.doFinal(json)
            val tmp = File(ctx.cacheDir, "dms.selftest")
            tmp.writeBytes(sealed)
            val back = tmp.readBytes()
            tmp.delete()
            val dec = Cipher.getInstance("AES/GCM/NoPadding")
            dec.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, back.copyOfRange(0, IV_BYTES)))
            val m = decode(String(dec.doFinal(back.copyOfRange(IV_BYTES, back.size)), Charsets.UTF_8))["peer"]?.first()
            m?.id == "abc" && m.text == "round trip / ✓" && m.mine
        }
    } catch (e: Exception) { false }
}
