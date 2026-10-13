package io.github.nasmonitor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import android.content.Context
import io.github.nasmonitor.app.*
import kotlinx.coroutines.launch

// ── 可折叠、可拖拽排序的详情页 ──

data class Section(val id: String, val title: String)

@Composable
fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    LiquidAwareCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", fontSize = 11.sp, color = Subtle)
            }
            if (expanded) {
                Divider(color = Track, thickness = 0.5.dp)
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
            }
        }
    }
}

@Composable
fun ServiceRowDetail(name: String, svc: ServiceStatus) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (svc.status == "up") Green else Red))
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
        svc.ms?.let { Text("${"%.0f".format(it)}ms", fontSize = 12.sp, color = Muted) }
    }
}

// ── NAS 详情 ──
@Composable
fun NasScreen(onBack: () -> Unit, onSsh: () -> Unit = {}, onEdit: () -> Unit = {}) {
    var data by remember { mutableStateOf<NasDetailResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val cached = DataCache.get<NasDetailResponse>("nas_detail")
                if (cached != null) data = cached
                val fresh = ApiClient.getNasDetail()
                DataCache.set("nas_detail", fresh)
                data = fresh
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "请求失败"
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("section_order", Context.MODE_PRIVATE)
    val defaultOrder = listOf("system", "storage", "services", "hardware", "kuma", "latency")
    val allowed = defaultOrder.toSet()
    var sections by remember {
        val saved = prefs.getString("nas_order", null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it in allowed }
            ?.let { list -> list + defaultOrder.filter { it !in list } }
        mutableStateOf(if (saved.isNullOrEmpty()) defaultOrder else saved)
    }
    var draggedIdx by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    var expanded by remember { mutableStateOf(defaultOrder.associateWith { true }.toMutableMap()) }

    Scaffold(containerColor = PageBackground, topBar = {
        AppPageHeader("NAS 详情", onBack = onBack) {
            TextButton(onClick = onEdit) { Text("编辑连接") }
        }
    }) { pad ->
        val scrollState = rememberScrollState()
        RefreshContainer(loading, { load() }, Modifier.fillMaxSize().padding(pad)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading && data == null) LoadFeedback("正在加载 NAS 详情…")
            error?.let { LoadFeedback("加载失败：$it · 下拉重试") }
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onSsh), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(BlueSoft)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, tint = Blue); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text("SSH 终端", color = Ink, fontWeight = FontWeight.SemiBold); Text("使用已保存的 SSH 连接配置（直连/跳板方式以当前设置为准）", color = Muted, fontSize = 11.sp) }
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = Muted)
                }
            }

            sections.forEachIndexed { idx, sectionId ->
                val isExpanded = expanded[sectionId] ?: true
                val isDragging = draggedIdx == idx
                Box(modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .pointerInput(idx, sections) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { draggedIdx = idx; dragOffset = 0f },
                            onDrag = { change, dragAmount -> change.consume(); dragOffset += dragAmount.y },
                            onDragEnd = {
                                if (draggedIdx >= 0) {
                                    val swapIdx = (draggedIdx + (dragOffset / 200).toInt()).coerceIn(0, sections.size - 1)
                                    if (swapIdx != draggedIdx) {
                                        sections = sections.toMutableList().also { val item = it.removeAt(draggedIdx); it.add(swapIdx, item) }
                                        prefs.edit().putString("nas_order", sections.joinToString(",")).apply()
                                    }
                                }
                                draggedIdx = -1; dragOffset = 0f
                            },
                            onDragCancel = { draggedIdx = -1; dragOffset = 0f }
                        )
                    }
                ) {
                    CollapsibleSection(sectionTitle(sectionId), isExpanded, onToggle = { expanded = expanded.toMutableMap().also { it[sectionId] = !isExpanded } }) {
                        when (sectionId) {
                            "system" -> SystemSection(data)
                            "storage" -> StorageSection(data)
                            "services" -> ServicesSection(data?.services)
                            "hardware" -> HardwareSection(data)
                            "kuma" -> KumaSection(data)
                            "latency" -> LatencySection(data)
                        }
                    }
                }
            }
        }
        }
    }
}

fun sectionTitle(id: String) = when (id) {
    "system" -> "系统"
    "storage" -> "存储"
    "services" -> "服务探活"
    "hardware" -> "硬件状态"
    "kuma" -> "Kuma 监控"
    "latency" -> "延迟"
    else -> id
}

@Composable
fun SystemSection(data: NasDetailResponse?) {
    data?.system?.let { sys ->
        sys.cpu?.let { Text("CPU ${"%.1f".format(it.percent)}% · ${it.cores} 核", fontSize = 13.sp, color = Ink) }
        sys.memory?.let { Text("内存 ${"%.1f".format(it.percent)}% · ${"%.1f".format(it.usedGb)}/${"%.1f".format(it.totalGb)}GB", fontSize = 13.sp, color = Ink) }
        sys.load?.let { Text("负载 ${"%.1f".format(it.min1)} / ${"%.1f".format(it.min5)} / ${"%.1f".format(it.min15)}", fontSize = 12.sp, color = Muted) }
        sys.uptime?.let { Text("开机 $it", fontSize = 12.sp, color = Muted) }
    }
}

@Composable
private fun <T> TwoColumnGrid(items: List<T>, content: @Composable (T) -> Unit) {
    items.chunked(2).forEach { rowItems ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            rowItems.forEach { item ->
                Column(Modifier.weight(1f)) { content(item) }
            }
            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
fun StorageSection(data: NasDetailResponse?) {
    val vols = data?.ha?.volumes.orEmpty()
    if (vols.isNotEmpty()) {
        TwoColumnGrid(vols) { v -> StorageCell(v.name, v.percent, v.capacity) }
    } else {
        val disks = data?.system?.disks?.filter { it.mount.startsWith("/vol") && it.mount.length > 4 && it.mount[4].isDigit() }?.sortedBy { it.mount }.orEmpty()
        if (disks.isEmpty()) Text("暂无数据", color = Muted, fontSize = 12.sp)
        else TwoColumnGrid(disks) { d -> StorageCell(d.mount.removePrefix("/"), d.percent, "${"%.0f".format(d.totalGb)}G") }
    }
}

@Composable
private fun StorageCell(name: String, pct: Double, cap: String) {
    val color = usageColor(pct)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 13.sp, color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${"%.0f".format(pct)}%", fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
        }
        Text(cap.replace(" / ", "/"), fontSize = 10.sp, color = Muted, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Track)) {
            Box(Modifier.fillMaxWidth((pct / 100).toFloat().coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}


@Composable
fun ServicesSection(services: Map<String, ServiceStatus>?) {
    val items = services?.entries?.sortedBy { it.key }.orEmpty()
    if (items.isEmpty()) { Text("暂无数据", color = Muted, fontSize = 12.sp); return }
    TwoColumnGrid(items) { (name, svc) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (svc.status == "up") Green else Red))
            Spacer(Modifier.width(7.dp))
            Text(name, fontSize = 11.sp, color = Ink, modifier = Modifier.weight(1f), maxLines = 1)
            svc.ms?.let { Text("${"%.0f".format(it)}ms", fontSize = 10.sp, color = Muted) }
        }
    }
}

@Composable
fun HardwareSection(data: NasDetailResponse?) {
    val ha = data?.ha
    if (ha == null) { Text("暂无数据", color = Muted, fontSize = 12.sp); return }
    var hasData = false
    @Composable
    fun sectionTitle(title: String) { Text(title, fontSize = 12.sp, color = Muted, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(4.dp)) }

    if (data.hddTemps.isNotEmpty()) {
        hasData = true; sectionTitle("物理磁盘温度")
        // 磁盘名称按现场设备动态生成：HDD/SSD 由转速属性判定，NVMe 单列，新增硬盘自动出现。
        val details = ha.diskDetails
        TwoColumnGrid(data.hddTemps.entries.sortedBy { diskSortKey(it.key) }) { (disk, temp) ->
            DetailPairCell(diskLabel(disk, details[disk]), "${temp}°C", when { temp >= 50 -> Red; temp >= 45 -> Amber; else -> Green })
        }
    }
    if (ha.fans.isNotEmpty()) {
        hasData = true; sectionTitle("风扇")
        TwoColumnGrid(ha.fans.entries.sortedBy { it.key }) { (name, fan) ->
            DetailPairCell(name.replace("fan", "风扇 "), listOfNotNull(fan.rpm?.let { "$it RPM" }, fan.mode, fan.pwm?.let { "PWM $it" }).joinToString(" · ").ifEmpty { "—" }, Blue)
        }
    }
    ha.ups?.let { u ->
        val metrics = listOf("状态" to (u.status ?: "—"), "电量" to (u.charge?.let { "$it%" } ?: "—"), "负载" to (u.load?.let { "$it%" } ?: "—"), "输入" to (u.inputVoltage?.let { "$it V" } ?: "—"), "输出" to (u.outputVoltage?.let { "$it V" } ?: "—"), "电池" to (u.batteryVoltage?.let { "$it V" } ?: "—"))
        if (metrics.any { it.second != "—" }) { hasData = true; sectionTitle("UPS"); TwoColumnGrid(metrics) { (k,v) -> DetailPairCell(k, v, if (k == "状态" && v == "OL") Green else if (k == "负载") Amber else Blue) } }
    }
    ha.cabinet?.let { c ->
        val metrics = listOf("温度" to (c.temperature?.let { "$it°C" } ?: "—"), "湿度" to (c.humidity?.let { "$it%" } ?: "—"), "功率" to (c.power?.let { "$it W" } ?: "—"), "今日电量" to (c.energyToday?.let { "$it kWh" } ?: "—"), "本月电量" to (c.energyMonth?.let { "$it kWh" } ?: "—"), "累计电量" to (c.energyTotal?.let { "$it kWh" } ?: "—"))
        if (metrics.any { it.second != "—" }) { hasData = true; sectionTitle("机柜环境"); TwoColumnGrid(metrics) { (k,v) -> DetailPairCell(k, v, if (k == "温度" && (c.temperature?.toDoubleOrNull() ?: 0.0) >= 45) Red else if (k == "功率") Amber else Blue) } }
    }
    if (!hasData) Text("暂无数据", color = Muted, fontSize = 12.sp)
}

private fun diskSortKey(name: String): Int = Regex("^(\\D*)(\\d+)$").find(name)
    ?.let { it.groupValues[1].length * 10000 + (it.groupValues[2].toIntOrNull() ?: 0) }
    ?: Int.MAX_VALUE

private fun diskLabel(name: String, detail: DiskDetail?): String = when {
    name.startsWith("nvme") -> "NVMe $name"
    detail?.rotational == true -> "HDD $name"
    detail != null -> "SSD $name"
    else -> name
}

@Composable
private fun DetailPairCell(label: String, value: String, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 11.sp, color = Subtle, maxLines = 1)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
fun KumaSection(data: NasDetailResponse?) {
    val items = data?.kuma.orEmpty()
    if (items.isEmpty()) { Text("暂无数据", color = Muted, fontSize = 12.sp); return }
    TwoColumnGrid(items) { m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (m.status == "up") Green else Red))
            Spacer(Modifier.width(7.dp)); Text(m.name, color = Ink, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (m.latencyMs > 0) Text("${"%.0f".format(m.latencyMs)}ms", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
fun LatencySection(data: NasDetailResponse?) {
    val ms = data?.latency?.nasToAliyun ?: data?.latency?.aliyun
    if (ms != null) TwoColumnGrid(listOf("NAS → 阿里云" to "${"%.1f".format(ms)}ms")) { (k,v) -> DetailPairCell(k, v, Blue) }
    else Text("暂无数据", color = Muted, fontSize = 12.sp)
}

// ── 阿里云详情 ──
@Composable
fun AliyunScreen(onBack: () -> Unit, onSsh: () -> Unit = {}, onEdit: () -> Unit = {}) {
    var data by remember { mutableStateOf<AliyunDetailResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val cached = DataCache.get<AliyunDetailResponse>("aliyun_detail")
                if (cached != null) data = cached
                val fresh = ApiClient.getAliyunDetail()
                DataCache.set("aliyun_detail", fresh)
                data = fresh
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "请求失败"
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = PageBackground, topBar = {
        AppPageHeader("阿里云详情", onBack = onBack) {
            TextButton(onClick = onEdit) { Text("编辑连接") }
        }
    }) { pad ->
        val scrollState = rememberScrollState()
        var sysExp by remember { mutableStateOf(true) }
        var storExp by remember { mutableStateOf(true) }
        var svcExp by remember { mutableStateOf(true) }
        RefreshContainer(loading, { load() }, Modifier.fillMaxSize().padding(pad)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading && data == null) LoadFeedback("正在加载阿里云详情…")
            error?.let { LoadFeedback("加载失败：$it · 下拉重试") }
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onSsh), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(BlueSoft)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, tint = Blue); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text("SSH 终端", color = Ink, fontWeight = FontWeight.SemiBold); Text("使用已保存的 SSH 连接配置（直连/跳板方式以当前设置为准）", color = Muted, fontSize = 11.sp) }
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = Muted)
                }
            }

            data?.system?.let { sys ->
                CollapsibleSection("系统", sysExp, onToggle = { sysExp = !sysExp }, {
                    sys.cpu?.let { Text("CPU ${"%.1f".format(it.percent)}% · ${it.cores} 核", fontSize = 13.sp, color = Ink) }
                    sys.memory?.let { Text("内存 ${"%.1f".format(it.percent)}% · ${"%.1f".format(it.usedGb)}/${"%.1f".format(it.totalGb)}GB", fontSize = 13.sp, color = Ink) }
                    sys.load?.let { Text("负载 ${"%.1f".format(it.min1)} / ${"%.1f".format(it.min5)} / ${"%.1f".format(it.min15)}", fontSize = 12.sp, color = Muted) }
                    sys.uptime?.let { Text("开机 $it", fontSize = 12.sp, color = Muted) }
                })
            }
            data?.system?.disks?.let { disks ->
                CollapsibleSection("存储", storExp, onToggle = { storExp = !storExp }, {
                    val visible = disks.filter { it.mount.startsWith("/") && it.mount.length < 20 }
                    TwoColumnGrid(visible) { d -> StorageCell(d.mount, d.percent, "${"%.0f".format(d.totalGb)}G") }
                })
            }
            data?.services?.let { svcs ->
                CollapsibleSection("服务探活", svcExp, onToggle = { svcExp = !svcExp }, {
                    TwoColumnGrid(svcs.entries.sortedBy { it.key }) { (name, svc) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (svc.status == "up") Green else Red))
                            Spacer(Modifier.width(7.dp)); Text(name, fontSize = 11.sp, color = Ink, modifier = Modifier.weight(1f), maxLines = 1)
                            svc.ms?.let { Text("${"%.0f".format(it)}ms", fontSize = 10.sp, color = Muted) }
                        }
                    }
                })
            }
            data?.latency?.nasToAliyun?.let { ms ->
                LiquidAwareCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                    Text("NAS → 阿里云 ${"%.1f".format(ms)}ms", modifier = Modifier.padding(14.dp), fontSize = 13.sp, color = Muted)
                }
            }
        }
        }
    }
}

// ── 腾讯云详情 ──
@Composable
fun TencentScreen(onBack: () -> Unit, onSsh: () -> Unit = {}, onEdit: () -> Unit = {}, serverId: String = "tencent") {
    var data by remember { mutableStateOf<AliyunDetailResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val cached = DataCache.get<AliyunDetailResponse>("${serverId}_detail")
                if (cached != null) data = cached
                val fresh = ApiClient.getServerDetail(serverId)
                DataCache.set("${serverId}_detail", fresh)
                data = fresh
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "请求失败"
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = PageBackground, topBar = {
        AppPageHeader("${ServerRegistry.name(serverId)}详情", onBack = onBack) {
            TextButton(onClick = onEdit) { Text("编辑连接") }
        }
    }) { pad ->
        val scrollState = rememberScrollState()
        var sysExp by remember { mutableStateOf(true) }
        var storExp by remember { mutableStateOf(true) }
        var svcExp by remember { mutableStateOf(true) }
        RefreshContainer(loading, { load() }, Modifier.fillMaxSize().padding(pad)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading && data == null) LoadFeedback("正在加载服务器详情…")
            error?.let { LoadFeedback("加载失败：$it · 下拉重试") }
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onSsh), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(BlueSoft)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, tint = Blue); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text("SSH 终端", color = Ink, fontWeight = FontWeight.SemiBold); Text("使用已保存的 SSH 连接配置（直连/跳板方式以当前设置为准）", color = Muted, fontSize = 11.sp) }
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = Muted)
                }
            }

            data?.system?.let { sys ->
                CollapsibleSection("系统", sysExp, onToggle = { sysExp = !sysExp }, {
                    sys.cpu?.let { Text("CPU ${"%.1f".format(it.percent)}% · ${it.cores} 核", fontSize = 13.sp, color = Ink) }
                    sys.memory?.let { Text("内存 ${"%.1f".format(it.percent)}% · ${"%.1f".format(it.usedGb)}/${"%.1f".format(it.totalGb)}GB", fontSize = 13.sp, color = Ink) }
                    sys.load?.let { Text("负载 ${"%.1f".format(it.min1)} / ${"%.1f".format(it.min5)} / ${"%.1f".format(it.min15)}", fontSize = 12.sp, color = Muted) }
                    sys.uptime?.let { Text("开机 $it", fontSize = 12.sp, color = Muted) }
                })
            }
            data?.system?.disks?.let { disks ->
                CollapsibleSection("存储", storExp, onToggle = { storExp = !storExp }, {
                    val visible = disks.filter { it.mount.startsWith("/") && it.mount.length < 20 }
                    TwoColumnGrid(visible) { d -> StorageCell(d.mount, d.percent, "${"%.0f".format(d.totalGb)}G") }
                })
            }
            data?.services?.let { svcs ->
                CollapsibleSection("服务探活", svcExp, onToggle = { svcExp = !svcExp }, {
                    TwoColumnGrid(svcs.entries.sortedBy { it.key }) { (name, svc) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (svc.status == "up") Green else Red))
                            Spacer(Modifier.width(7.dp)); Text(name, fontSize = 11.sp, color = Ink, modifier = Modifier.weight(1f), maxLines = 1)
                            svc.ms?.let { Text("${"%.0f".format(it)}ms", fontSize = 10.sp, color = Muted) }
                        }
                    }
                })
            }
            
        }
        }
    }
}
