package com.iqoo.neo10.chargemonitor.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object PrefUtil {
    private const val PREFS_NAME = "charge_monitor_prefs"
    private const val KEY_DARK_THEME = "dark_theme"

    fun isDarkTheme(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DARK_THEME, true) // 默认黑色主题
    }

    fun setDarkTheme(context: Context, dark: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DARK_THEME, dark).apply()
    }

    fun applyTheme(context: Context) {
        val dark = isDarkTheme(context)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
