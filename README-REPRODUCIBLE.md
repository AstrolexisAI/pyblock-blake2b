# Reproducible build — PyBLØCK Android

This recipe lets anyone rebuild the published APKs and confirm they match what we ship. Applies to
both apps — **PyBLØCK** (`com.astrolexis.pyblock`) and **PyBLØCK ᛒ / BLAKE2b**
(`com.astrolexis.pyblockblake2b`). Same toolchain for both.

## 1. What we do and do NOT compile
- The Android projects are **pure JVM/Kotlin + Android Gradle Plugin**. No Rust, no NDK, no
  `Cargo.toml`, no `.so` are compiled or committed here.
- The native libraries (`libbdkffi`, `libsecp256k1-jni`) come **prebuilt from Maven** as immutable
  released artifacts:
  - `org.bitcoindevkit:bdk-android:3.0.0`
  - `fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.18.0`
  Their byte-for-byte reproducibility is an **upstream** concern (Bitcoin Dev Kit / ACINQ). We pin
  them to an exact version and **verify each artifact by SHA-256** (see §3).

## 2. Toolchain pins (identical for both apps)
| Component | Version |
|---|---|
| JDK (build) | Temurin/OpenJDK **17.0.20** |
| Android Gradle Plugin | **8.7.3** |
| Gradle (wrapper) | **8.11.1** |
| Kotlin (android + compose + serialization plugins) | **2.3.10** |
| compileSdk / targetSdk | **35** / **35** · build-tools **35.0.0** (AGP 8.7.3 default) |
| Jetpack Compose | BOM **2024.12.01** |
| Packaged ABIs | arm64-v8a, armeabi-v7a, x86_64 |
| R8 / minify | **off** (`isMinifyEnabled = false`) — no obfuscation variance |

## 3. Dependency pinning (supply chain)
`gradle/verification-metadata.xml` records the **SHA-256 of every resolved artifact**, including the
prebuilt native `.so` from the Maven dependencies above. Gradle verifies these on every build; a
mismatch fails the build. Regenerate/inspect with:
```
./gradlew --write-verification-metadata sha256 assembleRelease
```

## 4. Build
```
./gradlew clean assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```
(A keystore is only needed to *sign*; the unsigned APK content is what you compare.)

## 5. Verify against the published APK (for auditors)
The signing block differs by signer by design (v2 + v3 schemes, our key), so **compare the UNSIGNED
APK**:
```
apksigcopier extract published.apk sig.zip     # or strip the v2/v3 block
diffoscope your-unsigned.apk published-unsigned.apk
```
Do **not** report "does not reproduce" because of the signature — only the signed block should differ.
Goal: byte-identical unsigned APK given the pins in §2 + the same Maven artifacts (verified by §3).

## 6. Signing (published APKs)
Signed with **APK Signature Scheme v2 + v3** (same key; v3 enables future key rotation via a lineage
without users reinstalling). Certificate SHA-256 digests (unchanged across releases):
- PyBLØCK (main): `e80718578e4a6671c16a5e9b6f28e6d8fb6c362aa3cbeb2b87507429bf3cb1d6`
- PyBLØCK ᛒ (BLAKE2b): `e86002aa3ac72325099f92065ec8ab3b7adc70db9e74514ebd53c78acdba3fb5`
Verify: `apksigner verify -v --print-certs the.apk` → v2 true, v3 true, digest as above.

Licensed under the Apache License 2.0 (see LICENSE). Copyright 2026 AstroLexis LLC.
