package com.example.myapplication

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 深浅色外观（持久化 + 与后续主题色切换配合：accent 仅引用 colors 中 ui_accent / ui_accent_soft）。
 */
object ThemeAppearance {

    const val PREFS_NAME = "app_prefs"
    const val KEY_UI_APPEARANCE_MODE = "ui_appearance_mode"

    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"
    const val MODE_SYSTEM = "system"

    fun applyFromPrefs(context: Context) {
        val mode = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_UI_APPEARANCE_MODE, null)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun persistMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UI_APPEARANCE_MODE, mode)
            .apply()
    }

    /** 在「当前实际深浅」之间切换，并固定为手动浅色/深色（不再跟随系统）。 */
    fun toggleLightDark(context: Context) {
        val mask = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val uiDark = mask == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val newMode = if (uiDark) MODE_LIGHT else MODE_DARK
        persistMode(context, newMode)
        AppCompatDelegate.setDefaultNightMode(
            if (newMode == MODE_LIGHT) AppCompatDelegate.MODE_NIGHT_NO
            else AppCompatDelegate.MODE_NIGHT_YES
        )
    }
}
