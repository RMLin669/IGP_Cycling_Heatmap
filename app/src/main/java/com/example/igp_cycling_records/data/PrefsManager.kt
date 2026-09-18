package com.example.igp_cycling_heatmap.data

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "igp_cycling_heatmap"
        private const val KEY_TOKEN = "igpsport_token"
        private const val KEY_USERNAME = "igpsport_username"
        private const val KEY_DARK_MAP = "dark_map"
        private const val KEY_SINGLE_COLOR_MODE = "single_color_mode"
        private const val KEY_SINGLE_ROUTE_COLOR = "single_route_color"
        private const val KEY_HEAT_VISIBLE = "heat_visible"
        private const val DEFAULT_SINGLE_ROUTE_COLOR = 0xFFE53935.toInt()
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLogin(token: String, username: String?) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username ?: "用户")
            .apply()
    }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)
    fun username(): String = prefs.getString(KEY_USERNAME, "") ?: ""
    fun isLoggedIn(): Boolean = !token().isNullOrBlank()

    fun isDarkMap(): Boolean = prefs.getBoolean(KEY_DARK_MAP, true)

    fun isSingleColorMode(): Boolean = prefs.getBoolean(KEY_SINGLE_COLOR_MODE, false)

    fun singleRouteColor(): Int = prefs.getInt(
        KEY_SINGLE_ROUTE_COLOR,
        DEFAULT_SINGLE_ROUTE_COLOR,
    )

    fun isHeatVisible(): Boolean = prefs.getBoolean(KEY_HEAT_VISIBLE, true)

    fun saveMapStyle(dark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_MAP, dark).apply()
    }

    fun saveSingleColorMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SINGLE_COLOR_MODE, enabled).apply()
    }

    fun saveSingleRouteColor(color: Int) {
        prefs.edit().putInt(KEY_SINGLE_ROUTE_COLOR, color).apply()
    }

    fun saveHeatVisible(visible: Boolean) {
        prefs.edit().putBoolean(KEY_HEAT_VISIBLE, visible).apply()
    }

    fun clearLogin() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USERNAME).apply()
    }
}
