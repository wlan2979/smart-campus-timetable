package com.campus.timetable.feature.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class WidgetKind(
    val title: String,
    val subtitle: String,
    val sizeHint: String,
    val receiverClass: Class<*>
) {
    TODAY("今日概览", "一屏聚合日期教学周、下一节课倒计时、实时天气与今日课程", "推荐 4×3", TodayCoursesWidgetReceiver::class.java),
    NEXT("下一节课倒计时", "显示下一节课程名称、剩余倒计时与上课地点", "推荐 3×2", NextCourseCountdownWidgetReceiver::class.java),
    WEEKLY("本周课表", "清晰概览周一至周日整周课程安排，自适应桌面大小", "推荐 4×4", WeeklyTimetableWidgetReceiver::class.java),
    WEATHER_PE("天气与体育课提醒", "显示当前气温天气、今日体育课与恶劣天气提醒", "推荐 4×2", WeatherPeWidgetReceiver::class.java)
}

data class BrandGuideInfo(
    val brandName: String,
    val manualSteps: List<String>,
    val refreshTip: String
)

object DeviceWidgetBrandGuide {
    fun resolveForCurrentDevice(manufacturer: String = Build.MANUFACTURER.orEmpty()): BrandGuideInfo {
        val m = manufacturer.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> BrandGuideInfo(
                brandName = "小米 / Redmi（HyperOS / MIUI）",
                manualSteps = listOf(
                    "1. 返回手机桌面，双指在空白处向内捏合，或长按桌面空白处。",
                    "2. 点击底部「添加小部件」→ 在搜索框输入「桃课」，或在全部小部件底部选择「Android 小部件 → 桃课」。",
                    "3. 选择需要的小组件拖放到桌面即可。"
                ),
                refreshTip = "若发现小组件长时间不自动刷新，可在「手机管家 → 应用管理 → 桃课」中开启「允许自启动」并将省电策略设为「无限制」（此为厂商后台策略，并非 Android 运行时权限）。"
            )
            m.contains("huawei") -> BrandGuideInfo(
                brandName = "华为（HarmonyOS / EMUI）",
                manualSteps = listOf(
                    "1. 返回手机桌面，双指在空白处向内捏合进入桌面编辑。",
                    "2. 点击底部「服务卡片」→ 滑至最底部进入「窗口小工具」。",
                    "3. 找到「桃课」，选择对应小组件添加到桌面。"
                ),
                refreshTip = "若后台长时间未刷新，可在「设置 → 应用和服务 → 应用启动管理 → 桃课」中关闭自动管理并允许后台活动。"
            )
            m.contains("honor") -> BrandGuideInfo(
                brandName = "荣耀（MagicOS）",
                manualSteps = listOf(
                    "1. 双指在桌面空白处捏合，点击底部「桌面卡片」。",
                    "2. 向下滑动到底部点击「经典小组件 / 窗口小工具」。",
                    "3. 找到「桃课」并点选添加至桌面。"
                ),
                refreshTip = "若需定时后台刷新，可在「手机管家 → 应用启动管理」中允许「桃课」后台活动。"
            )
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> BrandGuideInfo(
                brandName = "OPPO / 一加 / 真我（ColorOS）",
                manualSteps = listOf(
                    "1. 长按桌面空白处或双指捏合，点击左上角「+」或底部「卡片」。",
                    "2. 下拉选择「通用插件 / Android 小组件」，找到「桃课」。",
                    "3. 点击或长按拖动到桌面空白位置。"
                ),
                refreshTip = "若小组件隔天未刷新，可在「设置 → 应用 → 自启动」中允许「桃课」自启动并关闭深度省电冻结。"
            )
            m.contains("vivo") || m.contains("iqoo") -> BrandGuideInfo(
                brandName = "vivo / iQOO（OriginOS）",
                manualSteps = listOf(
                    "1. 在桌面空白处长按或上滑呼出「原子组件」库。",
                    "2. 在组件列表中找到「桃课」（或滑至底部「Android 挂件 → 桃课」）。",
                    "3. 拖放到桌面即可使用。"
                ),
                refreshTip = "若需要跨天自动更新，可在「i管家 → 应用管理 → 权限管理 → 自启动」中允许「桃课」后台高耗电与自启动。"
            )
            m.contains("samsung") -> BrandGuideInfo(
                brandName = "三星（One UI）",
                manualSteps = listOf(
                    "1. 长按手机桌面空白处，点击底部「小组件」。",
                    "2. 搜索或滑动找到「桃课」，展开查看全部 4 种小组件。",
                    "3. 点击「添加」或拖动到桌面。"
                ),
                refreshTip = "若被电池休眠限制刷新，可在「设置 → 电池 → 后台使用限制」中将「桃课」移出休眠应用。"
            )
            else -> BrandGuideInfo(
                brandName = "原生 Android / Pixel / 其他品牌",
                manualSteps = listOf(
                    "1. 长按手机桌面空白区域，点击「微件 / 小组件（Widgets）」。",
                    "2. 在列表中找到「桃课」，展开全部 4 种课表与天气小组件。",
                    "3. 长按并拖动到桌面即可完成添加。"
                ),
                refreshTip = "原生 Android 桌面小组件无需额外运行时权限，课程或作息修改后会自动刷新。"
            )
        }
    }
}

data class WidgetsCenterUiState(
    val today: TodayWidgetData? = null,
    val nextCourse: NextCourseWidgetData? = null,
    val weekly: WeeklyWidgetData? = null,
    val weatherPe: WeatherPeWidgetData? = null,
    val pinnedCounts: Map<WidgetKind, Int> = emptyMap(),
    val pinSupportedByLauncher: Boolean = false,
    val brandGuide: BrandGuideInfo = DeviceWidgetBrandGuide.resolveForCurrentDevice(),
    val feedback: String? = null
)

@HiltViewModel
class WidgetsCenterViewModel @Inject constructor(
    private val loader: WidgetSnapshotLoader,
    private val refresher: GlanceWidgetRefresher
) : ViewModel() {

    private val _uiState = MutableStateFlow(WidgetsCenterUiState())
    val uiState: StateFlow<WidgetsCenterUiState> = _uiState.asStateFlow()

    fun refreshAll(context: Context) {
        viewModelScope.launch {
            val mgr = AppWidgetManager.getInstance(context)
            val pinSupported = mgr.isRequestPinAppWidgetSupported
            val counts = WidgetKind.entries.associateWith { refresher.getPinnedCount(it.receiverClass) }
            val today = runCatching { loader.loadToday() }.getOrNull()
            val next = runCatching { loader.loadNextCourse() }.getOrNull()
            val weekly = runCatching { loader.loadWeekly() }.getOrNull()
            val weatherPe = runCatching { loader.loadWeatherPe() }.getOrNull()
            _uiState.update {
                it.copy(
                    today = today,
                    nextCourse = next,
                    weekly = weekly,
                    weatherPe = weatherPe,
                    pinnedCounts = counts,
                    pinSupportedByLauncher = pinSupported
                )
            }
        }
    }

    fun triggerImmediateWidgetUpdate(context: Context) {
        viewModelScope.launch {
            refresher.refreshAllWidgets()
            refreshAll(context)
            _uiState.update { it.copy(feedback = "已刷新全部 4 种桌面小组件的实时课表与天气数据") }
        }
    }

    fun requestPinWidget(context: Context, kind: WidgetKind, onUnsupported: () -> Unit) {
        val mgr = AppWidgetManager.getInstance(context)
        if (!mgr.isRequestPinAppWidgetSupported) {
            onUnsupported()
            return
        }
        val provider = ComponentName(context, kind.receiverClass)
        val callbackIntent = Intent(context, WidgetPinnedCallbackReceiver::class.java).apply {
            putExtra("widget_label", kind.title)
        }
        val successCallback = PendingIntent.getBroadcast(
            context,
            kind.ordinal + 7000,
            callbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val launched = runCatching {
            mgr.requestPinAppWidget(provider, null, successCallback)
        }.getOrDefault(false)

        if (launched) {
            _uiState.update {
                it.copy(feedback = "已向系统桌面发起「${kind.title}」添加请求，请在系统弹窗中点击确认（如桌面未弹窗请参考下方手动指南）")
            }
        } else {
            onUnsupported()
        }
    }

    fun clearFeedback() { _uiState.update { it.copy(feedback = null) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetsCenterScreen(
    onBack: () -> Unit,
    viewModel: WidgetsCenterViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showManualGuideDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshAll(context) }

    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("添加与管理桌面小组件") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("返回")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.triggerImmediateWidgetUpdate(context) }) { Text("立即刷新") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 权限说明 + 系统支持状态
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("关于 Android 桌面小组件", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Android 原生桌面小组件不需要额外运行时权限。下方点击「一键添加到桌面」将直接调用系统固定小组件接口由系统确认添加；若当前手机桌面不支持一键固定，可按下方「${state.brandGuide.brandName}」图文步骤手动添加。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = { showManualGuideDialog = true },
                                label = { Text("查看 ${state.brandGuide.brandName} 添加教程") },
                                leadingIcon = { Icon(Icons.Outlined.HelpOutline, null, Modifier.size(16.dp)) }
                            )
                        }
                    }
                }
            }

            // 4 种小组件实时预览与一键添加卡片
            WidgetKind.entries.forEach { kind ->
                item(key = kind.name) {
                    val pinnedCount = state.pinnedCounts[kind] ?: 0
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${kind.title}（${kind.sizeHint}）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(kind.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (pinnedCount > 0) {
                                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                                        Text(
                                            "桌面已添 $pinnedCount 个",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // 实时真实数据预览框
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    when (kind) {
                                        WidgetKind.TODAY -> {
                                            val d = state.today
                                            Text(
                                                "${d?.dateLabel ?: "今日"} · ${d?.weekLabel.orEmpty()}",
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            d?.weatherText?.takeIf { it.isNotBlank() }?.let {
                                                Text("天气：$it", style = MaterialTheme.typography.bodyMedium)
                                            }
                                            d?.nextCourseText?.takeIf { it.isNotBlank() }?.let {
                                                Text(
                                                    listOf(it, d.nextCourseMeta).filter { m -> m.isNotBlank() }.joinToString(" · "),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            if (d == null || d.sessions.isEmpty()) {
                                                Text("今日暂无课程安排", style = MaterialTheme.typography.bodyMedium)
                                            } else {
                                                d.sessions.take(3).forEach { s ->
                                                    Text("${s.startTime} ${s.course.name} · ${s.effectiveLocation.ifBlank { "待定" }}", style = MaterialTheme.typography.bodyMedium)
                                                }
                                            }
                                        }
                                        WidgetKind.NEXT -> {
                                            val d = state.nextCourse
                                            if (d == null || !d.hasNextCourse) {
                                                Text(d?.emptyMessage ?: "今日剩余时间暂无课程", style = MaterialTheme.typography.bodyMedium)
                                            } else {
                                                Text(d.courseName, fontWeight = FontWeight.Bold)
                                                Text(d.countdownText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                                Text("${d.timeText} · ${d.locationText}", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                        WidgetKind.WEEKLY -> {
                                            val d = state.weekly
                                            Text("${d?.weekLabel ?: "本周"} · ${d?.semesterName.orEmpty()}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                            d?.days?.take(5)?.forEach { day ->
                                                val text = if (day.sessions.isEmpty()) "无课" else day.sessions.take(2).joinToString(" / ") { it.course.name }
                                                Text("${day.dayShortZh}${if (day.isToday) "*" else ""}：$text", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                        WidgetKind.WEATHER_PE -> {
                                            val d = state.weatherPe
                                            Text("${d?.cityName ?: "北京"} · ${d?.temperatureText ?: "--°C"} ${d?.conditionText.orEmpty()}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                            Text(
                                                if (d == null || d.todayPeCourses.isEmpty()) "今日体育课：无" else "今日体育课：${d.todayPeCourses.joinToString("、")}",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(d?.adviceText ?: "暂无天气预警", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.requestPinWidget(context, kind) { showManualGuideDialog = true }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.AddToHomeScreen, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("添加到桌面")
                                }
                                OutlinedButton(onClick = { showManualGuideDialog = true }) {
                                    Text("手动步骤")
                                }
                            }
                        }
                    }
                }
            }

            // 分品牌桌面添加与后台刷新参考说明
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("如何添加桌面小组件（已识别：${state.brandGuide.brandName}）", style = MaterialTheme.typography.titleMedium)
                        state.brandGuide.manualSteps.forEach { step ->
                            Text(step, style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("小组件自动刷新说明", fontWeight = FontWeight.SemiBold)
                        Text(state.brandGuide.refreshTip, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                    }
                                )
                            }
                        }) { Text("打开本应用系统信息页（可选调整电池策略）") }
                    }
                }
            }
        }
    }

    if (showManualGuideDialog) {
        AlertDialog(
            onDismissRequest = { showManualGuideDialog = false },
            title = { Text("手动添加小组件步骤（以${state.brandGuide.brandName}为例）", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!state.pinSupportedByLauncher) {
                        Text("当前手机桌面暂不支持应用内直接一键固定小组件，请按以下步骤从系统桌面手动添加：", color = MaterialTheme.colorScheme.primary)
                    }
                    state.brandGuide.manualSteps.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(4.dp))
                    Text(state.brandGuide.refreshTip, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showManualGuideDialog = false }) { Text("我知道了") } }
        )
    }
}
