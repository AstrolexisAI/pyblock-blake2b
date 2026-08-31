package com.astrolexis.pyblock.data.wallet

import com.astrolexis.pyblock.data.crypto.VanityCrypto

/** Parses raw (unconfirmed) transaction hex to find how many sats it pays a
 *  P2PKH address. Lets the wallet surface an incoming 0-conf payment the instant
 *  it hits PyBLØCK's node, before the CBF client sees the first confirmation.
 *  Byte-for-byte mirror of iOS `WalletMempoolAPI.incomingSats`. */
object MempoolParse {
    /** Sats [txHex] pays to the P2PKH [address] (sum of matching outputs), or 0. */
    fun incomingSats(address: String, txHex: String): Long {
        val dec = VanityCrypto.base58Decode(address) ?: return 0
        if (dec.size != 25) return 0
        val raw = hexToBytes(txHex) ?: return 0
        // OP_DUP OP_HASH160 <20-byte h160> OP_EQUALVERIFY OP_CHECKSIG
        val spk = byteArrayOf(0x76, 0xa9.toByte(), 0x14) +
            dec.copyOfRange(1, 21) + byteArrayOf(0x88.toByte(), 0xac.toByte())

        var i = 0
        fun u8(): Int? { if (i >= raw.size) return null; return raw[i++].toInt() and 0xff }
        fun take(n: Int): ByteArray? {
            if (n < 0 || i + n > raw.size) return null
            val s = raw.copyOfRange(i, i + n); i += n; return s
        }
        fun varint(): Long? {
            val n = u8() ?: return null
            return when {
                n < 0xfd -> n.toLong()
                n == 0xfd -> take(2)?.let { (it[0].toLong() and 0xff) or ((it[1].toLong() and 0xff) shl 8) }
                n == 0xfe -> take(4)?.let { b -> var v = 0L; for (k in 0 until 4) v = v or ((b[k].toLong() and 0xff) shl (8 * k)); v }
                else -> take(8)?.let { b -> var v = 0L; for (k in 0 until 8) v = v or ((b[k].toLong() and 0xff) shl (8 * k)); v }
            }
        }

        if (take(4) == null) return 0                                            // version
        if (i + 1 < raw.size && (raw[i].toInt() and 0xff) == 0x00 && (raw[i + 1].toInt() and 0xff) == 0x01) i += 2  // segwit marker+flag
        val nin = varint() ?: return 0
        var n = 0L
        while (n < nin) {
            if (take(32) == null || take(4) == null) return 0                    // prev outpoint
            val sl = varint() ?: return 0
            if (take(sl.toInt()) == null || take(4) == null) return 0            // scriptSig + sequence
            n++
        }
        val nout = varint() ?: return 0
        var total = 0L
        var o = 0L
        while (o < nout) {
            val valB = take(8) ?: break
            val sl = varint() ?: break
            val script = take(sl.toInt()) ?: break
            if (script.contentEquals(spk)) {
                var v = 0L; for (k in 0 until 8) v = v or ((valB[k].toLong() and 0xff) shl (8 * k)); total += v
            }
            o++
        }
        return total
    }

    private fun hexToBytes(s: String): ByteArray? =
        if (s.length % 2 != 0) null
        else try { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } } catch (e: Exception) { null }
}
