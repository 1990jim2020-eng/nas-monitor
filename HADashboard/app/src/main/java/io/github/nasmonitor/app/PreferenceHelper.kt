package io.github.nasmonitor.app

import android.content.Context

object PreferenceHelper {
    private const val NAME = "hadashboard_prefs"
    private const val KEY_REFRESH = "refresh_seconds"
    private const val KEY_THEME = "theme"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_IGNORED_ALERT_SIGNATURE = "ignored_alert_signature"
    private const val KEY_ACCENT = "accent_color"

    fun getRefreshInterval(context: Context): Int {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_REFRESH, 5)
    }

    fun setRefreshInterval(context: Context, seconds: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(KEY_REFRESH, seconds).apply()
    }

    fun getTheme(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_THEME, "system") ?: "system"

    fun setTheme(context: Context, theme: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_THEME, theme).apply()
    }

    fun getApiToken(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_API_TOKEN, "") ?: ""

    fun setApiToken(context: Context, token: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_API_TOKEN, token.trim()).apply()
    }

    fun clearApiToken(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY_API_TOKEN).apply()
    }

    fun getServerUrl(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_SERVER_URL, "") ?: ""

    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_SERVER_URL, url.trim()).apply()
    }

    fun clearServerUrl(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY_SERVER_URL).apply()
    }

    fun getIgnoredAlertSignature(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_IGNORED_ALERT_SIGNATURE, "") ?: ""

    fun setIgnoredAlertSignature(context: Context, signature: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_IGNORED_ALERT_SIGNATURE, signature).apply()
    }

    fun getAccentColor(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_ACCENT, "#5B7CFA") ?: "#5B7CFA"

    fun setAccentColor(context: Context, hex: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_ACCENT, hex).apply()
    }
}
