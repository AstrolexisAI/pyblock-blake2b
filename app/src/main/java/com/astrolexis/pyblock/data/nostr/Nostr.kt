package com.astrolexis.pyblock.data.nostr

import android.content.Context
import com.astrolexis.pyblock.data.crypto.Nip44
import com.astrolexis.pyblock.data.store.SecurePrefs
import com.astrolexis.pyblock.data.util.Bech32
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom

/** A signed Nostr event (NIP-01). */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
)

/**
 * Nostr identity + event signing (NIP-01, BIP-340 Schnorr). The secret key is a
 * dedicated 32-byte key in Keystore-backed EncryptedSharedPreferences — NOT
 * reused from any wallet key. Mirrors the iOS `Nostr.swift`.
 */
object Nostr {
    private val secp: Secp256k1 by lazy { Secp256k1.get() }
    private val rng = SecureRandom()

    /** Dedicated NIP-28 channel id (= sha256("pyblock.btc.community.v1")). */
    const val channelId = "14934fd5ad4c0da45a4f903fee1ca5a57448d685dd7fe8ad1699609e04a0074a"

    /** Whale-only lounge (= sha256("pyblock.btc.whale.v1")). The client gates
     *  entry by tier; the relay's write policy enforces it server-side. */
    const val whaleChannelId = "a054a2c57f0f49a9aa1ac12ea82ba5c4638881da3b7368a28fdbfefeb88beeb5"

    private const val PREFS = "pyblock_nostr"
    private const val KEY_SK = "nostr-sk-v1"
    private const val KEY_NAME = "nostr-name"
    private const val KEY_COLOR = "nostr-flair-color"
    private const val KEY_SHARE_RECEIVE = "nostr-share-receive"

    // Fund/identity-critical store (holds the Nostr secret key). Never wipe it on
    // a transient Keystore failure — resetOnCorruption=false preserves the blob and
    // throws SecurePrefsUnavailableException instead (callers already runCatching).
    private fun prefs(ctx: Context) = SecurePrefs.open(ctx, PREFS, "${PREFS}_legacy", resetOnCorruption = false)

    // MARK: Identity

    /** In-memory cache so a transient Keystore blip after first load returns the
     *  SAME key rather than re-opening (and never regenerates the identity). */
    @Volatile private var cachedSk: ByteArray? = null

    fun secretKey(ctx: Context): ByteArray {
        cachedSk?.let { return it }
        val p = prefs(ctx)
        p.getString(KEY_SK, null)?.let { return it.hexBytes().also { b -> cachedSk = b } }
        while (true) {
            val b = ByteArray(32).also { rng.nextBytes(it) }
            try { secp.pubkeyCreate(b); p.edit().putString(KEY_SK, b.hexStr()).apply(); cachedSk = b; return b }
            catch (e: Exception) { /* out of range, retry */ }
        }
    }

    fun pubkeyHex(ctx: Context): String =
        secp.pubKeyCompress(secp.pubkeyCreate(secretKey(ctx))).copyOfRange(1, 33).hexStr()

    /** NIP-19 `npub…` for a hex pubkey. */
    fun npub(fromHex: String): String {
        val bytes = fromHex.hexBytesOrNull() ?: return ""
        if (bytes.size != 32) return ""
        return Bech32.encode("npub", bytes)
    }

    fun myNpub(ctx: Context): String = npub(pubkeyHex(ctx))

    /** NIP-19 `nsec…` — YOUR Nostr private key. Sensitive: anyone with it can post
     *  as you and read your DMs. Only surfaced behind an explicit reveal. */
    fun myNsec(ctx: Context): String = Bech32.encode("nsec", secretKey(ctx))

    // MARK: Display name (kind-0 profile)

    // Non-critical reads/writes share the (now non-destructive) store, so guard
    // them against a transient SecurePrefsUnavailableException — degrade, no crash.
    fun displayName(ctx: Context): String? =
        runCatching { prefs(ctx).getString(KEY_NAME, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun setDisplayName(ctx: Context, name: String) {
        runCatching { prefs(ctx).edit().putString(KEY_NAME, name.trim()).apply() }
    }

    /** Opt-in to being payable in chat: when true the kind-0 profile advertises
     *  your BIP-47 PayNym (fresh unlinkable address per payment). Default OFF —
     *  a chat reader must never auto-leak a receive identity, and the raw reused
     *  on-chain address is NEVER auto-broadcast. */
    fun shareReceiveInChat(ctx: Context): Boolean =
        runCatching { prefs(ctx).getBoolean(KEY_SHARE_RECEIVE, false) }.getOrDefault(false)

    fun setShareReceiveInChat(ctx: Context, v: Boolean) {
        runCatching { prefs(ctx).edit().putBoolean(KEY_SHARE_RECEIVE, v).apply() }
    }

    // MARK: Chat flair (kind-0 "color")

    fun flairColor(ctx: Context): String? =
        runCatching { prefs(ctx).getString(KEY_COLOR, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun setFlairColor(ctx: Context, color: String) {
        runCatching { prefs(ctx).edit().putString(KEY_COLOR, color).apply() }
    }

    fun metadataEvent(ctx: Context, name: String, btcAddress: String?, paynym: String? = null, tier: String? = null, color: String? = null, createdAt: Long): NostrEvent? {
        // Optional `btc` field = your receive address, so peers can pay you directly.
        val btc = if (!btcAddress.isNullOrBlank()) ",\"btc\":${jsonStr(btcAddress)}" else ""
        // Optional `paynym` = BIP-47 reusable code (fresh addr per pay, no OP_RETURN).
        val pc = if (!paynym.isNullOrBlank()) ",\"paynym\":${jsonStr(paynym)}" else ""
        // Optional `tier` = "whale"/"pro" community flair.
        val tr = if (!tier.isNullOrBlank()) ",\"tier\":${jsonStr(tier)}" else ""
        // Optional `color` = CHAT FLAIR name color (palette key).
        val cl = if (!color.isNullOrBlank()) ",\"color\":${jsonStr(color)}" else ""
        val content = """{"name":${jsonStr(name)},"display_name":${jsonStr(name)}$btc$pc$tr$cl}"""
        return makeEvent(ctx, 0, content, emptyList(), createdAt)
    }

    // MARK: Events

    /** Build + sign a NIP-01 event. */
    fun makeEvent(ctx: Context, kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent? {
        return try {
            val sk = secretKey(ctx)
            val pubkey = secp.pubKeyCompress(secp.pubkeyCreate(sk)).copyOfRange(1, 33).hexStr()
            val serialized = serializeForId(pubkey, createdAt, kind, tags, content)
            val digest = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
            val id = digest.hexStr()
            val sig = secp.signSchnorr(digest, sk, null).hexStr()
            NostrEvent(id, pubkey, createdAt, kind, tags, content, sig)
        } catch (e: Exception) { null }
    }

    // MARK: Encrypted DMs (NIP-44)

    /** kind-4 event with NIP-44-encrypted content + a `p` tag to the recipient. */
    fun makeDMEvent(ctx: Context, to: String, plaintext: String, createdAt: Long): NostrEvent? {
        val ciphertext = Nip44.encrypt(plaintext, secretKey(ctx), to) ?: return null
        // Protocol markers (read receipts) carry a visible ["t","ack"] tag so the
        // relay-side DM-push watcher can skip them — it only sees ciphertext, and
        // without the hint every ack raised a bogus "new DM" push on the peer.
        // The tag leaks only "this is an ack", never content.
        val tags = if (plaintext.startsWith("pyblock:read?"))
            listOf(listOf("p", to), listOf("t", "ack"))
        else listOf(listOf("p", to))
        return makeEvent(ctx, 4, ciphertext, tags, createdAt)
    }

    /** Decrypt a kind-4 DM. Returns (peer pubkey, plaintext). */
    fun decryptDM(ctx: Context, ev: NostrEvent, myPubkeyHex: String): Pair<String, String>? {
        val peer = if (ev.pubkey == myPubkeyHex)
            ev.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1) ?: return null
        else ev.pubkey
        val text = Nip44.decrypt(ev.content, secretKey(ctx), peer) ?: return null
        return peer to text
    }

    // MARK: NIP-01 canonical serialization for the event id

    private fun serializeForId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val sb = StringBuilder()
        sb.append("[0,\"").append(pubkey).append("\",").append(createdAt).append(',').append(kind).append(',')
        sb.append('[')
        tags.forEachIndexed { i, tag ->
            if (i > 0) sb.append(',')
            sb.append('[')
            tag.forEachIndexed { j, s -> if (j > 0) sb.append(','); sb.append(jsonStr(s)) }
            sb.append(']')
        }
        sb.append(']').append(',').append(jsonStr(content)).append(']')
        return sb.toString()
    }

    /** JSON string with NIP-01 escaping rules. */
    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}

private val HEXC = "0123456789abcdef".toCharArray()
internal fun ByteArray.hexStr(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) { val v = b.toInt() and 0xff; sb.append(HEXC[v ushr 4]); sb.append(HEXC[v and 0xf]) }
    return sb.toString()
}
internal fun String.hexBytes(): ByteArray =
    ByteArray(length / 2) { ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte() }
internal fun String.hexBytesOrNull(): ByteArray? =
    try { if (length % 2 != 0) null else hexBytes() } catch (e: Exception) { null }
