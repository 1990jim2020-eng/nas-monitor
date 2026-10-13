package io.github.nasmonitor.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.navigation.NavHostController
import io.github.nasmonitor.app.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun Gauge(value: Double, label: String, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = value.toFloat().coerceIn(0f, 100f),
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "gauge"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            Canvas(Modifier.size(48.dp)) {
                drawArc(Track, startAngle = 135f, sweepAngle = 270f, useCenter = false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                drawArc(if (value > 80) Red else if (value > 60) Amber else Green, startAngle = 135f, sweepAngle = 270f * animated / 100f, useCenter = false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            }
            Text("${animated.toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (value >= 80) Red else if (value >= 60) Amber else Green)
        }
        Text(label, fontSize = TextTiny, color = Muted)
    }
}

private fun compactUptime(raw: String): String {
    val days = Regex("(\\d+)\\s*days?").find(raw)?.groupValues?.getOrNull(1)
    return if (days != null) "${days}天" else raw.take(8)
}

@Composable
fun ServerHeader(name: String, meshIp: String, uptime: String, online: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ServerIllustration(height = 42)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = TextH2, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.width(8.dp))
                StatusBadge(if (online) "在线" else "离线", if (online) "ok" else "err")
            }
            Spacer(Modifier.height(2.dp))
            Text("组网 IP：$meshIp", fontSize = TextCaption, color = Muted, maxLines = 1)
        }
        Text(compactUptime(uptime), fontSize = 11.sp, color = Subtle)
    }
}

@Composable
private fun PublicIpInline(deviceName: String, publicIp: String?) {
    if (publicIp.isNullOrBlank()) return
    var ipVisible by rememberSaveable(publicIp) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("公网 IP", fontSize = TextTiny, color = Muted)
        Spacer(Modifier.width(4.dp))
        if (ipVisible) {
            Text(publicIp, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = Ink, maxLines = 1)
        } else {
            Text("已隐藏", fontSize = 10.sp, color = Subtle)
        }
        TextButton(
            onClick = { ipVisible = !ipVisible },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            modifier = Modifier
                .heightIn(min = 24.dp)
                .semantics {
                    contentDescription = if (ipVisible) "隐藏${deviceName}公网 IP" else "显示${deviceName}公网 IP"
                    stateDescription = if (ipVisible) "已显示" else "已隐藏"
                }
        ) { Text(if (ipVisible) "隐藏" else "显示", fontSize = 10.sp) }
    }
}

@Composable
fun StatLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = Muted); Spacer(Modifier.width(4.dp))
        Text(value, fontSize = 12.sp, color = Ink, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UsageMetricTile(label: String, value: Double, modifier: Modifier = Modifier) {
    val accent = usageColor(value)
    Column(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(usageSoft(value)).padding(10.dp)
    ) {
        Text("${value.toInt()}%", fontSize = TextH2, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = TextTiny, color = Muted)
        Spacer(Modifier.height(7.dp))
        LinearProgressIndicator(
            progress = (value / 100.0).toFloat().coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)),
            color = accent,
            trackColor = CardWhite.copy(alpha = 0.72f)
        )
    }
}

@Composable
private fun NetworkMetricTile(upload: String, download: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(CardTint).padding(10.dp)
    ) {
        Text("网络", fontSize = TextTiny, color = Muted)
        Spacer(Modifier.height(4.dp))
        NetworkRateLine("↓", download)
        Spacer(Modifier.height(3.dp))
        NetworkRateLine("↑", upload)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkRateLine(direction: String, rate: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$direction ${rate.substringBeforeLast(' ', rate)}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        if (' ' in rate) {
            Text(rate.substringAfterLast(' '), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Ink, softWrap = false)
        }
    }
}

@Composable
fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(onClick = onClick).padding(4.dp)) {
        Icon(icon, null, tint = Ink, modifier = Modifier.size(16.dp)); Spacer(Modifier.height(3.dp)); Text(label, fontSize = TextTiny, color = Muted)
    }
}

private fun formatRateShort(bytesPerSecond: Double): String = when {
    bytesPerSecond >= 1024.0 * 1024 -> String.format("%.1f MB", bytesPerSecond / 1024 / 1024)
    bytesPerSecond >= 1024.0 -> String.format("%.0f KB", bytesPerSecond / 1024)
    else -> String.format("%.0f B", bytesPerSecond)
}

fun formatRate(bytesPerSecond: Double): String = when {
    bytesPerSecond >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / 1024 / 1024)
    bytesPerSecond >= 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024)
    else -> "%.0f B/s".format(bytesPerSecond)
}

internal fun airportUsedProgress(usedPercent: Double): Float = (usedPercent.coerceAtLeast(0.0) / 100.0).toFloat().coerceIn(0f, 1f)
internal fun airportRemainingPercent(usedPercent: Double): Double = (100.0 - usedPercent.coerceAtLeast(0.0)).coerceIn(0.0, 100.0)

@Composable
fun CoreServicesRow(services: Map<String, ServiceStatus>, expanded: Boolean, onToggle: () -> Unit) {
    val online = services.count { it.value.status == "up" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val known = services.isNotEmpty()
        Icon(Icons.Default.Favorite, null, tint = if (known && online == services.size) Green else Amber, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
        Text("核心服务", fontSize = TextTiny, color = Muted); Spacer(Modifier.width(4.dp))
        Text(
            when {
                !known -> "暂无数据"
                online == services.size -> "全部正常"
                else -> "${services.size - online} 项异常"
            },
            fontSize = 10.sp,
            color = if (known && online == services.size) StatusHealthyInk else StatusAttentionInk,
            fontWeight = FontWeight.SemiBold
        )
        TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 24.dp)) {
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = Muted, modifier = Modifier.size(14.dp))
            Text(if (expanded) "收起" else "展开", fontSize = TextTiny, color = Muted)
        }
    }
}

@Composable
fun ServerCard(name: String, meshIp: String, uptime: String, cpu: Double, memory: Double, upload: String, download: String, online: Boolean, coreServices: Map<String, ServiceStatus>, publicIp: String? = null, supportsLogs: Boolean = false, onClick: () -> Unit, onTrends: () -> Unit = {}, onContainers: () -> Unit = {}, onServices: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    FrostCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            ServerHeader(name, meshIp, uptime, online)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageMetricTile("CPU", cpu, Modifier.weight(1f))
                UsageMetricTile("内存", memory, Modifier.weight(1f))
                NetworkMetricTile(upload, download, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!publicIp.isNullOrBlank()) {
                    PublicIpInline(name, publicIp)
                }
                Spacer(Modifier.weight(1f))
                CoreServicesRow(coreServices, expanded, onToggle = { expanded = !expanded })
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp)); Divider(color = Track); Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QuickAction(Icons.Default.KeyboardArrowUp, "趋势", onTrends); QuickAction(Icons.Default.List, if (supportsLogs) "容器与日志" else "容器", onContainers); QuickAction(Icons.Default.Favorite, "服务", onServices); QuickAction(Icons.Default.ArrowForward, "详情", onClick)
                }
            }
        }
    }
}

@Composable
fun RouterCard(router: RouterStatus, onClick: () -> Unit) {
    val online = router.status == "up" && router.internet != "down"
    val statusText = if (online) "在线" else if (router.internet == "down") "离线" else "未知"
    FrostCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (online) BlueSoft else Track), contentAlignment = Alignment.Center) {
                    Text("R", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (online) Blue else Muted)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("爱快路由器", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text("${router.address.ifBlank { "路由器" }} · ${router.wan_ip.ifEmpty { "LAN" }}", fontSize = 12.sp, color = Muted, maxLines = 1)
                }
                StatusBadge(statusText, if (online) "ok" else "err")
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Gauge(router.cpu_usage, "CPU")
                Spacer(Modifier.width(8.dp))
                Gauge(router.mem_usage, "内存")
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
                    Text(if (router.cpu_temp > 0) "${router.cpu_temp}°C" else "---", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (router.cpu_temp > 70) Red else Amber)
                    Text("温度", fontSize = TextTiny, color = Muted)
                }
            }
            Spacer(Modifier.height(16.dp))
            router.wans.forEach { w ->
                if (w.ip.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Text(w.name.uppercase() + ": " + w.ip, fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
                        Text("↓" + formatRateShort(w.download_speed), fontSize = 10.sp, color = Green)
                        Text("↑" + formatRateShort(w.upload_speed), fontSize = 10.sp, color = Amber)
                    }
                }
            }
        }
    }
}

@Composable
fun StatBox(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = TextTiny, color = Subtle)
    }
}


@Composable
fun AirportTrafficCard(items: List<AirportTrafficItem>) {
    FrostCard {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null, tint = Blue, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("机场流量", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.weight(1f))
                Text("订阅快照", fontSize = 10.sp, color = Subtle)
            }
            Spacer(Modifier.height(12.dp))
            items.forEachIndexed { index, item ->
                val usedPercent = item.usedPercent.coerceAtLeast(0.0)
                val remainingPercent = airportRemainingPercent(usedPercent)
                val color = when (item.level) { "critical" -> Red; "warning" -> Amber; else -> Green }
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f))
                        Text("剩余 ${"%.1f".format(item.remainingGb)} / ${"%.0f".format(item.totalGb)} GB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = airportUsedProgress(usedPercent), modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(6.dp)), color = color, trackColor = Track)
                    Spacer(Modifier.height(5.dp))
                    val todayUsage = item.todayUsedGb?.let { "%.2f GB".format(it) } ?: "暂不可用"
                    val todayNote = if (item.todayUsedGb == null) "" else if (item.todayUsagePartial) " · 部分统计" else " · 订阅估算"
                    Text("今日使用 $todayUsage$todayNote", fontSize = 12.sp, color = Ink)
                    Spacer(Modifier.height(3.dp))
                    Text("已用 ${"%.0f".format(usedPercent)}% · 剩余 ${"%.0f".format(remainingPercent)}% · 重置 ${item.resetDays?.let { "${it}天" } ?: "-"} · ${item.expire ?: "-"}", fontSize = 10.sp, color = Subtle)
                }
                if (index != items.lastIndex) Spacer(Modifier.height(13.dp))
            }
        }
    }
}

@Composable
private fun OverviewStatusSummary(overview: OverviewResponse) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val alertSignature = overview.alerts.sortedWith(compareBy<AlertItem> { it.host }.thenBy { it.kind }.thenBy { it.message }.thenBy { it.firstSeen ?: "" })
        .joinToString("|") { "${it.host}:${it.kind}:${it.message}:${it.firstSeen ?: ""}" }
    var ignoredSignature by remember { mutableStateOf(PreferenceHelper.getIgnoredAlertSignature(context)) }
    if (overview.alerts.isNotEmpty() && alertSignature == ignoredSignature) return
    val hosts = overview.serverEntries().filter { ServerRegistry.contains(it.id) }.map { it.metrics }
    val onlineHosts = hosts.count { it.status == "up" }
    val services = hosts.flatMap { it.coreServices.values }
    val healthyServices = services.count { it.status == "up" }
    val criticalAlerts = overview.alerts.count { it.level == "critical" }
    val totalAlerts = overview.alerts.size
    val healthy = totalAlerts == 0 && onlineHosts == hosts.size && healthyServices == services.size
    val surface = when {
        criticalAlerts > 0 -> StatusCriticalSurface
        healthy -> StatusSummarySurface
        else -> StatusAttentionSurface
    }
    val ink = when {
        criticalAlerts > 0 -> StatusCriticalInk
        healthy -> StatusSummaryInk
        else -> StatusAttentionInk
    }
    val title = when {
        criticalAlerts > 0 -> "存在需要立即处理的问题"
        healthy -> "当前运行正常"
        else -> "存在需要关注的状态"
    }
    val attention = if (totalAlerts == 0) "0 项待处理" else "$totalAlerts 项待处理"
    LiquidAwareCard(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = surface),
        elevation = MonitorCardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (healthy) Icons.Default.Favorite else Icons.Default.Warning,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(8.dp), color = CardWhite.copy(alpha = 0.72f)) {
                    Text(attention, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = ink, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            Text("$onlineHosts / ${hosts.size} 台服务器在线 · $healthyServices / ${services.size} 核心服务正常", fontSize = 11.sp, color = Muted)
            if (overview.alerts.isNotEmpty()) {
                TextButton(
                    onClick = {
                        ignoredSignature = alertSignature
                        PreferenceHelper.setIgnoredAlertSignature(context, alertSignature)
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) { Text("忽略当前警告", fontSize = 11.sp, color = ink) }
            }
        }
    }
}

private fun overviewFailureText(error: Exception, hasCachedData: Boolean): String {
    val detail = error.message.orEmpty().lowercase()
    val networkFailure = detail.contains("unable to resolve") || detail.contains("no address associated") || detail.contains("unknownhost") || detail.contains("network")
    return when {
        networkFailure && hasCachedData -> "网络不佳，当前显示的是缓存数据"
        networkFailure -> "网络不佳，请检查网络后重试"
        hasCachedData -> "连接异常，当前显示的是缓存数据"
        else -> "连接异常，请稍后重试"
    }
}

@Composable
fun OverviewScreen(navController: NavHostController) {
    val managedServers by ServerRegistry.servers.collectAsState()
    var overview by remember { mutableStateOf<OverviewResponse?>(null) }
    var airportTraffic by remember { mutableStateOf<AirportTrafficResponse?>(null) }
    var airportError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var refreshMode by remember { mutableStateOf(PreferenceHelper.getRefreshInterval(context)) }
    var loading by remember { mutableStateOf(false) }
    var pullRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() { scope.launch {
        if (loading) return@launch
        loading = true
        try {
            // 进程内缓存优先；冷启动时使用上次成功快照，避免网络采集期间白屏。
            val startup = StartupOverviewCache.load(context)
            if (startup != null) {
                overview = startup
                if (!StartupOverviewCache.isStale(context)) error = null
            }
            val cached = DataCache.get<OverviewResponse>("overview")
            if (cached != null) overview = cached
            try {
                val fresh = ApiClient.getOverview()
                overview = fresh
                DataCache.set("overview", fresh)
                StartupOverviewCache.save(context, fresh)
                try {
                    airportTraffic = ApiClient.getAirportTraffic()
                    airportError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    airportError = "机场流量刷新失败，保留上次数据"
                }
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (overview == null) error = overviewFailureText(e, hasCachedData = false)
                else error = overviewFailureText(e, hasCachedData = true)
            }
        } finally { loading = false; pullRefreshing = false }
    } }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(Unit) {
        while (isActive) {
            refreshMode = PreferenceHelper.getRefreshInterval(context)
            val wait = if (refreshMode > 0) refreshMode else 5
            delay(wait * 1000L)
            if (refreshMode > 0) load()
        }
    }

    Scaffold(containerColor = PageBackground, topBar = {
        AppPageHeader("服务器") { TextButton(onClick = { navController.navigate("servers") }) { Text("管理") } }
    }) { pad ->
        RefreshContainer(pullRefreshing, { if (!loading) { pullRefreshing = true; load() } }, Modifier.padding(pad)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp + LocalFloatingNavigationPadding.current)) {
            if (loading && overview == null) item { LoadFeedback("正在加载…") }
            if (error != null) item { LoadFeedback("${error!!} · 下拉重试") }
            if (airportError != null) item { LoadFeedback("${airportError!!} · 下拉重试") }
            val metrics = overview?.serverEntries().orEmpty().associateBy { it.id }
            managedServers.forEach { server ->
                val n = metrics[server.id]?.metrics ?: ServerStatus()
                item(key = server.id) { ServerCard(server.name, n.meshIp ?: server.meshIp, n.uptime, n.cpu, n.memory,
                    formatRate(n.network?.txBps ?: 0.0), formatRate(n.network?.rxBps ?: 0.0), n.status == "up", n.coreServices,
                    publicIp = n.publicIp, supportsLogs = server.id == "nas", onClick = { navController.navigate(serverDetailRoute(server.id)) },
                    onTrends = { navController.navigate("trends/${server.id}") }, onContainers = { navController.navigate("containers/${server.id}") },
                    onServices = { navController.navigate("services/${server.id}") }) }
            }
            overview?.router?.let { r -> item { RouterCard(r, onClick = { navController.navigate("router") }) } }
            airportTraffic?.items?.takeIf { it.isNotEmpty() }?.let { items -> item { AirportTrafficCard(items) } }
        }
        }
    }
}

@Composable
fun AlertsScreen(navController: NavHostController) {
    val managedServers by ServerRegistry.servers.collectAsState()
    var overview by remember { mutableStateOf<OverviewResponse?>(null) }
    var verified by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() { scope.launch {
        if (loading) return@launch
        loading = true
        verified = false
        try {
            val cached = DataCache.get<OverviewResponse>("overview")
            if (cached != null) overview = cached
            try {
                val fresh = ApiClient.getOverview()
                overview = fresh
                DataCache.set("overview", fresh)
                verified = true
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (overview == null) error = overviewFailureText(e, hasCachedData = false)
                else error = overviewFailureText(e, hasCachedData = true)
            }
        } finally { loading = false }
    } }
    LaunchedEffect(Unit) { load() }
    Scaffold(containerColor = PageBackground, topBar = {
        AppPageHeader("告警")
    }) { pad ->
        RefreshContainer(loading, { load() }, Modifier.padding(pad)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp + LocalFloatingNavigationPadding.current)) {
            if (loading && overview == null) item { LoadFeedback("正在加载…") }
            if (error != null) item { LoadFeedback("${error!!} · 下拉重试") }
            val alerts = overview?.alerts.orEmpty().filter { alert -> managedServers.any { it.id == alert.host } }
            if (overview != null && alerts.isNotEmpty()) item {
                val criticalCount = alerts.count { it.level == "critical" }
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = if (criticalCount > 0) StatusCriticalSurface else StatusAttentionSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (criticalCount > 0) Icons.Default.Warning else Icons.Default.Info, null, tint = if (criticalCount > 0) StatusCriticalInk else StatusAttentionInk, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("待处理", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            Text("按严重度与首次出现时间排序", fontSize = 11.sp, color = Muted)
                        }
                        Surface(shape = RoundedCornerShape(8.dp), color = CardWhite.copy(alpha = 0.72f)) {
                            Text("${alerts.size} 项", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (criticalCount > 0) StatusCriticalInk else StatusAttentionInk, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                        }
                    }
                }
            }
            if (overview != null && alerts.isEmpty()) item {
                LiquidAwareCard(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                    Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Favorite, null, tint = Green, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                verified && error == null -> "当前没有需要处理的告警"
                                error != null -> "上次获取的数据没有告警"
                                else -> "尚未验证当前告警状态"
                            },
                            color = if (verified && error == null) Green else Amber,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                verified && error == null -> "暂无告警"
                                error != null -> "上次获取的数据暂无告警"
                                else -> "正在刷新…"
                            },
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            alerts.forEach { alert -> item {
                val critical = alert.level == "critical"
                LiquidAwareCard(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (critical) Red else Amber))
                                Spacer(Modifier.width(10.dp))
                                Text(ServerRegistry.name(alert.host), fontSize = 11.sp, color = Muted)
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = RoundedCornerShape(4.dp), color = if (critical) StatusCriticalSurface else StatusAttentionSurface) {
                                    Text(if (critical) "严重" else "警告", fontSize = 10.sp, color = if (critical) StatusCriticalInk else StatusAttentionInk, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(alert.message, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp))
                            alert.firstSeen?.let { fs ->
                                Spacer(Modifier.height(3.dp))
                                Text("首次出现 ${fs.take(19).replace("T", " ")}", fontSize = 10.sp, color = Subtle, modifier = Modifier.padding(start = 20.dp))
                            }
                        }
                    }
                }
            } }
        }
        }
    }
}

@Composable
fun AppBottomBar(selected: Int, navController: NavHostController? = null) {
    val items = listOf(
        Triple("服务器", Icons.Default.Dns, "overview"),
        Triple("告警", Icons.Default.Warning, "alerts"),
        Triple("网关", Icons.Default.SettingsEthernet, "gateway"),
        Triple("工具", Icons.Default.Build, "tools"),
        Triple("安全", Icons.Default.Lock, "security"),
        Triple("设置", Icons.Default.Settings, "settings")
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .then(if (isGlassTheme()) Modifier.liquidGlass(RoundedCornerShape(28.dp), navigation = true, elevation = 8.dp)
                else Modifier.shadow(10.dp, RoundedCornerShape(28.dp), clip = false)),
        shape = RoundedCornerShape(28.dp),
        color = if (isGlassTheme()) Color.Transparent else NavigationSurface,
        tonalElevation = 0.dp
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.5f))
                .padding(horizontal = 4.dp, vertical = 5.dp)
        ) {
            val itemWidth = maxWidth / items.size
            // 滑动指示器： animateDpAsState 平滑移动
            val indicatorOffset by androidx.compose.animation.core.animateDpAsState(
                targetValue = itemWidth * selected,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.6f,
                    stiffness = 300f
                ),
                label = "navIndicator"
            )
            // 指示器背景（放在 items 下面）
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .then(if (isGlassTheme()) Modifier.liquidGlass(RoundedCornerShape(22.dp), elevation = 0.dp) else Modifier)
                    .clip(RoundedCornerShape(if (isGlassTheme()) 22.dp else 14.dp))
                    .background(BlueSoft)
            )
            // items
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                items.forEachIndexed { index, (label, icon, route) ->
                    val active = selected == index
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    val scale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (pressed) 0.92f else 1f,
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 400f),
                        label = "pressScale"
                    )
                    val fg by androidx.compose.animation.animateColorAsState(
                        targetValue = if (active) Blue else Muted,
                        animationSpec = androidx.compose.animation.core.tween(200),
                        label = "navFg"
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .clip(RoundedCornerShape(14.dp))
                            .selectable(
                                selected = active,
                                role = Role.Tab,
                                interactionSource = interactionSource,
                                indication = LocalIndication.current
                            ) {
                                navController?.navigate(route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.height(1.dp))
                        Text(label, fontSize = TextTiny, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = fg)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolsScreen(navController: NavHostController) {
    val managedServers by ServerRegistry.servers.collectAsState()
    LaunchedEffect(Unit) { runCatching { ApiClient.getServers() } }
    val hosts = managedServers.map { ToolHost(it.id, it.name, if (it.id == "nas") "本地存储与容器" else "服务器监控", if (it.id == "nas") Icons.Default.Storage else Icons.Default.Cloud) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { hosts.size }
    val selected = pagerState.currentPage.coerceIn(0, hosts.lastIndex)

    Scaffold(
        containerColor = PageBackground,
        topBar = {
            Column {
                AppPageHeader("工具")
                ToolHostTabs(hosts = hosts, selected = selected) { target ->
                    scope.launch { pagerState.animateScrollToPage(target) }
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                key = { hosts[it].id }
            ) { page ->
                ToolHostPage(host = hosts[page], navController = navController)
            }
        }
    }
}

private data class ToolHost(
    val id: String,
    val label: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

internal data class ToolEntrySpec(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    val route: String
)

internal fun toolEntrySpecsForHost(hostId: String, hostLabel: String): List<ToolEntrySpec> = buildList {
    add(ToolEntrySpec(Icons.Default.KeyboardArrowUp, "趋势", "$hostLabel 资源曲线", "trends/$hostId"))
    if (hostId != "tencent") {
        add(ToolEntrySpec(Icons.Default.Build, "温度", "$hostLabel 传感器", "temperature/$hostId"))
    }
    add(ToolEntrySpec(Icons.Default.AllInbox, "容器", if (hostId == "nas") "NAS Docker 状态与日志" else "$hostLabel 容器清单", "containers/$hostId"))
    add(ToolEntrySpec(Icons.Default.SettingsEthernet, "服务", "$hostLabel 服务探活", "services/$hostId"))
    add(ToolEntrySpec(Icons.Default.Terminal, "服务器", "进入${hostLabel}详情", serverDetailRoute(hostId)))

}

@Composable
private fun ToolHostTabs(hosts: List<ToolHost>, selected: Int, onSelect: (Int) -> Unit) {
    ContentSizedTabs(hosts.map { it.label }, selected, onSelect)
}

@Composable
private fun ToolHostPage(host: ToolHost, navController: NavHostController) {
    val entries = toolEntrySpecsForHost(host.id, host.label)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 10.dp + LocalFloatingNavigationPadding.current),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(entries, key = { it.route }) { entry ->
            ToolEntry(entry.icon, entry.title, entry.subtitle, Modifier.fillMaxWidth()) {
                navController.navigate(entry.route)
            }
        }
        item(key = "shared-tools", span = { GridItemSpan(maxLineSpan) }) {
            ScrollableSharedTools(navController)
        }
    }
}

@Composable
private fun ScrollableSharedTools(navController: NavHostController) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Divider(color = Track)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolEntry(Icons.Default.QuestionAnswer, "知识问答", "AnythingLLM 知识库", Modifier.weight(1f)) { navController.navigate("ask") }
            ToolEntry(Icons.Default.Link, "知识归档", "链接与文件收藏", Modifier.weight(1f)) { navController.navigate("knowledge-archive") }
        }
    }
}

@Composable
private fun ToolEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    LiquidAwareCard(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
        Column(Modifier.padding(14.dp).heightIn(min = 82.dp)) {
            Icon(icon, null, tint = Blue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 10.sp, color = Muted, lineHeight = 13.sp)
        }
    }
}
