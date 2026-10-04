package org.onionroute.lite.service

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.runtime.Immutable
import org.onionroute.lite.Constants

/**
 * Data class representing an installed application.
 *
 * @Immutable so Compose treats it as skippable. Without it, [Drawable] has no
 * stability contract, the compiler infers this class as unstable, and AppRow
 * re-executes on every LazyColumn scroll/recycle/re-measure — with three
 * TextStyle copies, three RoundedCornerShapes and two Color copies each time.
 * Every field is a `val` assigned once at construction, so the annotation is
 * truthful.
 */
@Immutable
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val icon: Drawable?
)

/**
 * Routing mode for per-app VPN configuration.
 *
 * [ALL] — Route all apps through Tor, exclude selected apps.
 * [SELECTED_ONLY] — Only route selected apps through Tor.
 */
enum class RoutingMode {
    ALL,
    SELECTED_ONLY
}

/**
 * Manages which apps should be routed through the Tor VPN.
 *
 * Persists selections in SharedPreferences. Two modes are supported:
 * - **ALL**: All apps are routed through Tor; specific apps can be excluded.
 * - **SELECTED_ONLY**: Only explicitly selected apps are routed through Tor.
 *
 * The app's own package is always excluded from routing to prevent
 * the VPN from recursing on itself.
 */
class AppSelectionManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    private val packageManager: PackageManager = appContext.packageManager

    companion object {
        private const val TAG = "AppSelectionManager"

        /** Fixed raster size for cached app icons (see [loadBoundedIcon]). */
        private const val ICON_CACHE_PX = 96
    }

    // ── Installed Apps ─────────────────────────────────────────────

    /**
     * Returns a list of all installed internet-capable applications,
     * sorted alphabetically by app name. System apps are placed last.
     */
    fun getInstalledApps(): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()
        val internetPermission = android.Manifest.permission.INTERNET

        // One transaction for the full package list AND their permissions.
        //
        // GET_PERMISSIONS is load-bearing, not decorative: without it
        // `requestedPermissions` is null on every PackageInfo, the INTERNET
        // check below can never be true, and the app list comes back EMPTY.
        val installedPackages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        for (packageInfo in installedPackages) {
            val appInfo = packageInfo.applicationInfo ?: continue

            // Skip our own package — it's always excluded from routing
            if (appInfo.packageName == appContext.packageName) continue

            // Only include apps that declare internet permission. The array is
            // present because GET_PERMISSIONS was requested above.
            val hasInternet = packageInfo.requestedPermissions?.any { it == internetPermission } == true
            if (!hasInternet) continue

            val appName = try {
                appInfo.loadLabel(packageManager).toString()
            } catch (e: Exception) {
                appInfo.packageName
            }

            val icon = loadBoundedIcon(appInfo)

            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            apps.add(
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = appName,
                    isSystemApp = isSystemApp,
                    icon = icon
                )
            )
        }

        // Sort: non-system apps first (alphabetically), then system apps.
        //
        // A Collator computes one sort key per app. The previous
        // `compareBy { it.appName.lowercase() }` allocated a case-folded key per
        // COMPARISON instead — O(n log n) strings rather than n — and
        // ignoreCase matching does char-by-char regionMatches with no fast
        // path. Collator.PRIMARY is also locale-correct, so accented app names
        // sort where a user of that locale expects.
        val collator = java.text.Collator.getInstance().apply {
            strength = java.text.Collator.PRIMARY
        }
        return apps.sortedWith(
            compareBy<AppInfo>({ it.isSystemApp }, { collator.getCollationKey(it.appName) })
        )
    }

    /**
     * Loads an app icon RASTERIZED TO A FIXED SIZE.
     *
     * LOW-RAM: [PackageManager.loadIcon] returns full-resolution adaptive
     * launcher icons — up to 432px bitmaps on modern apps. Holding one per
     * installed app (often 150-400 apps) in Compose state cost 30-80 MB of
     * heap on the 1.5GB-RAM devices this app targets. The list renders
     * icons at ~36dp, so anything beyond ~96px is wasted pixels: we draw
     * each icon once into a 96x96 bitmap, bounding the whole list to
     * ~15 MB worst case regardless of source resolution.
     *
     * ONE BINDER CALL, NOT N
     * ---------------------
     * The list used to be built from getInstalledApplications(0) plus a
     * per-package getPackageInfo(pkg, GET_PERMISSIONS) to test for INTERNET.
     * Each of those is an IPC round trip into PackageManagerService, so a
     * device with 300 installed apps paid 300 binder transactions just to
     * populate this list. getInstalledPackages(GET_PERMISSIONS) returns the
     * permission array for every package in a single transaction.
     */
    private fun loadBoundedIcon(appInfo: ApplicationInfo): Drawable? {
        val raw: Drawable = try {
            appInfo.loadIcon(packageManager) ?: return null
        } catch (e: Exception) {
            return null
        }
        return try {
            val size = ICON_CACHE_PX
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            raw.setBounds(0, 0, size, size)
            raw.draw(canvas)
            BitmapDrawable(appContext.resources, bitmap)
        } catch (e: Exception) {
            // Rasterization failed. Deliberately return null rather than `raw`.
            //
            // The old fallback handed back the original icon — exactly the
            // full-resolution drawable this method exists to avoid holding — and
            // it failed under OutOfMemoryError, the one moment when keeping a
            // large drawable is least affordable. One un-bounded icon can be
            // ~750 KB of ARGB_8888, so a single fallback per app would restore
            // the entire 30-80 MB regression this function was written to fix.
            //
            // null is safe: AppSelectionScreen renders the app's initial letter
            // in a rounded avatar when the icon is absent, so the row still
            // reads correctly and nothing crashes.
            Log.w(TAG, "Icon rasterization failed for ${appInfo.packageName}; " +
                "using letter avatar")
            null
        }
    }

    // ── Routing Mode ───────────────────────────────────────────────

    /**
     * Set the routing mode: [RoutingMode.ALL] or [RoutingMode.SELECTED_ONLY].
     */
    fun setMode(mode: RoutingMode) {
        prefs.edit()
            .putString(Constants.PREF_APP_ROUTING_MODE, mode.name)
            .apply()
    }

    /**
     * Get the current routing mode. Defaults to [RoutingMode.ALL].
     */
    fun getRoutingMode(): RoutingMode {
        val name = prefs.getString(Constants.PREF_APP_ROUTING_MODE, null)
        return try {
            name?.let { RoutingMode.valueOf(it) } ?: RoutingMode.ALL
        } catch (e: IllegalArgumentException) {
            RoutingMode.ALL
        }
    }

    // ── App Selection ──────────────────────────────────────────────

    /**
     * Determine whether the given app should be routed through Tor,
     * based on the current routing mode and stored selections.
     */
    fun isAppTorRouted(packageName: String): Boolean {
        return when (getRoutingMode()) {
            RoutingMode.ALL -> {
                // In ALL mode, all apps are routed unless explicitly excluded
                val excluded = getExcludedApps()
                packageName !in excluded
            }
            RoutingMode.SELECTED_ONLY -> {
                // In SELECTED_ONLY mode, only selected apps are routed
                val selected = getSelectedApps()
                packageName in selected
            }
        }
    }

    /**
     * Set whether the given app should be routed through Tor.
     *
     * In [RoutingMode.ALL] mode, this adds/removes the app from the excluded set.
     * In [RoutingMode.SELECTED_ONLY] mode, this adds/removes the app from the selected set.
     */
    fun setAppTorRouted(packageName: String, routed: Boolean) {
        when (getRoutingMode()) {
            RoutingMode.ALL -> {
                val excluded = getExcludedApps().toMutableSet()
                if (routed) {
                    excluded.remove(packageName)
                } else {
                    excluded.add(packageName)
                }
                prefs.edit()
                    .putStringSet(Constants.PREF_EXCLUDED_APPS, excluded)
                    .apply()
            }
            RoutingMode.SELECTED_ONLY -> {
                val selected = getSelectedApps().toMutableSet()
                if (routed) {
                    selected.add(packageName)
                } else {
                    selected.remove(packageName)
                }
                prefs.edit()
                    .putStringSet(Constants.PREF_SELECTED_APPS, selected)
                    .apply()
            }
        }
    }

    /**
     * Returns the set of package names explicitly selected for routing
     * (used in [RoutingMode.SELECTED_ONLY] mode).
     */
    fun getSelectedApps(): Set<String> {
        return prefs.getStringSet(Constants.PREF_SELECTED_APPS, emptySet()) ?: emptySet()
    }

    /**
     * Returns the set of package names explicitly excluded from routing
     * (used in [RoutingMode.ALL] mode).
     */
    fun getExcludedApps(): Set<String> {
        return prefs.getStringSet(Constants.PREF_EXCLUDED_APPS, emptySet()) ?: emptySet()
    }
}
