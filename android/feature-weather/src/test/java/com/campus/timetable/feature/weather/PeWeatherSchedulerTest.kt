package com.campus.timetable.feature.weather

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.domain.engine.DefaultClassPeriodsFactory
import com.campus.timetable.domain.model.*
import com.campus.timetable.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PeWeatherSchedulerTest {

    // 模拟周一（2026-09-07 19:00），周二（2026-09-08）上午有体育课
    private val mondayClock = object : AppClock {
        override fun zoneId(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun instant(): Instant =
            LocalDateTime.of(2026, 9, 7, 19, 0).atZone(zoneId()).toInstant()
    }

    @Test
    fun `checks Tuesday PE weather on Monday evening and degrades gracefully on API error`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sem = Semester(id = "sem-1", userId = "u1", name = "2026秋", startDate = "2026-09-07", totalWeeks = 16)
        val periods = DefaultClassPeriodsFactory.createForSemester("sem-1", 12)
        val peCourse = CourseWithDetails(
            course = Course(id = "c-pe", semesterId = "sem-1", name = "大学体育(篮球)", courseType = CourseType.PHYSICAL_EDUCATION),
            schedules = listOf(CourseSchedule(id = "s-pe", courseId = "c-pe", dayOfWeek = 2, startPeriod = 3, endPeriod = 4, startWeek = 1, endWeek = 16)),
            exceptions = emptyList()
        )
        val timetableRepo = FakeTimetableRepo(sem, periods, listOf(peCourse))
        val holidayRepo = FakeHolidayRepo()

        val rainyTuesday = WeatherSnapshot(
            providerName = "Open-Meteo",
            cityName = "北京",
            currentTempC = 24.0,
            currentCondition = "多云",
            currentAqi = 60,
            fetchedAt = mondayClock.currentTimeMillis(),
            daily = listOf(
                WeatherForecastDay(
                    date = "2026-09-08",
                    tempMinC = 19.0,
                    tempMaxC = 28.0,
                    conditionText = "雷阵雨",
                    weatherCode = 95,
                    precipitationProbMax = 85,
                    isRain = true,
                    isSnow = false,
                    isThunder = true,
                    aqiMax = 80,
                    hourly = listOf(WeatherHourlyPoint(10, 26.0, 95, "雷阵雨", 85, true, false, true, 80))
                )
            )
        )

        val weatherRepo = FakeWeatherRepo(Result.success(rainyTuesday))
        val scheduler = PeWeatherScheduler(context, timetableRepo, holidayRepo, weatherRepo, mondayClock)

        // 周一晚检查周二体育课天气 -> 准确触发雷电与降雨预警
        val advice = scheduler.checkAndNotifyForDate(LocalDate.of(2026, 9, 8), "明日体育课天气预报", sendNotification = false)
        assertNotNull(advice)
        assertTrue(advice!!.needsAlert)
        assertTrue(WeatherAlertType.RAIN in advice.alertTypes)
        assertTrue(WeatherAlertType.THUNDER in advice.alertTypes)

        // 天气接口异常时，返回 null 不抛异常，不影响正常课表与闹钟
        val failedWeatherRepo = FakeWeatherRepo(Result.failure(RuntimeException("Network timeout")))
        val failedScheduler = PeWeatherScheduler(context, timetableRepo, holidayRepo, failedWeatherRepo, mondayClock)
        val safeResult = failedScheduler.checkAndNotifyForDate(LocalDate.of(2026, 9, 8), "复查", sendNotification = false)
        assertNull(safeResult)
    }

    private class FakeTimetableRepo(val sem: Semester, val periods: List<ClassPeriod>, val courses: List<CourseWithDetails>) : TimetableRepository {
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

    private class FakeHolidayRepo : HolidayRepository {
        override fun observeHolidaysByYear(year: Int) = flowOf(emptyList<Holiday>())
        override suspend fun getHolidaysByYear(year: Int) = emptyList<Holiday>()
        override suspend fun syncYearHolidays(year: Int, reminderAdvanceDays: Int) = HolidayYearStatus(year, false, "", "", null, emptyList(), "")
        override suspend fun updateHolidayReminderDays(year: Int, reminderAdvanceDays: Int) = Unit
        override fun observeSchoolOverrides(semesterId: String) = flowOf(emptyList<SchoolCalendarOverride>())
        override suspend fun getSchoolOverrides(semesterId: String) = emptyList<SchoolCalendarOverride>()
        override suspend fun saveSchoolOverride(override: SchoolCalendarOverride) = Unit
        override suspend fun deleteSchoolOverride(overrideId: String) = Unit
    }

    private class FakeWeatherRepo(private val liveResult: Result<WeatherSnapshot>) : WeatherRepository {
        private var setting = WeatherSetting()
        override fun observeWeatherSetting() = flowOf(setting)
        override suspend fun getWeatherSetting() = setting
        override suspend fun saveWeatherSetting(setting: WeatherSetting) { this.setting = setting }
        override suspend fun fetchLiveWeather(setting: WeatherSetting) = liveResult
        override suspend fun searchCities(query: String, provider: WeatherProviderType) = Result.success(emptyList<CityOption>())
        override fun getCachedWeather(setting: WeatherSetting): WeatherSnapshot? = null
    }
}
