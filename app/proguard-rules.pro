# OnionRoute Lite ProGuard / R8 Rules
#
# OPTIMIZATION NOTES:
# - AGP 8.2 + AAPT automatically generate keep rules for every manifest-
#   referenced component (activities, services, receivers, tile services),
#   so explicit -keep rules for those classes are NOT needed.
# - The previous revision blanket-kept org.onionroute.lite.service.**,
#   .ui.**, MainActivity, Constants, etc. with { *; } — that disabled
#   R8 shrinking AND obfuscation for essentially the whole app package
#   (hundreds of KB of dead weight in the release APK).
# - The app performs NO reflection on its own classes (verified: only
#   java.lang.Process#pid via getMethod, a framework class), so all app
#   code can be safely shrunk/renamed.

# ── Native method names (JNI) ─────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── hev-socks5-tunnel JNI bridge ──────────────────────────────
# This class is resolved BY NAME from native code, not from Kotlin.
#
# libtun2socks.so's JNI_OnLoad does:
#     FindClass("org/onionroute/lite/tor/Tun2SocksNative")
#     RegisterNatives(klass, {TProxyStartService, TProxyStopService, TProxyGetStats})
# The lookup string is compiled into the .so at build time via
# -DPKGNAME/-DCLSNAME in scripts/build-tun2socks.sh, so R8 cannot see the
# dependency. If this class is renamed or stripped, FindClass returns null and
# RegisterNatives dereferences it -> immediate native crash inside
# System.loadLibrary, with no Java stack to explain it.
#
# The generic `native <methods>` rule above happens to cover this today, but the
# failure mode is a native crash rather than a build error, so the dependency
# is stated explicitly instead of left implicit.
-keep class org.onionroute.lite.tor.Tun2SocksNative { *; }

# ── Kotlin coroutines ─────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Aggressive optimization for smaller APK ───────────────────
# Strip verbose logging from release builds (d/v/i only; w/e kept for
# field diagnostics which matter for a Tor client).
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Keep crash-report stack traces readable without keeping full metadata.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Suppress warnings ─────────────────────────────────────────
-dontwarn kotlinx.coroutines.**
