package io.github.nasmonitor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nasmonitor.app.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ── 磁盘详情页 ──
@Composable
fun DisksScreen(onBack: () -> Unit) {
    var overview by remember { mutableStateOf<OverviewResponse?>(null) }
    var nasDetail by remember { mutableStateOf<NasDetailResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            try { overview = ApiClient.getOverview(); nasDetail = ApiClient.getNasDetail(); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "加载失败" }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }
    Scaffold(containerColor = PageBackground, topBar = {
        AppPageHeader("存储总览", onBack = onBack)
    }) { pad ->
        RefreshContainer(loading, { load() }, Modifier.padding(pad)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                if (error != null) item { LoadFeedback("加载失败：$error · 下拉重试") }
                if (loading && overview == null) item { LoadFeedback("正在加载存储…") }
                if (overview != null) {
                    val disks = nasDetail?.system?.disks?.filter { it.mount.startsWith("/vol") && it.mount.getOrNull(4)?.isDigit() == true }
                        ?: overview?.nas?.disks?.filter { it.mount.startsWith("/vol") && it.mount.getOrNull(4)?.isDigit() == true }
                        ?: emptyList()
                    val haVolumes = nasDetail?.ha?.volumes.orEmpty()
                    item { Text("NAS 存储卷", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink) }
                    if (haVolumes.isNotEmpty()) {
                        haVolumes.forEach { v -> item { DiskCard(v.name, v.percent, v.capacity, v.used, source = "HA") } }
                    } else if (disks.isNotEmpty()) {
                        disks.sortedBy { it.mount }.forEach { d ->
                            item { DiskCard(d.mount.removePrefix("/"), d.percent, "${"%.0f".format(d.totalGb)}G", "${"%.1f".format(d.usedGb)}G", source = "Glances") }
                        }
                    } else {
                        item { LiquidAwareCard(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(CardWhite)) { Text("暂无磁盘数据", modifier = Modifier.padding(24.dp), color = Muted) } }
                    }
                    overview?.serverEntries()?.filter { it.id != "nas" && ServerRegistry.contains(it.id) }?.forEach { server ->
                    server.metrics.disks.filter { it.mount == "/" }.forEach { d ->
                        item { Spacer(Modifier.height(8.dp)); Text("${server.name} 存储", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink) }
                        item { DiskCard("系统盘", d.percent, "${"%.0f".format(d.totalGb)}G", "${"%.1f".format(d.usedGb)}G", source = "Glances") }
                    }
                    }
                }
            }
        }
    }
}

@Composable
fun DiskCard(name: String, percent: Double, capacity: String, used: String, source: String = "") {
    val color = usageColor(percent)
    val soft = usageSoft(percent)
    FrostCard {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                Text("${"%.0f".format(percent)}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Track)) {
                Box(Modifier.fillMaxWidth((percent / 100).toFloat().coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) { Text("已用 $used / 共 $capacity", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f)); if (percent >= 80) Text("⚠ 建议清理", fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ── 服务状态页 ──
@Composable
fun ServicesScreen(host: String, onBack: () -> Unit, onContainers: () -> Unit = {}) {
    var data by remember { mutableStateOf<Map<String, ServiceStatus>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    fun load() {
        if (loading) return
        loading = true
        scope.launch {
        try {
            data = when (host) {
                "nas" -> ApiClient.getNasDetail().services
                "aliyun" -> ApiClient.getAliyunDetail().services
                "tencent" -> ApiClient.getTencentDetail().services
                else -> ApiClient.getServerDetail(host).services
            }
            error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "加载失败" }
        finally { loading = false }
    } }
    LaunchedEffect(Unit) { load() }
    Scaffold(containerColor = PageBackground, topBar = {
        AppPageHeader("${hostLabel(host)} 服务", onBack = onBack)
    }) { pad ->
        RefreshContainer(loading, { load() }, Modifier.padding(pad)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                if (error != null) item { LoadFeedback("加载失败：$error · 下拉重试") }
                if (loading && data == null) item { LoadFeedback("正在加载服务…") }
                if (!loading && data != null && data!!.isEmpty()) item { LoadFeedback("暂无服务数据") }
                item { TextButton(onClick = onContainers) { Text("查看此主机容器") } }
                data?.entries?.sortedBy { it.key }?.forEach { (name, svc) ->
                    item {
                        LiquidAwareCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (svc.status == "up") Green else Red))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium)
                                    svc.ms?.let { Text("${"%.0f".format(it)}ms · HTTP ${svc.code ?: "--"}", fontSize = 11.sp, color = Muted) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 设备插画（宝塔风格服务器机箱） ──
@Composable
fun ServerIllustration(height: Int = 100, modifier: Modifier = Modifier) {
    LiquidAwareCard(modifier.size((height * 0.85).dp, height.dp), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(2.dp)) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val w = size.width; val h = size.height
            val unitH = h / 5
            val unitW = w * 0.65f
            val x0 = (w - unitW) / 2f
            // 4 层服务器单元
            val layers = listOf(0f, 1f, 2f, 3f)
            layers.forEach { i ->
                val y = i * unitH + unitH * 0.08f
                val uh = unitH * 0.84f
                // 机身
                drawRoundRect(Track, Offset(x0, y), Size(unitW, uh), CornerRadius(4f, 4f))
                // 前面板凹槽
                drawRoundRect(CardTint, Offset(x0 + 4f, y + 4f), Size(unitW - 8f, uh * 0.35f), CornerRadius(2f, 2f))
                // 绿色指示灯条
                drawRoundRect(Green, Offset(x0 + 6f, y + uh * 0.4f), Size(unitW * 0.6f, 3f), CornerRadius(1.5f, 1.5f))
                // 右侧接口
                val rx = x0 + unitW - 6f
                drawRoundRect(Subtle, Offset(rx, y + 4f), Size(4f, uh * 0.25f), CornerRadius(1f, 1f))
                // 指示灯
                drawCircle(Green, 2.5f, Offset(rx + 2f, y + uh * 0.55f))
                drawCircle(Subtle, 2.5f, Offset(rx + 2f, y + uh * 0.68f))
                drawCircle(Green, 2.5f, Offset(rx + 2f, y + uh * 0.81f))
            }
            // 底部基座阴影
            drawRoundRect(Subtle, Offset(x0 + 4f, h - 3f), Size(unitW - 8f, 3f), CornerRadius(1.5f, 1.5f))
        }
    }
}
