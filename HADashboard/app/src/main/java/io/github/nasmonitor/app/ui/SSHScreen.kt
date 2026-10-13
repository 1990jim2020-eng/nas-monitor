package io.github.nasmonitor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import io.github.nasmonitor.app.SecureCredentialStore
import io.github.nasmonitor.app.SshAuthMethod
import io.github.nasmonitor.app.SshCredential
import io.github.nasmonitor.app.SshHostIdentity
import io.github.nasmonitor.app.SshProfileStore
import io.github.nasmonitor.app.SecureHostKeyRepository
import io.github.nasmonitor.app.SshSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

private data class SshProfile(val name: String, val host: String, val port: Int, val user: String, val password: String, val useJump: Boolean, val jumpHost: String, val jumpPort: Int, val jumpUser: String, val jumpPassword: String, val targetAuth: SshCredential, val jumpAuth: SshCredential)
private data class HostPrompt(val id: Long, val alias: String, val fingerprint: String, val decision: CompletableFuture<Boolean>)

@Composable
fun SSHScreen(serverId: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isNas = serverId == "nas"
    val isAliyun = serverId == "aliyun"
    val prefix = "server.$serverId"
    fun defaultName() = when (serverId) { "nas" -> "NAS"; "aliyun" -> "阿里云 ECS"; "tencent" -> "腾讯云服务器"; else -> io.github.nasmonitor.app.ServerRegistry.name(serverId) }
    fun defaultHost() = io.github.nasmonitor.app.ServerRegistry.host(serverId)
    fun defaultPort() = "22"
    fun defaultUser() = when (serverId) { "tencent" -> "ubuntu"; else -> "root" }
    fun defaultPassword() = if (isNas) SecureCredentialStore.get(context, "target.password") else if (serverId in listOf("aliyun", "tencent")) (if (serverId in listOf("nas", "aliyun", "tencent")) SecureCredentialStore.get(context, "jump.password") else "") else ""
    fun value(key: String, fallback: String) = SecureCredentialStore.get(context, "$prefix.$key").ifEmpty { fallback }
    val profile = remember(serverId) {
        val targetPassword = value("password", defaultPassword())
        val jumpPassword = value("jumpPassword", (if (serverId in listOf("nas", "aliyun", "tencent")) SecureCredentialStore.get(context, "jump.password") else ""))
        SshProfile(
            name = value("name", defaultName()), host = value("host", defaultHost()),
            port = value("port", defaultPort()).toIntOrNull() ?: defaultPort().toInt(),
            user = value("user", defaultUser()), password = targetPassword,
            useJump = value("useJump", if (isNas) "true" else "false") == "true", jumpHost = value("jumpHost", ""),
            jumpPort = value("jumpPort", "22").toIntOrNull() ?: 22, jumpUser = value("jumpUser", "root"), jumpPassword = jumpPassword,
            targetAuth = SshProfileStore.auth(context, prefix, false, targetPassword),
            jumpAuth = SshProfileStore.auth(context, prefix, true, jumpPassword)
        )
    }
    var command by remember { mutableStateOf("") }
    var quickOpen by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<Session?>(null) }
    var jumpSession by remember { mutableStateOf<Session?>(null) }
    var shell by remember { mutableStateOf<ChannelShell?>(null) }
    var shellInput by remember { mutableStateOf<OutputStream?>(null) }
    var hostPrompt by remember { mutableStateOf<HostPrompt?>(null) }
    val promptSequence = remember { AtomicLong(0L) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val terminal = remember { TerminalBuffer(80, 24) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()

    fun append(text: String) { terminal.feed(text); revision++ }
    fun disconnect() {
        shellInput?.let { runCatching { it.close() } }
        shell?.let { runCatching { it.disconnect() } }
        session?.let { runCatching { it.disconnect() } }
        jumpSession?.let { runCatching { it.disconnect() } }
        shellInput = null; shell = null; session = null; jumpSession = null; connected = false
    }
    fun friendly(stage: String, e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("Auth fail", true) -> "$stage 认证失败，请检查用户名、密码或私钥"
            message.contains("timeout", true) -> "$stage 连接超时，请检查地址、端口和网络"
            message.contains("UnknownHost", true) || message.contains("resolve", true) -> "$stage 地址无法解析"
            else -> "$stage 连接失败：${message.ifBlank { "未知错误" }}"
        }
    }
    fun sendRaw(text: String, onSuccess: () -> Unit = {}) {
        val stream = shellInput ?: run {
            sendError = "发送失败：SSH 尚未连接"
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                stream.write(text.toByteArray())
                stream.flush()
                withContext(Dispatchers.Main) {
                    sendError = null
                    onSuccess()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    sendError = "发送失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }
    fun sendCommand() {
        val value = command
        if (value.isNotBlank()) sendRaw(value + "\n") { if (command == value) command = "" }
    }
    fun confirmUnknown(alias: String, fingerprint: String): Boolean {
        val decision = CompletableFuture<Boolean>()
        val id = promptSequence.incrementAndGet()
        scope.launch(Dispatchers.Main) { hostPrompt = HostPrompt(id, alias, fingerprint, decision) }
        return try {
            decision.get(60, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            decision.complete(false)
            scope.launch(Dispatchers.Main) {
                if (hostPrompt?.id == id) hostPrompt = null
            }
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            decision.complete(false)
            scope.launch(Dispatchers.Main) { if (hostPrompt?.id == id) hostPrompt = null }
            false
        } catch (_: Exception) {
            decision.complete(false)
            scope.launch(Dispatchers.Main) { if (hostPrompt?.id == id) hostPrompt = null }
            false
        }
    }
    fun connect() {
        if (connecting || connected) return
        if (profile.targetAuth.method == SshAuthMethod.PASSWORD && profile.targetAuth.password.isBlank()) {
            append("请先通过“编辑连接”配置此服务器的 SSH 凭据。\r\n")
            return
        }
        connecting = true
        scope.launch {
            var stage = if (profile.useJump) "跳板机" else "目标主机"
            try {
                val result = withContext(Dispatchers.IO) {
                    val repository = SecureHostKeyRepository(context)
                    var jump: Session? = null
                    var target: Session? = null
                    try {
                        val targetHost: String
                        val targetPort: Int
                        if (profile.useJump) {
                            val jumpAlias = SshHostIdentity.name(profile.jumpHost, profile.jumpPort)
                            jump = SshSessionFactory.create(profile.jumpHost, profile.jumpPort, profile.jumpUser, profile.jumpAuth, repository, jumpAlias, ::confirmUnknown)
                            jump.connect(10000)
                            stage = "目标主机"; targetHost = "127.0.0.1"; targetPort = jump.setPortForwardingL(0, profile.host, profile.port)
                        } else { targetHost = profile.host; targetPort = profile.port }
                        val targetAlias = SshHostIdentity.name(profile.host, profile.port)
                        target = SshSessionFactory.create(targetHost, targetPort, profile.user, profile.targetAuth, repository, targetAlias, ::confirmUnknown)
                        target.connect(10000)
                        val channel = target.openChannel("shell") as ChannelShell
                        channel.setPtyType("xterm-256color", 80, 24, 0, 0); channel.connect(5000)
                        arrayOf(target, jump, channel, channel.inputStream)
                    } catch (e: Exception) {
                        runCatching { target?.disconnect() }; runCatching { jump?.disconnect() }
                        throw e
                    }
                }
                session = result[0] as Session; jumpSession = result[1] as Session?; shell = result[2] as ChannelShell; shellInput = (result[2] as ChannelShell).outputStream; connected = true
                append("\r\n已${if (profile.useJump) "通过跳板机" else "直接"}连接 ${profile.name} (${profile.host}:${profile.port})。\r\n")
                scope.launch(Dispatchers.IO) {
                    val input = result[3] as InputStream; val channel = result[2] as ChannelShell; val buffer = ByteArray(4096)
                    while (channel.isConnected) { val count = input.read(buffer); if (count <= 0) break; withContext(Dispatchers.Main) { append(String(buffer, 0, count, Charsets.UTF_8)) } }
                    withContext(Dispatchers.Main) { connected = false; append("\r\nSSH 连接已断开。\r\n") }
                }
            } catch (e: Exception) { append("\r\n${friendly(stage, e)}\r\n"); disconnect() } finally { connecting = false }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            hostPrompt?.decision?.complete(false)
            hostPrompt = null
            disconnect()
        }
    }
    LaunchedEffect(revision) {
        val atBottom = vertical.value >= vertical.maxValue - 8
        if (atBottom) vertical.scrollTo(vertical.maxValue)
    }

    if (hostPrompt != null) {
        val prompt = hostPrompt!!
        CompactConfirmationDialog(
            onDismissRequest = { if (hostPrompt?.id == prompt.id) { prompt.decision.complete(false); hostPrompt = null } },
            title = { Text("确认 SSH 主机指纹") },
            text = { Text("首次连接 ${prompt.alias}。\nSHA-256：${prompt.fingerprint}\n\n请与服务器控制台或管理员提供的指纹核对。") },
            confirmButton = { TextButton(onClick = { if (hostPrompt?.id == prompt.id) { prompt.decision.complete(true); hostPrompt = null } }) { Text("信任并继续") } },
            dismissButton = { TextButton(onClick = { if (hostPrompt?.id == prompt.id) { prompt.decision.complete(false); hostPrompt = null } }) { Text("拒绝") } }
        )
    }
    Scaffold(containerColor = PageBackground, topBar = {
        AppPageHeader("${profile.name} · SSH", onBack = { disconnect(); onBack() }) {
            TextButton(onClick = onEdit) { Text("编辑连接") }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (connecting) "连接中…" else if (connected) "已连接" else "未连接", fontSize = 12.sp, color = if (connected) Green else Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = ::connect, enabled = !connecting && !connected, modifier = Modifier.height(44.dp)) { Text(if (connecting) "连接中…" else "连接", fontSize = 12.sp) }
                TextButton(onClick = ::disconnect, enabled = connected) { Text("断开", color = if (connected) Red else Muted) }
            }
            Text("${profile.user}@${profile.host}:${profile.port} · 离开此页将断开连接", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth().background(TerminalBg, RoundedCornerShape(12.dp)).padding(8.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal)) {
                    terminal.render().forEach { line -> Text(line, color = TerminalFg, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, softWrap = false) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Ctrl+C" to "\u0003", "Ctrl+D" to "\u0004", "Esc" to "\u001B", "Tab" to "\t", "↑" to "\u001B[A", "↓" to "\u001B[B", "←" to "\u001B[D", "→" to "\u001B[C").forEach { (label, value) ->
                    OutlinedButton(onClick = { sendRaw(value) }, enabled = connected, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(label, fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(command, { command = it }, label = { Text("输入命令") }, singleLine = true, enabled = connected, modifier = Modifier.fillMaxWidth().padding(end = 80.dp))
                    TextButton(onClick = { quickOpen = true }, enabled = connected, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.align(Alignment.CenterEnd).height(44.dp)) { Text("快捷命令", fontSize = 11.sp) }
                    DropdownMenu(expanded = quickOpen, onDismissRequest = { quickOpen = false }) { listOf("htop", "top", "uptime", "df -h", "docker ps", "free -h").forEach { item -> DropdownMenuItem(text = { Text(item, fontFamily = FontFamily.Monospace) }, onClick = { command = item; quickOpen = false }) } }
                }
                Button(onClick = ::sendCommand, enabled = connected && command.isNotBlank(), modifier = Modifier.height(52.dp), contentPadding = PaddingValues(horizontal = 14.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentFill)) { Text("发送") }
            }
            sendError?.let { Text(it, color = Red, fontSize = 12.sp) }
        }
    }
}
