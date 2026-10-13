package io.github.nasmonitor.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ManagedServer(
    val id: String = "",
    val name: String = "",
    @SerializedName("glances_url") val glancesUrl: String = "",
    @SerializedName("mesh_ip") val meshIp: String = "",
    val deletable: Boolean = true,
    val metrics: ServerStatus = ServerStatus()
)

data class ServerListResponse(val servers: List<ManagedServer> = emptyList())

fun OverviewResponse.serverEntries(): List<ManagedServer> = servers ?: listOf(
    ManagedServer("nas", "NAS", deletable = false, metrics = nas),
    ManagedServer("aliyun", "阿里云 ECS", metrics = aliyun),
    ManagedServer("tencent", "腾讯云服务器", metrics = tencent)
)

fun serverDetailRoute(id: String) = if (id in listOf("nas", "aliyun", "tencent")) id else "server/$id"

fun OverviewResponse.withActiveServers(): OverviewResponse = copy(
    servers = serverEntries().filter { ServerRegistry.contains(it.id) },
    aliyun = if (ServerRegistry.contains("aliyun")) aliyun else ServerStatus(),
    tencent = if (ServerRegistry.contains("tencent")) tencent else ServerStatus(),
    alerts = alerts.filter { ServerRegistry.contains(it.host) }
)

object ServerRegistry {
    private val defaults = listOf(ManagedServer("nas", "NAS", deletable = false),
        ManagedServer("aliyun", "阿里云 ECS"), ManagedServer("tencent", "腾讯云服务器"))
    private val state = MutableStateFlow(defaults)
    val servers = state.asStateFlow()
    private var context: Context? = null
    fun init(context: Context) {
        this.context = context.applicationContext
        val raw = context.getSharedPreferences("monitor_servers", Context.MODE_PRIVATE).getString("servers", null)
        if (raw != null) runCatching { state.value = Gson().fromJson(raw, ServerListResponse::class.java).servers }
    }
    fun name(id: String) = state.value.find { it.id == id }?.name ?: id
    fun host(id: String) = state.value.find { it.id == id }?.meshIp.orEmpty()
    fun contains(id: String) = state.value.any { it.id == id }

    @Synchronized
    fun update(entries: List<ManagedServer>) {
        val ctx = context ?: return
        val removed = state.value.filter { old -> entries.none { it.id == old.id } }
        fun aliases(server: ManagedServer): Set<String> {
            fun saved(key: String, fallback: String) = SecureCredentialStore.get(ctx, "server.${server.id}.$key").ifEmpty { fallback }
            val host = saved("host", server.meshIp)
            val port = saved("port", "22").toIntOrNull() ?: 22
            return buildSet {
                if (host.isNotBlank()) add(SshHostIdentity.name(host, port))
                if (saved("useJump", if (server.id == "nas") "true" else "false") == "true") {
                    add(SshHostIdentity.name(saved("jumpHost", ""), saved("jumpPort", "22").toIntOrNull() ?: 22))
                }
            }
        }
        val retainedAliases = entries.flatMap { aliases(it) }.toSet()
        removed.forEach { server ->
            (aliases(server) - retainedAliases).forEach { SecureCredentialStore.remove(ctx, "ssh.hostkey.$it") }
            SecureCredentialStore.removePrefix(ctx, "server.${server.id}.")
            DataCache.removeServer(server.id)
        }
        if (removed.isNotEmpty()) {
            DataCache.invalidate("overview")
            DataCache.invalidate("nas_detail")
            PreferenceHelper.setIgnoredAlertSignature(ctx, "")
            ctx.getSharedPreferences("hadashboard_overview_snapshot", Context.MODE_PRIVATE).edit().clear().apply()
        }
        state.value = entries.map { it.copy(metrics = ServerStatus()) }
        ctx.getSharedPreferences("monitor_servers", Context.MODE_PRIVATE).edit()
            .putString("servers", Gson().toJson(ServerListResponse(state.value))).apply()
    }
}
