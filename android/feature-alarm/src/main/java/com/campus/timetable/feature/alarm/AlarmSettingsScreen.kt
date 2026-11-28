package com.campus.timetable.feature.alarm

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.domain.model.*
import com.campus.timetable.domain.repository.AlarmRepository
import com.campus.timetable.domain.repository.TimetableRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlarmUiState(
    val globalSetting: AlarmSetting = AlarmSetting(id = "global_alarm"),
    val courses: List<CourseWithDetails> = emptyList(),
    val courseSettings: Map<String, AlarmSetting> = emptyMap(),
    val activeScheduledCount: Int = 0,
    val exactAlarmGranted: Boolean = true,
    val isTodayMuted: Boolean = false,
    val feedback: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlarmViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmRepository: AlarmRepository,
    private val timetableRepository: TimetableRepository,
    private val alarmScheduler: CourseAlarmScheduler,
    private val clock: AppClock
) : ViewModel() {

    private val _feedback = MutableStateFlow<String?>(null)
    private val _refreshTick = MutableStateFlow(0)

    private val coursesFlow = timetableRepository.observeCurrentSemester().flatMapLatest { sem ->
        if (sem == null) flowOf(emptyList()) else timetableRepository.observeCoursesWithDetails(sem.id)
    }

    val uiState: StateFlow<AlarmUiState> = combine(
        alarmRepository.observeGlobalSetting(),
        coursesFlow,
        _refreshTick,
        _feedback
    ) { global, courses, _, feedback ->
        val perCourse = alarmRepository.getAllAlarmSettings()
            .filter { it.courseId != null }
            .associateBy { it.courseId!! }
        AlarmUiState(
            globalSetting = global,
            courses = courses,
            courseSettings = perCourse,
            activeScheduledCount = alarmScheduler.getActiveScheduledCount(),
            exactAlarmGranted = alarmScheduler.canScheduleExactAlarms(),
            isTodayMuted = global.muteDate == clock.today().toString(),
            feedback = feedback
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlarmUiState())

    fun updateGlobal(transform: (AlarmSetting) -> AlarmSetting) {
        viewModelScope.launch {
            val next = transform(uiState.value.globalSetting)
            alarmRepository.saveAlarmSetting(next)
            alarmScheduler.rescheduleAllUpcomingAlarms("global_setting_changed")
            _refreshTick.value += 1
            _feedback.value = "闹钟设置已保存并重新调度未来 7 天课程提醒"
        }
    }

    fun toggleTodayMute() {
        viewModelScope.launch {
            if (uiState.value.isTodayMuted) {
                alarmRepository.clearMuteToday()
                _feedback.value = "已恢复今日课程提醒"
            } else {
                alarmRepository.muteTodayAll(clock.today().toString())
                _feedback.value = "已一键关闭今天所有课程提醒"
            }
            alarmScheduler.rescheduleAllUpcomingAlarms("mute_today_toggled")
            _refreshTick.value += 1
        }
    }

    fun toggleCourseAlarm(course: Course, enabled: Boolean) {
        viewModelScope.launch {
            val existing = uiState.value.courseSettings[course.id] ?: uiState.value.globalSetting.copy(
                id = "course_alarm_${course.id}",
                scopeType = AlarmScopeType.COURSE,
                courseId = course.id
            )
            alarmRepository.saveAlarmSetting(existing.copy(enabled = enabled))
            alarmScheduler.rescheduleAllUpcomingAlarms("course_alarm_toggled")
            _refreshTick.value += 1
            _feedback.value = "已${if (enabled) "开启" else "关闭"}「${course.name}」的课程提醒"
        }
    }

    fun testAlarmNow() {
        val g = uiState.value.globalSetting
        alarmScheduler.ensureNotificationChannels()
        val intent = Intent(context, CourseAlarmReceiver::class.java).apply {
            action = CourseAlarmScheduler.ACTION_FIRE_ALARM
            putExtra(CourseAlarmScheduler.EXTRA_REQUEST_CODE, 999001)
            putExtra(CourseAlarmScheduler.EXTRA_COURSE_NAME, "高等数学（测试提醒）")
            putExtra(CourseAlarmScheduler.EXTRA_LOCATION, "教学楼A栋302")
            putExtra(CourseAlarmScheduler.EXTRA_TIME_TEXT, "08:00-09:40")
            putExtra(CourseAlarmScheduler.EXTRA_IS_CLASS_END, false)
            putExtra(CourseAlarmScheduler.EXTRA_MODE, g.mode.name)
            putExtra(CourseAlarmScheduler.EXTRA_RINGTONE_URI, g.ringtoneUri)
            putExtra(CourseAlarmScheduler.EXTRA_VIBRATE, g.vibrate)
        }
        context.sendBroadcast(intent)
        _feedback.value = "已触发一次「${g.mode.label}」即时验证"
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            alarmScheduler.rescheduleAllUpcomingAlarms("diagnostics_refresh")
            _refreshTick.value += 1
        }
    }

    fun clearFeedback() { _feedback.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmSettingsScreen(
    viewModel: AlarmViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshDiagnostics() }

    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            viewModel.updateGlobal { it.copy(ringtoneUri = uri?.toString()) }
        }
    }

    val hasNotificationPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val canUseFullScreenIntent = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
        }.getOrDefault(true)
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val ignoringBatteryOpt = pm.isIgnoringBatteryOptimizations(context.packageName)

    var customAdvanceInput by remember(state.globalSetting.advanceMinutes) {
        mutableStateOf(state.globalSetting.advanceMinutes.toString())
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("智能上课闹钟") },
                actions = {
                    TextButton(onClick = { viewModel.testAlarmNow() }) { Text("即时测试") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 权限与后台存活诊断卡片
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("后台提醒权限与可靠性状态", style = MaterialTheme.typography.titleMedium)
                        Text("当前已调度未来 7 天提醒数：${state.activeScheduledCount} 条", style = MaterialTheme.typography.bodyMedium)

                        if (!hasNotificationPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Android 13+ 通知权限未授予", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                    Text("去授权")
                                }
                            }
                        }

                        if (!state.exactAlarmGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "未授予精确闹钟权限：当前已自动降级为系统非精确调度（可能存在数分钟偏差）。",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    TextButton(onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                                    data = Uri.parse("package:${context.packageName}")
                                                }
                                            )
                                        }
                                    }) { Text("前往系统设置开启精确闹钟") }
                                }
                            }
                        } else {
                            Text("精确闹钟权限：已就绪（支持手机重启、修改时间与时区切换后自动恢复）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }

                        if (!canUseFullScreenIntent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            state.globalSetting.mode == AlarmMode.ALARM_CLOCK
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Android 14+ 全屏闹钟提醒未授予（锁屏可能仅弹通知）", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                        )
                                    }
                                }) { Text("去开启") }
                            }
                        }

                        if (!ignoringBatteryOpt) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("电池优化限制中，可能影响厂商后台闹钟", style = MaterialTheme.typography.labelMedium)
                                TextButton(onClick = {
                                    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                                }) { Text("省电设置") }
                            }
                        }

                        Text(
                            "提示：若手动强制停止（Force Stop）应用或受厂商深度省电策略拦截，Android 系统会清除后台闹钟，请保持应用允许自启动与后台运行。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 全局总开关 + 一键关闭今日
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("开启上课提醒总开关", fontWeight = FontWeight.SemiBold)
                                Text("关闭后将取消全部定时提醒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = state.globalSetting.enabled,
                                onCheckedChange = { checked -> viewModel.updateGlobal { it.copy(enabled = checked) } }
                            )
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("一键关闭今天全部课程提醒", fontWeight = FontWeight.SemiBold)
                                Text("仅影响今天，明天自动恢复正常提醒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = state.isTodayMuted,
                                onCheckedChange = { viewModel.toggleTodayMute() }
                            )
                        }
                    }
                }
            }

            // 提醒模式、提前时间、铃声与震动
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("提醒模式", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AlarmMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.globalSetting.mode == mode,
                                    onClick = { viewModel.updateGlobal { it.copy(mode = mode) } },
                                    label = { Text(mode.label) }
                                )
                            }
                        }

                        Text("全局提前提醒时间", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(5, 10, 15, 30).forEach { min ->
                                FilterChip(
                                    selected = state.globalSetting.advanceMinutes == min,
                                    onClick = { viewModel.updateGlobal { it.copy(advanceMinutes = min) } },
                                    label = { Text("${min}分钟") }
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customAdvanceInput,
                                onValueChange = { customAdvanceInput = it.filter(Char::isDigit) },
                                label = { Text("自定义提前分钟") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(onClick = {
                                val v = (customAdvanceInput.toIntOrNull() ?: 15).coerceIn(1, 180)
                                viewModel.updateGlobal { it.copy(advanceMinutes = v) }
                            }) { Text("应用") }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("开启下课时间提醒")
                            Switch(
                                checked = state.globalSetting.enableClassEndReminder,
                                onCheckedChange = { v -> viewModel.updateGlobal { it.copy(enableClassEndReminder = v) } }
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("开启闹钟震动")
                            Switch(
                                checked = state.globalSetting.vibrate,
                                onCheckedChange = { v -> viewModel.updateGlobal { it.copy(vibrate = v) } }
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("自定义闹钟铃声")
                                Text(
                                    if (state.globalSetting.ringtoneUri.isNullOrBlank()) "系统默认闹钟铃声" else "已选择自定义铃声",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(onClick = {
                                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择上课闹钟铃声")
                                    state.globalSetting.ringtoneUri?.let {
                                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                                    }
                                }
                                ringtoneLauncher.launch(intent)
                            }) { Text("选择铃声") }
                        }
                    }
                }
            }

            // 按单课程独立控制开关
            if (state.courses.isNotEmpty()) {
                item { Text("单课程提醒快捷开关（更多细粒度设置可点击课程详情）", style = MaterialTheme.typography.titleMedium) }
                items(state.courses, key = { it.course.id }) { item ->
                    val override = state.courseSettings[item.course.id]
                    val enabled = override?.enabled ?: state.globalSetting.enabled
                    val adv = override?.advanceMinutes ?: state.globalSetting.advanceMinutes
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.course.name, fontWeight = FontWeight.Medium)
                                Text(
                                    "提前 ${adv} 分钟 · ${override?.mode?.label ?: state.globalSetting.mode.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { v -> viewModel.toggleCourseAlarm(item.course, v) }
                            )
                        }
                    }
                }
            }
        }
    }
}
