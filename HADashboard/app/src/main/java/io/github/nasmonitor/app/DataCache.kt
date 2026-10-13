package io.github.nasmonitor.app

import android.content.Context
import com.google.gson.Gson

object DataCache {
    private const val PREFS = "monitor_data_cache"
    private const val JSON_PREFIX = "json_"
    private const val TIME_PREFIX = "time_"
    private val map = java.util.concurrent.ConcurrentHashMap<String, Pair<Any, Long>>()
    private val removedServers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val gson = Gson()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun <T> get(key: String): T? = @Suppress("UNCHECKED_CAST") (map[key]?.first as? T)

    fun <T> get(key: String, type: Class<T>): T? {
        val memory = map[key]?.first
        if (memory != null) return memory as? T
        val p = prefs ?: return null
        val raw = p.getString(JSON_PREFIX + key, null) ?: return null
        return try {
            val value = gson.fromJson(raw, type)
            val savedAt = p.getLong(TIME_PREFIX + key, 0L)
            if (value != null) map[key] = Pair(value as Any, savedAt)
            value
        } catch (_: Exception) {
            null
        }
    }

    fun set(key: String, data: Any) {
        if (removedServers.any { key == "${it}_detail" || key == "security_$it" }) return
        val value = if (data is OverviewResponse) data.withActiveServers() else data
        val now = System.currentTimeMillis()
        map[key] = Pair(value, now)
        prefs?.edit()
            ?.putString(JSON_PREFIX + key, gson.toJson(value))
            ?.putLong(TIME_PREFIX + key, now)
            ?.apply()
    }

    fun savedAt(key: String): Long = map[key]?.second
        ?: prefs?.getLong(TIME_PREFIX + key, 0L)
        ?: 0L

    fun isStale(key: String, maxAgeMs: Long = 10000): Boolean {
        val saved = savedAt(key)
        return saved == 0L || System.currentTimeMillis() - saved > maxAgeMs
    }

    fun invalidate(key: String) {
        map.remove(key)
        prefs?.edit()?.remove(JSON_PREFIX + key)?.remove(TIME_PREFIX + key)?.apply()
    }

    @Synchronized
    fun removeServer(id: String) {
        removedServers.add(id)
        val keys = map.keys.toSet() + prefs?.all.orEmpty().keys.map { it.removePrefix(JSON_PREFIX).removePrefix(TIME_PREFIX) }
        keys.filter { it.split('_', '/', ':').contains(id) || it.startsWith("${id}_") || it.endsWith("_$id") || it.contains("_${id}_") }
            .forEach { invalidate(it) }
    }
}
