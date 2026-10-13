package io.github.nasmonitor.app

import android.content.Context
import com.google.gson.Gson

data class GatewayCacheSnapshot(
    val savedAt: Long = 0L,
    val overview: NewApiGatewayOverview? = null,
    val affinity: NewApiAffinityState? = null,
    val probe: NewApiProbeResponse? = null,
    val models: List<NewApiModelMeta>? = null,
    val logs: NewApiLogPage? = null,
    val logPage: Int = 0
)

object GatewayCache {
    private const val PREFS = "hadashboard_gateway_snapshot"
    private const val JSON = "snapshot_json"
    private const val VERSION = 1
    private val gson = Gson()

    fun load(context: Context): GatewayCacheSnapshot? = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt("version", 0) != VERSION) null
        else prefs.getString(JSON, null)?.let { gson.fromJson(it, GatewayCacheSnapshot::class.java) }
    } catch (_: Exception) { null }

    fun save(context: Context, snapshot: GatewayCacheSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("version", VERSION)
            .putString(JSON, gson.toJson(snapshot.copy(savedAt = System.currentTimeMillis())))
            .apply()
    }
}
