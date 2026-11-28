package com.campus.timetable.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campus.timetable.core.theme.CourseColorPalette
import com.campus.timetable.domain.engine.HolidayCalendarResolver
import com.campus.timetable.domain.engine.ScheduleResolutionEngine
import com.campus.timetable.domain.model.ClassPeriod
import com.campus.timetable.domain.model.DayCalendarStatus
import com.campus.timetable.domain.model.ResolvedCourseSession
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    onAddCourse: () -> Unit,
    onCourseClick: (String) -> Unit,
    onOpenImport: () -> Unit,
    onOpenSemester: () -> Unit,
    onOpenHoliday: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenWidgets: () -> Unit,
    viewModel: TimetableViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showWeekPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showWeekPicker = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "第 ${state.selectedWeek} 周",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (state.selectedWeek == state.currentActualWeek) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(
                                        text = "本周",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Icon(
                                Icons.Outlined.ArrowDropDown,
                                contentDescription = "选择周次",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = state.semester?.name ?: "默认学期",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (state.selectedWeek != state.currentActualWeek) {
                        TextButton(onClick = { viewModel.backToCurrentWeek() }) {
                            Text("回本周")
                        }
                    }
                    TextButton(onClick = { viewModel.selectPreviousWeek() }) { Text("上一周") }
                    TextButton(onClick = { viewModel.selectNextWeek() }) { Text("下一周") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddCourse,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("添加课程") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 顶部快捷状态条：体育课天气预警 / 冲突提示 / 今日静音 / 校历与天气/作息/小组件入口
            QuickStatusRow(
                state = state,
                onToggleMuteToday = { viewModel.toggleMuteToday() },
                onOpenSemester = onOpenSemester,
                onOpenHoliday = onOpenHoliday,
                onOpenWeather = onOpenWeather,
                onOpenWidgets = onOpenWidgets
            )

            // 星期与日期表头（周一至周日）
            WeekDayHeaderRow(
                weekDates = state.weekDates,
                today = state.today,
                dayStatuses = state.dayStatuses
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 课表网格主体
            val totalCoursesInWeek = state.daySessions.values.sumOf { it.size }
            val periodsCount = state.semester?.periodsPerDay ?: 12
            val rowHeight = 62.dp

            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    // 左侧节次时间轴
                    PeriodTimeColumn(
                        periodsCount = periodsCount,
                        periods = state.periods,
                        rowHeight = rowHeight
                    )

                    // 7 天课程列
                    state.weekDates.forEach { date ->
                        val sessions = state.daySessions[date].orEmpty()
                        val isToday = date == state.today
                        DayScheduleColumn(
                            modifier = Modifier.weight(1f),
                            periodsCount = periodsCount,
                            rowHeight = rowHeight,
                            isToday = isToday,
                            sessions = sessions,
                            onCourseClick = onCourseClick
                        )
                    }
                }

                // 如果本周尚无课程，提供非侵入式引导浮条（不遮挡空网格结构）
                if (!state.isLoading && totalCoursesInWeek == 0) {
                    ElevatedCard(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.EventNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "第 ${state.selectedWeek} 周暂无排课",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "游客模式可直接使用全部本地功能，支持手动添加课程或导入 Excel / PDF / 教务课表。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = onOpenImport) {
                                    Text("导入课表")
                                }
                                Button(onClick = onAddCourse) {
                                    Text("添加课程")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showWeekPicker) {
        val totalWeeks = state.semester?.totalWeeks ?: 20
        AlertDialog(
            onDismissRequest = { showWeekPicker = false },
            title = { Text("切换教学周次") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (rowStart in 1..totalWeeks step 5) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (w in rowStart until (rowStart + 5).coerceAtMost(totalWeeks + 1)) {
                                val selected = w == state.selectedWeek
                                val isCurrent = w == state.currentActualWeek
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        viewModel.jumpToWeek(w)
                                        showWeekPicker = false
                                    },
                                    label = {
                                        Text(if (isCurrent) "${w}*" else "$w", fontSize = 12.sp)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    Text(
                        "* 标记为当前教学周（第 ${state.currentActualWeek} 周）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.backToCurrentWeek()
                    showWeekPicker = false
                }) { Text("返回本周") }
            },
            dismissButton = {
                TextButton(onClick = { showWeekPicker = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun QuickStatusRow(
    state: TimetableUiState,
    onToggleMuteToday: () -> Unit,
    onOpenSemester: () -> Unit,
    onOpenHoliday: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenWidgets: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        if (state.weekConflicts.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "本周检测到 ${state.weekConflicts.size} 处课程时间冲突：${state.weekConflicts.first().reason}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        state.todayPeAdvice?.let { advice ->
            if (advice.needsAlert) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .clickable { onOpenWeather() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Thunderstorm,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = advice.detail,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = onOpenSemester,
                label = { Text("学期/作息", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(14.dp)) }
            )
            AssistChip(
                onClick = onOpenHoliday,
                label = { Text("节假日", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Outlined.EventAvailable, null, Modifier.size(14.dp)) }
            )
            AssistChip(
                onClick = onOpenWeather,
                label = { Text("天气", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Outlined.WbSunny, null, Modifier.size(14.dp)) }
            )
            AssistChip(
                onClick = onOpenWidgets,
                label = { Text("小组件", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Outlined.Widgets, null, Modifier.size(14.dp)) }
            )
            FilterChip(
                selected = state.isTodayMuted,
                onClick = onToggleMuteToday,
                label = { Text(if (state.isTodayMuted) "已静音" else "静音", fontSize = 11.sp) }
            )
        }
    }
}

@Composable
private fun WeekDayHeaderRow(
    weekDates: List<LocalDate>,
    today: LocalDate,
    dayStatuses: Map<LocalDate, DayCalendarStatus>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            val monthNum = weekDates.firstOrNull()?.monthValue ?: today.monthValue
            Text(
                text = "${monthNum}月",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        weekDates.forEach { date ->
            val isToday = date == today
            val status = dayStatuses[date]
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = HolidayCalendarResolver.dayOfWeekShortZh(date.dayOfWeek.value),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
                status?.badgeText?.let { badge ->
                    Text(
                        text = badge,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (status.isHolidayOffDay) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodTimeColumn(
    periodsCount: Int,
    periods: List<ClassPeriod>,
    rowHeight: Dp
) {
    Column(
        modifier = Modifier
            .width(44.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        for (p in 1..periodsCount) {
            val (startTime, endTime) = ScheduleResolutionEngine.resolvePeriodTimeRange(periods, 0, p, p)
            Box(
                modifier = Modifier
                    .height(rowHeight)
                    .fillMaxWidth()
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$p",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 12.sp
                    )
                    Text(
                        text = startTime.toString().take(5),
                        fontSize = 8.5.sp,
                        lineHeight = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = endTime.toString().take(5),
                        fontSize = 8.5.sp,
                        lineHeight = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DayScheduleColumn(
    modifier: Modifier,
    periodsCount: Int,
    rowHeight: Dp,
    isToday: Boolean,
    sessions: List<ResolvedCourseSession>,
    onCourseClick: (String) -> Unit
) {
    val totalHeight = rowHeight * periodsCount
    val bgColor = if (isToday) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.14f)
    } else {
        Color.Transparent
    }

    // 为重叠冲突课程分配并排子列，彻底避免文字重叠不可读
    val placed = remember(sessions) { assignOverlapLanes(sessions) }

    BoxWithConstraints(
        modifier = modifier
            .height(totalHeight)
            .background(bgColor)
    ) {
        val columnWidth = maxWidth
        // 网格背景参考线
        Column(modifier = Modifier.fillMaxSize()) {
            repeat(periodsCount) {
                Box(
                    modifier = Modifier
                        .height(rowHeight)
                        .fillMaxWidth()
                        .border(0.4.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                )
            }
        }

        placed.forEach { item ->
            val session = item.session
            val clampedStart = session.startPeriod.coerceIn(1, periodsCount)
            val clampedEnd = session.endPeriod.coerceIn(clampedStart, periodsCount)
            val topOffset = rowHeight * (clampedStart - 1)
            val cardHeight = rowHeight * (clampedEnd - clampedStart + 1)
            val laneWidth = columnWidth / item.totalLanes
            val leftOffset = laneWidth * item.laneIndex

            val baseColor = CourseColorPalette.parseColor(session.course.colorHex)
            val cardBg = if (session.holidayPaused) baseColor.copy(alpha = 0.35f) else baseColor.copy(alpha = 0.90f)

            Box(
                modifier = Modifier
                    .offset(x = leftOffset, y = topOffset)
                    .width(laneWidth)
                    .height(cardHeight)
                    .padding(1.5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(cardBg)
                    .then(
                        if (session.hasConflict) {
                            Modifier.border(1.5.dp, Color(0xFFFFD54F), RoundedCornerShape(8.dp))
                        } else Modifier
                    )
                    .clickable { onCourseClick(session.course.id) }
                    .padding(horizontal = 3.dp, vertical = 3.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = buildString {
                            if (session.hasConflict) append("!")
                            if (session.isPhysicalEducation) append("[体]")
                            if (session.isRescheduled) append("[调]")
                            append(session.course.name)
                        },
                        color = Color.White,
                        fontSize = if (item.totalLanes > 1) 9.sp else 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 12.sp,
                        maxLines = if (clampedEnd > clampedStart) 3 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Column {
                        if (session.effectiveLocation.isNotBlank()) {
                            Text(
                                text = "@${session.effectiveLocation}",
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (clampedEnd > clampedStart && session.effectiveTeacher.isNotBlank()) {
                            Text(
                                text = session.effectiveTeacher,
                                color = Color.White.copy(alpha = 0.80f),
                                fontSize = 8.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class PlacedSession(
    val session: ResolvedCourseSession,
    val laneIndex: Int,
    val totalLanes: Int
)

private fun assignOverlapLanes(sessions: List<ResolvedCourseSession>): List<PlacedSession> {
    if (sessions.isEmpty()) return emptyList()
    val sorted = sessions.sortedBy { it.startPeriod }
    val result = mutableListOf<PlacedSession>()

    // 将互相存在节次重叠的课程聚类为一个簇，再给簇内分配不重叠子列
    val visited = BooleanArray(sorted.size)
    for (i in sorted.indices) {
        if (visited[i]) continue
        val cluster = mutableListOf<ResolvedCourseSession>()
        val queue = ArrayDeque<Int>()
        queue.add(i)
        visited[i] = true
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            cluster += sorted[cur]
            for (j in sorted.indices) {
                if (!visited[j] && ScheduleResolutionEngine.periodsOverlap(
                        sorted[cur].startPeriod, sorted[cur].endPeriod,
                        sorted[j].startPeriod, sorted[j].endPeriod
                    )
                ) {
                    visited[j] = true
                    queue.add(j)
                }
            }
        }
        val lanes = mutableListOf<MutableList<ResolvedCourseSession>>()
        val assignment = mutableListOf<Pair<ResolvedCourseSession, Int>>()
        for (s in cluster.sortedBy { it.startPeriod }) {
            var placedLane = -1
            for (lIdx in lanes.indices) {
                val canFit = lanes[lIdx].none {
                    ScheduleResolutionEngine.periodsOverlap(it.startPeriod, it.endPeriod, s.startPeriod, s.endPeriod)
                }
                if (canFit) {
                    lanes[lIdx].add(s)
                    placedLane = lIdx
                    break
                }
            }
            if (placedLane == -1) {
                lanes.add(mutableListOf(s))
                placedLane = lanes.lastIndex
            }
            assignment += s to placedLane
        }
        val total = lanes.size.coerceAtLeast(1)
        assignment.forEach { (s, lane) -> result += PlacedSession(s, lane, total) }
    }
    return result
}
