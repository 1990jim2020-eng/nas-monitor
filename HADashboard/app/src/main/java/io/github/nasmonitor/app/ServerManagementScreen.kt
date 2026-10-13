package io.github.nasmonitor.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nasmonitor.app.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ServerManagementScreen(onBack: () -> Unit, onSshConfig: (String) -> Unit) {
    val servers by ServerRegistry.servers.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var verified by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ManagedServer?>(null) }
    val scope = rememberCoroutineScope()
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { runAction { ApiClient.getServers() } }
    Scaffold(containerColor = PageBackground, topBar = { AppPageHeader("管理服务器", onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CardWhite)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("添加服务器", style = MaterialTheme.typography.titleMedium, color = Ink)
                        Text("填写 Glances v4 地址，检查连接后保存。SSH 可在保存后单独配置。", color = Muted)
                        OutlinedTextField(name, { name = it; verified = "" }, label = { Text("服务器名称") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(address, { address = it; verified = "" }, label = { Text("Glances 地址") }, placeholder = { Text("http://主机地址:61208") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(enabled = !busy && name.isNotBlank() && address.isNotBlank(), onClick = {
                                runAction { ApiClient.checkServer(name.trim(), address.trim()); verified = name + "\n" + address; message = "连接正常，可以保存" }
                            }, modifier = Modifier.weight(1f)) { Text("检查连接") }
                            Button(enabled = !busy && verified == name + "\n" + address, onClick = {
                                runAction {
                                    ApiClient.addServer(name.trim(), address.trim())
                                    name = ""; address = ""; verified = ""; message = "服务器已添加"
                                }
                            }, modifier = Modifier.weight(1f)) { Text("保存") }
                        }
                    }
                }
            }
            if (busy) item { LoadFeedback("正在处理，请稍候…") }
            message?.let { item { Text(it, color = Ink) } }
            item { Text("已接入 · ${servers.size} 台", style = MaterialTheme.typography.titleMedium, color = Ink) }
            items(servers, key = { it.id }) { server ->
                Card(colors = CardDefaults.cardColors(containerColor = CardWhite)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(server.name, style = MaterialTheme.typography.titleMedium, color = Ink)
                        Text(if (server.deletable) server.glancesUrl else "NAS 本机 · 固定监控入口", color = Muted)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(enabled = !busy, onClick = { onSshConfig(server.id) }) { Text("SSH 配置") }
                            if (server.deletable) TextButton(enabled = !busy, onClick = { deleting = server }) { Text("删除", color = Red) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { server ->
        CompactConfirmationDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${server.name}？") },
            text = { Text("将清除 Monitor 中的监控配置、告警、趋势、缓存与本机 SSH 配置。不会操作云实例或服务器文件；其他设备会在下次同步时清理。") },
            confirmButton = { TextButton(onClick = { deleting = null; runAction { ApiClient.deleteServer(server.id); message = "已删除 ${server.name}" } }) { Text("删除", color = Red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
