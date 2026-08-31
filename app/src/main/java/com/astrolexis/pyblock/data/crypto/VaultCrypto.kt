package com.astrolexis.pyblock.data.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto primitives for the spending-password vault: scrypt (memory-hard KDF,
 * Bither/BIP-38 style) + AES-256-GCM. Byte-for-byte mirror of the iOS
 * `VaultCrypto.swift`. Pure JVM (java.security / javax.crypto) so it's unit-testable
 * on the host. FUND-CRITICAL — [selfTest] validates scrypt against the official
 * RFC 7914 vectors and the wrap round-trip; the app refuses to run if it fails.
 */
object VaultCrypto {
    const val N = 16384
    const val R = 8
    const val P = 1
    const val DK = 32

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    // MARK: AES-256-GCM (iv(12) ‖ ciphertext ‖ tag(16))

    fun seal(plaintext: ByteArray, key: ByteArray): ByteArray? {
        if (key.size != 32) return null
        return try {
            val iv = randomBytes(12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            iv + c.doFinal(plaintext)
        } catch (e: Exception) { null }
    }

    fun open(blob: ByteArray, key: ByteArray): ByteArray? {
        if (key.size != 32 || blob.size < 12 + 16) return null
        return try {
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.doFinal(ct)
        } catch (e: Exception) { null }   // auth failure (wrong key / tampered) → null
    }

    // MARK: VMK wrapping — the GCM tag doubles as the password verifier.

    data class WrappedKey(val salt: ByteArray, val blob: ByteArray, val n: Int, val r: Int, val p: Int) {
        fun serialize(): String {
            val b64 = Base64.getEncoder()
            return "$n:$r:$p:${b64.encodeToString(salt)}:${b64.encodeToString(blob)}"
        }
        companion object {
            fun parse(s: String): WrappedKey? = try {
                val f = s.split(":"); val d = Base64.getDecoder()
                WrappedKey(d.decode(f[3]), d.decode(f[4]), f[0].toInt(), f[1].toInt(), f[2].toInt())
            } catch (e: Exception) { null }
        }
    }

    fun wrapVMK(vmk: ByteArray, password: String): WrappedKey? {
        val salt = randomBytes(16)
        val kek = scrypt(password.toByteArray(Charsets.UTF_8), salt, N, R, P, DK)
        val blob = seal(vmk, kek) ?: return null
        return WrappedKey(salt, blob, N, R, P)
    }

    fun unwrapVMK(w: WrappedKey, password: String): ByteArray? {
        val kek = scrypt(password.toByteArray(Charsets.UTF_8), w.salt, w.n, w.r, w.p, DK)
        return open(w.blob, kek)
    }

    // MARK: scrypt (RFC 7914) — word-based (Int) for speed.

    fun scrypt(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        val bw = 32 * r
        val b = pbkdf2(password, salt, 1, p * 128 * r)
        val words = IntArray(p * bw)
        for (i in words.indices) {
            words[i] = (b[i*4].toInt() and 0xff) or ((b[i*4+1].toInt() and 0xff) shl 8) or
                ((b[i*4+2].toInt() and 0xff) shl 16) or ((b[i*4+3].toInt() and 0xff) shl 24)
        }
        for (i in 0 until p) roMix(words, i * bw, n, r)
        for (i in words.indices) {
            val w = words[i]
            b[i*4] = (w and 0xff).toByte(); b[i*4+1] = ((w ushr 8) and 0xff).toByte()
            b[i*4+2] = ((w ushr 16) and 0xff).toByte(); b[i*4+3] = ((w ushr 24) and 0xff).toByte()
        }
        return pbkdf2(password, b, 1, dkLen)
    }

    private fun roMix(words: IntArray, offset: Int, n: Int, r: Int) {
        val bw = 32 * r
        val v = IntArray(n * bw)
        val x = IntArray(bw); val y = IntArray(bw)
        System.arraycopy(words, offset, x, 0, bw)
        for (i in 0 until n) {
            System.arraycopy(x, 0, v, i * bw, bw)
            blockMix(x, y, r)
            System.arraycopy(y, 0, x, 0, bw)
        }
        for (i in 0 until n) {
            val j = x[(2*r - 1)*16] and (n - 1)   // Integerify: first word (LE int) of last block
            for (k in 0 until bw) x[k] = x[k] xor v[j*bw + k]
            blockMix(x, y, r)
            System.arraycopy(y, 0, x, 0, bw)
        }
        System.arraycopy(x, 0, words, offset, bw)
    }

    private fun blockMix(b: IntArray, y: IntArray, r: Int) {
        val t = IntArray(16)
        System.arraycopy(b, (2*r - 1)*16, t, 0, 16)
        for (i in 0 until 2*r) {
            for (k in 0 until 16) t[k] = t[k] xor b[i*16 + k]
            salsa(t)
            val dst = (if (i % 2 == 0) i / 2 else r + (i - 1) / 2) * 16
            System.arraycopy(t, 0, y, dst, 16)
        }
    }

    private fun salsa(w: IntArray) {
        fun rot(a: Int, b: Int) = (a shl b) or (a ushr (32 - b))
        var x0=w[0]; var x1=w[1]; var x2=w[2]; var x3=w[3]; var x4=w[4]; var x5=w[5]; var x6=w[6]; var x7=w[7]
        var x8=w[8]; var x9=w[9]; var x10=w[10]; var x11=w[11]; var x12=w[12]; var x13=w[13]; var x14=w[14]; var x15=w[15]
        repeat(4) {
            x4 = x4 xor rot(x0 + x12, 7);  x8 = x8 xor rot(x4 + x0, 9);   x12 = x12 xor rot(x8 + x4, 13);  x0 = x0 xor rot(x12 + x8, 18)
            x9 = x9 xor rot(x5 + x1, 7);   x13 = x13 xor rot(x9 + x5, 9); x1 = x1 xor rot(x13 + x9, 13);   x5 = x5 xor rot(x1 + x13, 18)
            x14 = x14 xor rot(x10 + x6, 7); x2 = x2 xor rot(x14 + x10, 9); x6 = x6 xor rot(x2 + x14, 13);  x10 = x10 xor rot(x6 + x2, 18)
            x3 = x3 xor rot(x15 + x11, 7); x7 = x7 xor rot(x3 + x15, 9);  x11 = x11 xor rot(x7 + x3, 13);  x15 = x15 xor rot(x11 + x7, 18)
            x1 = x1 xor rot(x0 + x3, 7);   x2 = x2 xor rot(x1 + x0, 9);   x3 = x3 xor rot(x2 + x1, 13);    x0 = x0 xor rot(x3 + x2, 18)
            x6 = x6 xor rot(x5 + x4, 7);   x7 = x7 xor rot(x6 + x5, 9);   x4 = x4 xor rot(x7 + x6, 13);    x5 = x5 xor rot(x4 + x7, 18)
            x11 = x11 xor rot(x10 + x9, 7); x8 = x8 xor rot(x11 + x10, 9); x9 = x9 xor rot(x8 + x11, 13);  x10 = x10 xor rot(x9 + x8, 18)
            x12 = x12 xor rot(x15 + x14, 7); x13 = x13 xor rot(x12 + x15, 9); x14 = x14 xor rot(x13 + x12, 13); x15 = x15 xor rot(x14 + x13, 18)
        }
        w[0]=w[0]+x0; w[1]=w[1]+x1; w[2]=w[2]+x2; w[3]=w[3]+x3; w[4]=w[4]+x4; w[5]=w[5]+x5; w[6]=w[6]+x6; w[7]=w[7]+x7
        w[8]=w[8]+x8; w[9]=w[9]+x9; w[10]=w[10]+x10; w[11]=w[11]+x11; w[12]=w[12]+x12; w[13]=w[13]+x13; w[14]=w[14]+x14; w[15]=w[15]+x15
    }

    // MARK: PBKDF2-HMAC-SHA256 (hand-rolled HMAC so empty keys / RFC vectors work)

    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val out = ByteArray(((dkLen + 31) / 32) * 32)
        var block = 1
        var pos = 0
        while (pos < out.size) {
            val msg = salt + byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte())
            var u = hmacSha256(password, msg)
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = hmacSha256(password, u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, pos, 32)
            pos += 32; block++
        }
        return out.copyOf(dkLen)
    }

    private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(64)
        val k = if (key.size > 64) md.digest(key) else key
        System.arraycopy(k, 0, block, 0, k.size)
        val ipad = ByteArray(64) { (block[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(64) { (block[it].toInt() xor 0x5c).toByte() }
        md.reset(); md.update(ipad); md.update(msg); val inner = md.digest()
        md.reset(); md.update(opad); md.update(inner); return md.digest()
    }

    // MARK: Self-test (fail-closed)

    fun selfTest(): Boolean {
        // RFC 7914 §12: scrypt("","",16,1,1,64)
        val v1 = scrypt(ByteArray(0), ByteArray(0), 16, 1, 1, 64).toHex()
        if (v1 != "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906") return false
        // r=2 exercises the BlockMix permutation. Ref: hashlib.scrypt.
        val v2 = scrypt("pass".toByteArray(), "salt".toByteArray(), 16, 2, 1, 64).toHex()
        if (v2 != "0227f451f171a74a52117e195e21e0c30e5775a43082a5896a6edb7c501bb7380d6855a5d8ac0e04fc90bdf0902aae2f6b3746964ad1a48a98322ead81227533") return false
        // AES-GCM + VMK wrap round-trip (small scrypt params so launch stays fast).
        val vmk = randomBytes(32); val salt = randomBytes(16)
        val kek = scrypt("pw".toByteArray(), salt, 16, 1, 1, 32)
        val blob = seal(vmk, kek) ?: return false
        if (!(open(blob, kek)?.contentEquals(vmk) ?: false)) return false
        val wrongKek = scrypt("nope".toByteArray(), salt, 16, 1, 1, 32)
        if (open(blob, wrongKek) != null) return false   // wrong password rejected
        return true
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
