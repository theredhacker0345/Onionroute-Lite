package org.onionroute.lite.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.onionroute.lite.Constants

/**
 * Manages switching between launcher icon aliases for camouflage.
 *
 * Inspired by Orbot's disguise feature — allows the user to replace
 * OnionRoute Lite's launcher icon and label with a generic-looking
 * alternative (notes, weather, music, calculator).
 *
 * Only one launcher alias can be enabled at a time. The default
 * icon uses the main MainActivity component directly.
 *
 * Usage:
 *   val switcher = IconSwitcher(context)
 *   switcher.switchTo(IconSwitcher.AppIcon.NOTE)
 *   val current = switcher.getCurrentIcon()
 */
class IconSwitcher(private val context: Context) {

    // ── App Icon Enum ──────────────────────────────────────────
    enum class AppIcon(val alias: String, val label: String, val key: String) {
        DEFAULT(".MainActivity",  "OnionRoute Lite", "default"),
        NOTE(".AliasNote",       "Quick Notes",     "note"),
        WEATHER(".AliasWeather",  "Weather Now",     "weather"),
        MUSIC(".AliasMusic",      "Beat Player",     "music"),
        CALC(".AliasCalc",        "Quick Calc",      "calc");

        /** Full component name including package */
        fun componentName(pkg: String): ComponentName =
            ComponentName(pkg, pkg + alias)
    }

    // ── Switch Icon ────────────────────────────────────────────
    /**
     * Enable the given [icon] alias and disable all others.
     * The change takes effect immediately — the system will
     * update the home screen launcher icon within a few seconds.
     */
    fun switchTo(icon: AppIcon) {
        val pkg = context.packageName
        val pm = context.packageManager

        // Disable all aliases first
        for (entry in AppIcon.entries) {
            val cn = entry.componentName(pkg)
            val newState = if (entry == icon)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            pm.setComponentEnabledSetting(
                cn,
                newState,
                PackageManager.DONT_KILL_APP
            )
        }

        // Persist selection
        savePreference(icon)
    }

    // ── Get Current Icon ───────────────────────────────────────
    /**
     * Returns the currently active [AppIcon] by checking which
     * alias component is enabled, falling back to the saved
     * preference, then DEFAULT.
     */
    fun getCurrentIcon(): AppIcon {
        val pkg = context.packageName
        val pm = context.packageManager

        for (entry in AppIcon.entries) {
            val cn = entry.componentName(pkg)
            val state = pm.getComponentEnabledSetting(cn)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return entry
            }
        }

        // If no alias is explicitly enabled, check preferences
        return readPreference()
    }

    // ── Preference Persistence ─────────────────────────────────
    private fun savePreference(icon: AppIcon) {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(Constants.PREF_APP_ICON, icon.key)
            .apply()
    }

    private fun readPreference(): AppIcon {
        val key = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Constants.PREF_APP_ICON, null)

        return AppIcon.entries.find { it.key == key } ?: AppIcon.DEFAULT
    }
}
