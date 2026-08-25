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
