package com.astrolexis.pyblock.data.wallet

/**
 * A one-shot hand-off from the chat to the Wallet screen: "open the send flow
 * pre-filled with this address/amount". Set when the user taps Pay/Send on a
 * private payment, consumed once by the Wallet screen. `peer` (if set) is the
 * chat pubkey to send a receipt back to after a successful send.
 */
object PendingPayment {
    @Volatile private var pending: Request? = null

    data class Request(val address: String, val amountSats: Long?, val peer: String? = null)

    fun set(address: String, amountSats: Long?, peer: String? = null) {
        pending = Request(address, amountSats, peer)
    }

    fun consume(): Request? {
        val p = pending
        pending = null
        return p
    }
}

/**
 * A one-shot hand-off from the Wallet screen back to the chat after a successful
 * pay: "post a payment receipt to this peer". Consumed by the chat on resume.
 */
object PendingReceipt {
    @Volatile private var pending: Data? = null

    data class Data(val peer: String, val txid: String, val amountSats: Long?)

    fun set(peer: String, txid: String, amountSats: Long?) { pending = Data(peer, txid, amountSats) }

    fun consume(): Data? {
        val p = pending
        pending = null
        return p
    }
}
