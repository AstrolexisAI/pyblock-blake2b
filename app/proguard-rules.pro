# kotlinx.serialization — keep serializers
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.astrolexis.pyblock.data.model.** { *; }
-keepclassmembers class ** { @kotlinx.serialization.SerialName <fields>; }
# @Serializable data classes outside data.model (wallet + nostr models)
-keep @kotlinx.serialization.Serializable class com.astrolexis.pyblock.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Native bindings (fund-critical: wallet + vanity crypto) ---
# These libraries ship NO consumer proguard rules, so R8 would otherwise
# obfuscate/strip their JNI/JNA classes and crash on load.

# BitcoinDevKit (UniFFI over JNA) — the on-device CBF wallet node
-keep class org.bitcoindevkit.** { *; }
-dontwarn org.bitcoindevkit.**

# JNA — reflection + native method mapping; must be kept fully
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# secp256k1-kmp (ACINQ, JNI) — vanity/key crypto + NIP-44/Nostr signing
-keep class fr.acinq.secp256k1.** { *; }
-dontwarn fr.acinq.secp256k1.**

# Retrofit 2.11 + suspend functions — preserve generic signatures & annotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Tink / androidx.security-crypto (EncryptedSharedPreferences) — optional
# errorprone annotations not on the runtime classpath
-dontwarn com.google.errorprone.annotations.**
