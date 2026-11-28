package com.campus.timetable.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campus.timetable.core.theme.CourseColorPalette
import com.campus.timetable.domain.engine.DefaultClassPeriodsFactory
import com.campus.timetable.domain.engine.HolidayCalendarResolver
import com.campus.timetable.domain.model.*
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    courseId: String,
    onBack: () -> Unit,
    onEditCourse: (String) -> Unit,
    viewModel: CourseDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(courseId) { viewModel.load(courseId) }
    val details by viewModel.details.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showExceptionDialog by remember { mutableStateOf<CourseExceptionType?>(null) }
    var showAlarmDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("课程详情") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("返回")
                    }
                },
                actions = {
                    TextButton(onClick = { onEditCourse(courseId) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("编辑")
                    }
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
                }
            )
        }
    ) { padding ->
        val item = details
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶部课程概览卡片
            val accent = CourseColorPalette.parseColor(item.course.colorHex)
            Card(
                colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.14f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(accent))
                        Spacer(Modifier.width(8.dp))
                        Text(item.course.name, style = MaterialTheme.typography.headlineSmall)
                    }
                    AssistChip(
                        onClick = { onEditCourse(courseId) },
                        label = {
                            Text(
                                if (item.course.courseType == CourseType.PHYSICAL_EDUCATION) {
                                    "体育课程（已自动接入天气预警）"
                                } else "普通课程"
                            )
                        }
                    )
                    if (item.course.teacher.isNotBlank()) {
                        Text("任课教师：${item.course.teacher}", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (item.course.location.isNotBlank()) {
                        Text("默认教室：${item.course.location}", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (item.course.note.isNotBlank()) {
                        Text("备注：${item.course.note}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // 周期排课规则列表
            Text("周期排课规则", style = MaterialTheme.typography.titleMedium)
            item.schedules.forEach { sch ->
                val weekDesc = when (sch.weekRuleType) {
                    WeekRuleType.ALL -> "第 ${sch.startWeek}-${sch.endWeek} 周（每周）"
                    WeekRuleType.ODD -> "第 ${sch.startWeek}-${sch.endWeek} 周（单周）"
                    WeekRuleType.EVEN -> "第 ${sch.startWeek}-${sch.endWeek} 周（双周）"
                    WeekRuleType.CUSTOM -> "自定义周：第 ${sch.customWeeks.joinToString(",")} 周"
                }
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "周${HolidayCalendarResolver.dayOfWeekShortZh(sch.dayOfWeek)} · 第 ${sch.startPeriod}-${sch.endPeriod} 节",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(weekDesc, style = MaterialTheme.typography.bodyMedium)
                        sch.locationOverride?.let { Text("专属地点：$it", style = MaterialTheme.typography.bodyMedium) }
                        sch.teacherOverride?.let { Text("专属教师：$it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }

            // 临时调课/停课/换教室/换教师操作区
            Text("临时变更（独立保存，不破坏原始排课）", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { showExceptionDialog = CourseExceptionType.RESCHEDULE },
                    modifier = Modifier.weight(1f)
                ) { Text("临时调课") }
                FilledTonalButton(
                    onClick = { showExceptionDialog = CourseExceptionType.CANCEL },
                    modifier = Modifier.weight(1f)
                ) { Text("临时停课") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showExceptionDialog = CourseExceptionType.MODIFY_LOCATION },
                    modifier = Modifier.weight(1f)
                ) { Text("临时换教室") }
                OutlinedButton(
                    onClick = { showExceptionDialog = CourseExceptionType.MODIFY_TEACHER },
                    modifier = Modifier.weight(1f)
                ) { Text("临时换教师") }
            }

            if (item.exceptions.isNotEmpty()) {
                Text("已生效的临时变更记录", style = MaterialTheme.typography.titleMedium)
                item.exceptions.forEach { ex ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${ex.exceptionType.label} · 原日期 ${ex.originalDate}", fontWeight = FontWeight.SemiBold)
                                ex.targetDate?.let {
                                    Text("调至：$it 第${ex.targetStartPeriod}-${ex.targetEndPeriod}节", style = MaterialTheme.typography.bodyMedium)
                                }
                                ex.newLocation?.let { Text("新教室：$it", style = MaterialTheme.typography.bodyMedium) }
                                ex.newTeacher?.let { Text("新教师：$it", style = MaterialTheme.typography.bodyMedium) }
                                if (ex.reason.isNotBlank()) {
                                    Text("说明：${ex.reason}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = { viewModel.deleteException(courseId, ex.id) }) {
                                Text("撤销")
                            }
                        }
                    }
                }
            }

            // 单课程独立提醒规则
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().clickable { showAlarmDialog = true }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("本课程独立闹钟规则", style = MaterialTheme.typography.titleMedium)
                        val alarmDesc = item.alarmSetting?.let {
                            if (!it.enabled) "已单独关闭本课程提醒"
                            else "${it.mode.label} · 提前 ${it.advanceMinutes} 分钟${if (it.enableClassEndReminder) " · 含下课提醒" else ""}"
                        } ?: "当前跟随全局闹钟默认设置（点击可单独覆盖）"
                        Text(alarmDesc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Alarm, contentDescription = null)
                }
            }
        }
    }

    // 临时变更弹窗（多时段课程允许自由选择要变更的具体上课时段）
    val exType = showExceptionDialog
    if (exType != null && details != null && details!!.schedules.isNotEmpty()) {
        val schedules = details!!.schedules
        var selectedScheduleId by remember(exType, schedules) { mutableStateOf(schedules.first().id) }
        val activeSchedule = schedules.firstOrNull { it.id == selectedScheduleId } ?: schedules.first()
        var originalDate by remember(exType) { mutableStateOf(LocalDate.now().toString()) }
        var targetDate by remember(exType) { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
        var targetStart by remember(exType, selectedScheduleId) { mutableStateOf(activeSchedule.startPeriod.toString()) }
        var targetEnd by remember(exType, selectedScheduleId) { mutableStateOf(activeSchedule.endPeriod.toString()) }
        var newLocation by remember(exType, selectedScheduleId) { mutableStateOf(activeSchedule.locationOverride ?: details!!.course.location) }
        var newTeacher by remember(exType, selectedScheduleId) { mutableStateOf(activeSchedule.teacherOverride ?: details!!.course.teacher) }
        var reason by remember(exType) { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showExceptionDialog = null },
            title = { Text(exType.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (schedules.size > 1) {
                        Text("选择要变更的上课时段：", style = MaterialTheme.typography.labelMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            schedules.forEach { sch ->
                                FilterChip(
                                    selected = sch.id == selectedScheduleId,
                                    onClick = { selectedScheduleId = sch.id },
                                    label = {
                                        Text("周${HolidayCalendarResolver.dayOfWeekShortZh(sch.dayOfWeek)} 第${sch.startPeriod}-${sch.endPeriod}节")
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = originalDate,
                        onValueChange = { originalDate = it },
                        label = { Text("原上课日期 (YYYY-MM-DD)") },
                        singleLine = true
                    )
                    if (exType == CourseExceptionType.RESCHEDULE) {
                        OutlinedTextField(
                            value = targetDate,
                            onValueChange = { targetDate = it },
                            label = { Text("调整后日期 (YYYY-MM-DD)") },
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = targetStart,
                                onValueChange = { targetStart = it },
                                label = { Text("开始节次") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = targetEnd,
                                onValueChange = { targetEnd = it },
                                label = { Text("结束节次") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                    if (exType == CourseExceptionType.RESCHEDULE || exType == CourseExceptionType.MODIFY_LOCATION) {
                        OutlinedTextField(
                            value = newLocation,
                            onValueChange = { newLocation = it },
                            label = { Text("新上课地点") },
                            singleLine = true
                        )
                    }
                    if (exType == CourseExceptionType.RESCHEDULE || exType == CourseExceptionType.MODIFY_TEACHER) {
                        OutlinedTextField(
                            value = newTeacher,
                            onValueChange = { newTeacher = it },
                            label = { Text("新任课教师") },
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("变更说明（选填）") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addException(
                        courseId = courseId,
                        scheduleId = selectedScheduleId,
                        type = exType,
                        originalDate = originalDate,
                        targetDate = if (exType == CourseExceptionType.RESCHEDULE) targetDate else null,
                        targetStartPeriod = targetStart.toIntOrNull(),
                        targetEndPeriod = targetEnd.toIntOrNull(),
                        newLocation = if (exType == CourseExceptionType.RESCHEDULE || exType == CourseExceptionType.MODIFY_LOCATION) newLocation else null,
                        newTeacher = if (exType == CourseExceptionType.RESCHEDULE || exType == CourseExceptionType.MODIFY_TEACHER) newTeacher else null,
                        reason = reason
                    )
                    showExceptionDialog = null
                }) { Text("保存变更") }
            },
            dismissButton = {
                TextButton(onClick = { showExceptionDialog = null }) { Text("取消") }
            }
        )
    }

    if (showAlarmDialog && details != null) {
        val current = details!!.alarmSetting ?: AlarmSetting(
            id = "course_alarm_$courseId",
            scopeType = AlarmScopeType.COURSE,
            courseId = courseId
        )
        var enabled by remember(current) { mutableStateOf(current.enabled) }
        var mode by remember(current) { mutableStateOf(current.mode) }
        var advance by remember(current) { mutableStateOf(current.advanceMinutes.toString()) }
        var classEnd by remember(current) { mutableStateOf(current.enableClassEndReminder) }

        AlertDialog(
            onDismissRequest = { showAlarmDialog = false },
            title = { Text("单独配置本课程提醒") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("开启本课程提醒")
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlarmMode.entries.forEach { m ->
                            FilterChip(
                                selected = mode == m,
                                onClick = { mode = m },
                                label = { Text(m.label) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = advance,
                        onValueChange = { advance = it.filter { ch -> ch.isDigit() } },
                        label = { Text("提前提醒分钟数") },
                        singleLine = true
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("开启下课提醒")
                        Switch(checked = classEnd, onCheckedChange = { classEnd = it })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveCourseAlarm(
                        current.copy(
                            id = "course_alarm_$courseId",
                            scopeType = AlarmScopeType.COURSE,
                            courseId = courseId,
                            enabled = enabled,
                            mode = mode,
                            advanceMinutes = (advance.toIntOrNull() ?: 15).coerceIn(1, 180),
                            enableClassEndReminder = classEnd
                        )
                    )
                    showAlarmDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAlarmDialog = false }) { Text("取消") } }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除课程？") },
            text = { Text("删除后将同时取消该课程对应的所有上课闹钟提醒。") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteCourse(courseId, onBack)
                    }
                ) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditScreen(
    courseId: String?,
    onBack: () -> Unit,
    viewModel: CourseEditViewModel = hiltViewModel()
) {
    LaunchedEffect(courseId) { viewModel.initForCourse(courseId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (courseId.isNullOrBlank()) "添加课程" else "编辑课程") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("返回")
                    }
                },
                actions = {
                    TextButton(
                        enabled = !state.isSaving,
                        onClick = { viewModel.save(onBack) }
                    ) { Text("保存", fontWeight = FontWeight.Bold) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.error?.let { err ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                    Text(err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }

            if (state.conflicts.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "检测到 ${state.conflicts.size} 处排课时间冲突（仍可保存并在课表并排高亮标注）：",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        state.conflicts.take(4).forEach { c ->
                            Text(
                                "• 与「${if (c.courseAName == state.name) c.courseBName else c.courseAName}」在 ${c.reason}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("课程名称 *") },
                placeholder = { Text("例如：高等数学 / 大学体育(篮球)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.teacher,
                    onValueChange = { viewModel.updateTeacher(it) },
                    label = { Text("任课教师") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.location,
                    onValueChange = { viewModel.updateLocation(it) },
                    label = { Text("上课地点") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            // 课程类型（含自动体育课识别提示）
            Text("课程类型", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CourseType.entries.forEach { t ->
                    FilterChip(
                        selected = state.courseType == t,
                        onClick = { viewModel.updateCourseType(t) },
                        label = { Text(t.label) }
                    )
                }
            }
            if (state.isAutoDetectedPe) {
                Text(
                    "已根据课程名称自动识别为体育课程，保存后将自动接入体育课天气提醒（您也可手动切回普通课程）。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 课程卡片颜色
            Text("课程颜色", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CourseColorPalette.hexColors.take(8).forEach { hex ->
                    val color = CourseColorPalette.parseColor(hex)
                    val selected = state.colorHex.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier)
                            .clickable { viewModel.updateColor(hex) }
                    )
                }
            }

            // 排课时间段列表
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("上课时间与周次规则", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { viewModel.addScheduleSlot() }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("新增时段")
                }
            }

            state.schedules.forEachIndexed { idx, slot ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("时段 ${idx + 1}", fontWeight = FontWeight.SemiBold)
                            if (state.schedules.size > 1) {
                                TextButton(onClick = { viewModel.removeScheduleSlot(idx) }) { Text("移除") }
                            }
                        }

                        // 星期选择
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            (1..7).forEach { dow ->
                                FilterChip(
                                    selected = slot.dayOfWeek == dow,
                                    onClick = { viewModel.updateScheduleSlot(idx) { it.copy(dayOfWeek = dow) } },
                                    label = { Text(HolidayCalendarResolver.dayOfWeekShortZh(dow)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // 节次范围
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = slot.startPeriod.toString(),
                                onValueChange = { v ->
                                    v.toIntOrNull()?.let { p ->
                                        viewModel.updateScheduleSlot(idx) { it.copy(startPeriod = p.coerceIn(1, state.periodsPerDay)) }
                                    }
                                },
                                label = { Text("开始节次") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = slot.endPeriod.toString(),
                                onValueChange = { v ->
                                    v.toIntOrNull()?.let { p ->
                                        viewModel.updateScheduleSlot(idx) { it.copy(endPeriod = p.coerceIn(1, state.periodsPerDay)) }
                                    }
                                },
                                label = { Text("结束节次") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        // 周次规则类型
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WeekRuleType.entries.forEach { rule ->
                                FilterChip(
                                    selected = slot.weekRuleType == rule,
                                    onClick = { viewModel.updateScheduleSlot(idx) { it.copy(weekRuleType = rule) } },
                                    label = { Text(rule.label) }
                                )
                            }
                        }

                        if (slot.weekRuleType == WeekRuleType.CUSTOM) {
                            Text("点选具体上课周次：", style = MaterialTheme.typography.labelMedium)
                            for (rowStart in 1..state.totalWeeks step 5) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                    for (w in rowStart until (rowStart + 5).coerceAtMost(state.totalWeeks + 1)) {
                                        val checked = w in slot.customWeeks
                                        FilterChip(
                                            selected = checked,
                                            onClick = {
                                                viewModel.updateScheduleSlot(idx) {
                                                    val next = if (checked) it.customWeeks - w else it.customWeeks + w
                                                    it.copy(customWeeks = next)
                                                }
                                            },
                                            label = { Text("$w") },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = slot.startWeek.toString(),
                                    onValueChange = { v ->
                                        v.toIntOrNull()?.let { w ->
                                            viewModel.updateScheduleSlot(idx) { it.copy(startWeek = w.coerceIn(1, state.totalWeeks)) }
                                        }
                                    },
                                    label = { Text("起始周") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = slot.endWeek.toString(),
                                    onValueChange = { v ->
                                        v.toIntOrNull()?.let { w ->
                                            viewModel.updateScheduleSlot(idx) { it.copy(endWeek = w.coerceIn(1, state.totalWeeks)) }
                                        }
                                    },
                                    label = { Text("结束周") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.note,
                onValueChange = { viewModel.updateNote(it) },
                label = { Text("课程备注（选填）") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemesterManagementScreen(
    onBack: () -> Unit,
    onOpenBellSchedule: () -> Unit,
    viewModel: SemesterManagementViewModel = hiltViewModel()
) {
    val semesters by viewModel.semesters.collectAsStateWithLifecycle()
    val current by viewModel.currentSemester.collectAsStateWithLifecycle()
    val periods by viewModel.classPeriods.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedDayForPeriods by remember { mutableIntStateOf(0) } // 0=全周默认, 1..7=周一至周日独立作息
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(feedback) {
        feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("学期与节次时间管理") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showCreateDialog = true }) { Text("新建学期") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("作息时间规则编辑器", fontWeight = FontWeight.SemiBold)
                            Text(
                                "设置每节课时长、小/大课间、午休与晚间课程，可按星期独立配置并套用模板",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(onClick = onOpenBellSchedule) { Text("编辑作息") }
                    }
                }
            }

            item { Text("学期切换", style = MaterialTheme.typography.titleMedium) }
            items(semesters, key = { it.id }) { sem ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = sem.name + if (sem.isCurrent) "（当前）" else "",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "开学：${sem.startDate} · 共 ${sem.totalWeeks} 周 · 每天 ${sem.periodsPerDay} 节",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!sem.isCurrent) {
                            TextButton(onClick = { viewModel.switchSemester(sem.id) }) { Text("切换") }
                            TextButton(onClick = { viewModel.deleteSemester(sem.id) }) { Text("删除") }
                        }
                    }
                }
            }

            current?.let { cur ->
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("编辑当前学期参数", style = MaterialTheme.typography.titleMedium)
                    var editName by remember(cur.id) { mutableStateOf(cur.name) }
                    var editStart by remember(cur.id) { mutableStateOf(cur.startDate) }
                    var editWeeks by remember(cur.id) { mutableStateOf(cur.totalWeeks.toString()) }
                    var editPeriods by remember(cur.id) { mutableStateOf(cur.periodsPerDay.toString()) }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("学期名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = editStart,
                            onValueChange = { editStart = it },
                            label = { Text("第一周开始日期 (YYYY-MM-DD)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = editWeeks,
                                onValueChange = { editWeeks = it.filter(Char::isDigit) },
                                label = { Text("总周数") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = editPeriods,
                                onValueChange = { editPeriods = it.filter(Char::isDigit) },
                                label = { Text("每天节数") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.updateCurrentSemesterMeta(
                                    editName,
                                    editStart,
                                    editWeeks.toIntOrNull() ?: 20,
                                    editPeriods.toIntOrNull() ?: 12
                                )
                            }
                        ) { Text("保存学期参数") }
                    }
                }

                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("节次作息时间（支持按不同星期单独定制）", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = selectedDayForPeriods == 0,
                            onClick = { selectedDayForPeriods = 0 },
                            label = { Text("全周") }
                        )
                        (1..7).forEach { dow ->
                            val hasCustom = periods.any { it.dayOfWeek == dow }
                            FilterChip(
                                selected = selectedDayForPeriods == dow,
                                onClick = { selectedDayForPeriods = dow },
                                label = { Text(HolidayCalendarResolver.dayOfWeekShortZh(dow) + if (hasCustom) "*" else "") }
                            )
                        }
                    }

                    val baseList = remember(cur.id, periods, selectedDayForPeriods) {
                        val defaults = DefaultClassPeriodsFactory.createForSemester(cur.id, cur.periodsPerDay)
                        defaults.map { def ->
                            periods.firstOrNull { it.dayOfWeek == selectedDayForPeriods && it.periodNumber == def.periodNumber }
                                ?: periods.firstOrNull { it.dayOfWeek == 0 && it.periodNumber == def.periodNumber }
                                ?: def
                        }
                    }
                    var editable by remember(baseList) { mutableStateOf(baseList) }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        editable.forEachIndexed { idx, cp ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("第${cp.periodNumber}节", modifier = Modifier.width(52.dp), fontWeight = FontWeight.Medium)
                                OutlinedTextField(
                                    value = cp.startTime,
                                    onValueChange = { v ->
                                        editable = editable.mapIndexed { i, item -> if (i == idx) item.copy(startTime = v) else item }
                                    },
                                    label = { Text("开始") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = cp.endTime,
                                    onValueChange = { v ->
                                        editable = editable.mapIndexed { i, item -> if (i == idx) item.copy(endTime = v) else item }
                                    },
                                    label = { Text("结束") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.saveDayPeriods(selectedDayForPeriods, editable) }) {
                                Text(if (selectedDayForPeriods == 0) "保存通用作息" else "保存周${HolidayCalendarResolver.dayOfWeekShortZh(selectedDayForPeriods)}独立作息")
                            }
                            if (selectedDayForPeriods != 0) {
                                OutlinedButton(onClick = { viewModel.clearDaySpecificPeriods(selectedDayForPeriods) }) {
                                    Text("恢复跟随全周")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("2026-2027 第二学期") }
        var startDate by remember { mutableStateOf(LocalDate.now().toString()) }
        var totalWeeks by remember { mutableStateOf("20") }
        var periodsPerDay by remember { mutableStateOf("12") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建学期") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("学期名称") }, singleLine = true)
                    OutlinedTextField(value = startDate, onValueChange = { startDate = it }, label = { Text("开学日期 (YYYY-MM-DD)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = totalWeeks, onValueChange = { totalWeeks = it.filter(Char::isDigit) }, label = { Text("总周数") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = periodsPerDay, onValueChange = { periodsPerDay = it.filter(Char::isDigit) }, label = { Text("每天节数") }, modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createSemester(name, startDate, totalWeeks.toIntOrNull() ?: 20, periodsPerDay.toIntOrNull() ?: 12)
                    showCreateDialog = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("取消") } }
        )
    }
}
