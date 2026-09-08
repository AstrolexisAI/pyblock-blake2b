package com.astrolexis.pyblock

import com.astrolexis.pyblock.data.crypto.PaymentCode
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BIP-47 correctness gates, runnable off-device.
 *
 * These same functions run inside `check()` at app start, so a regression here is a crash on
 * launch. Having them as JVM tests means the failure is caught at build time instead, and means
 * the vectors can be verified on a machine with no Android device attached.
 *
 * Only the Context-free gates live here; the notification SEND round trip needs the on-device
 * identity store and stays in the startup check.
 */
class PaymentCodeVectorsTest {

    /** Paper-backup key: round trip, whitespace tolerance, tamper rejection, cross-platform vector. */
    @Test fun identityKeyRoundTripsAndRejectsTampering() =
        assertTrue(PaymentCode.identityKeySelfTest())

    /** Legacy vectors must never move (coins sit at those addresses) and must differ from spec ones. */
    @Test fun bothSchemesMatchTheirPinnedVectors() =
        assertTrue(PaymentCode.schemeVectorSelfTest())

    /** The official BIP-47 vectors, through the production send AND receive paths. */
    @Test fun interopWithTheOfficialBip47Vectors() =
        assertTrue(PaymentCode.interopTestBIP47())

    /** Notification unblinding against the official vector tx. */
    @Test fun notificationUnblindMatchesTheOfficialVector() =
        assertTrue(PaymentCode.notificationSelfTest())
}
