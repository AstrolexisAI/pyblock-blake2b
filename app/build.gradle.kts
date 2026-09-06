import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing — creds live in keystore.properties (gitignored), never in VCS.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.astrolexis.pyblock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.astrolexis.pyblockblake2b"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "0.2.3"
        // Only ship ABIs the native deps (bdk-android / secp256k1-kmp) actually
        // provide, so the APK can't land on a device with no matching .so.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v2 + v3 (same key, no rotation now). v3 enables future keystore rotation via a
                // signing lineage without users losing the app. Verify: apksigner verify -v → v2 & v3 true.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // BETA: minify OFF to guarantee no R8-obfuscation crash of the native
            // wallet bindings (JNA/BDK/secp256k1 ship no consumer proguard rules).
            // Keep rules are in place — re-enable both for the stable release once
            // validated on-device.
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Kotlin 2.3 removed the kotlinOptions DSL — jvmTarget now lives here.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    // Chat image thumbnails/zoom (community-chat photo sharing).
    implementation("io.coil-kt:coil-compose:2.7.0")
    // EXIF orientation so camera photos don't upload sideways.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    // BC-UR (ur:crypto-psbt) for air-gap PSBTs — fountain-coded animated QR,
    // interoperable with the iOS URKit path + Sparrow/Keystone. Pure-JVM; pulls
    // co.nstant.in:cbor transitively.
    implementation("com.sparrowwallet:hummingbird:1.7.4")
    // hummingbird declares cbor as runtime-only; we reference it directly (byte-string
    // decode fallback), so pull it onto the compile classpath explicitly.
    implementation("co.nstant.in:cbor:0.9")
    // Camera QR scanner: CameraX preview/analysis + a Compose overlay (square QR
    // frame, on-brand), decoding frames with zxing core.
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.18.0")
    // Bitcoin Dev Kit (wallet) + bundled Kyoto CBF light client (CbfBuilder/
    // CbfClient/CbfNode) — the on-device node, same UniFFI API as iOS bdk-swift.
    implementation("org.bitcoindevkit:bdk-android:3.0.0")
    implementation("org.unifiedpush.android:connector:3.0.10") {
        // Avoid a duplicate-class clash with tink-android (from security-crypto);
        // the connector uses the same Tink API surface provided by tink-android.
        exclude(group = "com.google.crypto.tink", module = "tink")
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
