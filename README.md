# PyBLØCK ᛒ — BLAKE2b wallet (Android)

A self-custody Bitcoin wallet for the **BLAKE2b** proof-of-work fork, plus live network stats and a
community layer. Native Android (Kotlin + Jetpack Compose), non-custodial: keys are generated and
held **on device**.

> This is the dedicated **BLAKE2b** app (`com.astrolexis.pyblockblake2b`). The Bitcoin (SHA-256) app
> is a separate project.

## Features
- **Wallet** — on-device keys (Android Keystore–backed encryption), spendable vs replay-locked
  balance, coin control, a 4-step Send wizard, coordinator-free **Ricochet**, and **PayNym** (BIP-47).
- **Network** — live POOL and CHIRP stats, mined blocks, a block-participation view.
- **Community chat** — a shared, moderated Nostr channel (report/block), plus encrypted DMs.
- **Sovereignty** — your keys never leave the device; the wallet signs locally (bdk-ffi). Optional
  Lightning purchases are **server-mediated**: the app only receives BOLT11 invoices / LNURL from the
  server and shows the QR — it never holds any Lightning node credentials.

## Build & reproducibility
Standard Gradle build:
```
./gradlew clean assembleRelease
```
The full toolchain pins and a byte-for-byte verification recipe (apksigcopier + diffoscope, against
the published APK) are in **[README-REPRODUCIBLE.md](README-REPRODUCIBLE.md)**. Every dependency —
including the prebuilt native libraries from Maven — is pinned by SHA-256 in
`gradle/verification-metadata.xml`.

Releases are signed with **APK Signature Scheme v2 + v3**. Certificate SHA-256:
`e86002aa3ac72325099f92065ec8ab3b7adc70db9e74514ebd53c78acdba3fb5`
(`apksigner verify -v --print-certs`).

## License
[Apache License 2.0](LICENSE) · Copyright 2026 AstroLexis LLC.
