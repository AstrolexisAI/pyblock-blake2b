package com.astrolexis.pyblock.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** POST /api/devices.php — UnifiedPush registration (sovereign push). */
@Serializable
data class DeviceRegPush(
    val platform: String = "android",
    val bundle: String = "com.astrolexis.pyblock",
    @SerialName("push_provider") val pushProvider: String = "unifiedpush",
    val endpoint: String = "",
    // Chat identity so the server can route "you got a DM" pushes
    // (kind-4 p-tag → device). Empty clears the npub↔device mapping.
    @SerialName("nostr_pubkey") val nostrPubkey: String = "",
    val preferences: Map<String, Boolean> = mapOf(
        "lotto_block" to true, "bip110_block" to true, "my_address" to true,
        // DMs are pushed NATIVELY on Android (DmPushService keeps its own relay
        // link) — opt out of server-side DM push to avoid double notifications.
        // nostr_pubkey still travels: the whale-lounge whitelist needs it.
        "nostr_dm" to false,
    ),
)

/** GET /api/app/android_version.php — sideloaded APK update check. */
@Serializable
data class AndroidVersion(
    val ok: Boolean = false,
    val available: Boolean = false,
    @SerialName("latest_version_code") val latestVersionCode: Int = 0,
    @SerialName("latest_version_name") val latestVersionName: String = "",
    @SerialName("apk_url") val apkUrl: String = "",
    val sha256: String = "",
    @SerialName("min_supported_version_code") val minSupportedVersionCode: Int = 0,
    val changelog: String = "",
)
