package com.campus.timetable.domain.engine

import com.campus.timetable.domain.model.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 体育课自动识别器：识别大学/高职常见体育及运动项目课程，同时避免误判理论人文类课程（如体育经济、体育法等）。
 */
object PeCourseDetector {
    private val includeKeywords = listOf(
        "体育", "大学体育", "公共体育", "体质测试", "体能", "篮球", "足球", "排球",
        "羽毛球", "乒乓球", "网球", "游泳", "太极", "武术", "健美操", "田径",
        "瑜伽", "轮滑", "跆拳道", "散打", "形体", "啦啦操", "定向越野", "棒垒球", "跳绳"
    )
    private val excludeKeywords = listOf(
        "体育经济", "体育法", "体育管理", "体育新闻", "体育传播", "体育产业", "体育史", "体育哲学", "体育心理学"
    )

    fun isLikelyPeCourse(courseName: String): Boolean {
        val normalized = courseName.trim()
        if (normalized.isEmpty()) return false
        if (excludeKeywords.any { normalized.contains(it, ignoreCase = true) }) return false
        return includeKeywords.any { normalized.contains(it, ignoreCase = true) }
    }
}

/**
 * 标准默认作息时间表生成器（中国高校常见 12 节制，用户可在学期管理中按全周或按星期自定义）。
 */
object DefaultClassPeriodsFactory {
    private val defaultTimes = listOf(
        1 to ("08:00" to "08:45"),
        2 to ("08:55" to "09:40"),
        3 to ("10:00" to "10:45"),
        4 to ("10:55" to "11:40"),
        5 to ("14:00" to "14:45"),
        6 to ("14:55" to "15:40"),
        7 to ("16:00" to "16:45"),
        8 to ("16:55" to "17:40"),
        9 to ("19:00" to "19:45"),
        10 to ("19:55" to "20:40"),
        11 to ("20:50" to "21:35"),
        12 to ("21:45" to "22:30")
    )

    fun createForSemester(semesterId: String, periodsPerDay: Int = 12): List<ClassPeriod> {
        return (1..periodsPerDay).map { idx ->
            val fallbackStart = LocalTime.of(8, 0).plusMinutes(((idx - 1) * 55L).coerceAtMost(840))
            val fallbackEnd = fallbackStart.plusMinutes(45)
            val pair = defaultTimes.firstOrNull { it.first == idx }?.second
            ClassPeriod(
                semesterId = semesterId,
                dayOfWeek = 0,
                periodNumber = idx,
                startTime = pair?.first ?: fallbackStart.toString().take(5),
                endTime = pair?.second ?: fallbackEnd.toString().take(5)
            )
        }
    }
}

/**
 * 法定节假日与学校校历决策引擎。
 * 核心规则：
 * 1. 学校实际校历覆盖（SchoolCalendarOverride）优先级永远高于国务院法定节假日与调休。
 * 2. 国务院周末调休工作日（STATUTORY_WORKDAY）绝不自动等同于大学补课日，仅做标注提醒，除非学校校历明确配置补课映射。
 * 3. 法定节假日（LEGAL_HOLIDAY）默认暂停普通课程闹钟；若学校校历覆盖为照常上课/补课且允许闹钟恢复，则自动恢复。
 */
object HolidayCalendarResolver {
    fun resolveDayStatus(
        semester: Semester,
        date: LocalDate,
        holidays: List<Holiday>,
        overrides: List<SchoolCalendarOverride>
    ): DayCalendarStatus {
        val weekNumber = ScheduleResolutionEngine.calculateWeekNumber(semester.parsedStartDate(), date)
        val dateStr = date.toString()
        // 支持连续区间假期事件（startDate..endDate）匹配任意一天
        val holiday = holidays.firstOrNull { it.containsDate(date) }
        val override = overrides.firstOrNull { !it.isDeleted && it.date == dateStr }

        if (override != null) {
            return when (override.overrideType) {
                CalendarOverrideType.HOLIDAY -> DayCalendarStatus(
                    date = date,
                    weekNumber = weekNumber,
                    holiday = holiday,
                    schoolOverride = override,
                    isHolidayOffDay = true,
                    shouldPauseRegularAlarms = !override.enableAlarmOverride,
                    badgeText = "校假",
                    detailText = "学校校历：${override.title}（放假）"
                )
                CalendarOverrideType.SUSPENDED -> DayCalendarStatus(
                    date = date,
                    weekNumber = weekNumber,
                    holiday = holiday,
                    schoolOverride = override,
                    isHolidayOffDay = true,
                    shouldPauseRegularAlarms = true,
                    badgeText = "停课",
                    detailText = "学校校历：${override.title}（全校停课）"
                )
                CalendarOverrideType.NORMAL_CLASS -> DayCalendarStatus(
                    date = date,
                    weekNumber = weekNumber,
                    holiday = holiday,
                    schoolOverride = override,
                    isHolidayOffDay = false,
                    shouldPauseRegularAlarms = !override.enableAlarmOverride,
                    badgeText = "照常",
                    detailText = "学校校历：${override.title}（覆盖节假日照常上课）"
                )
                CalendarOverrideType.MAKEUP_CLASS -> {
                    val mappedDow = override.mappedDayOfWeek ?: date.dayOfWeek.value
                    val mappedWeek = override.mappedWeekNumber ?: weekNumber
                    DayCalendarStatus(
                        date = date,
                        weekNumber = weekNumber,
                        holiday = holiday,
                        schoolOverride = override,
                        isHolidayOffDay = false,
                        shouldPauseRegularAlarms = !override.enableAlarmOverride,
                        effectiveDayOfWeekForRegularClasses = mappedDow,
                        effectiveWeekForRegularClasses = mappedWeek,
                        badgeText = "补周${dayOfWeekShortZh(mappedDow)}",
                        detailText = "学校补课：${override.title}（按周${dayOfWeekShortZh(mappedDow)}课表上课）"
                    )
                }
            }
        }

        if (holiday != null) {
            return if (holiday.isOffDay && holiday.holidayType == HolidayType.LEGAL_HOLIDAY) {
                DayCalendarStatus(
                    date = date,
                    weekNumber = weekNumber,
                    holiday = holiday,
                    isHolidayOffDay = true,
                    shouldPauseRegularAlarms = true,
                    badgeText = "休·${holiday.name.take(2)}",
                    detailText = "法定节假日：${holiday.name}（默认暂停普通课程闹钟，如学校照常上课可在校历中覆盖）"
                )
            } else {
                DayCalendarStatus(
                    date = date,
                    weekNumber = weekNumber,
                    holiday = holiday,
                    isHolidayOffDay = false,
                    shouldPauseRegularAlarms = false,
                    badgeText = "调休班",
                    detailText = "国务院调休工作日（${holiday.name}）：未自动套用补课，请以学校教务处通知为准"
                )
            }
        }

        return DayCalendarStatus(
            date = date,
            weekNumber = weekNumber
        )
    }

    fun dayOfWeekShortZh(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "一"
        2 -> "二"
        3 -> "三"
        4 -> "四"
        5 -> "五"
        6 -> "六"
        7 -> "日"
        else -> "?"
    }
}

/**
 * 课表核心解析与调度计算引擎。
 * 负责周次计算、单双周/自定义周规则、按星期差异化作息、临时调课/停课合成与冲突标注。
 */
object ScheduleResolutionEngine {

    /**
     * 将学期开始日期对齐到当周周一，计算目标日期对应的教学周数（从第 1 周起；早于开学为 <= 0）。
     */
    fun calculateWeekNumber(semesterStartDate: LocalDate, targetDate: LocalDate): Int {
        val firstMonday = semesterStartDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val targetMonday = targetDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeksDiff = ChronoUnit.WEEKS.between(firstMonday, targetMonday).toInt()
        return weeksDiff + 1
    }

    /**
     * 获取第 weekNumber 周对应的周一到周日 7 个日期。
     */
    fun datesOfWeek(semesterStartDate: LocalDate, weekNumber: Int): List<LocalDate> {
        val firstMonday = semesterStartDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val monday = firstMonday.plusWeeks((weekNumber - 1).toLong())
        return (0L..6L).map { monday.plusDays(it) }
    }

    /**
     * 判断某条周期排课规则是否在指定周次生效。
     */
    fun matchesWeek(schedule: CourseSchedule, weekNumber: Int): Boolean {
        if (schedule.isDeleted || weekNumber <= 0) return false
        return when (schedule.weekRuleType) {
            WeekRuleType.ALL -> weekNumber in schedule.startWeek..schedule.endWeek
            WeekRuleType.ODD -> weekNumber in schedule.startWeek..schedule.endWeek && weekNumber % 2 == 1
            WeekRuleType.EVEN -> weekNumber in schedule.startWeek..schedule.endWeek && weekNumber % 2 == 0
            WeekRuleType.CUSTOM -> weekNumber in schedule.customWeeks
        }
    }

    /**
     * 获取某一天第 startPeriod 到 endPeriod 节的起止时间（优先匹配按星期自定义作息，其次回落到 dayOfWeek=0 通用作息）。
     */
    fun resolvePeriodTimeRange(
        periods: List<ClassPeriod>,
        dayOfWeek: Int,
        startPeriod: Int,
        endPeriod: Int
    ): Pair<LocalTime, LocalTime> {
        fun findPeriod(num: Int): ClassPeriod? {
            return periods.firstOrNull { it.dayOfWeek == dayOfWeek && it.periodNumber == num }
                ?: periods.firstOrNull { it.dayOfWeek == 0 && it.periodNumber == num }
        }

        val startObj = findPeriod(startPeriod)
        val endObj = findPeriod(endPeriod)
        val startTime = startObj?.parsedStartTime()
            ?: LocalTime.of(8, 0).plusMinutes(((startPeriod - 1) * 55L).coerceIn(0, 840))
        val endTime = endObj?.parsedEndTime()
            ?: startTime.plusMinutes(((endPeriod - startPeriod + 1).coerceAtLeast(1) * 50L).coerceAtMost(300))
        return startTime to endTime
    }

    /**
     * 计算指定某一天的所有有效课程实例（包含临时调课迁入、停课排除、教室/教师临时变更、节假日与校历覆盖、冲突标注）。
     */
    fun resolveDaySessions(
        semester: Semester,
        date: LocalDate,
        courses: List<CourseWithDetails>,
        periods: List<ClassPeriod>,
        holidays: List<Holiday> = emptyList(),
        overrides: List<SchoolCalendarOverride> = emptyList(),
        includeCancelled: Boolean = false
    ): Pair<DayCalendarStatus, List<ResolvedCourseSession>> {
        val dayStatus = HolidayCalendarResolver.resolveDayStatus(semester, date, holidays, overrides)
        val dateStr = date.toString()
        val weekNumber = dayStatus.weekNumber
        val rawSessions = mutableListOf<ResolvedCourseSession>()

        // 1. 常规排课（若当天未放假且未全校停课，或处于法定假日但仍需在课表显示并标注节假日暂停）
        val skipRegularBecauseSchoolSuspended = dayStatus.schoolOverride?.overrideType in setOf(
            CalendarOverrideType.HOLIDAY,
            CalendarOverrideType.SUSPENDED
        )

        if (!skipRegularBecauseSchoolSuspended && dayStatus.effectiveWeekForRegularClasses in 1..semester.totalWeeks) {
            val queryDow = dayStatus.effectiveDayOfWeekForRegularClasses
            val queryWeek = dayStatus.effectiveWeekForRegularClasses

            for (item in courses) {
                if (item.course.isDeleted) continue
                for (schedule in item.schedules) {
                    if (schedule.isDeleted) continue
                    if (schedule.dayOfWeek != queryDow) continue
                    if (!matchesWeek(schedule, queryWeek)) continue

                    // 查找针对原日期的临时变更记录
                    val matchingExceptions = item.exceptions.filter {
                        !it.isDeleted && it.scheduleId == schedule.id && it.originalDate == dateStr
                    }

                    val cancelOrMoveOut = matchingExceptions.firstOrNull {
                        it.exceptionType == CourseExceptionType.CANCEL ||
                            it.exceptionType == CourseExceptionType.RESCHEDULE
                    }

                    val (startTime, endTime) = resolvePeriodTimeRange(
                        periods, date.dayOfWeek.value, schedule.startPeriod, schedule.endPeriod
                    )

                    if (cancelOrMoveOut != null) {
                        if (includeCancelled) {
                            val note = if (cancelOrMoveOut.exceptionType == CourseExceptionType.CANCEL) {
                                "临时停课${if (cancelOrMoveOut.reason.isNotBlank()) "：${cancelOrMoveOut.reason}" else ""}"
                            } else {
                                "已调至 ${cancelOrMoveOut.targetDate} 第${cancelOrMoveOut.targetStartPeriod}-${cancelOrMoveOut.targetEndPeriod}节"
                            }
                            rawSessions += ResolvedCourseSession(
                                course = item.course,
                                scheduleId = schedule.id,
                                date = date,
                                weekNumber = weekNumber,
                                dayOfWeek = date.dayOfWeek.value,
                                startPeriod = schedule.startPeriod,
                                endPeriod = schedule.endPeriod,
                                startTime = startTime,
                                endTime = endTime,
                                effectiveTeacher = schedule.teacherOverride ?: item.course.teacher,
                                effectiveLocation = schedule.locationOverride ?: item.course.location,
                                isCancelled = true,
                                exceptionNote = note,
                                holidayPaused = dayStatus.shouldPauseRegularAlarms
                            )
                        }
                        continue
                    }

                    val locMod = matchingExceptions.lastOrNull { it.exceptionType == CourseExceptionType.MODIFY_LOCATION }
                    val teacherMod = matchingExceptions.lastOrNull { it.exceptionType == CourseExceptionType.MODIFY_TEACHER }
                    val effLoc = locMod?.newLocation ?: schedule.locationOverride ?: item.course.location
                    val effTeacher = teacherMod?.newTeacher ?: schedule.teacherOverride ?: item.course.teacher
                    val isMod = locMod != null || teacherMod != null
                    val modNotes = buildList {
                        if (locMod != null) add("教室临时变更为 ${locMod.newLocation}")
                        if (teacherMod != null) add("教师临时变更为 ${teacherMod.newTeacher}")
                    }.joinToString("；").ifBlank { null }

                    rawSessions += ResolvedCourseSession(
                        course = item.course,
                        scheduleId = schedule.id,
                        date = date,
                        weekNumber = weekNumber,
                        dayOfWeek = date.dayOfWeek.value,
                        startPeriod = schedule.startPeriod,
                        endPeriod = schedule.endPeriod,
                        startTime = startTime,
                        endTime = endTime,
                        effectiveTeacher = effTeacher,
                        effectiveLocation = effLoc,
                        isModified = isMod,
                        exceptionNote = modNotes,
                        holidayPaused = dayStatus.shouldPauseRegularAlarms
                    )
                }
            }
        }

        // 2. 临时调课迁入到当天（targetDate == dateStr）
        for (item in courses) {
            if (item.course.isDeleted) continue
            for (ex in item.exceptions) {
                if (ex.isDeleted) continue
                if (ex.exceptionType != CourseExceptionType.RESCHEDULE) continue
                if (ex.targetDate != dateStr) continue
                val baseSchedule = item.schedules.firstOrNull { it.id == ex.scheduleId }
                val targetStart = ex.targetStartPeriod ?: baseSchedule?.startPeriod ?: 1
                val targetEnd = ex.targetEndPeriod ?: baseSchedule?.endPeriod ?: targetStart
                val (startTime, endTime) = resolvePeriodTimeRange(
                    periods, date.dayOfWeek.value, targetStart, targetEnd
                )
                rawSessions += ResolvedCourseSession(
                    course = item.course,
                    scheduleId = ex.scheduleId,
                    date = date,
                    weekNumber = weekNumber,
                    dayOfWeek = date.dayOfWeek.value,
                    startPeriod = targetStart,
                    endPeriod = targetEnd,
                    startTime = startTime,
                    endTime = endTime,
                    effectiveTeacher = ex.newTeacher ?: baseSchedule?.teacherOverride ?: item.course.teacher,
                    effectiveLocation = ex.newLocation ?: baseSchedule?.locationOverride ?: item.course.location,
                    isRescheduled = true,
                    isModified = true,
                    exceptionNote = "临时调课（原 ${ex.originalDate}）${if (ex.reason.isNotBlank()) "：${ex.reason}" else ""}",
                    holidayPaused = false // 明确调入当天的临时课不因常规假日静音
                )
            }
        }

        // 3. 当日课程冲突标注（仅在非取消课程之间检测节次重叠）
        val activeSessions = rawSessions.filter { !it.isCancelled }
        val annotated = rawSessions.map { s ->
            if (s.isCancelled) return@map s
            val conflicts = activeSessions.filter { other ->
                other !== s &&
                    !(other.course.id == s.course.id && other.scheduleId == s.scheduleId && other.isRescheduled == s.isRescheduled) &&
                    periodsOverlap(s.startPeriod, s.endPeriod, other.startPeriod, other.endPeriod)
            }.map { it.course.name }.distinct()
            if (conflicts.isEmpty()) s else s.copy(conflictingCourseNames = conflicts)
        }.sortedWith(compareBy<ResolvedCourseSession> { it.startPeriod }.thenBy { it.course.name })

        return dayStatus to annotated
    }

    fun periodsOverlap(startA: Int, endA: Int, startB: Int, endB: Int): Boolean {
        return startA <= endB && startB <= endA
    }
}

/**
 * 多维课程冲突检测引擎。
 * 综合星期、周次（普通/单双周/自定义周）、节次重叠、临时调课与停课，绝不仅凭课程名判断。
 */
object ConflictDetectionEngine {

    fun detectConflictsInSemester(
        semester: Semester,
        courses: List<CourseWithDetails>,
        periods: List<ClassPeriod>,
        holidays: List<Holiday> = emptyList(),
        overrides: List<SchoolCalendarOverride> = emptyList(),
        fromWeek: Int = 1,
        toWeek: Int = semester.totalWeeks
    ): List<CourseConflict> {
        val result = mutableListOf<CourseConflict>()
        val startDate = semester.parsedStartDate()
        val safeFrom = fromWeek.coerceAtLeast(1)
        val safeTo = toWeek.coerceAtMost(semester.totalWeeks)

        for (week in safeFrom..safeTo) {
            for (date in ScheduleResolutionEngine.datesOfWeek(startDate, week)) {
                val (_, sessions) = ScheduleResolutionEngine.resolveDaySessions(
                    semester = semester,
                    date = date,
                    courses = courses,
                    periods = periods,
                    holidays = holidays,
                    overrides = overrides,
                    includeCancelled = false
                )
                for (i in sessions.indices) {
                    for (j in i + 1 until sessions.size) {
                        val a = sessions[i]
                        val b = sessions[j]
                        if (ScheduleResolutionEngine.periodsOverlap(a.startPeriod, a.endPeriod, b.startPeriod, b.endPeriod)) {
                            val ovStart = maxOf(a.startPeriod, b.startPeriod)
                            val ovEnd = minOf(a.endPeriod, b.endPeriod)
                            result += CourseConflict(
                                date = date,
                                weekNumber = week,
                                dayOfWeek = date.dayOfWeek.value,
                                courseAId = a.course.id,
                                courseAName = a.course.name,
                                courseBId = b.course.id,
                                courseBName = b.course.name,
                                overlapStartPeriod = ovStart,
                                overlapEndPeriod = ovEnd,
                                reason = "第${week}周 周${HolidayCalendarResolver.dayOfWeekShortZh(date.dayOfWeek.value)}（$date）第${ovStart}-${ovEnd}节时间重叠"
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * 在保存新课、编辑排课或批量导入预览时，预检测目标课程与现有课表的真实冲突。
     */
    fun detectConflictsForDraft(
        semester: Semester,
        existingCourses: List<CourseWithDetails>,
        draftCourse: CourseWithDetails,
        periods: List<ClassPeriod>,
        holidays: List<Holiday> = emptyList(),
        overrides: List<SchoolCalendarOverride> = emptyList()
    ): List<CourseConflict> {
        val merged = existingCourses.filterNot { it.course.id == draftCourse.course.id } + draftCourse
        return detectConflictsInSemester(semester, merged, periods, holidays, overrides)
            .filter { it.courseAId == draftCourse.course.id || it.courseBId == draftCourse.course.id }
    }
}

/**
 * 体育课天气判定与合并提醒引擎。
 */
object PeWeatherRuleEngine {

    fun evaluatePeSessionsForDate(
        date: LocalDate,
        peSessions: List<ResolvedCourseSession>,
        forecastDay: WeatherForecastDay?,
        setting: WeatherSetting
    ): PeWeatherAdvice? {
        if (!setting.enablePeWeatherReminder) return null
        val activePe = peSessions.filter { it.isPhysicalEducation && !it.isCancelled && !it.holidayPaused }
        if (activePe.isEmpty() || forecastDay == null) return null

        val alerts = linkedSetOf<WeatherAlertType>()
        val reasons = mutableListOf<String>()

        // 根据体育课具体上课小时段精准匹配逐小时预报，若无逐小时则回退至当日预报
        val relevantHours = activePe.flatMap { s ->
            val startH = s.startTime.hour
            val endH = s.endTime.hour
            forecastDay.hourly.filter { it.hour in startH..endH }
        }

        val checkRain = if (relevantHours.isNotEmpty()) {
            relevantHours.any { it.isRain || it.precipitationProb >= 40 }
        } else {
            forecastDay.isRain || forecastDay.precipitationProbMax >= 40
        }
        val checkSnow = if (relevantHours.isNotEmpty()) relevantHours.any { it.isSnow } else forecastDay.isSnow
        val checkThunder = if (relevantHours.isNotEmpty()) relevantHours.any { it.isThunder } else forecastDay.isThunder
        val maxTemp = relevantHours.maxOfOrNull { it.tempC } ?: forecastDay.tempMaxC
        val minTemp = relevantHours.minOfOrNull { it.tempC } ?: forecastDay.tempMinC
        val maxAqi = relevantHours.mapNotNull { it.aqi }.maxOrNull() ?: forecastDay.aqiMax

        if (setting.remindThunder && checkThunder) {
            alerts += WeatherAlertType.THUNDER
            reasons += "雷电天气（注意户外防雷安全）"
        }
        if (setting.remindRain && checkRain) {
            alerts += WeatherAlertType.RAIN
            reasons += "有降雨风险（降水概率 ${forecastDay.precipitationProbMax}%，请带雨具并关注室内场馆调整）"
        }
        if (setting.remindSnow && checkSnow) {
            alerts += WeatherAlertType.SNOW
            reasons += "有降雪天气（注意地面湿滑与保暖）"
        }
        if (maxTemp >= setting.highTempThresholdC) {
            alerts += WeatherAlertType.HIGH_TEMP
            reasons += "高温 ${"%.1f".format(maxTemp)}°C（达到阈值 ${setting.highTempThresholdC}°C，请做好防暑补水）"
        }
        if (minTemp <= setting.lowTempThresholdC) {
            alerts += WeatherAlertType.LOW_TEMP
            reasons += "低温 ${"%.1f".format(minTemp)}°C（低于阈值 ${setting.lowTempThresholdC}°C，请注意添衣热身）"
        }
        if (maxAqi != null && maxAqi >= setting.aqiThreshold) {
            alerts += WeatherAlertType.POOR_AQI
            reasons += "空气质量较差（AQI $maxAqi，建议减少剧烈户外运动）"
        }

        val courseNames = activePe.map { it.course.name }.distinct()
        val timeRange = activePe.joinToString("、") { "${it.startTime}-${it.endTime}" }
        val needsAlert = alerts.isNotEmpty()
        val summary = if (needsAlert) {
            "体育课天气预警：${alerts.joinToString("/") { it.label }}"
        } else {
            "体育课天气适宜（${forecastDay.conditionText} ${forecastDay.tempMinC.toInt()}~${forecastDay.tempMaxC.toInt()}°C）"
        }
        val detail = if (needsAlert) {
            "$date（${courseNames.joinToString("、")}，$timeRange）预计出现：${reasons.joinToString("；")}。"
        } else {
            "$date（${courseNames.joinToString("、")}，$timeRange）天气平稳，无异常预警。"
        }

        return PeWeatherAdvice(
            date = date,
            peCourseNames = courseNames,
            timeRangeText = timeRange,
            alertTypes = alerts.toList(),
            summary = summary,
            detail = detail,
            needsAlert = needsAlert
        )
    }
}

/**
 * 游客数据与云端账号合并及备份导入冲突解决引擎（Last-Write-Wins + 显式冲突记录，绝不静默丢数据）。
 */
object BackupMergeEngine {

    fun merge(local: BackupBundle, remote: BackupBundle): BackupMergeResult {
        val conflicts = mutableListOf<MergeConflictItem>()
        var added = 0
        var updated = 0

        fun <T> mergeList(
            entityType: String,
            localList: List<T>,
            remoteList: List<T>,
            idOf: (T) -> String,
            updatedAtOf: (T) -> Long,
            titleOf: (T) -> String
        ): List<T> {
            val map = linkedMapOf<String, T>()
            localList.forEach { map[idOf(it)] = it }
            for (r in remoteList) {
                val id = idOf(r)
                val l = map[id]
                if (l == null) {
                    map[id] = r
                    added++
                } else if (l != r) {
                    val lTs = updatedAtOf(l)
                    val rTs = updatedAtOf(r)
                    if (rTs > lTs) {
                        map[id] = r
                        updated++
                        conflicts += MergeConflictItem(entityType, id, titleOf(r), "采用云端较新版本")
                    } else if (lTs > rTs) {
                        conflicts += MergeConflictItem(entityType, id, titleOf(l), "保留本地较新版本")
                    } else {
                        conflicts += MergeConflictItem(entityType, id, titleOf(l), "时间戳一致，保留本地版本")
                    }
                }
            }
            return map.values.toList()
        }

        val semesters = mergeList("学期", local.semesters, remote.semesters, { it.id }, { it.updatedAt }, { it.name })
        val periods = mergeList("节次时间", local.classPeriods, remote.classPeriods, { it.id }, { it.updatedAt }, { "第${it.periodNumber}节" })
        val bellTemplates = mergeList("作息模板", local.bellTemplates, remote.bellTemplates, { it.id }, { it.updatedAt }, { it.name })
        val courses = mergeList("课程", local.courses, remote.courses, { it.id }, { it.updatedAt }, { it.name })
        val schedules = mergeList("排课规则", local.schedules, remote.schedules, { it.id }, { it.updatedAt }, { "周${it.dayOfWeek} 第${it.startPeriod}-${it.endPeriod}节" })
        val exceptions = mergeList("调停课记录", local.exceptions, remote.exceptions, { it.id }, { it.updatedAt }, { "${it.exceptionType.label} (${it.originalDate})" })
        val overrides = mergeList("学校校历", local.schoolOverrides, remote.schoolOverrides, { it.id }, { it.updatedAt }, { "${it.date} ${it.title}" })
        val alarms = mergeList("闹钟设置", local.alarmSettings, remote.alarmSettings, { it.id }, { it.updatedAt }, { it.scopeType.name })

        val mergedWeather = when {
            local.weatherSetting == null -> remote.weatherSetting
            remote.weatherSetting == null -> local.weatherSetting
            remote.weatherSetting.updatedAt > local.weatherSetting.updatedAt -> remote.weatherSetting
            else -> local.weatherSetting
        }

        // 确保至多只有一个当前学期
        val currentSemesters = semesters.filter { !it.isDeleted && it.isCurrent }
        val normalizedSemesters = if (currentSemesters.size > 1) {
            val winnerId = currentSemesters.maxByOrNull { it.updatedAt }?.id
            semesters.map { if (it.id == winnerId) it else it.copy(isCurrent = false) }
        } else {
            semesters
        }

        return BackupMergeResult(
            merged = BackupBundle(
                schemaVersion = maxOf(local.schemaVersion, remote.schemaVersion),
                appVersion = local.appVersion,
                exportedAt = System.currentTimeMillis(),
                semesters = normalizedSemesters,
                classPeriods = periods,
                bellTemplates = bellTemplates,
                courses = courses,
                schedules = schedules,
                exceptions = exceptions,
                schoolOverrides = overrides,
                alarmSettings = alarms,
                weatherSetting = mergedWeather
            ),
            addedCount = added,
            updatedCount = updated,
            conflicts = conflicts
        )
    }
}
