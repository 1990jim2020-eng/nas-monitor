package io.github.nasmonitor.app

import android.content.Context
import com.google.gson.Gson

object StartupOverviewCache {
    private const val PREFS = "hadashboard_overview_snapshot"
    private const val JSON = "overview_json"
    private const val SAVED = "overview_saved_at"
    private const val MAX_AGE = 10 * 60 * 1000L
    private val gson = Gson()

    fun load(context: Context): OverviewResponse? {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(JSON, null)
            if (raw == null) null else gson.fromJson(raw, OverviewResponse::class.java).withActiveServers()
        } catch (_: Exception) { null }
    }

    fun save(context: Context, value: OverviewResponse) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(JSON, gson.toJson(value.withActiveServers())).putLong(SAVED, System.currentTimeMillis()).apply()
    }

    fun isStale(context: Context): Boolean = System.currentTimeMillis() - context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SAVED, 0L) > MAX_AGE
}
