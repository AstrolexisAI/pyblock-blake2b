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
        // NIP-44 (ChaCha20 vs RFC 8439, conversation key vs the spec vector) is what stands between a
        // DM and being readable by the relay. Its self-test existed and was only ever called from a
        // screen this app never shows. Fail-closed, like the vault.
        check(com.astrolexis.pyblock.data.crypto.Nip44.selfTest()) { "NIP-44 self-test failed — refusing to run" }
        // Event verification: a verifier that says no to everything empties the chat, and one that
        // says yes to everything is not a verifier. Sign, verify, tamper, verify again.
        check(com.astrolexis.pyblock.data.nostr.Nostr.selfTestVerify(this)) { "Nostr verify self-test failed — refusing to run" }
        // The DM archive depends on the Keystore, which can be unavailable during direct boot —
        // a failure here must degrade (the relay still has the recent window), never crash.
        if (!com.astrolexis.pyblock.data.nostr.DMArchive.selfTest(this))
            android.util.Log.w("PyBLOCKchat", "DM archive round-trip failed — conversations will not persist this session")
        com.astrolexis.pyblock.data.store.AppStrings.init(this)
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
