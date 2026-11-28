package com.campus.timetable.feature.holiday

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.domain.engine.HolidayCalendarResolver
import com.campus.timetable.domain.model.*
import com.campus.timetable.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class HolidayUiState(
    val selectedYear: Int = LocalDate.now().year,
    val legalHolidayEvents: List<Holiday> = emptyList(),
    val statutoryWorkdays: List<Holiday> = emptyList(),
    val schoolOverrides: List<SchoolCalendarOverride> = emptyList(),
    val syncStatusNotice: String? = null,
    val isSyncing: Boolean = false,
    val holidayReminderAdvanceDays: Int = 1
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HolidayViewModel @Inject constructor(
    private val holidayRepository: HolidayRepository,
    private val timetableRepository: TimetableRepository,
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: AlarmSchedulerPort,
    private val weatherScheduler: WeatherSchedulerPort,
    private val widgetRefresher: WidgetRefresherPort,
    private val clock: AppClock
) : ViewModel() {

    private val _year = MutableStateFlow(clock.today().year)
    private val _syncNotice = MutableStateFlow<String?>(null)
    private val _isSyncing = MutableStateFlow(false)
    private val reminderDaysFlow = settingsRepository.observeHolidayReminderAdvanceDays()

    private val holidaysFlow = _year.flatMapLatest { y -> holidayRepository.observeHolidaysByYear(y) }
    private val overridesFlow = timetableRepository.observeCurrentSemester().flatMapLatest { sem ->
        if (sem == null) flowOf(emptyList()) else holidayRepository.observeSchoolOverrides(sem.id)
    }

    val uiState: StateFlow<HolidayUiState> = combine(
        _year, holidaysFlow, overridesFlow, _syncNotice, _isSyncing, reminderDaysFlow
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val allHolidays = arr[1] as List<Holiday>
        HolidayUiState(
            selectedYear = arr[0] as Int,
            legalHolidayEvents = allHolidays.filter { it.isOffDay },
            statutoryWorkdays = allHolidays.filter { !it.isOffDay },
            schoolOverrides = arr[2] as List<SchoolCalendarOverride>,
            syncStatusNotice = arr[3] as String?,
            isSyncing = arr[4] as Boolean,
            holidayReminderAdvanceDays = arr[5] as Int
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HolidayUiState(selectedYear = clock.today().year))

    fun selectYear(year: Int) {
        _year.value = year
        _syncNotice.value = null
    }

    fun syncSelectedYearFromOfficialSource() {
        viewModelScope.launch {
            val y = _year.value
            val advanceDays = reminderDaysFlow.first()
            _isSyncing.value = true
            val status = holidayRepository.syncYearHolidays(y, advanceDays)
            _isSyncing.value = false
            _syncNotice.value = status.notice
            alarmScheduler.rescheduleAllUpcomingAlarms("holidays_synced")
            weatherScheduler.schedulePeWeatherChecks()
            widgetRefresher.refreshAllWidgets()
        }
    }

    fun addSchoolOverride(
        dateIso: String,
        type: CalendarOverrideType,
        mappedDayOfWeek: Int?,
        title: String,
        note: String,
        enableAlarmOverride: Boolean
    ) {
        viewModelScope.launch {
            val parsed = runCatching { LocalDate.parse(dateIso.trim()) }.getOrNull()
            if (parsed == null || title.isBlank()) {
                _syncNotice.value = "请填写正确的日期（YYYY-MM-DD）和校历标题"
                return@launch
            }
            val sem = timetableRepository.getCurrentSemester() ?: return@launch
            holidayRepository.saveSchoolOverride(
                SchoolCalendarOverride(
                    semesterId = sem.id,
                    date = parsed.toString(),
                    overrideType = type,
                    mappedDayOfWeek = if (type == CalendarOverrideType.MAKEUP_CLASS) mappedDayOfWeek ?: 1 else null,
                    title = title.trim(),
                    note = note.trim(),
                    enableAlarmOverride = enableAlarmOverride
                )
            )
            alarmScheduler.rescheduleAllUpcomingAlarms("school_override_saved")
            weatherScheduler.schedulePeWeatherChecks()
            widgetRefresher.refreshAllWidgets()
            _syncNotice.value = "已保存学校校历覆盖规则（优先级高于法定节假日），并已更新课程闹钟"
        }
    }

    fun deleteSchoolOverride(id: String) {
        viewModelScope.launch {
            holidayRepository.deleteSchoolOverride(id)
            alarmScheduler.rescheduleAllUpcomingAlarms("school_override_deleted")
            widgetRefresher.refreshAllWidgets()
            _syncNotice.value = "已删除该条学校校历规则"
        }
    }

    fun setReminderAdvanceDays(days: Int) {
        val safe = days.coerceIn(0, 7)
        viewModelScope.launch {
            settingsRepository.setHolidayReminderAdvanceDays(safe)
            holidayRepository.updateHolidayReminderDays(_year.value, safe)
            alarmScheduler.rescheduleAllUpcomingAlarms("holiday_reminder_updated")
            _syncNotice.value = if (safe == 0) {
                "已关闭节前提醒（设置已持久保存）"
            } else {
                "已设置每个连续假期提前 $safe 天提醒一次（绝不重复提醒，设置已保存）"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HolidayScreen(
    onBack: () -> Unit,
    viewModel: HolidayViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddOverrideDialog by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("节假日与校历", maxLines = 1) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showAddOverrideDialog = true }) { Text("+ 校历覆盖") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 年份选择与同步按钮
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { viewModel.selectYear(state.selectedYear - 1) }) { Text("上一年") }
                            Text("${state.selectedYear} 年", style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { viewModel.selectYear(state.selectedYear + 1) }) { Text("下一年") }
                        }
                        Button(
                            enabled = !state.isSyncing,
                            onClick = { viewModel.syncSelectedYearFromOfficialSource() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (state.isSyncing) "同步中..." else "同步国务院安排") }

                        val metaSample = state.legalHolidayEvents.firstOrNull() ?: state.statutoryWorkdays.firstOrNull()
                        if (metaSample != null) {
                            Text(
                                "数据来源：${metaSample.source}\n版本：${metaSample.version} · 更新：${fmt.format(Date(metaSample.updatedAt))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        state.syncStatusNotice?.let { notice ->
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                                Text(notice, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        Text(
                            "合并与调休规则：同一个连续法定假期自动合并为单条完整区间（每个假期仅创建 1 条节前提醒），放假期间默认暂停普通课程闹钟；国务院周末调休工作日不会自动等同大学补课日，学校校历覆盖永远优先。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 节假日前单次提醒设置
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column {
                            Text("连续假期节前提醒", fontWeight = FontWeight.Medium)
                            Text("每个连续假期仅提醒一次", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(0 to "关", 1 to "前1天", 2 to "前2天", 3 to "前3天").forEach { (d, label) ->
                                FilterChip(
                                    selected = state.holidayReminderAdvanceDays == d,
                                    onClick = { viewModel.setReminderAdvanceDays(d) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }

            // 1. 合并后的完整法定节假日区间列表
            item {
                Text(
                    "${state.selectedYear} 年法定节假日（已按连续假期合并 · 共 ${state.legalHolidayEvents.size} 个假期）",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (state.legalHolidayEvents.isEmpty()) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Text(
                            "本地尚未同步 ${state.selectedYear} 年法定节假日数据，请点击上方「同步国务院安排」。若国务院尚未公布该年通知，系统会明确提示而不虚构。",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(state.legalHolidayEvents, key = { it.id }) { h ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 连续区间色条视觉标识
                            Box(
                                modifier = Modifier
                                    .width(5.dp)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(h.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(
                                            text = "共 ${h.totalDays} 天",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "${h.formatDateRangeZh()}（${h.date}${if (h.endDate != h.date) " 至 ${h.endDate}" else ""}）",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = buildString {
                                        append("期间默认暂停普通课程闹钟")
                                        h.reminderDate?.let { append(" · 节前提醒 $it（仅1次）") }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 2. 国务院调休工作日（与法定假期严格区分，不自动等同大学补课日）
            if (state.statutoryWorkdays.isNotEmpty()) {
                item {
                    Text(
                        "${state.selectedYear} 年国务院周末调休工作日（高校补课以学校校历为准）",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(state.statutoryWorkdays, key = { it.id }) { w ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${w.formatDateRangeZh()} · ${w.name}（调休班）", fontWeight = FontWeight.Medium)
                                Text(
                                    "国务院调休工作日，系统不会自动套用补课；如贵校安排补课请添加下方学校校历覆盖。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            AssistChip(onClick = { showAddOverrideDialog = true }, label = { Text("设校历") })
                        }
                    }
                }
            }

            // 3. 学校特殊校历覆盖（最高优先级）
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("学校特殊校历覆盖（优先于国家法定节假日）", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showAddOverrideDialog = true }) { Text("新增") }
                }
            }
            if (state.schoolOverrides.isEmpty()) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Text(
                            "暂无学校校历自定义项。可添加：学校单独放假、法定假日照常上课（恢复闹钟）、按指定星期补课或全校临时停课。",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(state.schoolOverrides, key = { it.id }) { ov ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${ov.date} · ${ov.title}（${ov.overrideType.label}）", fontWeight = FontWeight.SemiBold)
                                ov.mappedDayOfWeek?.let { dow ->
                                    Text("补课映射：按周${HolidayCalendarResolver.dayOfWeekShortZh(dow)}课表上课", style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(
                                    if (ov.enableAlarmOverride) "允许恢复课程闹钟" else "暂停普通课程闹钟",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            TextButton(onClick = { viewModel.deleteSchoolOverride(ov.id) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddOverrideDialog) {
        var dateInput by remember { mutableStateOf(LocalDate.now().toString()) }
        var type by remember { mutableStateOf(CalendarOverrideType.MAKEUP_CLASS) }
        var mappedDow by remember { mutableIntStateOf(3) }
        var title by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var enableAlarm by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showAddOverrideDialog = false },
            title = { Text("添加学校特殊校历覆盖") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = dateInput, onValueChange = { dateInput = it }, label = { Text("日期 (YYYY-MM-DD)") }, singleLine = true)
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("校历说明（如：国庆调课补周三）") }, singleLine = true)
                    CalendarOverrideType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                    }
                    if (type == CalendarOverrideType.MAKEUP_CLASS) {
                        Text("选择当天按星期几的课表上课：", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..7).forEach { d ->
                                FilterChip(
                                    selected = mappedDow == d,
                                    onClick = { mappedDow = d },
                                    label = { Text(HolidayCalendarResolver.dayOfWeekShortZh(d)) }
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("当日恢复正常课程闹钟")
                        Switch(checked = enableAlarm, onCheckedChange = { enableAlarm = it })
                    }
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注（选填）") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addSchoolOverride(dateInput, type, mappedDow, title, note, enableAlarm)
                    showAddOverrideDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAddOverrideDialog = false }) { Text("取消") } }
        )
    }
}
