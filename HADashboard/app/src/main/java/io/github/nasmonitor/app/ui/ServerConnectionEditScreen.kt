package io.github.nasmonitor.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.github.nasmonitor.app.SshAuthMethod
import io.github.nasmonitor.app.SshCredential
import io.github.nasmonitor.app.SshHostIdentity
import io.github.nasmonitor.app.SshKeyTools
import io.github.nasmonitor.app.SshProfileStore
import io.github.nasmonitor.app.SecureHostKeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.nasmonitor.app.SecureCredentialStore

@Composable
fun ServerConnectionEditScreen(serverId: String, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isNas = serverId == "nas"
    val prefix = "server.$serverId"
    fun defaultName() = when (serverId) { "nas" -> "NAS"; "aliyun" -> "阿里云 ECS"; "tencent" -> "腾讯云服务器"; else -> io.github.nasmonitor.app.ServerRegistry.name(serverId) }
    fun defaultHost() = io.github.nasmonitor.app.ServerRegistry.host(serverId)
    fun defaultPort() = "22"
    fun defaultUser() = when (serverId) { "tencent" -> "ubuntu"; else -> "root" }
    fun defaultPassword() = if (isNas) SecureCredentialStore.get(context, "target.password") else if (serverId in listOf("aliyun", "tencent")) (if (serverId in listOf("nas", "aliyun", "tencent")) SecureCredentialStore.get(context, "jump.password") else "") else ""
    fun saved(key: String, fallback: String) = SecureCredentialStore.get(context, "$prefix.$key").ifEmpty { fallback }

    var name by rememberSaveable(serverId) { mutableStateOf(saved("name", defaultName())) }
    var host by rememberSaveable(serverId) { mutableStateOf(saved("host", defaultHost())) }
    var port by rememberSaveable(serverId) { mutableStateOf(saved("port", defaultPort())) }
    var user by rememberSaveable(serverId) { mutableStateOf(saved("user", defaultUser())) }
    var password by remember { mutableStateOf(saved("password", defaultPassword())) }
    var useJump by rememberSaveable(serverId) { mutableStateOf(saved("useJump", if (isNas) "true" else "false") == "true") }
    var jumpHost by rememberSaveable(serverId) { mutableStateOf(saved("jumpHost", "")) }
    var jumpPort by rememberSaveable(serverId) { mutableStateOf(saved("jumpPort", "22")) }
    var jumpUser by rememberSaveable(serverId) { mutableStateOf(saved("jumpUser", "root")) }
    var jumpPassword by remember { mutableStateOf(saved("jumpPassword", (if (serverId in listOf("nas", "aliyun", "tencent")) SecureCredentialStore.get(context, "jump.password") else ""))) }
    val targetStored = remember(serverId) { SshProfileStore.auth(context, prefix, false, defaultPassword()) }
    val jumpStored = remember(serverId) { SshProfileStore.auth(context, prefix, true, (if (serverId in listOf("nas", "aliyun", "tencent")) SecureCredentialStore.get(context, "jump.password") else "")) }
    var targetMethod by rememberSaveable(serverId) { mutableStateOf(targetStored.method) }
    var targetKey by remember { mutableStateOf(targetStored.privateKey) }
    var targetPassphrase by remember { mutableStateOf(targetStored.passphrase) }
    var jumpMethod by rememberSaveable(serverId) { mutableStateOf(jumpStored.method) }
    var jumpKey by remember { mutableStateOf(jumpStored.privateKey) }
    var jumpPassphrase by remember { mutableStateOf(jumpStored.passphrase) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var validationErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val nameFocus = remember { FocusRequester() }
    val hostFocus = remember { FocusRequester() }
    val portFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val jumpHostFocus = remember { FocusRequester() }
    val jumpPortFocus = remember { FocusRequester() }
    val jumpUserFocus = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(validationErrors) {
        when (validationErrors.keys.firstOrNull()) {
            "name" -> nameFocus.requestFocus()
            "host" -> hostFocus.requestFocus()
            "port" -> portFocus.requestFocus()
            "user" -> userFocus.requestFocus()
            "jumpHost" -> jumpHostFocus.requestFocus()
            "jumpPort" -> jumpPortFocus.requestFocus()
            "jumpUser" -> jumpUserFocus.requestFocus()
        }
    }

    val original = remember(serverId) {
        listOf(saved("name", defaultName()), saved("host", defaultHost()), saved("port", defaultPort()),
            saved("user", defaultUser()), saved("password", defaultPassword()),
            saved("useJump", if (isNas) "true" else "false"), saved("jumpHost", ""),
            saved("jumpPort", "22"), saved("jumpUser", "root"),
            saved("jumpPassword", (if (serverId in listOf("nas", "aliyun", "tencent")) SecureCredentialStore.get(context, "jump.password") else "")))
    }
    val dirty = listOf(name, host, port, user, password, useJump.toString(), jumpHost, jumpPort, jumpUser, jumpPassword) != original ||
        targetMethod != targetStored.method || targetKey != targetStored.privateKey || targetPassphrase != targetStored.passphrase ||
        jumpMethod != jumpStored.method || jumpKey != jumpStored.privateKey || jumpPassphrase != jumpStored.passphrase
    var confirmExit by remember { mutableStateOf(false) }
    fun requestBack() { if (dirty) confirmExit = true else onBack() }
    BackHandler { requestBack() }
    if (confirmExit) CompactConfirmationDialog(
        containerColor = OverlaySurface,
        onDismissRequest = { confirmExit = false },
        title = { Text("放弃未保存的修改？") },
        text = { Text("连接配置尚未保存。") },
        confirmButton = { TextButton(onClick = onBack) { Text("放弃修改", color = Red) } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续编辑") } }
    )

    fun save() {
        if (saving) return
        saving = true
        error = null
        validationErrors = emptyMap()
        try {
        val p = port.toIntOrNull()
        val jp = jumpPort.toIntOrNull()
        val fieldErrors = linkedMapOf<String, String>()
        if (name.isBlank()) fieldErrors["name"] = "请输入连接名称"
        if (host.isBlank()) fieldErrors["host"] = "请输入目标主机或 IP"
        if (p == null || p !in 1..65535) fieldErrors["port"] = "端口必须是 1～65535"
        if (user.isBlank()) fieldErrors["user"] = "请输入用户名"
        if (useJump) {
            if (jumpHost.isBlank()) fieldErrors["jumpHost"] = "请输入跳板机地址"
            if (jp == null || jp !in 1..65535) fieldErrors["jumpPort"] = "端口必须是 1～65535"
            if (jumpUser.isBlank()) fieldErrors["jumpUser"] = "请输入跳板机用户名"
        }
        if (fieldErrors.isNotEmpty()) {
            validationErrors = fieldErrors
            return
        }
        // 先完成全部私钥校验，任何失败都不触碰磁盘中的旧配置。
        if (targetMethod == SshAuthMethod.PRIVATE_KEY) SshKeyTools.validate(targetKey, targetPassphrase)
        if (useJump && jumpMethod == SshAuthMethod.PRIVATE_KEY) SshKeyTools.validate(jumpKey, jumpPassphrase)
        val values = mapOf("name" to name.trim(), "host" to host.trim(), "port" to port.trim(), "user" to user.trim(), "password" to password, "useJump" to useJump.toString(), "jumpHost" to jumpHost.trim(), "jumpPort" to jumpPort.trim(), "jumpUser" to jumpUser.trim(), "jumpPassword" to jumpPassword)
        val authKeys = listOf("target.auth", "target.privateKey", "target.passphrase", "jump.auth", "jump.privateKey", "jump.passphrase")
        val old = (values.keys.map { "$prefix.$it" } + authKeys.map { "$prefix.$it" }).associateWith { SecureCredentialStore.get(context, it) to SecureCredentialStore.contains(context, it) }
        try {
            values.forEach { (key, value) -> SecureCredentialStore.put(context, "$prefix.$key", value) }
            SshProfileStore.saveAuth(context, prefix, false, SshCredential(targetMethod, password, targetKey, targetPassphrase))
            SshProfileStore.saveAuth(context, prefix, true, SshCredential(jumpMethod, jumpPassword, jumpKey, jumpPassphrase))
        } catch (e: Exception) {
            old.forEach { (key, previous) -> if (previous.second) SecureCredentialStore.put(context, key, previous.first) else SecureCredentialStore.remove(context, key) }
            error = e.message ?: "连接配置保存失败，已保留原配置"
            return
        }
        onSaved()
        } catch (e: Exception) {
            error = e.message ?: "私钥无效，未保存配置"
        } finally { saving = false }
    }

    Scaffold(containerColor = PageBackground, topBar = {
        AppPageHeader("编辑连接", onBack = ::requestBack, subtitle = defaultName()) {
            TextButton(onClick = { save() }, enabled = !saving) { Text(if (saving) "保存中…" else "保存", color = Blue, fontWeight = FontWeight.SemiBold) }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 18.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (validationErrors.isNotEmpty() || error != null) {
                Text(
                    "请先修正：${(validationErrors.values + listOfNotNull(error)).distinct().joinToString("；")}",
                    color = Red,
                    fontSize = 12.sp
                )
            }
            Text("基本信息", fontSize = 12.sp, color = Muted)
            FrostCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it; validationErrors = validationErrors - "name" }, label = { Text("名称") }, singleLine = true, isError = "name" in validationErrors, supportingText = { validationErrors["name"]?.let { Text(it) } }, modifier = Modifier.fillMaxWidth().focusRequester(nameFocus))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(host, { host = it; validationErrors = validationErrors - "host" }, label = { Text(if (useJump) "目标星空组网地址" else "主机 / IP") }, singleLine = true, isError = "host" in validationErrors, supportingText = { validationErrors["host"]?.let { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f).focusRequester(hostFocus))
                        OutlinedTextField(port, { port = it; validationErrors = validationErrors - "port" }, label = { Text("端口") }, singleLine = true, isError = "port" in validationErrors, supportingText = { validationErrors["port"]?.let { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(92.dp).focusRequester(portFocus))
                    }
                    OutlinedTextField(user, { user = it; validationErrors = validationErrors - "user" }, label = { Text("用户名") }, singleLine = true, isError = "user" in validationErrors, supportingText = { validationErrors["user"]?.let { Text(it) } }, modifier = Modifier.fillMaxWidth().focusRequester(userFocus))
                    SshAuthEditor("目标主机", targetMethod, { targetMethod = it }, password, { password = it }, targetKey, { targetKey = it }, targetPassphrase, { targetPassphrase = it }, user, host, port.toIntOrNull() ?: 22)
                }
            }
            Text("连接方式", fontSize = 12.sp, color = Muted)
            FrostCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("通过跳板机", color = Ink, fontWeight = FontWeight.Medium); Text(if (useJump) "先连接下方跳板机，再转发到目标" else "直接连接此服务器", color = Muted, fontSize = 11.sp) }
                        Switch(checked = useJump, onCheckedChange = { useJump = it })
                    }
                    if (useJump) {
                        Divider(color = Track)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(jumpHost, { jumpHost = it; validationErrors = validationErrors - "jumpHost" }, label = { Text("跳板机公网地址") }, singleLine = true, isError = "jumpHost" in validationErrors, supportingText = { validationErrors["jumpHost"]?.let { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f).focusRequester(jumpHostFocus))
                            OutlinedTextField(jumpPort, { jumpPort = it; validationErrors = validationErrors - "jumpPort" }, label = { Text("端口") }, singleLine = true, isError = "jumpPort" in validationErrors, supportingText = { validationErrors["jumpPort"]?.let { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(92.dp).focusRequester(jumpPortFocus))
                        }
                        OutlinedTextField(jumpUser, { jumpUser = it; validationErrors = validationErrors - "jumpUser" }, label = { Text("跳板机用户名") }, singleLine = true, isError = "jumpUser" in validationErrors, supportingText = { validationErrors["jumpUser"]?.let { Text(it) } }, modifier = Modifier.fillMaxWidth().focusRequester(jumpUserFocus))
                        SshAuthEditor("跳板机", jumpMethod, { jumpMethod = it }, jumpPassword, { jumpPassword = it }, jumpKey, { jumpKey = it }, jumpPassphrase, { jumpPassphrase = it }, jumpUser, jumpHost, jumpPort.toIntOrNull() ?: 22)
                    }
                }
            }
            error?.let { Text(it, color = Red, fontSize = 12.sp) }
            Text("首次连接会显示 SHA-256 主机指纹；指纹变化将强制拒绝。", color = Amber, fontSize = 11.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshAuthEditor(label: String, method: SshAuthMethod, setMethod: (SshAuthMethod) -> Unit, password: String, setPassword: (String) -> Unit, privateKey: String, setPrivateKey: (String) -> Unit, passphrase: String, setPassphrase: (String) -> Unit, user: String, host: String, port: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { context.contentResolver.openInputStream(it)!!.bufferedReader().use { reader -> reader.readText() } }.onSuccess { key -> setPrivateKey(key); status = "已导入私钥，保存前将验证" }.onFailure { status = "导入失败" } }
    }
    val publicKey = remember(privateKey, passphrase, user, host) { runCatching { if (privateKey.isBlank()) "" else SshKeyTools.publicKey(privateKey, passphrase, "$user@$host") }.getOrDefault("") }
    val exportKey = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { runCatching { context.contentResolver.openOutputStream(it)!!.bufferedWriter().use { writer -> writer.write(publicKey + "\n") } }.onSuccess { status = "公钥已导出" }.onFailure { status = "导出失败" } }
    }
    fun generateKey() {
        if (generating) return
        generating = true
        scope.launch {
            try {
                val generated = withContext(Dispatchers.Default) { SshKeyTools.generateRsa3072("$user@$host") }
                setPrivateKey(generated.privateKey)
                status = "已生成 RSA 3072 密钥"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "生成失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                generating = false
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = method == SshAuthMethod.PASSWORD, onClick = { setMethod(SshAuthMethod.PASSWORD) }, enabled = !generating, label = { Text("密码") })
        FilterChip(selected = method == SshAuthMethod.PRIVATE_KEY, onClick = { setMethod(SshAuthMethod.PRIVATE_KEY) }, enabled = !generating, label = { Text("私钥") })
    }
    if (method == SshAuthMethod.PASSWORD) {
        OutlinedTextField(password, setPassword, label = { Text("$label 密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    } else {
        OutlinedTextField(passphrase, setPassphrase, label = { Text("私钥口令（如有）") }, singleLine = true, enabled = !generating, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text(if (privateKey.isBlank()) "尚未配置私钥" else "私钥已配置（仅以 Android Keystore 加密保存）", color = Muted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { importKey.launch(arrayOf("application/x-pem-file", "text/plain", "application/octet-stream")) }, enabled = !generating) { Text("导入私钥") }
            TextButton(
                onClick = { if (privateKey.isBlank()) generateKey() else confirmReplace = true },
                enabled = !generating
            ) {
                if (generating) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("生成中…")
                } else Text(if (privateKey.isBlank()) "生成密钥" else "替换密钥")
            }
        }
        if (publicKey.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SSH public key", publicKey)); status = "公钥已复制" }, enabled = !generating) { Text("复制公钥") }
            TextButton(onClick = { exportKey.launch("${host.ifBlank { "ssh" }}.pub") }, enabled = !generating) { Text("导出公钥") }
        }
    }
    val hostName = SshHostIdentity.name(host, port)
    val repository = remember { SecureHostKeyRepository(context) }
    repository.fingerprint(hostName)?.let { fingerprint ->
        Text("已信任 $hostName\n$fingerprint", color = Muted, fontSize = 10.sp)
        TextButton(onClick = { repository.forget(hostName); status = "已忘记 $hostName 的主机指纹" }) { Text("忘记主机指纹", color = Red) }
    }
    status?.let { Text(it, color = if (it.contains("失败") || it.contains("无效")) Red else Green, fontSize = 11.sp) }
    if (confirmReplace) {
        CompactConfirmationDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("替换已有私钥？") },
            text = { Text("当前私钥尚未保存的新旧内容都会被替换，请确认后再生成。") },
            confirmButton = {
                TextButton(onClick = { confirmReplace = false; generateKey() }) { Text("替换", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } }
        )
    }
}
