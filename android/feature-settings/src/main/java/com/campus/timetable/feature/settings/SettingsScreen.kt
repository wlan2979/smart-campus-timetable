package com.campus.timetable.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.campus.timetable.core.config.AppRuntimeConfig
import com.campus.timetable.core.theme.AppThemeMode
import com.campus.timetable.core.theme.ThemeSeedColor
import com.campus.timetable.domain.repository.SettingsRepository
import com.campus.timetable.domain.repository.WidgetRefresherPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val seedColor: ThemeSeedColor = ThemeSeedColor.OCEAN_BLUE,
    val appVersion: String = "2.2.4",
    val feedback: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val widgetRefresher: WidgetRefresherPort,
    private val config: AppRuntimeConfig
) : ViewModel() {

    private val _feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.observeThemeMode(),
        settingsRepository.observeSeedColor(),
        _feedback
    ) { modeStr, seedKey, fb ->
        SettingsUiState(
            themeMode = runCatching { AppThemeMode.valueOf(modeStr) }.getOrDefault(AppThemeMode.SYSTEM),
            seedColor = ThemeSeedColor.fromKey(seedKey),
            appVersion = config.appVersion,
            feedback = fb
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState(appVersion = config.appVersion))

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode.name) }
    }

    fun setSeedColor(seed: ThemeSeedColor) {
        viewModelScope.launch { settingsRepository.setSeedColor(seed.key) }
    }

    fun clearLocalSessions() {
        viewModelScope.launch {
            settingsRepository.clearSensitiveLocalSessions()
            _feedback.value = "已清除本地 Keystore 加密会话缓存"
        }
    }

    fun refreshWidgetsNow() {
        viewModelScope.launch {
            widgetRefresher.refreshAllWidgets()
            _feedback.value = "已向全部 4 个桌面小组件发送刷新指令"
        }
    }

    fun clearFeedback() { _feedback.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenSemester: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenHoliday: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenWidgets: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("桃课 · 设置与偏好") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column {
                        ListItem(
                            headlineContent = { Text("学期与作息时间管理") },
                            supportingContent = { Text("学期切换、开学日期、总周数；自定义课时、小/大课间、午休晚间、按星期独立作息与多套模板") },
                            leadingContent = { Icon(Icons.Outlined.Schedule, null) },
                            modifier = Modifier.clickable { onOpenSemester() }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("添加桌面小组件 / 如何添加桌面小组件") },
                            supportingContent = { Text("一键固定 4 种原生小组件到桌面 + 分品牌（小米/华为/OPPO/vivo/三星/Pixel）教程") },
                            leadingContent = { Icon(Icons.Outlined.Widgets, null) },
                            modifier = Modifier.clickable { onOpenWidgets() }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("账号、云同步与手动备份") },
                            supportingContent = { Text("手机号验证码登录、游客数据合并、离线 JSON 导出/恢复") },
                            leadingContent = { Icon(Icons.Outlined.CloudSync, null) },
                            modifier = Modifier.clickable { onOpenSync() }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("法定节假日与学校校历") },
                            supportingContent = { Text("连续假期自动合并展示、单次节前提醒与大学特殊校历覆盖") },
                            leadingContent = { Icon(Icons.Outlined.EventAvailable, null) },
                            modifier = Modifier.clickable { onOpenHoliday() }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("天气与体育课提醒") },
                            supportingContent = { Text("Open-Meteo / 和风天气数据源、城市搜索、按需定位与体育课预警阈值") },
                            leadingContent = { Icon(Icons.Outlined.WbSunny, null) },
                            modifier = Modifier.clickable { onOpenWeather() }
                        )
                    }
                }
            }

            // 主题与深色模式
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("外观主题（Material Design 3）", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                        Text("自定义主题主色", fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ThemeSeedColor.entries.forEach { seed ->
                                val selected = state.seedColor == seed
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(seed.primaryLight)
                                            .then(
                                                if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                                else Modifier
                                            )
                                            .clickable { viewModel.setSeedColor(seed) }
                                    )
                                    Text(seed.label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            // 隐私安全与快捷操作
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("桌面小组件与隐私安全", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onOpenWidgets) { Text("添加桌面小组件") }
                            FilledTonalButton(onClick = { viewModel.refreshWidgetsNow() }) { Text("立即刷新") }
                            OutlinedButton(onClick = { viewModel.clearLocalSessions() }) { Text("清除会话") }
                        }
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                            Text(
                                "隐私承诺：「桃课」采用本地优先存储，不收集非必要个人信息，绝不保存或向云端同步您的学校教务账号密码。版本 v${state.appVersion}。",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
