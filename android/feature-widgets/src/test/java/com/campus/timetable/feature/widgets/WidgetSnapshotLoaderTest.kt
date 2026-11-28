package com.campus.timetable.feature.widgets

import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.domain.engine.DefaultClassPeriodsFactory
import com.campus.timetable.domain.model.*
import com.campus.timetable.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class WidgetSnapshotLoaderTest {

    // 模拟 2026-09-08（第1周周二）08:30，当天 10:00-11:40 有体育课
    private val tuesdayMorningClock = object : AppClock {
        override fun zoneId(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun instant(): Instant = LocalDateTime.of(2026, 9, 8, 8, 30).atZone(zoneId()).toInstant()
    }

    @Test
    fun `all four widgets compute accurate real data for today, next countdown, weekly, and weather pe`() = runTest {
        val sem = Semester(id = "sem-1", userId = "u1", name = "2026秋", startDate = "2026-09-07", totalWeeks = 16)
        val periods = DefaultClassPeriodsFactory.createForSemester("sem-1", 12)
        val peCourse = CourseWithDetails(
            course = Course(id = "c-pe", semesterId = "sem-1", name = "大学体育(网球)", location = "东区网球场", courseType = CourseType.PHYSICAL_EDUCATION),
            schedules = listOf(CourseSchedule(id = "s1", courseId = "c-pe", dayOfWeek = 2, startPeriod = 3, endPeriod = 4, startWeek = 1, endWeek = 16)),
            exceptions = emptyList()
        )
        val snapshot = WeatherSnapshot(
            providerName = "Open-Meteo",
            cityName = "杭州",
            currentTempC = 35.0,
            currentCondition = "雷阵雨",
            currentAqi = 90,
            fetchedAt = tuesdayMorningClock.currentTimeMillis(),
            daily = listOf(
                WeatherForecastDay(
                    date = "2026-09-08", tempMinC = 25.0, tempMaxC = 36.0, conditionText = "雷阵雨",
                    weatherCode = 95, precipitationProbMax = 90, isRain = true, isSnow = false, isThunder = true, aqiMax = 90
                )
            )
        )

        val loader = WidgetSnapshotLoader(
            timetableRepository = StubTimetableRepo(sem, periods, listOf(peCourse)),
            holidayRepository = StubHolidayRepo(),
            weatherRepository = StubWeatherRepo(WeatherSetting(cityName = "杭州"), snapshot),
            clock = tuesdayMorningClock
        )

        // 1. 今日课程小组件
        val today = loader.loadToday()
        assertEquals(1, today.sessions.size)
        assertEquals("大学体育(网球)", today.sessions.first().course.name)

        // 2. 下一节课倒计时小组件（08:30 -> 10:00 = 1小时30分钟）
        val next = loader.loadNextCourse()
        assertTrue(next.hasNextCourse)
        assertEquals("大学体育(网球)", next.courseName)
        assertTrue(next.countdownText.contains("1小时30分钟"))
        assertEquals("东区网球场", next.locationText)

        // 3. 本周课表小组件（周一到周日 7 天完整结构）
        val weekly = loader.loadWeekly()
        assertEquals(7, weekly.days.size)
        assertEquals(1, weekly.days[1].sessions.size)
        assertTrue(weekly.days[1].isToday)

        // 4. 天气与体育课提醒小组件
        val weatherPe = loader.loadWeatherPe()
        assertEquals("杭州", weatherPe.cityName)
        assertEquals("35°C", weatherPe.temperatureText)
        assertEquals(1, weatherPe.todayPeCourses.size)
        assertTrue(weatherPe.hasAlert)
        assertTrue(weatherPe.adviceText.contains("预警"))
    }

    private class StubTimetableRepo(val sem: Semester, val periods: List<ClassPeriod>, val courses: List<CourseWithDetails>) : TimetableRepository {
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
        override suspend fun getCoursesWithDetails(semesterId: String) = courses
        override suspend fun getCourseWithDetails(courseId: String) = courses.firstOrNull { it.course.id == courseId }
        override suspend fun saveCourseWithSchedules(course: Course, schedules: List<CourseSchedule>) = Unit
        override suspend fun deleteCourse(courseId: String) = Unit
        override suspend fun saveCourseException(exception: CourseException) = Unit
        override suspend fun deleteCourseException(exceptionId: String) = Unit
    }

    private class StubHolidayRepo : HolidayRepository {
        override fun observeHolidaysByYear(year: Int) = flowOf(emptyList<Holiday>())
        override suspend fun getHolidaysByYear(year: Int) = emptyList<Holiday>()
        override suspend fun syncYearHolidays(year: Int, reminderAdvanceDays: Int) = HolidayYearStatus(year, false, "", "", null, emptyList(), "")
        override suspend fun updateHolidayReminderDays(year: Int, reminderAdvanceDays: Int) = Unit
        override fun observeSchoolOverrides(semesterId: String) = flowOf(emptyList<SchoolCalendarOverride>())
        override suspend fun getSchoolOverrides(semesterId: String) = emptyList<SchoolCalendarOverride>()
        override suspend fun saveSchoolOverride(override: SchoolCalendarOverride) = Unit
        override suspend fun deleteSchoolOverride(overrideId: String) = Unit
    }

    private class StubWeatherRepo(private val s: WeatherSetting, private val snap: WeatherSnapshot?) : WeatherRepository {
        override fun observeWeatherSetting() = flowOf(s)
        override suspend fun getWeatherSetting() = s
        override suspend fun saveWeatherSetting(setting: WeatherSetting) = Unit
        override suspend fun fetchLiveWeather(setting: WeatherSetting) = Result.failure<WeatherSnapshot>(Exception())
        override suspend fun searchCities(query: String, provider: WeatherProviderType) = Result.success(emptyList<CityOption>())
        override fun getCachedWeather(setting: WeatherSetting) = snap
    }
}
