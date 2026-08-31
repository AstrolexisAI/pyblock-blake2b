package com.astrolexis.pyblock

import android.app.Application
import com.astrolexis.pyblock.data.crypto.MessageSign
import com.astrolexis.pyblock.data.crypto.PaymentCode
import com.astrolexis.pyblock.data.crypto.VaultCrypto
import com.astrolexis.pyblock.data.store.AddressStore
import com.astrolexis.pyblock.data.store.AuthStore
import com.astrolexis.pyblock.data.store.DefenderStore
import com.astrolexis.pyblock.data.store.DeviceStore
import com.astrolexis.pyblock.data.store.LivesStore
import com.astrolexis.pyblock.data.store.ThemeStore

/** Application entry point. */
class PyBlockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Fail-closed: BIP-47 PayNym derivation must agree send-side vs receive-side,
        // else a payment lands on an address nobody can spend. Refuse to run if not.
        check(PaymentCode.selfTest(this)) { "PaymentCode (BIP-47) self-test failed — refusing to run" }
        // Message signing must match an independent (RFC6979) reference vector, or the
        // signatures we emit won't verify in Electrum/Sparrow/Bither. Fail-closed.
        check(MessageSign.selfTest()) { "MessageSign self-test failed — refusing to run" }
        // Spending-password vault: scrypt must match RFC 7914 and AES-GCM must round-trip,
        // or we could encrypt keys we can't recover. Fail-closed.
        check(VaultCrypto.selfTest()) { "VaultCrypto self-test failed — refusing to run" }
        AddressStore.init(this)
        com.astrolexis.pyblock.data.wallet.WalletVault.init(this)   // learn if a spending password is set
        ThemeStore.init(this)
        com.astrolexis.pyblock.data.store.ChainStore.init(this)
        com.astrolexis.pyblock.data.store.ArcadeStore.init(this)
        com.astrolexis.pyblock.data.store.ThermalStore.init(this)
        DeviceStore.init(this)
        AuthStore.init(this)
        LivesStore.init(this)
        DefenderStore.init(this)
        // Wipe any leftover exported paper-backup PDF (contains plaintext WIFs)
        // so it never survives past the session that created it.
        com.astrolexis.pyblock.data.wallet.BackupPdf.cleanup(this)
    }
}
