# OnionRoute Lite ProGuard / R8 Rules
# Aggressive optimization for minimal APK size

# ── Native method names (JNI) ─────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Service classes (AndroidManifest references) ───────────────
-keep class org.onionroute.lite.service.** { *; }

# ── BroadcastReceiver ─────────────────────────────────────────
-keep class org.onionroute.lite.service.BootReceiver { *; }

# ── Constants (accessed via reflection by Android framework) ───
-keep class org.onionroute.lite.Constants { *; }

# ── MainActivity (referenced from manifest) ───────────────────
-keep class org.onionroute.lite.MainActivity { *; }

# ── KillSwitch ────────────────────────────────────────────────
-keep class org.onionroute.lite.KillSwitch { *; }

# ── Tile Services (referenced from manifest) ──────────────────
-keep class org.onionroute.lite.service.ConnectTileService { *; }
-keep class org.onionroute.lite.service.RefreshTileService { *; }

# ── IconSwitcher (uses PackageManager reflection) ─────────────
-keep class org.onionroute.lite.util.IconSwitcher { *; }
-keep class org.onionroute.lite.util.IconSwitcher$AppIcon { *; }

# ── AppSelectionManager ───────────────────────────────────────
-keep class org.onionroute.lite.service.AppSelectionManager { *; }
-keep class org.onionroute.lite.service.AppSelectionManager$* { *; }

# ── WebTunnelConfig ───────────────────────────────────────────
-keep class org.onionroute.lite.tor.WebTunnelConfig { *; }

# ── Compose / UI ──────────────────────────────────────────────
-keep class org.onionroute.lite.ui.** { *; }
-keepclassmembers class org.onionroute.lite.ui.** {
    *;
}

# ── Kotlin coroutines ─────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Kotlin reflective access ──────────────────────────────────
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ── AndroidX / Jetpack ───────────────────────────────────────
-keep class * extends androidx.lifecycle.LifecycleService { *; }
-dontwarn androidx.lifecycle.**

# ── Compose ───────────────────────────────────────────────────
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ── Aggressive optimization for smaller APK ───────────────────
# Remove all logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Strip debug info from all classes
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Suppress warnings ─────────────────────────────────────────
-dontwarn org.onionroute.lite.**
-dontwarn kotlinx.coroutines.**
