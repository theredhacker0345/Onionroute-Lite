package org.onionroute.lite.service

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import org.onionroute.lite.Constants

/**
 * Data class representing an installed application.
 */
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
    }

    // ── Installed Apps ─────────────────────────────────────────────

    /**
     * Returns a list of all installed internet-capable applications,
     * sorted alphabetically by app name. System apps are placed last.
     */
    fun getInstalledApps(): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()
        val installedPackages = packageManager.getInstalledApplications(0)

        for (appInfo in installedPackages) {
            // Skip our own package — it's always excluded from routing
            if (appInfo.packageName == appContext.packageName) continue

            // Only include apps that declare internet permission
            val hasInternet = appContext.packageManager
                .getPackageInfo(appInfo.packageName, PackageManager.GET_PERMISSIONS)
                ?.requestedPermissions
                ?.any { it == android.Manifest.permission.INTERNET } == true

            if (!hasInternet) continue

            val appName = try {
                appInfo.loadLabel(packageManager).toString()
            } catch (e: Exception) {
                appInfo.packageName
            }

            val icon = try {
                appInfo.loadIcon(packageManager)
            } catch (e: Exception) {
                null
            }

            val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

            apps.add(
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = appName,
                    isSystemApp = isSystemApp,
                    icon = icon
                )
            )
        }

        // Sort: non-system apps first (alphabetically), then system apps (alphabetically)
        return apps.sortedWith(compareBy({ it.isSystemApp }, { it.appName.lowercase() }))
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
