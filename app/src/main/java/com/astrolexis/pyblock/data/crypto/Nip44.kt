package com.astrolexis.pyblock.data.crypto

import android.util.Base64
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-44 v2 encryption for private Nostr DMs. secp256k1 ECDH (raw x) → HKDF →
 * ChaCha20 + HMAC-SHA256, per the spec. Mirrors the iOS `Nip44.swift`.
 *
 * secp256k1-kmp's `ecdh()` returns SHA-256 of the point (not what NIP-44 wants),
 * so the raw shared x is derived with `pubKeyTweakMul(peerPoint, myPriv)` =
 * myPriv · peerPoint, then the x-coordinate is taken.
 *
 * Symmetric crypto is verifiable: [selfTest] checks ChaCha20 against RFC 8439
 * and the conversation key against the official NIP-44 vector.
 */
object Nip44 {
    private val secp: Secp256k1 by lazy { Secp256k1.get() }
    private val rng = SecureRandom()

    // MARK: Public API

    fun encrypt(plaintext: String, mySecret: ByteArray, theirPubkeyHex: String): String? {
        val ck = conversationKey(mySecret, theirPubkeyHex) ?: return null
        val nonce = ByteArray(32).also { rng.nextBytes(it) }
        return encrypt(plaintext, ck, nonce)
    }

    fun decrypt(payload: String, mySecret: ByteArray, theirPubkeyHex: String): String? {
        val ck = conversationKey(mySecret, theirPubkeyHex) ?: return null
        return decrypt(payload, ck)
    }

    // MARK: Conversation key (ECDH → HKDF-extract)

    fun conversationKey(mySecret: ByteArray, theirPubkeyHex: String): ByteArray? {
        val xonly = theirPubkeyHex.hexBytes() ?: return null
        if (xonly.size != 32) return null
        val compressed = byteArrayOf(0x02) + xonly       // lift x-only to even-Y point
        return try {
            val point = secp.pubkeyParse(compressed)          // 65-byte internal point
            val shared = secp.pubKeyTweakMul(point, mySecret) // myPriv · peerPoint
            val sharedX = secp.pubKeyCompress(shared).copyOfRange(1, 33)  // drop prefix → x
            hmacSha256("nip44-v2".toByteArray(), sharedX)     // HKDF-extract
        } catch (e: Exception) { null }
    }

    // MARK: Encrypt / decrypt with a known conversation key + nonce

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray): String? {
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val ciphertext = chacha20(chachaKey, chachaNonce, 0, pad(plaintext.toByteArray()))
        val mac = hmacSha256(hmacKey, nonce + ciphertext)
        val out = byteArrayOf(0x02) + nonce + ciphertext + mac
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String? {
        val bytes = try { Base64.decode(payload, Base64.NO_WRAP) } catch (e: Exception) { return null }
        if (bytes.size < 1 + 32 + 32 || bytes[0].toInt() != 0x02) return null
        val nonce = bytes.copyOfRange(1, 33)
        val mac = bytes.copyOfRange(bytes.size - 32, bytes.size)
        val ciphertext = bytes.copyOfRange(33, bytes.size - 32)
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val expected = hmacSha256(hmacKey, nonce + ciphertext)
        if (!constantTimeEquals(expected, mac)) return null
        return unpad(chacha20(chachaKey, chachaNonce, 0, ciphertext))
    }

    // MARK: HKDF-expand → per-message keys

    private data class MsgKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray)

    private fun messageKeys(ck: ByteArray, nonce: ByteArray): MsgKeys {
        val out = hkdfExpand(ck, nonce, 76)
        return MsgKeys(out.copyOfRange(0, 32), out.copyOfRange(32, 44), out.copyOfRange(44, 76))
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-expand (RFC 5869) with SHA-256. */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t); mac.update(info); mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; counter++
        }
        return out
    }

    // MARK: Padding (NIP-44)

    fun calcPaddedLen(len: Int): Int {
        if (len <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(len - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * (((len - 1) / chunk) + 1)
    }

    fun pad(plaintext: ByteArray): ByteArray {
        val len = plaintext.size
        val paddedLen = calcPaddedLen(len)
        val out = ByteArray(2 + paddedLen)
        out[0] = (len ushr 8).toByte(); out[1] = (len and 0xff).toByte()
        System.arraycopy(plaintext, 0, out, 2, len)
        return out
    }

    fun unpad(padded: ByteArray): String? {
        if (padded.size < 2) return null
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        if (len < 1 || 2 + len > padded.size) return null
        return String(padded.copyOfRange(2, 2 + len), Charsets.UTF_8)
    }

    // MARK: ChaCha20 (RFC 8439)

    fun chacha20(key: ByteArray, nonce: ByteArray, counter: Int, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var ctr = counter
        var i = 0
        while (i < data.size) {
            val block = chachaBlock(key, ctr, nonce)
            val n = minOf(64, data.size - i)
            for (j in 0 until n) out[i + j] = (data[i + j].toInt() xor block[j].toInt()).toByte()
            i += 64; ctr++
        }
        return out
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
        ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun rotl(v: Int, c: Int): Int = (v shl c) or (v ushr (32 - c))

    private fun chachaBlock(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (k in 0 until 8) s[4 + k] = le32(key, k * 4)
        s[12] = counter
        s[13] = le32(nonce, 0); s[14] = le32(nonce, 4); s[15] = le32(nonce, 8)

        val x = s.copyOf()
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 7)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (k in 0 until 16) {
            val v = x[k] + s[k]
            out[k * 4] = (v and 0xff).toByte()
            out[k * 4 + 1] = ((v ushr 8) and 0xff).toByte()
            out[k * 4 + 2] = ((v ushr 16) and 0xff).toByte()
            out[k * 4 + 3] = ((v ushr 24) and 0xff).toByte()
        }
        return out
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // MARK: Self test (verifiable primitives)

    /** True if the crypto matches known-good vectors. Shown in the DM inbox header. */
    fun selfTest(): Boolean {
        // 1) ChaCha20 vs RFC 8439 §2.3.2 keystream (counter 1).
        val key = ByteArray(32) { it.toByte() }
        val nonce = byteArrayOf(0, 0, 0, 9, 0, 0, 0, 0x4a, 0, 0, 0, 0)
        val ks = chacha20(key, nonce, 1, ByteArray(64))
        if (ks.copyOfRange(0, 16).hexStr() != "10f1e7e4d13b5915500fdd1fa32071c4") return false

        // 2) NIP-44 conversation-key vector: sec1=1, sec2=2.
        val sec1 = ByteArray(32).also { it[31] = 1 }
        val sec2 = ByteArray(32).also { it[31] = 2 }
        val pub2 = xonlyPubkey(sec2) ?: return false
        val ck = conversationKey(sec1, pub2.hexStr()) ?: return false
        if (ck.hexStr() != "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d") return false

        // 3) Round-trip.
        val msg = "hello PyBLØCK 🧬"
        val pub1 = xonlyPubkey(sec1) ?: return false
        val ct = encrypt(msg, sec1, pub2.hexStr()) ?: return false
        val pt = decrypt(ct, sec2, pub1.hexStr())
        return pt == msg
    }

    private fun xonlyPubkey(sec: ByteArray): ByteArray? = try {
        secp.pubKeyCompress(secp.pubkeyCreate(sec)).copyOfRange(1, 33)
    } catch (e: Exception) { null }
}

private val HEX = "0123456789abcdef".toCharArray()
private fun ByteArray.hexStr(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) { val v = b.toInt() and 0xff; sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0xf]) }
    return sb.toString()
}
private fun String.hexBytes(): ByteArray? {
    val s = trim()
    if (s.length % 2 != 0) return null
    return try { ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() } }
    catch (e: Exception) { null }
}
