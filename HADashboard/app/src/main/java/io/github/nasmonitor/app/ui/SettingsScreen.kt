package io.github.nasmonitor.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nasmonitor.app.ApiClient
import io.github.nasmonitor.app.PreferenceHelper
import kotlinx.coroutines.launch


@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun SettingsScreen(onChangeToken: () -> Unit = {}) {
    val context = LocalContext.current
    var refreshSeconds by remember { mutableStateOf(PreferenceHelper.getRefreshInterval(context)) }
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(PreferenceHelper.getTheme(context)) }
    var accent by remember { mutableStateOf(PreferenceHelper.getAccentColor(context)) }
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(theme, systemDark) {
        applyTheme(if (theme == "system") (if (systemDark) "dark" else "light") else theme)
        setAccentColor(accent)
    }
    Scaffold(containerColor = PageBackground, topBar = { AppPageHeader("设置") }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = LocalFloatingNavigationPadding.current)) {
            LiquidAwareCard(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("服务器总览刷新", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 5, 10, 0).forEach { sec ->
                            AppFilterChip(
                                selected = refreshSeconds == sec,
                                onClick = { refreshSeconds = sec; PreferenceHelper.setRefreshInterval(context, sec) },
                                label = if (sec > 0) "$sec 秒" else "手动"
                            )
                        }
                    }
                    Text("仅影响服务器总览；其他页面按各自策略刷新。", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    Divider(color = Track, thickness = 1.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("外观主题", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "system" to "跟随系统",
                            "light" to "浅色",
                            "dark" to "深色",
                            "purple" to "紫夜",
                            "glass" to "玻璃",
                            "aurora" to "极光",
                            "sunset" to "日落"
                        ).forEach { (key, label) ->
                            AppFilterChip(
                                selected = theme == key,
                                onClick = {
                                    theme = key
                                    PreferenceHelper.setTheme(context, key)
                                    applyTheme(if (key == "system") (if (systemDark) "dark" else "light") else key)
                                },
                                label = label,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Divider(color = Track, thickness = 1.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("主题色", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentOptions.forEach { (name, color) ->
                            val selected = color.hex().equals(accent, ignoreCase = true)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).selectable(selected = selected, role = Role.RadioButton) {
                                    accent = color.hex()
                                    PreferenceHelper.setAccentColor(context, color.hex())
                                    setAccentColor(color.hex())
                                }.padding(vertical = 8.dp)
                            ) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(color)
                                    .border(if (selected) 2.dp else 1.dp, if (selected) Ink else Track, CircleShape))
                                Spacer(Modifier.height(4.dp))
                                Text(name, fontSize = 10.sp, color = if (selected) Blue else Muted)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            LiquidAwareCard(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onChangeToken),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(CardWhite),
                elevation = MonitorCardElevation(1.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("更换访问口令", fontSize = TextH2, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("重新验证 Monitor 访问权限", fontSize = 12.sp, color = Muted)
                    }
                    Text("›", fontSize = 24.sp, color = Muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = fontSize, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = BlueSoft,
            selectedLabelColor = Blue,
            containerColor = if (isGlassTheme()) GlassInnerFill else CardTint,
            labelColor = Muted
        )
    )
}
