package com.campus.timetable.feature.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.core.theme.CourseColorPalette
import com.campus.timetable.domain.engine.*
import com.campus.timetable.domain.model.*
import com.campus.timetable.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class TimetableUiState(
    val isLoading: Boolean = true,
    val semester: Semester? = null,
    val allSemesters: List<Semester> = emptyList(),
    val currentActualWeek: Int = 1,
    val selectedWeek: Int = 1,
    val today: LocalDate = LocalDate.now(),
    val weekDates: List<LocalDate> = emptyList(),
    val dayStatuses: Map<LocalDate, DayCalendarStatus> = emptyMap(),
    val daySessions: Map<LocalDate, List<ResolvedCourseSession>> = emptyMap(),
    val periods: List<ClassPeriod> = emptyList(),
    val weekConflicts: List<CourseConflict> = emptyList(),
    val todayPeAdvice: PeWeatherAdvice? = null,
    val isTodayMuted: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
    private val holidayRepository: HolidayRepository,
    private val alarmRepository: AlarmRepository,
    private val weatherRepository: WeatherRepository,
    private val alarmScheduler: AlarmSchedulerPort,
    private val widgetRefresher: WidgetRefresherPort,
    private val clock: AppClock
) : ViewModel() {

    private val _selectedWeekOffset = MutableStateFlow<Int?>(null)

    init {
        viewModelScope.launch {
            timetableRepository.ensureGuestInitialized()
        }
    }

    private val semesterFlow = timetableRepository.observeCurrentSemester()
    private val allSemestersFlow = timetableRepository.observeSemesters()

    private val semesterDataFlow = semesterFlow.flatMapLatest { semester ->
        if (semester == null) {
            flowOf(null)
        } else {
            val today = clock.today()
            combine(
                timetableRepository.observeCoursesWithDetails(semester.id),
                timetableRepository.observeClassPeriods(semester.id),
                holidayRepository.observeHolidaysByYear(today.year),
                holidayRepository.observeSchoolOverrides(semester.id),
                alarmRepository.observeGlobalSetting(),
                weatherRepository.observeWeatherSetting()
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                SemesterBundleData(
                    semester = semester,
                    courses = values[0] as List<CourseWithDetails>,
                    periods = values[1] as List<ClassPeriod>,
                    holidays = values[2] as List<Holiday>,
                    overrides = values[3] as List<SchoolCalendarOverride>,
                    globalAlarm = values[4] as AlarmSetting,
                    weatherSetting = values[5] as WeatherSetting
                )
            }
        }
    }

    val uiState: StateFlow<TimetableUiState> = combine(
        semesterDataFlow,
        allSemestersFlow,
        _selectedWeekOffset
    ) { bundle, allSemesters, selectedWeek ->
        val today = clock.today()
        if (bundle == null) {
            return@combine TimetableUiState(isLoading = false, today = today, allSemesters = allSemesters)
        }
        val sem = bundle.semester
        val actualWeek = ScheduleResolutionEngine.calculateWeekNumber(sem.parsedStartDate(), today)
            .coerceIn(1, sem.totalWeeks)
        val viewWeek = (selectedWeek ?: actualWeek).coerceIn(1, sem.totalWeeks)
        val weekDates = ScheduleResolutionEngine.datesOfWeek(sem.parsedStartDate(), viewWeek)

        val statuses = linkedMapOf<LocalDate, DayCalendarStatus>()
        val sessionsMap = linkedMapOf<LocalDate, List<ResolvedCourseSession>>()

        for (d in weekDates) {
            val (status, sessions) = ScheduleResolutionEngine.resolveDaySessions(
                semester = sem,
                date = d,
                courses = bundle.courses,
                periods = bundle.periods,
                holidays = bundle.holidays,
                overrides = bundle.overrides,
                includeCancelled = false
            )
            statuses[d] = status
            sessionsMap[d] = sessions
        }

        val conflicts = ConflictDetectionEngine.detectConflictsInSemester(
            semester = sem,
            courses = bundle.courses,
            periods = bundle.periods,
            holidays = bundle.holidays,
            overrides = bundle.overrides,
            fromWeek = viewWeek,
            toWeek = viewWeek
        )

        val cachedWeather = weatherRepository.getCachedWeather(bundle.weatherSetting)
        val (_, todaySessions) = ScheduleResolutionEngine.resolveDaySessions(
            semester = sem,
            date = today,
            courses = bundle.courses,
            periods = bundle.periods,
            holidays = bundle.holidays,
            overrides = bundle.overrides
        )
        val todayForecast = cachedWeather?.daily?.firstOrNull { it.date == today.toString() }
        val todayPeAdvice = PeWeatherRuleEngine.evaluatePeSessionsForDate(
            date = today,
            peSessions = todaySessions,
            forecastDay = todayForecast,
            setting = bundle.weatherSetting
        )

        TimetableUiState(
            isLoading = false,
            semester = sem,
            allSemesters = allSemesters,
            currentActualWeek = actualWeek,
            selectedWeek = viewWeek,
            today = today,
            weekDates = weekDates,
            dayStatuses = statuses,
            daySessions = sessionsMap,
            periods = bundle.periods,
            weekConflicts = conflicts,
            todayPeAdvice = todayPeAdvice,
            isTodayMuted = bundle.globalAlarm.muteDate == today.toString()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimetableUiState())

    fun selectPreviousWeek() {
        val current = uiState.value.selectedWeek
        if (current > 1) _selectedWeekOffset.value = current - 1
    }

    fun selectNextWeek() {
        val maxW = uiState.value.semester?.totalWeeks ?: 20
        val current = uiState.value.selectedWeek
        if (current < maxW) _selectedWeekOffset.value = current + 1
    }

    fun jumpToWeek(week: Int) {
        val maxW = uiState.value.semester?.totalWeeks ?: 20
        _selectedWeekOffset.value = week.coerceIn(1, maxW)
    }

    fun backToCurrentWeek() {
        _selectedWeekOffset.value = null
    }

    fun switchSemester(semesterId: String) {
        viewModelScope.launch {
            timetableRepository.switchCurrentSemester(semesterId)
            _selectedWeekOffset.value = null
            alarmScheduler.rescheduleAllUpcomingAlarms("semester_switched")
            widgetRefresher.refreshAllWidgets()
        }
    }

    fun toggleMuteToday() {
        viewModelScope.launch {
            val todayIso = clock.today().toString()
            if (uiState.value.isTodayMuted) {
                alarmRepository.clearMuteToday()
            } else {
                alarmRepository.muteTodayAll(todayIso)
            }
            alarmScheduler.rescheduleAllUpcomingAlarms("mute_today_toggled")
        }
    }

    private data class SemesterBundleData(
        val semester: Semester,
        val courses: List<CourseWithDetails>,
        val periods: List<ClassPeriod>,
        val holidays: List<Holiday>,
        val overrides: List<SchoolCalendarOverride>,
        val globalAlarm: AlarmSetting,
        val weatherSetting: WeatherSetting
    )
}

@HiltViewModel
class CourseDetailViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
    private val alarmRepository: AlarmRepository,
    private val alarmScheduler: AlarmSchedulerPort,
    private val weatherScheduler: WeatherSchedulerPort,
    private val widgetRefresher: WidgetRefresherPort,
    private val clock: AppClock
) : ViewModel() {

    private val _details = MutableStateFlow<CourseWithDetails?>(null)
    val details: StateFlow<CourseWithDetails?> = _details.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load(courseId: String) {
        viewModelScope.launch {
            _details.value = timetableRepository.getCourseWithDetails(courseId)
        }
    }

    fun addException(
        courseId: String,
        scheduleId: String,
        type: CourseExceptionType,
        originalDate: String,
        targetDate: String? = null,
        targetStartPeriod: Int? = null,
        targetEndPeriod: Int? = null,
        newLocation: String? = null,
        newTeacher: String? = null,
        reason: String = ""
    ) {
        viewModelScope.launch {
            val sem = timetableRepository.getCurrentSemester() ?: return@launch
            val origLocalDate = runCatching { LocalDate.parse(originalDate) }.getOrElse { clock.today() }
            val origWeek = ScheduleResolutionEngine.calculateWeekNumber(sem.parsedStartDate(), origLocalDate)
            val targetLocalDate = targetDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val ex = CourseException(
                courseId = courseId,
                scheduleId = scheduleId,
                exceptionType = type,
                originalDate = origLocalDate.toString(),
                originalWeek = origWeek,
                targetDate = targetLocalDate?.toString(),
                targetDayOfWeek = targetLocalDate?.dayOfWeek?.value,
                targetStartPeriod = targetStartPeriod,
                targetEndPeriod = targetEndPeriod,
                newLocation = newLocation?.takeIf { it.isNotBlank() },
                newTeacher = newTeacher?.takeIf { it.isNotBlank() },
                reason = reason.trim(),
                createdAt = clock.currentTimeMillis(),
                updatedAt = clock.currentTimeMillis()
            )
            timetableRepository.saveCourseException(ex)
            _details.value = timetableRepository.getCourseWithDetails(courseId)
            _message.value = "已保存${type.label}记录，原始周期规则保持不变"
            alarmScheduler.rescheduleAllUpcomingAlarms("exception_saved")
            weatherScheduler.schedulePeWeatherChecks()
            widgetRefresher.refreshAllWidgets()
        }
    }

    fun deleteException(courseId: String, exceptionId: String) {
        viewModelScope.launch {
            timetableRepository.deleteCourseException(exceptionId)
            _details.value = timetableRepository.getCourseWithDetails(courseId)
            _message.value = "已撤销该条临时变更"
            alarmScheduler.rescheduleAllUpcomingAlarms("exception_deleted")
            widgetRefresher.refreshAllWidgets()
        }
    }

    fun saveCourseAlarm(setting: AlarmSetting) {
        viewModelScope.launch {
            alarmRepository.saveAlarmSetting(setting)
            setting.courseId?.let { _details.value = timetableRepository.getCourseWithDetails(it) }
            _message.value = "已更新本课程提醒规则"
            alarmScheduler.rescheduleAllUpcomingAlarms("course_alarm_updated")
        }
    }

    fun deleteCourse(courseId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            timetableRepository.deleteCourse(courseId)
            alarmScheduler.rescheduleAllUpcomingAlarms("course_deleted")
            weatherScheduler.schedulePeWeatherChecks()
            widgetRefresher.refreshAllWidgets()
            onDone()
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

data class ScheduleDraft(
    val id: String = newId(),
    val dayOfWeek: Int = 1,
    val startPeriod: Int = 1,
    val endPeriod: Int = 2,
    val weekRuleType: WeekRuleType = WeekRuleType.ALL,
    val startWeek: Int = 1,
    val endWeek: Int = 16,
    val customWeeks: Set<Int> = emptySet(),
    val locationOverride: String = "",
    val teacherOverride: String = ""
)

data class CourseEditUiState(
    val courseId: String? = null,
    val semesterId: String = "",
    val totalWeeks: Int = 20,
    val periodsPerDay: Int = 12,
    val name: String = "",
    val teacher: String = "",
    val location: String = "",
    val courseType: CourseType = CourseType.NORMAL,
    val userManuallyChangedType: Boolean = false,
    val isAutoDetectedPe: Boolean = false,
    val colorHex: String = CourseColorPalette.hexColors.first(),
    val creditText: String = "",
    val note: String = "",
    val schedules: List<ScheduleDraft> = listOf(ScheduleDraft()),
    val conflicts: List<CourseConflict> = emptyList(),
    val error: String? = null,
    val isSaving: Boolean = false
)

@HiltViewModel
class CourseEditViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
    private val holidayRepository: HolidayRepository,
    private val alarmScheduler: AlarmSchedulerPort,
    private val weatherScheduler: WeatherSchedulerPort,
    private val widgetRefresher: WidgetRefresherPort,
    private val clock: AppClock
) : ViewModel() {

    private val _uiState = MutableStateFlow(CourseEditUiState())
    val uiState: StateFlow<CourseEditUiState> = _uiState.asStateFlow()

    fun initForCourse(courseId: String?) {
        viewModelScope.launch {
            timetableRepository.ensureGuestInitialized()
            val sem = timetableRepository.getCurrentSemester() ?: return@launch
            if (courseId.isNullOrBlank()) {
                _uiState.value = CourseEditUiState(
                    semesterId = sem.id,
                    totalWeeks = sem.totalWeeks,
                    periodsPerDay = sem.periodsPerDay,
                    schedules = listOf(ScheduleDraft(endWeek = sem.totalWeeks.coerceAtMost(16)))
                )
            } else {
                val existing = timetableRepository.getCourseWithDetails(courseId) ?: return@launch
                _uiState.value = CourseEditUiState(
                    courseId = existing.course.id,
                    semesterId = sem.id,
                    totalWeeks = sem.totalWeeks,
                    periodsPerDay = sem.periodsPerDay,
                    name = existing.course.name,
                    teacher = existing.course.teacher,
                    location = existing.course.location,
                    courseType = existing.course.courseType,
                    userManuallyChangedType = true,
                    isAutoDetectedPe = existing.course.isAutoDetectedPe,
                    colorHex = existing.course.colorHex,
                    creditText = if (existing.course.credit > 0) existing.course.credit.toString() else "",
                    note = existing.course.note,
                    schedules = existing.schedules.map {
                        ScheduleDraft(
                            id = it.id,
                            dayOfWeek = it.dayOfWeek,
                            startPeriod = it.startPeriod,
                            endPeriod = it.endPeriod,
                            weekRuleType = it.weekRuleType,
                            startWeek = it.startWeek,
                            endWeek = it.endWeek,
                            customWeeks = it.customWeeks.toSet(),
                            locationOverride = it.locationOverride.orEmpty(),
                            teacherOverride = it.teacherOverride.orEmpty()
                        )
                    }.ifEmpty { listOf(ScheduleDraft()) }
                )
            }
            recheckConflicts()
        }
    }

    fun updateName(name: String) {
        val current = _uiState.value
        val detectedPe = PeCourseDetector.isLikelyPeCourse(name)
        val newType = if (!current.userManuallyChangedType) {
            if (detectedPe) CourseType.PHYSICAL_EDUCATION else CourseType.NORMAL
        } else current.courseType
        val newColor = if (current.courseId == null && name.isNotBlank()) {
            CourseColorPalette.pickForCourseName(name)
        } else current.colorHex
        _uiState.value = current.copy(
            name = name,
            courseType = newType,
            isAutoDetectedPe = detectedPe,
            colorHex = newColor,
            error = null
        )
    }

    fun updateTeacher(v: String) { _uiState.value = _uiState.value.copy(teacher = v) }
    fun updateLocation(v: String) { _uiState.value = _uiState.value.copy(location = v) }
    fun updateCredit(v: String) { _uiState.value = _uiState.value.copy(creditText = v) }
    fun updateNote(v: String) { _uiState.value = _uiState.value.copy(note = v) }
    fun updateColor(hex: String) { _uiState.value = _uiState.value.copy(colorHex = hex) }

    fun updateCourseType(type: CourseType) {
        _uiState.value = _uiState.value.copy(courseType = type, userManuallyChangedType = true)
    }

    fun addScheduleSlot() {
        val current = _uiState.value
        _uiState.value = current.copy(
            schedules = current.schedules + ScheduleDraft(endWeek = current.totalWeeks.coerceAtMost(16))
        )
        recheckConflicts()
    }

    fun removeScheduleSlot(index: Int) {
        val current = _uiState.value
        if (current.schedules.size <= 1) return
        _uiState.value = current.copy(schedules = current.schedules.filterIndexed { i, _ -> i != index })
        recheckConflicts()
    }

    fun updateScheduleSlot(index: Int, transform: (ScheduleDraft) -> ScheduleDraft) {
        val current = _uiState.value
        val updated = current.schedules.mapIndexed { i, item ->
            if (i == index) transform(item) else item
        }
        _uiState.value = current.copy(schedules = updated, error = null)
        recheckConflicts()
    }

    private fun recheckConflicts() {
        viewModelScope.launch {
            val s = _uiState.value
            val sem = timetableRepository.getCurrentSemester() ?: return@launch
            val existingCourses = timetableRepository.getCoursesWithDetails(sem.id)
            val periods = timetableRepository.getClassPeriods(sem.id)
            val holidays = holidayRepository.getHolidaysByYear(clock.today().year)
            val overrides = holidayRepository.getSchoolOverrides(sem.id)
            val draftCourseId = s.courseId ?: "draft-preview"
            val draft = CourseWithDetails(
                course = Course(
                    id = draftCourseId,
                    semesterId = sem.id,
                    name = s.name.ifBlank { "待保存课程" },
                    teacher = s.teacher,
                    location = s.location,
                    courseType = s.courseType,
                    colorHex = s.colorHex
                ),
                schedules = s.schedules.map { it.toDomain(draftCourseId) },
                exceptions = existingCourses.firstOrNull { it.course.id == draftCourseId }?.exceptions.orEmpty()
            )
            val conflicts = ConflictDetectionEngine.detectConflictsForDraft(
                semester = sem,
                existingCourses = existingCourses,
                draftCourse = draft,
                periods = periods,
                holidays = holidays,
                overrides = overrides
            )
            _uiState.value = _uiState.value.copy(conflicts = conflicts)
        }
    }

    fun save(onSuccess: () -> Unit) {
        val s = _uiState.value
        if (s.name.trim().isEmpty()) {
            _uiState.value = s.copy(error = "请输入课程名称")
            return
        }
        for (slot in s.schedules) {
            if (slot.startPeriod > slot.endPeriod) {
                _uiState.value = s.copy(error = "开始节次不能大于结束节次")
                return
            }
            if (slot.weekRuleType == WeekRuleType.CUSTOM && slot.customWeeks.isEmpty()) {
                _uiState.value = s.copy(error = "自定义周次请至少选择 1 个上课周")
                return
            }
            if (slot.weekRuleType != WeekRuleType.CUSTOM && slot.startWeek > slot.endWeek) {
                _uiState.value = s.copy(error = "开始周次不能大于结束周次")
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val courseId = s.courseId ?: newId()
            val now = clock.currentTimeMillis()
            val course = Course(
                id = courseId,
                semesterId = s.semesterId,
                name = s.name.trim(),
                teacher = s.teacher.trim(),
                location = s.location.trim(),
                courseType = s.courseType,
                colorHex = s.colorHex,
                credit = s.creditText.toDoubleOrNull() ?: 0.0,
                note = s.note.trim(),
                isAutoDetectedPe = s.isAutoDetectedPe,
                createdAt = now,
                updatedAt = now
            )
            val schedules = s.schedules.map { it.toDomain(courseId) }
            timetableRepository.saveCourseWithSchedules(course, schedules)
            alarmScheduler.rescheduleAllUpcomingAlarms("course_saved")
            weatherScheduler.schedulePeWeatherChecks()
            widgetRefresher.refreshAllWidgets()
            _uiState.value = _uiState.value.copy(isSaving = false)
            onSuccess()
        }
    }

    private fun ScheduleDraft.toDomain(courseId: String) = CourseSchedule(
        id = id,
        courseId = courseId,
        dayOfWeek = dayOfWeek.coerceIn(1, 7),
        startPeriod = startPeriod.coerceAtLeast(1),
        endPeriod = endPeriod.coerceAtLeast(startPeriod),
        weekRuleType = weekRuleType,
        startWeek = startWeek.coerceAtLeast(1),
        endWeek = endWeek.coerceAtLeast(startWeek),
        customWeeks = customWeeks.sorted(),
        teacherOverride = teacherOverride.trim().ifBlank { null },
        locationOverride = locationOverride.trim().ifBlank { null }
    )
}

@HiltViewModel
class SemesterManagementViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
    private val alarmScheduler: AlarmSchedulerPort,
    private val widgetRefresher: WidgetRefresherPort,
    private val clock: AppClock
) : ViewModel() {

    val semesters: StateFlow<List<Semester>> = timetableRepository.observeSemesters()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentSemester: StateFlow<Semester?> = timetableRepository.observeCurrentSemester()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val classPeriods: StateFlow<List<ClassPeriod>> = currentSemester.flatMapLatest { sem ->
        if (sem == null) flowOf(emptyList()) else timetableRepository.observeClassPeriods(sem.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    fun createSemester(name: String, startDateIso: String, totalWeeks: Int, periodsPerDay: Int) {
        viewModelScope.launch {
            val parsedDate = runCatching { LocalDate.parse(startDateIso.trim()) }.getOrNull()
            if (name.isBlank() || parsedDate == null) {
                _feedback.value = "请填写正确的学期名称和开始日期（格式 YYYY-MM-DD）"
                return@launch
            }
            val user = timetableRepository.ensureGuestInitialized()
            val sem = Semester(
                userId = user.id,
                name = name.trim(),
                startDate = parsedDate.toString(),
                totalWeeks = totalWeeks.coerceIn(1, 30),
                periodsPerDay = periodsPerDay.coerceIn(4, 16),
                isCurrent = true,
                createdAt = clock.currentTimeMillis(),
                updatedAt = clock.currentTimeMillis()
            )
            timetableRepository.saveSemester(sem)
            alarmScheduler.rescheduleAllUpcomingAlarms("semester_created")
            widgetRefresher.refreshAllWidgets()
            _feedback.value = "已创建并切换至「${sem.name}」"
        }
    }

    fun updateCurrentSemesterMeta(name: String, startDateIso: String, totalWeeks: Int, periodsPerDay: Int) {
        viewModelScope.launch {
            val current = currentSemester.value ?: return@launch
            val parsedDate = runCatching { LocalDate.parse(startDateIso.trim()) }.getOrNull()
            if (name.isBlank() || parsedDate == null) {
                _feedback.value = "请填写有效的学期名称和日期（YYYY-MM-DD）"
                return@launch
            }
            val updated = current.copy(
                name = name.trim(),
                startDate = parsedDate.toString(),
                totalWeeks = totalWeeks.coerceIn(1, 30),
                periodsPerDay = periodsPerDay.coerceIn(4, 16)
            )
            val existingPeriods = timetableRepository.getClassPeriods(current.id)
            val defaults = DefaultClassPeriodsFactory.createForSemester(current.id, updated.periodsPerDay)
            val mergedPeriods = defaults.map { def ->
                existingPeriods.firstOrNull { it.dayOfWeek == 0 && it.periodNumber == def.periodNumber } ?: def
            } + existingPeriods.filter { it.dayOfWeek != 0 && it.periodNumber <= updated.periodsPerDay }
            timetableRepository.saveSemester(updated, mergedPeriods)
            alarmScheduler.rescheduleAllUpcomingAlarms("semester_updated")
            widgetRefresher.refreshAllWidgets()
            _feedback.value = "学期基础配置已更新"
        }
    }

    fun switchSemester(semesterId: String) {
        viewModelScope.launch {
            timetableRepository.switchCurrentSemester(semesterId)
            alarmScheduler.rescheduleAllUpcomingAlarms("semester_switched")
            widgetRefresher.refreshAllWidgets()
            _feedback.value = "已切换当前学期"
        }
    }

    fun deleteSemester(semesterId: String) {
        viewModelScope.launch {
            if (semesters.value.size <= 1) {
                _feedback.value = "至少需要保留一个学期"
                return@launch
            }
            timetableRepository.deleteSemester(semesterId)
            alarmScheduler.rescheduleAllUpcomingAlarms("semester_deleted")
            widgetRefresher.refreshAllWidgets()
            _feedback.value = "已删除学期"
        }
    }

    fun saveDayPeriods(dayOfWeek: Int, editedPeriods: List<ClassPeriod>) {
        viewModelScope.launch {
            val sem = currentSemester.value ?: return@launch
            val existing = timetableRepository.getClassPeriods(sem.id)
                .filterNot { it.dayOfWeek == dayOfWeek }
            val normalized = editedPeriods.map {
                it.copy(semesterId = sem.id, dayOfWeek = dayOfWeek, updatedAt = clock.currentTimeMillis())
            }
            timetableRepository.saveClassPeriods(sem.id, existing + normalized)
            alarmScheduler.rescheduleAllUpcomingAlarms("periods_updated")
            widgetRefresher.refreshAllWidgets()
            _feedback.value = if (dayOfWeek == 0) {
                "已保存全周通用节次时间"
            } else {
                "已保存周${HolidayCalendarResolver.dayOfWeekShortZh(dayOfWeek)}独立节次作息"
            }
        }
    }

    fun clearDaySpecificPeriods(dayOfWeek: Int) {
        if (dayOfWeek == 0) return
        viewModelScope.launch {
            val sem = currentSemester.value ?: return@launch
            val remaining = timetableRepository.getClassPeriods(sem.id).filterNot { it.dayOfWeek == dayOfWeek }
            timetableRepository.saveClassPeriods(sem.id, remaining)
            alarmScheduler.rescheduleAllUpcomingAlarms("periods_reset")
            _feedback.value = "已恢复周${HolidayCalendarResolver.dayOfWeekShortZh(dayOfWeek)}使用全周默认作息"
        }
    }

    fun clearFeedback() { _feedback.value = null }
}
