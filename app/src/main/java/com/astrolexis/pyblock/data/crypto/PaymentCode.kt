package com.astrolexis.pyblock.data.crypto

import android.content.Context
import com.astrolexis.pyblock.data.store.SecurePrefs
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BIP-47 reusable payment codes ("PayNym"), WITHOUT the on-chain OP_RETURN
 * notification — the payment code travels over the encrypted Nostr channel
 * instead (keeps the chain clean, anti-spam ideal). Identity is a random
 * keypair+chaincode (no seed phrase), matching the single-key vanity model.
 *
 * FUND-CRITICAL. [selfTest] checks the derivation is internally consistent
 * (the address the SENDER derives == the address the RECEIVER derives, whose
 * private key the receiver can then spend). Mirrors the iOS PaymentCode.swift.
 */
object PaymentCode {
    private val secp: Secp256k1 by lazy { Secp256k1.get() }
    private val rng = SecureRandom()

    private const val PREFS = "pyblock_paynym"
    private const val KEY_ID = "paynym-id-v1"
    private const val IDX_PREFS = "pyblock_paynym_idx"
    /** Scan floor for an imported incoming address — same as the vanity floor. */
    const val RECEIVE_BIRTHDAY = 955000

    private fun sha256(x: ByteArray) = MessageDigest.getInstance("SHA-256").digest(x)
    private fun hash256(x: ByteArray) = sha256(sha256(x))

    // MARK: - Identity (keypair + chaincode)

    data class Identity(val priv: ByteArray, val chainCode: ByteArray) {
        val pub: ByteArray get() = VanityCrypto.compressedPubkey(priv) ?: ByteArray(0)
    }

    fun newIdentity(): Identity = Identity(randomScalar(), random32())

    /** In-memory cache of the loaded identity so a transient Keystore blip after
     *  the first successful load returns the SAME key instead of re-opening. */
    @Volatile private var cached: Identity? = null

    /** This device's PayNym identity, created once and persisted (Keystore-backed).
     *  FUND-CRITICAL: this key derives every spendable stealth address for funds
     *  received via PayNym, so the store is opened with resetOnCorruption=false —
     *  a transient Keystore failure must NEVER wipe/regenerate it (that would make
     *  already-received stealth funds unspendable). May throw
     *  SecurePrefsUnavailableException on such a failure; the public [myCode]
     *  boundary degrades to "" so the UI never crashes. */
    fun mine(ctx: Context): Identity {
        cached?.let { return it }
        val p = SecurePrefs.open(ctx, PREFS, "${PREFS}_legacy", resetOnCorruption = false)
        p.getString(KEY_ID, null)?.hexToBytes()?.takeIf { it.size == 64 }?.let {
            return Identity(it.copyOfRange(0, 32), it.copyOfRange(32, 64)).also { id -> cached = id }
        }
        val id = newIdentity()
        p.edit().putString(KEY_ID, (id.priv + id.chainCode).toHexStr()).apply()
        cached = id
        return id
    }

    /** The "PM8T…" Base58Check payment code (version 0x47, 80-byte payload). */
    fun encode(id: Identity): String {
        val payload = ByteArray(80)
        payload[0] = 0x01           // version
        payload[1] = 0x00           // features
        System.arraycopy(id.pub, 0, payload, 2, 33)
        System.arraycopy(id.chainCode, 0, payload, 35, 32)
        return base58Check(0x47.toByte(), payload)
    }

    // Safe public boundary: if the Keystore-backed identity store is transiently
    // unavailable, degrade to "" (feature temporarily off) rather than crash the UI.
    fun myCode(ctx: Context): String = runCatching { encode(mine(ctx)) }.getOrDefault("")

    /** Parse a peer's "PM8T…" code → (33-byte pubkey, 32-byte chaincode). */
    fun decode(code: String): Pair<ByteArray, ByteArray>? {
        val data = base58CheckDecode(code) ?: return null
        if (data.size != 81 || data[0] != 0x47.toByte()) return null
        val p = data.copyOfRange(1, 81)
        if (p[0] != 0x01.toByte()) return null
        return p.copyOfRange(2, 35) to p.copyOfRange(35, 67)
    }

    // MARK: - Stealth address derivation (BIP-47 v1)

    /** SENDER: P2PKH address to pay for payment #[index] to (theirPub, theirCc). */
    fun sendAddress(mine: Identity, theirPub: ByteArray, theirCc: ByteArray, index: Int): String? {
        val bI = ckdPub(theirPub, theirCc, index) ?: return null
        val s = sharedS(mine.priv, bI) ?: return null
        val pI = pointAdd(bI, s) ?: return null          // B_i + s·G
        return VanityCrypto.p2pkhAddress(pI)
    }

    data class ReceiveKey(val wif: String, val address: String, val pubkeyHex: String)

    /** RECEIVER: the spendable key for incoming payment #[index] from (theirPub). */
    fun receiveKey(mine: Identity, theirPub: ByteArray, theirCc: ByteArray, index: Int): ReceiveKey? {
        val bI = ckdPriv(mine.priv, mine.pub, mine.chainCode, index) ?: return null
        val s = sharedS(bI, theirPub) ?: return null
        val pIpriv = try { secp.privKeyTweakAdd(bI, s) } catch (e: Exception) { return null }  // b_i + s
        val pub = VanityCrypto.compressedPubkey(pIpriv) ?: return null
        return ReceiveKey(VanityCrypto.wifCompressed(pIpriv), VanityCrypto.p2pkhAddress(pub), pub.toHexStr())
    }

    // MARK: - Sending to a peer's payment code (fresh address per payment)

    /** The stealth address to pay `peerCode` for payment #[index]. The caller
     *  passes index = number of "paid" receipts already sent to this peer, which
     *  stays in lockstep with what the receiver reconciles against (no mutable
     *  counter, and a cancelled send never skips an index). */
    fun deriveSendAddress(ctx: Context, peerCode: String, index: Int): String? {
        val (pub, cc) = decode(peerCode) ?: return null
        return sendAddress(mine(ctx), pub, cc, index)
    }

    /** True if a string looks like a BIP-47 payment code. */
    fun looksLikePaymentCode(s: String): Boolean = s.trim().startsWith("PM") && s.trim().length > 60

    /** Wallet/QR flow (no chat receipt): derive a fresh address using a stored
     *  per-code counter, then advance it so a re-send derives a new one. The
     *  recipient detects it via blind-scan. */
    fun nextWalletSendAddress(ctx: Context, peerCode: String): String? {
        val p = ctx.getSharedPreferences(IDX_PREFS, Context.MODE_PRIVATE)
        val key = "walletsend.$peerCode"
        val i = maxOf(0, p.getInt(key, 0))
        val addr = deriveSendAddress(ctx, peerCode, i) ?: return null
        p.edit().putInt(key, i + 1).apply()
        return addr
    }

    // MARK: - Receiving from a peer's payment code (receipt-triggered)

    data class IncomingKey(val wif: String, val address: String, val pubkeyHex: String, val index: Int)

    fun nextReceiveKey(ctx: Context, peerCode: String): IncomingKey? {
        val (pub, cc) = decode(peerCode) ?: return null
        val i = receivedCount(ctx, peerCode)
        val k = receiveKey(mine(ctx), pub, cc, i) ?: return null
        return IncomingKey(k.wif, k.address, k.pubkeyHex, i)
    }

    fun didReceive(ctx: Context, peerCode: String, index: Int) {
        val key = "recv.$peerCode"
        val p = idxPrefs(ctx)
        if (index + 1 > p.getInt(key, 0)) p.edit().putInt(key, index + 1).apply()
    }

    /** How many incoming keys from [peerCode] have been imported (= next index). */
    fun receivedCount(ctx: Context, peerCode: String) = maxOf(0, idxPrefs(ctx).getInt("recv.$peerCode", 0))

    /** Peek the incoming key at a SPECIFIC index without advancing state — used by the
     *  look-ahead scan to check candidate addresses on-chain before they're claimed. */
    fun receiveKeyAt(ctx: Context, peerCode: String, index: Int): IncomingKey? {
        val (pub, cc) = decode(peerCode) ?: return null
        val k = receiveKey(mine(ctx), pub, cc, index) ?: return null
        return IncomingKey(k.wif, k.address, k.pubkeyHex, index)
    }

    /** The next [gap] unclaimed incoming addresses (receivedCount … +gap-1) — a BIP-47
     *  look-ahead window to scan on-chain, so a payment is found even if its notification
     *  DM was lost. */
    fun lookaheadAddresses(ctx: Context, peerCode: String, gap: Int): List<Pair<Int, String>> {
        val start = receivedCount(ctx, peerCode)
        return (start until start + gap).mapNotNull { i -> receiveKeyAt(ctx, peerCode, i)?.let { i to it.address } }
    }

    private fun idxPrefs(ctx: Context) = ctx.getSharedPreferences(IDX_PREFS, Context.MODE_PRIVATE)

    // MARK: - BIP-47 notification (receiving from EXTERNAL wallets: Samourai/Sparrow)

    /** This wallet's BIP-47 notification address — P2PKH of the 0th derived pubkey of our
     *  payment code. External BIP-47 wallets pay a tiny notification tx here with an OP_RETURN
     *  carrying their (blinded) payment code. */
    fun notificationAddress(ctx: Context): String? {
        val id = mine(ctx)
        val n0 = ckdPub(id.pub, id.chainCode, 0) ?: return null
        return VanityCrypto.p2pkhAddress(n0)
    }

    // MARK: - BIP-47 notification (SENDING: announce MY code so the peer finds my payments)

    /** The PEER's notification address (P2PKH of index-0 of their code) — where we send
     *  the one-time notification tx so they learn our payment code. Mirrors iOS. */
    fun notificationAddressForPeer(peerCode: String): String? {
        val (pub, cc) = decode(peerCode) ?: return null
        val n0 = ckdPub(pub, cc, 0) ?: return null
        return VanityCrypto.p2pkhAddress(n0)
    }

    /** The 80-byte BLINDED payload carrying MY payment code, for a notification tx to
     *  [toPeerCode]. Inverse of [unblindNotification]: mask = HMAC-SHA512(key=outpoint,
     *  data = x(a·B0)); XOR pubkey-x (3..35) + chaincode (35..67). [designatedOutpoint]
     *  = designated input prev-txid (INTERNAL/wire order) ‖ vout (LE) = 36 bytes, i.e.
     *  exactly the bytes the receiver reads from the serialized input. */
    fun blindedNotificationPayload(ctx: Context, toPeerCode: String,
                                   designatedPrivkey: ByteArray, designatedOutpoint: ByteArray): ByteArray? {
        if (designatedPrivkey.size != 32 || designatedOutpoint.size != 36) return null
        val (pub, cc) = decode(toPeerCode) ?: return null
        val b0 = ckdPub(pub, cc, 0) ?: return null                 // peer notif pubkey B0
        val x = ecdhXCoord(designatedPrivkey, b0) ?: return null   // a·B0 == b0·A
        val mask = hmac512(designatedOutpoint, x)
        val id = mine(ctx)
        val p = ByteArray(80)
        p[0] = 0x01; p[1] = 0x00
        System.arraycopy(id.pub, 0, p, 2, 33)
        System.arraycopy(id.chainCode, 0, p, 35, 32)
        for (i in 0 until 32) {
            p[3 + i] = (p[3 + i].toInt() xor mask[i].toInt()).toByte()
            p[35 + i] = (p[35 + i].toInt() xor mask[32 + i].toInt()).toByte()
        }
        return p
    }

    /** Whether we've already broadcast a notification tx to this peer (once per peer, ever). */
    fun hasNotified(ctx: Context, peerCode: String): Boolean = idxPrefs(ctx).getInt("notified.$peerCode", 0) > 0
    fun markNotified(ctx: Context, peerCode: String) { idxPrefs(ctx).edit().putInt("notified.$peerCode", 1).apply() }

    /** Unblind a notification tx's OP_RETURN → the SENDER's "PM8T…" code, or null if invalid. */
    fun unblindNotification(ctx: Context, designatedPubkey: ByteArray, outpoint: ByteArray, payload: ByteArray): String? {
        // Accept a compressed (33) OR uncompressed (65) designated pubkey — PyBLØCK
        // vanity wallets can be uncompressed, so their P2PKH input reveals a 65-byte
        // key. secp.pubkeyParse in ecdhXCoord decompresses either; the point (x) is the same.
        if (payload.size != 80 || outpoint.size != 36 || (designatedPubkey.size != 33 && designatedPubkey.size != 65)) return null
        val id = mine(ctx)
        val b0 = ckdPriv(id.priv, id.pub, id.chainCode, 0) ?: return null
        val x = ecdhXCoord(b0, designatedPubkey) ?: return null
        val mask = hmac512(outpoint, x)                  // key = outpoint, data = raw X
        val p = payload.copyOf()
        for (i in 0 until 32) {
            p[3 + i] = (p[3 + i].toInt() xor mask[i].toInt()).toByte()
            p[35 + i] = (p[35 + i].toInt() xor mask[32 + i].toInt()).toByte()
        }
        if (p[0] != 0x01.toByte()) return null
        val code = base58Check(0x47.toByte(), p)
        return if (decode(code) != null) code else null
    }

    /** Raw ECDH shared-point X coordinate (32B) — NOT SHA256'd (unlike [sharedS]). */
    private fun ecdhXCoord(priv: ByteArray, pub: ByteArray): ByteArray? =
        try { secp.pubKeyCompress(secp.pubKeyTweakMul(secp.pubkeyParse(pub), priv)).copyOfRange(1, 33) } catch (e: Exception) { null }

    data class NotificationTx(val designatedPubkey: ByteArray, val outpoint: ByteArray, val payload: ByteArray)

    /** Parse a raw Bitcoin tx (hex); if it has an 80-byte v1 OP_RETURN, return the designated
     *  input pubkey + outpoint (wire order) + payload. Handles legacy P2PKH (base58, our own) and
     *  segwit witness inputs (external senders). Null if not a notification tx / malformed. */
    fun parseNotificationTx(rawHex: String): NotificationTx? {
        val raw = rawHex.hexToBytes() ?: return null
        var i = 0
        fun take(n: Int): ByteArray? { if (i + n > raw.size) return null; val r = raw.copyOfRange(i, i + n); i += n; return r }
        fun u8(): Int? { if (i >= raw.size) return null; return raw[i++].toInt() and 0xff }
        fun varint(): Int? {
            val n = u8() ?: return null
            return when {
                n < 0xfd -> n
                n == 0xfd -> take(2)?.let { (it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8) }
                n == 0xfe -> take(4)?.let { b -> (0..3).fold(0) { a, k -> a or ((b[k].toInt() and 0xff) shl (8 * k)) } }
                else -> take(8)?.let { b -> (0..7).fold(0) { a, k -> a or ((b[k].toInt() and 0xff) shl (8 * k)) } }
            }
        }
        take(4) ?: return null                            // version
        var segwit = false
        if (i + 1 < raw.size && raw[i].toInt() == 0 && raw[i + 1].toInt() == 1) { segwit = true; i += 2 }
        val nin = varint() ?: return null
        if (nin <= 0 || nin >= 10000) return null
        val phs = ArrayList<ByteArray>(); val vos = ArrayList<ByteArray>(); val sss = ArrayList<ByteArray>()
        for (k in 0 until nin) {
            val ph = take(32) ?: return null; val vo = take(4) ?: return null
            val sl = varint() ?: return null; val ss = take(sl) ?: return null; take(4) ?: return null
            phs.add(ph); vos.add(vo); sss.add(ss)
        }
        val nout = varint() ?: return null
        if (nout < 0 || nout >= 10000) return null
        var payload: ByteArray? = null
        for (k in 0 until nout) {
            take(8) ?: return null; val sl = varint() ?: return null; val spk = take(sl) ?: return null
            if (spk.size == 83 && (spk[0].toInt() and 0xff) == 0x6a && (spk[1].toInt() and 0xff) == 0x4c && (spk[2].toInt() and 0xff) == 0x50) {
                val p = spk.copyOfRange(3, spk.size)
                if (p.size == 80 && p[0].toInt() == 1) payload = p
            }
        }
        val wit = Array(nin) { emptyList<ByteArray>() }
        if (segwit) {
            for (k in 0 until nin) {
                val cnt = varint() ?: return null; if (cnt < 0 || cnt >= 10000) return null
                val items = ArrayList<ByteArray>()
                for (j in 0 until cnt) { val l = varint() ?: return null; items.add(take(l) ?: return null) }
                wit[k] = items
            }
        }
        val pl = payload ?: return null
        fun isPub(x: ByteArray) = (x.size == 33 && ((x[0].toInt() and 0xff) == 2 || (x[0].toInt() and 0xff) == 3)) || (x.size == 65 && (x[0].toInt() and 0xff) == 4)
        for (k in 0 until nin) {
            var A: ByteArray? = null
            if (wit[k].size >= 2 && isPub(wit[k][1])) A = wit[k][1]
            else scriptPushes(sss[k]).lastOrNull { isPub(it) }?.let { A = it }
            if (A != null) return NotificationTx(A!!, phs[k] + vos[k], pl)
        }
        return null
    }

    private fun scriptPushes(s: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(); var i = 0
        while (i < s.size) {
            val op = s[i].toInt() and 0xff; i++
            var len = 0
            when {
                op in 1..0x4b -> len = op
                op == 0x4c -> { if (i >= s.size) break; len = s[i].toInt() and 0xff; i++ }
                op == 0x4d -> { if (i + 1 >= s.size) break; len = (s[i].toInt() and 0xff) or ((s[i + 1].toInt() and 0xff) shl 8); i += 2 }
                op == 0x4e -> { if (i + 3 >= s.size) break; len = (0..3).fold(0) { a, k -> a or ((s[i + k].toInt() and 0xff) shl (8 * k)) }; i += 4 }
                else -> continue
            }
            if (i + len > s.size) break
            out.add(s.copyOfRange(i, i + len)); i += len
        }
        return out
    }

    // MARK: - BIP-32 non-hardened CKD

    private fun ckdPub(pub: ByteArray, cc: ByteArray, index: Int): ByteArray? {
        if (index < 0) return null
        val iL = hmac512(cc, pub + beBytes(index)).copyOfRange(0, 32)
        return pointAdd(pub, iL)                         // parent + iL·G
    }

    private fun ckdPriv(priv: ByteArray, pub: ByteArray, cc: ByteArray, index: Int): ByteArray? {
        if (index < 0) return null
        val iL = hmac512(cc, pub + beBytes(index)).copyOfRange(0, 32)
        // privKeyTweakAdd is native + IN-PLACE — copy so the caller's key (our
        // stored identity) is never mutated across derivations.
        return try { secp.privKeyTweakAdd(priv.copyOf(), iL) } catch (e: Exception) { null }
    }

    // MARK: - secp256k1 primitives

    /** s = SHA256( x-coord of (priv · pub) ), or null if not a valid scalar.
     *  NOTE: fr.acinq's point ops need a PARSED (65-byte) pubkey, not the raw
     *  33-byte compressed one — same as Nip44's ECDH. */
    private fun sharedS(priv: ByteArray, pub: ByteArray): ByteArray? {
        val shared = try { secp.pubKeyTweakMul(secp.pubkeyParse(pub), priv) } catch (e: Exception) { return null }
        val x = secp.pubKeyCompress(shared).copyOfRange(1, 33)
        val s = sha256(x)
        return if (secp.secKeyVerify(s)) s else null
    }

    /** pub + scalar·G → 33-byte compressed. */
    private fun pointAdd(pub: ByteArray, scalar: ByteArray): ByteArray? =
        try { secp.pubKeyCompress(secp.pubKeyTweakAdd(secp.pubkeyParse(pub), scalar)) } catch (e: Exception) { null }

    // MARK: - Scalars / helpers

    private fun randomScalar(): ByteArray {
        while (true) { val b = random32(); if (secp.secKeyVerify(b)) return b }
    }
    private fun random32() = ByteArray(32).also { rng.nextBytes(it) }

    private fun hmac512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }
    private fun beBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun base58Check(version: Byte, payload: ByteArray): String {
        val data = byteArrayOf(version) + payload
        return VanityCrypto.base58Encode(data + hash256(data).copyOfRange(0, 4))
    }
    private fun base58CheckDecode(s: String): ByteArray? {
        val raw = VanityCrypto.base58Decode(s) ?: return null
        if (raw.size < 5) return null
        val body = raw.copyOfRange(0, raw.size - 4)
        val cks = raw.copyOfRange(raw.size - 4, raw.size)
        return if (hash256(body).copyOfRange(0, 4).contentEquals(cks)) body else null
    }

    // MARK: - Self test

    /** True if send-side and receive-side derive the SAME address for several
     *  indices — the correctness gate. false ⇒ DO NOT ship. */
    fun selfTest(ctx: Context): Boolean {
        val alice = newIdentity(); val bob = newIdentity()
        for (i in 0 until 4) {
            val sent = sendAddress(alice, bob.pub, bob.chainCode, i)
            val recv = receiveKey(bob, alice.pub, alice.chainCode, i)
            if (sent == null || recv == null || sent != recv.address) return false
        }
        // Cross-platform vector: fixed identities must derive fixed addresses, so
        // iOS↔Android agree (else a payment lands where the peer can't spend).
        // These exact values are asserted identically in the iOS PaymentCode.swift.
        val fa = Identity(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 })
        val fb = Identity(ByteArray(32) { 0x33 }, ByteArray(32) { 0x44 })
        val expected = arrayOf("1Jfmg4Mjv8D8R1qATFGptrt3ewFMaie44m", "1JqHx2fwjRuu8pdZAwqZnqELTSq72gbq7P",
            "134f5JPNEQnfwYpGRrNL6jdbfz7A2THPdy", "1Assi9LXuvidasUz8ZyNqkeAXHVHhHc6EJ")
        for (i in 0 until 4) if (sendAddress(fa, fb.pub, fb.chainCode, i) != expected[i]) return false
        return interopTestBIP47() && notificationSelfTest() && notificationSendSelfTest(ctx)
    }

    /** SEND-side round trip: blind MY code toward a peer with a designated input key +
     *  outpoint (via the production [blindedNotificationPayload]), then unblind it from
     *  the peer's side → must recover MY code. Proves the sender blinding is the exact
     *  inverse of the (vector-verified) receiver unblind, so a notification tx we
     *  broadcast is decodable by the recipient. Mirrors iOS notificationSendSelfTest. */
    fun notificationSendSelfTest(ctx: Context): Boolean {
        val a = ByteArray(32) { 0x11 }                              // designated input privkey
        val aPub = VanityCrypto.compressedPubkey(a) ?: return false // its pubkey (what the receiver reads)
        val outpoint = ("ab".repeat(32) + "01000000").hexToBytes() ?: return false   // txid‖vout(LE)=36B
        if (outpoint.size != 36) return false
        val bob = Identity(ByteArray(32) { 0x33 }, ByteArray(32) { 0x44 })
        val bobCode = encode(bob)
        val blinded = blindedNotificationPayload(ctx, bobCode, a, outpoint) ?: return false
        if (blinded.size != 80) return false
        // Unblind AS BOB (mirror unblindNotification, but with Bob's key not mine()).
        val b0 = ckdPriv(bob.priv, bob.pub, bob.chainCode, 0) ?: return false
        val x = ecdhXCoord(b0, aPub) ?: return false
        val mask = hmac512(outpoint, x)
        val p = blinded.copyOf()
        for (i in 0 until 32) {
            p[3 + i] = (p[3 + i].toInt() xor mask[i].toInt()).toByte()
            p[35 + i] = (p[35 + i].toInt() xor mask[32 + i].toInt()).toByte()
        }
        if (p[0] != 0x01.toByte()) return false
        return base58Check(0x47.toByte(), p) == myCode(ctx)         // recovered == MY payment code
    }

    /** BIP-47 notification unblind interop gate — asserts the primitives + full unblind + the raw-tx
     *  parse against the official vectors (byte-identical to iOS PaymentCode.swift). */
    fun notificationSelfTest(): Boolean {
        val b0 = "04448fd1be0c9c13a5ca0b530e464b619dc091b299b98c5cab9978b32b4a1b8b".hexToBytes() ?: return false
        val a = "0272d83d8a1fa323feab1c085157a0791b46eba34afb8bfbfaeb3a3fcc3f2c9ad8".hexToBytes() ?: return false
        val o = "86f411ab1c8e70ae8a0795ab7a6757aea6e4d5ae1826fc7b8f00c597d500609c01000000".hexToBytes() ?: return false
        val expX = "736a25d9250238ad64ed5da03450c6a3f4f8f4dcdf0b58d1ed69029d76ead48d"
        val expMask = "be6e7a4256cac6f4d4ed4639b8c39c4cb8bece40010908e70d17ea9d77b4dc57f1da36f2d6641ccb37cf2b9f3146686462e0fa3161ae74f88c0afd4e307adbd5"
        val blinded = "010002063e4eb95e62791b06c50e1a3a942e1ecaaa9afbbeb324d16ae6821e091611fa96c0cf048f607fe51a0327f5e2528979311c78cb2de0d682c61e1180fc3d543b00000000000000000000000000".hexToBytes() ?: return false
        val alicePayload = "010002b85034fb08a8bfefd22848238257b252721454bbbfba2c3667f168837ea2cdad671af9f65904632e2dcc0c6ad314e11d53fc82fa4c4ea27a4a14eccecc478fee00000000000000000000000000"
        val x = ecdhXCoord(b0, a) ?: return false
        if (x.toHexStr() != expX) return false
        val mask = hmac512(o, x)
        if (mask.toHexStr() != expMask) return false
        for (i in 0 until 32) {
            blinded[3 + i] = (blinded[3 + i].toInt() xor mask[i].toInt()).toByte()
            blinded[35 + i] = (blinded[35 + i].toInt() xor mask[32 + i].toInt()).toByte()
        }
        if (blinded.toHexStr() != alicePayload) return false
        // Full raw-tx parse of the official notification tx.
        val rawTx = "010000000186f411ab1c8e70ae8a0795ab7a6757aea6e4d5ae1826fc7b8f00c597d500609c010000006b483045022100ac8c6dbc482c79e86c18928a8b364923c774bfdbd852059f6b3778f2319b59a7022029d7cc5724e2f41ab1fcfc0ba5a0d4f57ca76f72f19530ba97c860c70a6bf0a801210272d83d8a1fa323feab1c085157a0791b46eba34afb8bfbfaeb3a3fcc3f2c9ad8ffffffff0210270000000000001976a9148066a8e7ee82e5c5b9b7dc1765038340dc5420a988ac1027000000000000536a4c50010002063e4eb95e62791b06c50e1a3a942e1ecaaa9afbbeb324d16ae6821e091611fa96c0cf048f607fe51a0327f5e2528979311c78cb2de0d682c61e1180fc3d543b0000000000000000000000000000000000"
        val ntx = parseNotificationTx(rawTx) ?: return false
        return ntx.designatedPubkey.toHexStr() == "0272d83d8a1fa323feab1c085157a0791b46eba34afb8bfbfaeb3a3fcc3f2c9ad8" &&
            ntx.outpoint.toHexStr() == "86f411ab1c8e70ae8a0795ab7a6757aea6e4d5ae1826fc7b8f00c597d500609c01000000" &&
            ntx.payload.size == 80
    }

    /** Ecosystem-interop gate: the canonical BIP-47 spec vectors (Alice→Bob). Proves a PyBLØCK
     *  PayNym derives the SAME stealth addresses as Samourai/Sparrow/other BIP-47 wallets, so
     *  payments interoperate across the ecosystem. Alice's a0 + Bob's payment code are the
     *  published vector values (independently verified against the spec). */
    fun interopTestBIP47(): Boolean {
        val aliceA0 = "8d6a8ecd8ee5e0042ad0cb56e3a971c760b5145c3917a8e7beaf0ed92d7a520c".hexToBytes() ?: return false
        val alice = Identity(aliceA0, ByteArray(32)) // chaincode unused for send
        val bob = decode("PM8TJS2JxQ5ztXUpBBRnpTbcUXbUHy2T1abfrb3KkAAtMEGNbey4oumH7Hc578WgQJhPjBxteQ5GHHToTYHE3A1w6p7tU6KSoFmWBVbFGjKPisZDbP97") ?: return false
        val vectors = arrayOf(
            "141fi7TY3h936vRUKh1qfUZr8rSBuYbVBK", "12u3Uued2fuko2nY4SoSFGCoGLCBUGPkk6",
            "1FsBVhT5dQutGwaPePTYMe5qvYqqjxyftc", "1CZAmrbKL6fJ7wUxb99aETwXhcGeG3CpeA",
            "1KQvRShk6NqPfpr4Ehd53XUhpemBXtJPTL", "1KsLV2F47JAe6f8RtwzfqhjVa8mZEnTM7t",
            "1DdK9TknVwvBrJe7urqFmaxEtGF2TMWxzD", "16DpovNuhQJH7JUSZQFLBQgQYS4QB9Wy8e",
            "17qK2RPGZMDcci2BLQ6Ry2PDGJErrNojT5", "1GxfdfP286uE24qLZ9YRP3EWk2urqXgC4s")
        for (i in vectors.indices) if (sendAddress(alice, bob.first, bob.second, i) != vectors[i]) return false
        return true
    }
}

private fun ByteArray.toHexStr(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray? =
    if (length % 2 != 0) null else ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
