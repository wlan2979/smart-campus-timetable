package com.campus.timetable.feature.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.domain.engine.DefaultClassPeriodsFactory
import com.campus.timetable.domain.model.*
import com.campus.timetable.domain.repository.AlarmRepository
import com.campus.timetable.domain.repository.HolidayRepository
import com.campus.timetable.domain.repository.TimetableRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmSchedulerTest {

    // 2026-09-07 07:00（第1周周一，早上 08:00 有高等数学）
    private val fixedClock = object : AppClock {
        override fun zoneId(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun instant(): Instant =
            LocalDateTime.of(2026, 9, 7, 7, 0).atZone(zoneId()).toInstant()
    }

    @Test
    fun `alarm scheduling cancels stale alarms on course edit, respects holiday pause and school override`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sem = Semester(id = "sem-1", userId = "u1", name = "2026秋", startDate = "2026-09-07", totalWeeks = 16)
        val periods = DefaultClassPeriodsFactory.createForSemester("sem-1", 12)

        val course = Course(id = "c1", semesterId = "sem-1", name = "高等数学", location = "教A-101")
        val schMon = CourseSchedule(id = "s1", courseId = "c1", dayOfWeek = 1, startPeriod = 1, endPeriod = 2, startWeek = 1, endWeek = 16)

        val fakeTimetable = InMemoryTimetableRepo(sem, periods, mutableListOf(CourseWithDetails(course, listOf(schMon), emptyList())))
        val fakeHolidays = InMemoryHolidayRepo()
        val fakeAlarmRepo = InMemoryAlarmRepo(
            AlarmSetting(id = "global_alarm", enabled = true, mode = AlarmMode.ALARM_CLOCK, advanceMinutes = 15, enableClassEndReminder = true)
        )

        val scheduler = CourseAlarmScheduler(context, fakeTimetable, fakeHolidays, fakeAlarmRepo, fixedClock)
        val shadowAlarmManager = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

        // 1. 正常排课：应注册上课提前 15 分钟闹钟（07:45）+ 下课提醒（09:40）
        scheduler.rescheduleAllUpcomingAlarms("initial")
        val initialPlans = scheduler.buildUpcomingPlans(daysAhead = 2)
        assertEquals(2, initialPlans.size)
        assertEquals("高等数学", initialPlans[0].courseName)
        assertEquals("教A-101", initialPlans[0].location)
        assertEquals(15, initialPlans[0].advanceMinutes)
        assertFalse(initialPlans[0].isClassEnd)
        assertEquals(2, scheduler.getActiveScheduledCount())
        assertTrue(shadowAlarmManager.scheduledAlarms.isNotEmpty())
        assertNotNull(shadowAlarmManager.scheduledAlarms.firstOrNull())

        // 验证真闹钟广播构建的通知携带 fullScreenIntent，确保锁屏/后台可弹出响铃页
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val fireIntent = android.content.Intent(context, CourseAlarmReceiver::class.java).apply {
            action = CourseAlarmScheduler.ACTION_FIRE_ALARM
            putExtra(CourseAlarmScheduler.EXTRA_REQUEST_CODE, 8888)
            putExtra(CourseAlarmScheduler.EXTRA_COURSE_NAME, "高等数学")
            putExtra(CourseAlarmScheduler.EXTRA_LOCATION, "教A-101")
            putExtra(CourseAlarmScheduler.EXTRA_TIME_TEXT, "08:00-09:40")
            putExtra(CourseAlarmScheduler.EXTRA_MODE, AlarmMode.ALARM_CLOCK.name)
            putExtra(CourseAlarmScheduler.EXTRA_IS_CLASS_END, false)
            putExtra(CourseAlarmScheduler.EXTRA_IS_HOLIDAY, false)
        }
        CourseAlarmReceiver().onReceive(context, fireIntent)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val posted = Shadows.shadowOf(nm).allNotifications.firstOrNull { it.fullScreenIntent != null }
        assertNotNull("真正闹钟模式通知必须设置 fullScreenIntent", posted)

        // 2. 删除课程后重新调度 -> 旧闹钟全部被取消！
        fakeTimetable.courses.clear()
        scheduler.rescheduleAllUpcomingAlarms("course_deleted")
        assertEquals(0, scheduler.getActiveScheduledCount())
        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())

        // 3. 恢复课程并把 2026-09-07 标记为法定节假日 -> 普通课程闹钟自动暂停！
        fakeTimetable.courses.add(CourseWithDetails(course, listOf(schMon), emptyList()))
        fakeHolidays.holidays.add(
            Holiday(year = 2026, date = "2026-09-07", name = "测试法定假日", holidayType = HolidayType.LEGAL_HOLIDAY, isOffDay = true, source = "国务院", version = "v1")
        )
        scheduler.rescheduleAllUpcomingAlarms("holiday_added")
        assertEquals(0, scheduler.buildUpcomingPlans(daysAhead = 1).size)

        // 4. 添加学校特殊校历覆盖（照常上课且恢复闹钟）-> 闹钟自动恢复！
        fakeHolidays.overrides.add(
            SchoolCalendarOverride(semesterId = "sem-1", date = "2026-09-07", overrideType = CalendarOverrideType.NORMAL_CLASS, title = "学校正常行课", enableAlarmOverride = true)
        )
        scheduler.rescheduleAllUpcomingAlarms("school_override_added")
        assertEquals(2, scheduler.buildUpcomingPlans(daysAhead = 1).size)
    }

    @Test
    fun `boot restore receiver handles BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, and MY_PACKAGE_REPLACED`() = runTest {
        val reasons = mutableListOf<String>()
        val spyScheduler = object : com.campus.timetable.domain.repository.AlarmSchedulerPort {
            override suspend fun rescheduleAllUpcomingAlarms(reason: String) {
                reasons += reason
            }
            override suspend fun cancelAllAlarms() = Unit
            override fun canScheduleExactAlarms(): Boolean = true
        }
        assertTrue(AlarmRestoreReceiver.restoreIfSupported(android.content.Intent.ACTION_BOOT_COMPLETED, spyScheduler))
        assertTrue(AlarmRestoreReceiver.restoreIfSupported(android.content.Intent.ACTION_TIME_CHANGED, spyScheduler))
        assertTrue(AlarmRestoreReceiver.restoreIfSupported(android.content.Intent.ACTION_TIMEZONE_CHANGED, spyScheduler))
        assertTrue(AlarmRestoreReceiver.restoreIfSupported(android.content.Intent.ACTION_MY_PACKAGE_REPLACED, spyScheduler))
        assertFalse(AlarmRestoreReceiver.restoreIfSupported("com.other.UNRELATED_ACTION", spyScheduler))
        assertEquals(4, reasons.size)
        assertTrue(reasons.any { it.contains("BOOT_COMPLETED") })
        assertTrue(reasons.any { it.contains("TIMEZONE_CHANGED") })
    }

    private class InMemoryTimetableRepo(
        val sem: Semester,
        val periods: List<ClassPeriod>,
        val courses: MutableList<CourseWithDetails>
    ) : TimetableRepository {
        override suspend fun ensureGuestInitialized() = User()
        override fun observeCurrentUser(): Flow<User?> = flowOf(User())
        override suspend fun getCurrentUser() = User()
        override fun observeSemesters() = flowOf(listOf(sem))
        override fun observeCurrentSemester() = flowOf(sem)
        override suspend fun getCurrentSemester() = sem
        override suspend fun saveSemester(semester: Semester, periods: List<ClassPeriod>?) = Unit
        override suspend fun switchCurrentSemester(semesterId: String) = Unit
        override suspend fun deleteSemester(semesterId: String) = Unit
        override fun observeClassPeriods(semesterId: String) = flowOf(periods)
        override suspend fun getClassPeriods(semesterId: String) = periods
        override suspend fun saveClassPeriods(semesterId: String, periods: List<ClassPeriod>) = Unit
        override fun observeBellTemplates() = flowOf(emptyList<BellScheduleTemplate>())
        override suspend fun saveBellTemplate(template: BellScheduleTemplate) = Unit
        override suspend fun deleteBellTemplate(templateId: String) = Unit
        override suspend fun applyClassPeriodsToDays(semesterId: String, targetDays: List<Int>, templatePeriods: List<ClassPeriod>) = Unit
        override fun observeCoursesWithDetails(semesterId: String) = flowOf(courses)
        override suspend fun getCoursesWithDetails(semesterId: String) = courses.toList()
        override suspend fun getCourseWithDetails(courseId: String) = courses.firstOrNull { it.course.id == courseId }
        override suspend fun saveCourseWithSchedules(course: Course, schedules: List<CourseSchedule>) = Unit
        override suspend fun deleteCourse(courseId: String) = Unit
        override suspend fun saveCourseException(exception: CourseException) = Unit
        override suspend fun deleteCourseException(exceptionId: String) = Unit
    }

    private class InMemoryHolidayRepo : HolidayRepository {
        val holidays = mutableListOf<Holiday>()
        val overrides = mutableListOf<SchoolCalendarOverride>()
        override fun observeHolidaysByYear(year: Int) = flowOf(holidays)
        override suspend fun getHolidaysByYear(year: Int) = holidays.toList()
        override suspend fun syncYearHolidays(year: Int, reminderAdvanceDays: Int) = HolidayYearStatus(year, true, "test", "v1", 0L, holidays, "")
        override suspend fun updateHolidayReminderDays(year: Int, reminderAdvanceDays: Int) = Unit
        override fun observeSchoolOverrides(semesterId: String) = flowOf(overrides)
        override suspend fun getSchoolOverrides(semesterId: String) = overrides.toList()
        override suspend fun saveSchoolOverride(override: SchoolCalendarOverride) { overrides.add(override) }
        override suspend fun deleteSchoolOverride(overrideId: String) { overrides.removeAll { it.id == overrideId } }
    }

    private class InMemoryAlarmRepo(initial: AlarmSetting) : AlarmRepository {
        private val global = MutableStateFlow(initial)
        override fun observeGlobalSetting() = global
        override suspend fun getGlobalSetting() = global.value
        override fun observeCourseSetting(courseId: String) = flowOf(null)
        override suspend fun getCourseSetting(courseId: String): AlarmSetting? = null
        override suspend fun getAllAlarmSettings() = listOf(global.value)
        override suspend fun saveAlarmSetting(setting: AlarmSetting) { global.value = setting }
        override suspend fun muteTodayAll(dateIso: String) { global.value = global.value.copy(muteDate = dateIso) }
        override suspend fun clearMuteToday() { global.value = global.value.copy(muteDate = null) }
    }
}
