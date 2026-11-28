package com.campus.timetable.domain

import com.campus.timetable.domain.engine.*
import com.campus.timetable.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TimetableEnginesTest {

    private val semester = Semester(
        id = "sem-1",
        userId = "guest-1",
        name = "2026-2027 第一学期",
        startDate = "2026-09-07", // 周一
        totalWeeks = 20,
        periodsPerDay = 12
    )

    private val defaultPeriods = DefaultClassPeriodsFactory.createForSemester("sem-1", 12)

    @Test
    fun `week number calculation aligns to opening Monday`() {
        assertEquals(1, ScheduleResolutionEngine.calculateWeekNumber(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 7)))
        assertEquals(1, ScheduleResolutionEngine.calculateWeekNumber(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13)))
        assertEquals(2, ScheduleResolutionEngine.calculateWeekNumber(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 14)))
        assertEquals(5, ScheduleResolutionEngine.calculateWeekNumber(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `odd even and custom week rules resolve accurately`() {
        val oddCourse = CourseWithDetails(
            course = Course(id = "c-odd", semesterId = "sem-1", name = "数字逻辑实验"),
            schedules = listOf(CourseSchedule(id = "s-odd", courseId = "c-odd", dayOfWeek = 1, startPeriod = 1, endPeriod = 2, weekRuleType = WeekRuleType.ODD, startWeek = 1, endWeek = 16)),
            exceptions = emptyList()
        )
        val evenCourse = CourseWithDetails(
            course = Course(id = "c-even", semesterId = "sem-1", name = "大学物理实验"),
            schedules = listOf(CourseSchedule(id = "s-even", courseId = "c-even", dayOfWeek = 1, startPeriod = 1, endPeriod = 2, weekRuleType = WeekRuleType.EVEN, startWeek = 1, endWeek = 16)),
            exceptions = emptyList()
        )
        val customCourse = CourseWithDetails(
            course = Course(id = "c-custom", semesterId = "sem-1", name = "形势与政策"),
            schedules = listOf(CourseSchedule(id = "s-custom", courseId = "c-custom", dayOfWeek = 3, startPeriod = 5, endPeriod = 6, weekRuleType = WeekRuleType.CUSTOM, customWeeks = listOf(1, 3, 5, 7))),
            exceptions = emptyList()
        )

        // 第1周周一（2026-09-07）：仅单周课生效，不与双周课冲突
        val (_, week1Mon) = ScheduleResolutionEngine.resolveDaySessions(semester, LocalDate.of(2026, 9, 7), listOf(oddCourse, evenCourse), defaultPeriods)
        assertEquals(listOf("数字逻辑实验"), week1Mon.map { it.course.name })
        assertFalse(week1Mon.first().hasConflict)

        // 第2周周一（2026-09-14）：仅双周课生效
        val (_, week2Mon) = ScheduleResolutionEngine.resolveDaySessions(semester, LocalDate.of(2026, 9, 14), listOf(oddCourse, evenCourse), defaultPeriods)
        assertEquals(listOf("大学物理实验"), week2Mon.map { it.course.name })

        // 自定义周：第3周周三有课，第4周周三无课
        val (_, week3Wed) = ScheduleResolutionEngine.resolveDaySessions(semester, LocalDate.of(2026, 9, 23), listOf(customCourse), defaultPeriods)
        val (_, week4Wed) = ScheduleResolutionEngine.resolveDaySessions(semester, LocalDate.of(2026, 9, 30), listOf(customCourse), defaultPeriods)
        assertEquals(1, week3Wed.size)
        assertEquals(0, week4Wed.size)

        // 单双周同一节次在全学期内冲突数应为 0
        val conflicts = ConflictDetectionEngine.detectConflictsInSemester(semester, listOf(oddCourse, evenCourse), defaultPeriods)
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `reschedule and cancel exceptions preserve original schedule and affect conflict detection`() {
        val math = CourseWithDetails(
            course = Course(id = "c-math", semesterId = "sem-1", name = "高等数学", location = "教A-101"),
            schedules = listOf(CourseSchedule(id = "s-math", courseId = "c-math", dayOfWeek = 1, startPeriod = 1, endPeriod = 2, weekRuleType = WeekRuleType.ALL, startWeek = 1, endWeek = 4)),
            exceptions = listOf(
                // 将第2周周一(2026-09-14) 调课到第2周周二(2026-09-15) 第1-2节
                CourseException(
                    id = "ex-move",
                    courseId = "c-math",
                    scheduleId = "s-math",
                    exceptionType = CourseExceptionType.RESCHEDULE,
                    originalDate = "2026-09-14",
                    originalWeek = 2,
                    targetDate = "2026-09-15",
                    targetDayOfWeek = 2,
                    targetStartPeriod = 1,
                    targetEndPeriod = 2,
                    newLocation = "教B-205",
                    reason = "教师出差调课"
                )
            )
        )
        val english = CourseWithDetails(
            course = Course(id = "c-eng", semesterId = "sem-1", name = "大学英语", location = "外语楼301"),
            schedules = listOf(CourseSchedule(id = "s-eng", courseId = "c-eng", dayOfWeek = 2, startPeriod = 1, endPeriod = 2, weekRuleType = WeekRuleType.ALL, startWeek = 1, endWeek = 4)),
            exceptions = emptyList()
        )

        // 原日期 2026-09-14 已无高等数学
        val (_, origDay) = ScheduleResolutionEngine.resolveDaySessions(semester, LocalDate.of(2026, 9, 14), listOf(math, english), defaultPeriods)
        assertTrue(origDay.isEmpty())

        // 目标日期 2026-09-15 出现临时调课的高等数学（地点教B-205），且与大学英语检测到冲突
        val (_, targetDay) = ScheduleResolutionEngine.resolveDaySessions(semester, LocalDate.of(2026, 9, 15), listOf(math, english), defaultPeriods)
        assertEquals(2, targetDay.size)
        val movedMath = targetDay.first { it.course.id == "c-math" }
        assertTrue(movedMath.isRescheduled)
        assertEquals("教B-205", movedMath.effectiveLocation)
        assertTrue(movedMath.hasConflict)

        // 第3周周一(2026-09-21) 原规则依然完整保留！
        val (_, week3Mon) = ScheduleResolutionEngine.resolveDaySessions(semester, LocalDate.of(2026, 9, 21), listOf(math, english), defaultPeriods)
        assertEquals(1, week3Mon.size)
        assertEquals("教A-101", week3Mon.first().effectiveLocation)

        // 全学期冲突引擎仅检出 2026-09-15 这一处真实调课冲突
        val conflicts = ConflictDetectionEngine.detectConflictsInSemester(semester, listOf(math, english), defaultPeriods, fromWeek = 1, toWeek = 4)
        assertEquals(1, conflicts.size)
        assertEquals(LocalDate.of(2026, 9, 15), conflicts.first().date)

        // 如果把大学英语 2026-09-15 临时停课，则冲突自动消除
        val englishWithCancel = english.copy(
            exceptions = listOf(
                CourseException(
                    id = "ex-cancel",
                    courseId = "c-eng",
                    scheduleId = "s-eng",
                    exceptionType = CourseExceptionType.CANCEL,
                    originalDate = "2026-09-15",
                    originalWeek = 2,
                    reason = "英语活动停课一次"
                )
            )
        )
        val conflictsAfterCancel = ConflictDetectionEngine.detectConflictsInSemester(semester, listOf(math, englishWithCancel), defaultPeriods, fromWeek = 1, toWeek = 4)
        assertTrue(conflictsAfterCancel.isEmpty())
    }

    @Test
    fun `day specific class periods override global periods`() {
        val fridayOverride = ClassPeriod(
            semesterId = "sem-1",
            dayOfWeek = 5,
            periodNumber = 5,
            startTime = "13:30",
            endTime = "14:15"
        )
        val allPeriods = defaultPeriods + fridayOverride
        val (monStart, _) = ScheduleResolutionEngine.resolvePeriodTimeRange(allPeriods, 1, 5, 5)
        val (friStart, _) = ScheduleResolutionEngine.resolvePeriodTimeRange(allPeriods, 5, 5, 5)
        assertEquals(LocalTime.of(14, 0), monStart)
        assertEquals(LocalTime.of(13, 30), friStart)
    }

    @Test
    fun `pe detector recognizes real pe courses and rejects theoretical courses`() {
        assertTrue(PeCourseDetector.isLikelyPeCourse("大学体育(篮球)"))
        assertTrue(PeCourseDetector.isLikelyPeCourse("公共体育-羽毛球"))
        assertTrue(PeCourseDetector.isLikelyPeCourse("太极拳"))
        assertFalse(PeCourseDetector.isLikelyPeCourse("体育经济学"))
        assertFalse(PeCourseDetector.isLikelyPeCourse("体育法学概论"))
        assertFalse(PeCourseDetector.isLikelyPeCourse("高等数学"))
    }

    @Test
    fun `holiday pauses regular alarms and school override takes precedence over national holiday`() {
        val course = CourseWithDetails(
            course = Course(id = "c1", semesterId = "sem-1", name = "数据结构"),
            schedules = listOf(CourseSchedule(id = "s1", courseId = "c1", dayOfWeek = 4, startPeriod = 1, endPeriod = 2, weekRuleType = WeekRuleType.ALL, startWeek = 1, endWeek = 16)),
            exceptions = emptyList()
        )
        val nationalDay = Holiday(
            id = "h1", year = 2026, date = "2026-10-01", name = "国庆节",
            holidayType = HolidayType.LEGAL_HOLIDAY, isOffDay = true,
            source = "国务院办公厅", version = "2026-v1"
        )
        val statutoryWorkday = Holiday(
            id = "h2", year = 2026, date = "2026-10-10", name = "国庆节调休",
            holidayType = HolidayType.STATUTORY_WORKDAY, isOffDay = false,
            source = "国务院办公厅", version = "2026-v1"
        )

        // 1. 10月1日法定节假日：默认暂停普通课程闹钟
        val (statusHoliday, sessionsHoliday) = ScheduleResolutionEngine.resolveDaySessions(
            semester, LocalDate.of(2026, 10, 1), listOf(course), defaultPeriods, listOf(nationalDay, statutoryWorkday)
        )
        assertTrue(statusHoliday.isHolidayOffDay)
        assertTrue(statusHoliday.shouldPauseRegularAlarms)
        assertTrue(sessionsHoliday.first().holidayPaused)

        // 2. 10月10日（周六）国务院调休工作日：不自动把周四课表套到周六！
        val (statusWorkday, sessionsWorkday) = ScheduleResolutionEngine.resolveDaySessions(
            semester, LocalDate.of(2026, 10, 10), listOf(course), defaultPeriods, listOf(nationalDay, statutoryWorkday)
        )
        assertFalse(statusWorkday.isHolidayOffDay)
        assertEquals(6, statusWorkday.effectiveDayOfWeekForRegularClasses)
        assertTrue(sessionsWorkday.isEmpty())

        // 3. 学校校历覆盖：10月1日学校照常上课并恢复闹钟 + 10月10日学校明确按周四补课
        val overrides = listOf(
            SchoolCalendarOverride(
                semesterId = "sem-1", date = "2026-10-01", overrideType = CalendarOverrideType.NORMAL_CLASS,
                title = "校内实训照常", enableAlarmOverride = true
            ),
            SchoolCalendarOverride(
                semesterId = "sem-1", date = "2026-10-10", overrideType = CalendarOverrideType.MAKEUP_CLASS,
                mappedDayOfWeek = 4, title = "补周四课程", enableAlarmOverride = true
            )
        )
        val (statusOverriddenHoliday, sessionsOverriddenHoliday) = ScheduleResolutionEngine.resolveDaySessions(
            semester, LocalDate.of(2026, 10, 1), listOf(course), defaultPeriods, listOf(nationalDay), overrides
        )
        assertFalse(statusOverriddenHoliday.shouldPauseRegularAlarms)
        assertFalse(sessionsOverriddenHoliday.first().holidayPaused)

        val (_, sessionsMakeup) = ScheduleResolutionEngine.resolveDaySessions(
            semester, LocalDate.of(2026, 10, 10), listOf(course), defaultPeriods, listOf(statutoryWorkday), overrides
        )
        assertEquals(1, sessionsMakeup.size)
        assertEquals("数据结构", sessionsMakeup.first().course.name)
    }

    @Test
    fun `pe weather engine merges same-day pe classes and checks hourly thresholds`() {
        val pe1 = ResolvedCourseSession(
            course = Course(id = "p1", semesterId = "sem-1", name = "大学体育(足球)", courseType = CourseType.PHYSICAL_EDUCATION),
            scheduleId = "s1", date = LocalDate.of(2026, 9, 8), weekNumber = 1, dayOfWeek = 2,
            startPeriod = 3, endPeriod = 4, startTime = LocalTime.of(10, 0), endTime = LocalTime.of(11, 40),
            effectiveTeacher = "李教练", effectiveLocation = "西操场"
        )
        val pe2 = pe1.copy(
            course = pe1.course.copy(id = "p2", name = "体能训练"),
            startPeriod = 7, endPeriod = 8, startTime = LocalTime.of(16, 0), endTime = LocalTime.of(17, 40)
        )
        val forecast = WeatherForecastDay(
            date = "2026-09-08", tempMinC = 22.0, tempMaxC = 35.0, conditionText = "雷阵雨",
            weatherCode = 95, precipitationProbMax = 80, isRain = true, isSnow = false, isThunder = true, aqiMax = 165,
            hourly = listOf(
                WeatherHourlyPoint(10, 34.5, 95, "雷阵雨", 80, true, false, true, 165),
                WeatherHourlyPoint(16, 31.0, 61, "小雨", 60, true, false, false, 120)
            )
        )
        val advice = PeWeatherRuleEngine.evaluatePeSessionsForDate(
            LocalDate.of(2026, 9, 8), listOf(pe1, pe2), forecast, WeatherSetting(highTempThresholdC = 33.0, aqiThreshold = 150)
        )
        assertNotNull(advice)
        assertTrue(advice!!.needsAlert)
        assertEquals(2, advice.peCourseNames.size)
        assertTrue(WeatherAlertType.THUNDER in advice.alertTypes)
        assertTrue(WeatherAlertType.RAIN in advice.alertTypes)
        assertTrue(WeatherAlertType.HIGH_TEMP in advice.alertTypes)
        assertTrue(WeatherAlertType.POOR_AQI in advice.alertTypes)
    }

    @Test
    fun `chinese parser parses single even and custom week formats accurately`() {
        val odd = ChineseCourseTextParser.parseWeeks("第1-15周(单)")
        assertEquals(WeekRuleType.ODD, odd?.ruleType)
        assertEquals(1, odd?.startWeek)
        assertEquals(15, odd?.endWeek)

        val custom = ChineseCourseTextParser.parseWeeks("1,3,5,7,11周")
        assertEquals(WeekRuleType.CUSTOM, custom?.ruleType)
        assertEquals(listOf(1, 3, 5, 7, 11), custom?.customWeeks)

        val free = ChineseCourseTextParser.parseFreeTextLines("大学体育(篮球) 李强 东区篮球场 周二 3-4节 1-16周")
        assertEquals(1, free.size)
        assertEquals("大学体育(篮球)", free.first().name)
        assertEquals(CourseType.PHYSICAL_EDUCATION, free.first().courseType)
        assertEquals(2, free.first().dayOfWeek)
        assertEquals(3, free.first().startPeriod)
        assertEquals(4, free.first().endPeriod)
    }

    @Test
    fun `backup merge safely combines guest local data and cloud data with conflict visibility`() {
        val localCourse = Course(id = "c-shared", semesterId = "sem-1", name = "高等数学(本地修改)", updatedAt = 2000L)
        val guestOnly = Course(id = "c-guest", semesterId = "sem-1", name = "游客新增选修课", updatedAt = 1500L)
        val remoteOlder = Course(id = "c-shared", semesterId = "sem-1", name = "高等数学(云端旧版)", updatedAt = 1000L)
        val remoteOnly = Course(id = "c-cloud", semesterId = "sem-1", name = "云端既有课程", updatedAt = 1200L)

        val localTemplate = BellScheduleTemplate(id = "tpl-local", name = "我的自定义夏季作息", isPreset = false, updatedAt = 2500L)
        val remoteTemplate = BellScheduleTemplate(id = "tpl-cloud", name = "云端冬季作息", isPreset = false, updatedAt = 1800L)

        val result = BackupMergeEngine.merge(
            local = BackupBundle(courses = listOf(localCourse, guestOnly), bellTemplates = listOf(localTemplate)),
            remote = BackupBundle(courses = listOf(remoteOlder, remoteOnly), bellTemplates = listOf(remoteTemplate))
        )
        assertEquals(3, result.merged.courses.size)
        assertEquals("高等数学(本地修改)", result.merged.courses.first { it.id == "c-shared" }.name)
        assertTrue(result.merged.courses.any { it.id == "c-guest" })
        assertTrue(result.merged.courses.any { it.id == "c-cloud" })
        assertEquals(2, result.merged.bellTemplates.size)
        assertTrue(result.merged.bellTemplates.any { it.id == "tpl-local" })
        assertTrue(result.merged.bellTemplates.any { it.id == "tpl-cloud" })
        assertEquals(1, result.conflicts.size)
    }
}
