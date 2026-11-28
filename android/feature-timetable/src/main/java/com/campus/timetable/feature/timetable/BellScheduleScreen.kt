package com.campus.timetable.feature.timetable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.campus.timetable.domain.engine.BellScheduleRuleEngine
import com.campus.timetable.domain.engine.HolidayCalendarResolver
import com.campus.timetable.domain.model.*
import com.campus.timetable.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalTime
import javax.inject.Inject

data class BellEditorSnapshot(
    val rule: BellScheduleRule,
    val previewPeriods: List<ClassPeriod>
)

data class BellScheduleUiState(
    val semesterId: String = "",
    val semesterName: String = "",
    val selectedDayOfWeek: Int = 0, // 0=全周默认, 1..7=周一至周日独立作息
    val existingCustomizedDays: Set<Int> = emptySet(),
    val rule: BellScheduleRule = BellScheduleRule(),
    val previewPeriods: List<ClassPeriod> = emptyList(),
    val templates: List<BellScheduleTemplate> = emptyList(),
    val canUndo: Boolean = false,
    val validationError: String? = null,
    val feedback: String? = null
)

@HiltViewModel
class BellScheduleViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: AlarmSchedulerPort,
    private val weatherScheduler: WeatherSchedulerPort,
    private val widgetRefresher: WidgetRefresherPort
) : ViewModel() {

    private val _uiState = MutableStateFlow(BellScheduleUiState())
    val uiState: StateFlow<BellScheduleUiState> = _uiState.asStateFlow()

    private val undoStack = ArrayDeque<BellEditorSnapshot>()
    private var allDbPeriods: List<ClassPeriod> = emptyList()

    init {
        viewModelScope.launch {
            timetableRepository.ensureGuestInitialized()
            val sem = timetableRepository.getCurrentSemester() ?: return@launch
            allDbPeriods = timetableRepository.getClassPeriods(sem.id)
            val storedDefault = allDbPeriods.filter { it.dayOfWeek == 0 }.sortedBy { it.periodNumber }
            val restoredRule = BellScheduleRuleEngine.decodeRuleJson(
                settingsRepository.getSavedBellRuleJson(sem.id, 0)
            ) ?: BellScheduleRuleEngine.inferRuleFromPeriods(storedDefault, sem.periodsPerDay)
            val initialPeriods = resolveStoredOrGenerated(sem.id, 0, restoredRule)
            _uiState.update {
                it.copy(
                    semesterId = sem.id,
                    semesterName = sem.name,
                    existingCustomizedDays = allDbPeriods.map { p -> p.dayOfWeek }.filter { d -> d in 1..7 }.toSet(),
                    rule = restoredRule,
                    previewPeriods = initialPeriods
                )
            }
        }
        viewModelScope.launch {
            timetableRepository.observeBellTemplates().collect { list ->
                _uiState.update { it.copy(templates = list) }
            }
        }
    }

    private fun resolveStoredOrGenerated(semId: String, dow: Int, rule: BellScheduleRule): List<ClassPeriod> {
        val stored = allDbPeriods.filter { it.dayOfWeek == dow }.sortedBy { it.periodNumber }
        if (stored.isNotEmpty()) return stored
        return BellScheduleRuleEngine.generatePeriods(semId, dow, rule)
    }

    private fun pushUndo() {
        val cur = _uiState.value
        undoStack.addLast(BellEditorSnapshot(cur.rule, cur.previewPeriods))
        if (undoStack.size > 20) undoStack.removeFirst()
    }

    fun selectDay(dow: Int) {
        viewModelScope.launch {
            val cur = _uiState.value
            val stored = allDbPeriods.filter { it.dayOfWeek == dow }.sortedBy { it.periodNumber }
            val savedRule = BellScheduleRuleEngine.decodeRuleJson(
                settingsRepository.getSavedBellRuleJson(cur.semesterId, dow)
            )
            val nextRule = savedRule
                ?: if (stored.isNotEmpty()) BellScheduleRuleEngine.inferRuleFromPeriods(stored, cur.rule.totalPeriods)
                else cur.rule
            val nextPeriods = if (stored.isNotEmpty()) {
                stored
            } else {
                BellScheduleRuleEngine.generatePeriods(cur.semesterId, dow, nextRule)
            }
            _uiState.update {
                it.copy(
                    selectedDayOfWeek = dow,
                    rule = nextRule,
                    previewPeriods = nextPeriods,
                    validationError = null
                )
            }
        }
    }

    fun updateRuleAndRegenerate(transform: (BellScheduleRule) -> BellScheduleRule) {
        pushUndo()
        val cur = _uiState.value
        val nextRule = transform(cur.rule)
        val validation = BellScheduleRuleEngine.validateRule(nextRule)
        val generated = if (validation.isValid) {
            BellScheduleRuleEngine.generatePeriods(cur.semesterId, cur.selectedDayOfWeek, nextRule)
        } else cur.previewPeriods
        _uiState.update {
            it.copy(
                rule = nextRule,
                previewPeriods = generated,
                canUndo = undoStack.isNotEmpty(),
                validationError = validation.errorMessage
            )
        }
    }

    fun overrideSinglePeriod(periodNumber: Int, newStart: String, newEnd: String) {
        val startOk = runCatching { LocalTime.parse(newStart.trim()) }.getOrNull()
        val endOk = runCatching { LocalTime.parse(newEnd.trim()) }.getOrNull()
        if (startOk == null || endOk == null || !endOk.isAfter(startOk)) {
            _uiState.update { it.copy(validationError = "请输入正确的单节时间（HH:mm 且结束时间晚于开始时间）") }
            return
        }
        pushUndo()
        val cur = _uiState.value
        val nextPeriods = cur.previewPeriods.map { p ->
            if (p.periodNumber == periodNumber) {
                p.copy(startTime = newStart.trim(), endTime = newEnd.trim())
            } else p
        }
        val durationMin = Duration.between(startOk, endOk).toMinutes().toInt().coerceIn(10, 180)
        val nextRule = cur.rule.copy(
            customPeriodDurations = cur.rule.customPeriodDurations + (periodNumber to durationMin)
        )
        _uiState.update {
            it.copy(
                rule = nextRule,
                previewPeriods = nextPeriods,
                canUndo = undoStack.isNotEmpty(),
                validationError = null,
                feedback = "已单独调整第 $periodNumber 节时间（$newStart—$newEnd）"
            )
        }
    }

    fun setCustomBreakAfterPeriod(afterPeriod: Int, breakMinutes: Int) {
        updateRuleAndRegenerate { r ->
            r.copy(customBreaksAfterPeriod = r.customBreaksAfterPeriod + (afterPeriod to breakMinutes.coerceAtLeast(0)))
        }
    }

    fun removeCustomBreakAfterPeriod(afterPeriod: Int) {
        updateRuleAndRegenerate { r ->
            r.copy(customBreaksAfterPeriod = r.customBreaksAfterPeriod - afterPeriod)
        }
    }

    fun applyTemplate(template: BellScheduleTemplate) {
        pushUndo()
        val cur = _uiState.value
        val generated = BellScheduleRuleEngine.generatePeriods(cur.semesterId, cur.selectedDayOfWeek, template.rule)
        _uiState.update {
            it.copy(
                rule = template.rule,
                previewPeriods = generated,
                canUndo = undoStack.isNotEmpty(),
                validationError = null,
                feedback = "已载入模板「${template.name}」并生成预览，点击底部按钮即可应用"
            )
        }
    }

    fun saveCurrentAsTemplate(name: String, category: BellTemplateCategory) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(validationError = "请输入作息模板名称") }
            return
        }
        viewModelScope.launch {
            timetableRepository.saveBellTemplate(
                BellScheduleTemplate(
                    name = trimmed,
                    category = category,
                    rule = _uiState.value.rule,
                    isPreset = false
                )
            )
            _uiState.update { it.copy(feedback = "已保存作息模板「$trimmed」") }
        }
    }

    fun deleteTemplate(templateId: String) {
        viewModelScope.launch {
            timetableRepository.deleteBellTemplate(templateId)
            _uiState.update { it.copy(feedback = "已删除自定义模板") }
        }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        _uiState.update {
            it.copy(
                rule = prev.rule,
                previewPeriods = prev.previewPeriods,
                canUndo = undoStack.isNotEmpty(),
                validationError = null,
                feedback = "已撤销上一步修改"
            )
        }
    }

    fun resetToDefault() {
        val defaultTemplate = BellScheduleRuleEngine.defaultTemplates().first()
        applyTemplate(defaultTemplate)
    }

    /**
     * 应用预览时间表到目标星期或整个学期，并联动重算全部课程闹钟、体育课天气与桌面小组件，
     * 绝不覆盖用户已有的临时调课记录。
     */
    fun applyToTargets(targetDays: List<Int>, scopeLabel: String) {
        val cur = _uiState.value
        if (cur.semesterId.isBlank()) return
        val validation = BellScheduleRuleEngine.validateRule(cur.rule)
        if (!validation.isValid) {
            _uiState.update { it.copy(validationError = validation.errorMessage) }
            return
        }
        viewModelScope.launch {
            timetableRepository.applyClassPeriodsToDays(cur.semesterId, targetDays, cur.previewPeriods)
            val encodedRule = BellScheduleRuleEngine.encodeRuleJson(cur.rule)
            for (dow in targetDays.distinct()) {
                settingsRepository.saveBellRuleJson(cur.semesterId, dow, encodedRule)
            }
            allDbPeriods = timetableRepository.getClassPeriods(cur.semesterId)
            alarmScheduler.rescheduleAllUpcomingAlarms("bell_schedule_updated")
            weatherScheduler.schedulePeWeatherChecks()
            widgetRefresher.refreshAllWidgets()
            _uiState.update {
                it.copy(
                    existingCustomizedDays = allDbPeriods.map { p -> p.dayOfWeek }.filter { d -> d in 1..7 }.toSet(),
                    feedback = "已将作息应用到「$scopeLabel」，同步更新课表时间、闹钟提醒与桌面小组件（保留所有临时调课）"
                )
            }
        }
    }

    fun clearFeedback() { _uiState.update { it.copy(feedback = null) } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BellScheduleScreen(
    onBack: () -> Unit,
    viewModel: BellScheduleViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var editingSinglePeriod by remember { mutableStateOf<ClassPeriod?>(null) }
    var showSaveTemplateDialog by remember { mutableStateOf(false) }
    var showCustomBreakDialog by remember { mutableStateOf(false) }
    var showBatchApplyDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    var firstStartText by remember(state.rule.firstClassStartTime) { mutableStateOf(state.rule.firstClassStartTime) }
    var durationText by remember(state.rule.defaultPeriodDurationMinutes) { mutableStateOf(state.rule.defaultPeriodDurationMinutes.toString()) }
    var smallBreakText by remember(state.rule.defaultSmallBreakMinutes) { mutableStateOf(state.rule.defaultSmallBreakMinutes.toString()) }
    var bigBreakText by remember(state.rule.bigBreakMinutes) { mutableStateOf(state.rule.bigBreakMinutes.toString()) }
    var afternoonStartText by remember(state.rule.afternoonStartTime) { mutableStateOf(state.rule.afternoonStartTime) }
    var lunchBreakText by remember(state.rule.lunchBreakMinutes) { mutableStateOf(state.rule.lunchBreakMinutes.toString()) }
    var eveningStartText by remember(state.rule.eveningStartTime) { mutableStateOf(state.rule.eveningStartTime) }
    var eveningBreakText by remember(state.rule.eveningBreakMinutes) { mutableStateOf(state.rule.eveningBreakMinutes.toString()) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("作息规则编辑", maxLines = 1)
                        if (state.semesterName.isNotBlank()) {
                            Text(state.semesterName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("返回")
                    }
                },
                actions = {
                    TextButton(enabled = state.canUndo, onClick = { viewModel.undo() }) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("撤销")
                    }
                    TextButton(onClick = { viewModel.resetToDefault() }) { Text("恢复默认") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val compact = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                    OutlinedButton(
                        onClick = { showSaveTemplateDialog = true },
                        contentPadding = compact,
                        modifier = Modifier.weight(1f)
                    ) { Text("存为模板", maxLines = 1) }
                    FilledTonalButton(
                        onClick = {
                            val label = if (state.selectedDayOfWeek == 0) "全周默认作息" else "周${HolidayCalendarResolver.dayOfWeekShortZh(state.selectedDayOfWeek)}"
                            viewModel.applyToTargets(listOf(state.selectedDayOfWeek), label)
                        },
                        contentPadding = compact,
                        modifier = Modifier.weight(1.2f)
                    ) { Text("应用当前", maxLines = 1) }
                    Button(
                        onClick = { showBatchApplyDialog = true },
                        contentPadding = compact,
                        modifier = Modifier.weight(1.2f)
                    ) { Text("批量应用", maxLines = 1) }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.validationError?.let { err ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            // 一大节（两节连续小课 + 小课间）实时总时长提示
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val bigMinutes = BellScheduleRuleEngine.bigPeriodDurationMinutes(state.rule)
                    Text(
                        text = "当前规则：单节小课 ${state.rule.defaultPeriodDurationMinutes} 分钟 + 小课间 ${state.rule.defaultSmallBreakMinutes} 分钟 → 一大节（2 小节连堂）共 $bigMinutes 分钟",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            // 方式 C：按星期独立作息选择栏
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("生效星期范围（可为不同星期独立设置作息）", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = state.selectedDayOfWeek == 0,
                                onClick = { viewModel.selectDay(0) },
                                label = { Text("全周默认") }
                            )
                        }
                        items((1..7).toList()) { dow ->
                            val hasCustom = dow in state.existingCustomizedDays
                            FilterChip(
                                selected = state.selectedDayOfWeek == dow,
                                onClick = { viewModel.selectDay(dow) },
                                label = { Text("周${HolidayCalendarResolver.dayOfWeekShortZh(dow)}" + if (hasCustom) "*" else "") }
                            )
                        }
                    }
                }
            }

            // 方式 D：多套作息模板快速切换
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("作息模板（点击一键套用并实时预览）", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.templates, key = { it.id }) { tpl ->
                            InputChip(
                                selected = false,
                                onClick = { viewModel.applyTemplate(tpl) },
                                label = { Text(tpl.name) },
                                trailingIcon = if (!tpl.isPreset) {
                                    {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = "删除模板",
                                            modifier = Modifier.size(16.dp).clickable { viewModel.deleteTemplate(tpl.id) }
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }
            }

            // 方式 A：统一规则配置面板（自由配置课时、小课间、大课间、午休与晚间）
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("统一作息规则（自动推算整天时间）", style = MaterialTheme.typography.titleMedium)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = firstStartText,
                                onValueChange = { v ->
                                    firstStartText = v
                                    if (Regex("^\\d{2}:\\d{2}$").matches(v)) {
                                        viewModel.updateRuleAndRegenerate { it.copy(firstClassStartTime = v) }
                                    }
                                },
                                label = { Text("首节开始(HH:mm)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = durationText,
                                onValueChange = { v ->
                                    val digits = v.filter(Char::isDigit)
                                    durationText = digits
                                    digits.toIntOrNull()?.takeIf { it in 10..180 }?.let { d ->
                                        viewModel.updateRuleAndRegenerate { it.copy(defaultPeriodDurationMinutes = d) }
                                    }
                                },
                                label = { Text("每节时长(分钟)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        // 小课间 & 大课间
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = smallBreakText,
                                onValueChange = { v ->
                                    val digits = v.filter(Char::isDigit)
                                    smallBreakText = digits
                                    digits.toIntOrNull()?.let { m ->
                                        viewModel.updateRuleAndRegenerate { it.copy(defaultSmallBreakMinutes = m) }
                                    }
                                },
                                label = { Text("默认小课间(分)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = bigBreakText,
                                onValueChange = { v ->
                                    val digits = v.filter(Char::isDigit)
                                    bigBreakText = digits
                                    digits.toIntOrNull()?.let { m ->
                                        viewModel.updateRuleAndRegenerate { it.copy(bigBreakMinutes = m) }
                                    }
                                },
                                label = { Text("大课间时长(分)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        Text("大课间频率与当天节数：", style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(0 to "无大课间", 2 to "每2节", 3 to "每3节", 4 to "每4节").forEach { (n, label) ->
                                FilterChip(
                                    selected = state.rule.bigBreakEveryNPeriods == n,
                                    onClick = { viewModel.updateRuleAndRegenerate { it.copy(bigBreakEveryNPeriods = n) } },
                                    label = { Text(label) }
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("当天总节数：", style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) { listOf(0, 8, 10, 12, 14).forEach { cnt ->
                                FilterChip(
                                    selected = state.rule.totalPeriods == cnt,
                                    onClick = { viewModel.updateRuleAndRegenerate { it.copy(totalPeriods = cnt) } },
                                    label = { Text(if (cnt == 0) "无课" else "${cnt}节") }
                                )
                            }
                            }
                        }

                        HorizontalDivider()

                        // 上午结束节次 & 午休 / 下午开始
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("下午按固定时间开始（关闭则按午休时长推算）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = state.rule.useFixedAfternoonStart,
                                onCheckedChange = { checked ->
                                    viewModel.updateRuleAndRegenerate { it.copy(useFixedAfternoonStart = checked) }
                                }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.rule.morningEndPeriod.toString(),
                                onValueChange = { v ->
                                    v.filter(Char::isDigit).toIntOrNull()?.takeIf { it in 1..8 }?.let { n ->
                                        viewModel.updateRuleAndRegenerate { it.copy(morningEndPeriod = n) }
                                    }
                                },
                                label = { Text("上午末节") },
                                modifier = Modifier.weight(0.8f),
                                singleLine = true
                            )
                            if (state.rule.useFixedAfternoonStart) {
                                OutlinedTextField(
                                    value = afternoonStartText,
                                    onValueChange = { v ->
                                        afternoonStartText = v
                                        if (Regex("^\\d{2}:\\d{2}$").matches(v)) {
                                            viewModel.updateRuleAndRegenerate { it.copy(afternoonStartTime = v) }
                                        }
                                    },
                                    label = { Text("下午开始(HH:mm)") },
                                    modifier = Modifier.weight(1.2f),
                                    singleLine = true
                                )
                            } else {
                                OutlinedTextField(
                                    value = lunchBreakText,
                                    onValueChange = { v ->
                                        val d = v.filter(Char::isDigit)
                                        lunchBreakText = d
                                        d.toIntOrNull()?.let { m ->
                                            viewModel.updateRuleAndRegenerate { it.copy(lunchBreakMinutes = m) }
                                        }
                                    },
                                    label = { Text("午休时长(分钟)") },
                                    modifier = Modifier.weight(1.2f),
                                    singleLine = true
                                )
                            }
                        }

                        // 下午结束节次 & 晚间开始 / 晚间休息
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("晚间按固定时间开始（关闭则按晚休时长推算）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = state.rule.useFixedEveningStart,
                                onCheckedChange = { checked ->
                                    viewModel.updateRuleAndRegenerate { it.copy(useFixedEveningStart = checked) }
                                }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.rule.afternoonEndPeriod.toString(),
                                onValueChange = { v ->
                                    v.filter(Char::isDigit).toIntOrNull()?.takeIf { it in 4..12 }?.let { n ->
                                        viewModel.updateRuleAndRegenerate { it.copy(afternoonEndPeriod = n) }
                                    }
                                },
                                label = { Text("下午末节") },
                                modifier = Modifier.weight(0.8f),
                                singleLine = true
                            )
                            if (state.rule.useFixedEveningStart) {
                                OutlinedTextField(
                                    value = eveningStartText,
                                    onValueChange = { v ->
                                        eveningStartText = v
                                        if (Regex("^\\d{2}:\\d{2}$").matches(v)) {
                                            viewModel.updateRuleAndRegenerate { it.copy(eveningStartTime = v) }
                                        }
                                    },
                                    label = { Text("晚间开始(HH:mm)") },
                                    modifier = Modifier.weight(1.2f),
                                    singleLine = true
                                )
                            } else {
                                OutlinedTextField(
                                    value = eveningBreakText,
                                    onValueChange = { v ->
                                        val d = v.filter(Char::isDigit)
                                        eveningBreakText = d
                                        d.toIntOrNull()?.let { m ->
                                            viewModel.updateRuleAndRegenerate { it.copy(eveningBreakMinutes = m) }
                                        }
                                    },
                                    label = { Text("晚间休息(分钟)") },
                                    modifier = Modifier.weight(1.2f),
                                    singleLine = true
                                )
                            }
                        }

                        // 自定义任意节间休息
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("自定义特殊节间休息（如眼保健操/课间操）", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { showCustomBreakDialog = true }) { Text("+ 添加") }
                        }
                        if (state.rule.customBreaksAfterPeriod.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(state.rule.customBreaksAfterPeriod.entries.toList()) { (afterP, mins) ->
                                    InputChip(
                                        selected = true,
                                        onClick = { viewModel.removeCustomBreakAfterPeriod(afterP) },
                                        label = { Text("第${afterP}节后休${mins}分") },
                                        trailingIcon = { Icon(Icons.Outlined.Close, null, Modifier.size(14.dp)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 实时预览与方式 B（点击任意单节微调）
            item {
                Text(
                    "实时时间表预览（共 ${state.previewPeriods.size} 节 · 点击任意一节可单独微调时间）",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (state.previewPeriods.isEmpty()) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Text(
                            "当天已设置为「无课（0 节）」，确认应用后该星期不安排常规节次。",
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                itemsIndexed(state.previewPeriods, key = { _, p -> p.periodNumber }) { idx, period ->
                    val durMin = runCatching {
                        Duration.between(period.parsedStartTime(), period.parsedEndTime()).toMinutes()
                    }.getOrDefault(state.rule.defaultPeriodDurationMinutes.toLong())

                    val slotTag = when {
                        period.periodNumber <= state.rule.morningEndPeriod -> "上午"
                        period.periodNumber <= state.rule.afternoonEndPeriod -> "下午"
                        else -> "晚间"
                    }

                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth().clickable { editingSinglePeriod = period }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                                    Text(
                                        "$slotTag · 第${period.periodNumber}节",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Text(
                                    "${period.startTime} — ${period.endTime}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${durMin}分钟", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Outlined.Edit, contentDescription = "微调单节", modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    // 展示相邻两节之间的课间标签（小课间 / 大课间 / 午休 / 晚休）
                    val nextPeriod = state.previewPeriods.getOrNull(idx + 1)
                    if (nextPeriod != null) {
                        val gapMin = runCatching {
                            Duration.between(period.parsedEndTime(), nextPeriod.parsedStartTime()).toMinutes()
                        }.getOrDefault(0L)
                        val gapLabel = when {
                            period.periodNumber == state.rule.morningEndPeriod -> "午休时间"
                            period.periodNumber == state.rule.afternoonEndPeriod -> "晚间休息"
                            state.rule.customBreaksAfterPeriod.containsKey(period.periodNumber) -> "自定义休息"
                            state.rule.bigBreakEveryNPeriods > 0 && period.periodNumber % state.rule.bigBreakEveryNPeriods == 0 -> "大课间"
                            else -> "小课间"
                        }
                        Text(
                            text = "   ↓ $gapLabel：${gapMin} 分钟",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (gapLabel == "大课间" || gapLabel == "午休时间") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }

    // 单节单独微调弹窗（方式 B）
    editingSinglePeriod?.let { p ->
        var sInput by remember(p) { mutableStateOf(p.startTime) }
        var eInput by remember(p) { mutableStateOf(p.endTime) }
        AlertDialog(
            onDismissRequest = { editingSinglePeriod = null },
            title = { Text("单独修改第 ${p.periodNumber} 节时间") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sInput, onValueChange = { sInput = it }, label = { Text("开始时间 (HH:mm)") }, singleLine = true)
                    OutlinedTextField(value = eInput, onValueChange = { eInput = it }, label = { Text("结束时间 (HH:mm)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.overrideSinglePeriod(p.periodNumber, sInput, eInput)
                    editingSinglePeriod = null
                }) { Text("确认微调") }
            },
            dismissButton = { TextButton(onClick = { editingSinglePeriod = null }) { Text("取消") } }
        )
    }

    // 保存为作息模板弹窗（方式 D）
    if (showSaveTemplateDialog) {
        var tplName by remember { mutableStateOf("") }
        var tplCat by remember { mutableStateOf(BellTemplateCategory.CUSTOM) }
        AlertDialog(
            onDismissRequest = { showSaveTemplateDialog = false },
            title = { Text("保存为作息模板") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = tplName, onValueChange = { tplName = it }, label = { Text("模板名称（如：本校冬令时）") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(BellTemplateCategory.NORMAL, BellTemplateCategory.SUMMER, BellTemplateCategory.WINTER, BellTemplateCategory.CUSTOM).forEach { cat ->
                            FilterChip(selected = tplCat == cat, onClick = { tplCat = cat }, label = { Text(cat.label) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveCurrentAsTemplate(tplName, tplCat)
                    showSaveTemplateDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSaveTemplateDialog = false }) { Text("取消") } }
        )
    }

    // 自定义特殊课间弹窗
    if (showCustomBreakDialog) {
        var afterP by remember { mutableStateOf("2") }
        var breakM by remember { mutableStateOf("30") }
        AlertDialog(
            onDismissRequest = { showCustomBreakDialog = false },
            title = { Text("添加自定义节间休息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = afterP, onValueChange = { afterP = it.filter(Char::isDigit) }, label = { Text("第几节结束后") }, singleLine = true)
                    OutlinedTextField(value = breakM, onValueChange = { breakM = it.filter(Char::isDigit) }, label = { Text("休息时长（分钟）") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val p = (afterP.toIntOrNull() ?: 2).coerceIn(1, 19)
                    val m = (breakM.toIntOrNull() ?: 20).coerceIn(0, 240)
                    viewModel.setCustomBreakAfterPeriod(p, m)
                    showCustomBreakDialog = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showCustomBreakDialog = false }) { Text("取消") } }
        )
    }

    // 一键批量应用弹窗
    if (showBatchApplyDialog) {
        AlertDialog(
            onDismissRequest = { showBatchApplyDialog = false },
            title = { Text("选择批量应用范围") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("应用新时间表后，将自动更新课程显示时间、重新调度上课/下课闹钟与桌面小组件，且绝不覆盖已有的临时调课记录。")
                    Button(
                        onClick = {
                            viewModel.applyToTargets(listOf(0), "整个学期（全周默认）")
                            showBatchApplyDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("应用到整个学期（全周通用）") }
                    FilledTonalButton(
                        onClick = {
                            viewModel.applyToTargets(listOf(1, 2, 3, 4, 5), "周一至周五工作日")
                            showBatchApplyDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("仅批量应用到周一至周五") }
                    OutlinedButton(
                        onClick = {
                            viewModel.applyToTargets(listOf(6, 7), "周六与周日")
                            showBatchApplyDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("仅批量应用到周六、周日") }
                }
            },
            confirmButton = { TextButton(onClick = { showBatchApplyDialog = false }) { Text("关闭") } }
        )
    }
}
