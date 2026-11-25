package com.sleepmate.app

import android.content.Context

/**
 * Stores the package names explicitly selected by the user for SleepGuard protection.
 * An empty selection means that no third-party media app is protected.
 */
object MediaAppPreferences {
    private const val PREFS_NAME = "sleepguard_media_apps"
    private const val KEY_SELECTED_PACKAGES = "selected_packages"

    fun getSelectedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_SELECTED_PACKAGES, emptySet())
            ?.toSet()
            ?: emptySet()

    fun saveSelectedPackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SELECTED_PACKAGES, packages.toSet())
            .apply()
    }
}
