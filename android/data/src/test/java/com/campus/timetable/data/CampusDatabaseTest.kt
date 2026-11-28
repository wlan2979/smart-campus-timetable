package com.campus.timetable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.core.config.AppRuntimeConfig
import com.campus.timetable.data.local.CampusDatabase
import com.campus.timetable.data.remote.holiday.HolidayRemoteDataSource
import com.campus.timetable.data.repository.HolidayRepositoryImpl
import com.campus.timetable.data.repository.SettingsRepositoryImpl
import com.campus.timetable.data.repository.SchoolImportRepositoryImpl
import com.campus.timetable.data.repository.TimetableRepositoryImpl
import com.campus.timetable.core.security.KeystoreCryptoManager
import com.campus.timetable.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CampusDatabaseTest {

    private lateinit var db: CampusDatabase
    private lateinit var mockServer: MockWebServer

    private val fixedClock = object : AppClock {
        override fun zoneId(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun instant(): Instant = Instant.parse("2026-09-14T01:00:00Z")
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        db.close()
        mockServer.shutdown()
    }

    @Test
    fun `guest mode initializes usable semester and persists course schedules and exceptions`() = runTest {
        val repo = TimetableRepositoryImpl(db, fixedClock)
        val guest = repo.ensureGuestInitialized()
        assertTrue(guest.isGuest)

        val semester = repo.getCurrentSemester()
        assertNotNull(semester)
        assertEquals(12, repo.getClassPeriods(semester!!.id).size)

        val course = Course(
            id = "course-1",
            semesterId = semester.id,
            name = "编译原理",
            teacher = "赵教授",
            location = "计科楼302"
        )
        val schedule = CourseSchedule(
            id = "sch-1",
            courseId = course.id,
            dayOfWeek = 2,
            startPeriod = 3,
            endPeriod = 4,
            weekRuleType = WeekRuleType.ODD,
            startWeek = 1,
            endWeek = 15
        )
        repo.saveCourseWithSchedules(course, listOf(schedule))

        val ex = CourseException(
            id = "ex-1",
            courseId = course.id,
            scheduleId = schedule.id,
            exceptionType = CourseExceptionType.MODIFY_LOCATION,
            originalDate = "2026-09-15",
            originalWeek = 1,
            newLocation = "实验楼A108"
        )
        repo.saveCourseException(ex)

        val loaded = repo.getCourseWithDetails("course-1")
        assertNotNull(loaded)
        assertEquals("编译原理", loaded!!.course.name)
        assertEquals(1, loaded.schedules.size)
        assertEquals(1, loaded.exceptions.size)
        assertEquals("实验楼A108", loaded.exceptions.first().newLocation)
    }

    @Test
    fun `holiday repository persists published year and explicitly warns on unpublished 404 year`() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val config = AppRuntimeConfig(
            apiBaseUrl = mockServer.url("/").toString(),
            qweatherApiHost = "",
            qweatherApiKey = "",
            holidayRemoteUrlTemplate = mockServer.url("/holidays/{year}.json").toString(),
            appVersion = "1.0.0"
        )
        val remote = HolidayRemoteDataSource(OkHttpClient(), json, config)
        val repo = HolidayRepositoryImpl(db, remote, fixedClock)

        // 1. 已公布年份正常拉取并持久化来源和版本
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "year": 2026,
                  "papers": ["https://www.gov.cn/zhengce/content/202511/example.htm"],
                  "days": [
                    {"name": "国庆节", "date": "2026-10-01", "isOffDay": true},
                    {"name": "国庆节", "date": "2026-10-10", "isOffDay": false}
                  ]
                }
                """.trimIndent()
            )
        )
        val okStatus = repo.syncYearHolidays(2026)
        assertTrue(okStatus.isPublished)
        assertEquals(2, okStatus.holidays.size)
        assertTrue(okStatus.source.contains("国务院办公厅"))
        assertEquals(2, repo.getHolidaysByYear(2026).size)

        // 2. 未公布年份(404)：明确提示且不虚构数据
        mockServer.enqueue(MockResponse().setResponseCode(404))
        val unpubStatus = repo.syncYearHolidays(2029)
        assertFalse(unpubStatus.isPublished)
        assertTrue(unpubStatus.holidays.isEmpty())
        assertTrue(unpubStatus.notice.contains("尚未公布"))
    }

    @Test
    fun `batchImportCourses with replaceExisting clears stale semester courses atomically`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val timetableRepo = TimetableRepositoryImpl(db, fixedClock)
        timetableRepo.ensureGuestInitialized()
        val semester = timetableRepo.getCurrentSemester()!!

        val config = AppRuntimeConfig(
            apiBaseUrl = mockServer.url("/").toString(),
            qweatherApiHost = "",
            qweatherApiKey = "",
            holidayRemoteUrlTemplate = mockServer.url("/holidays/{year}.json").toString(),
            appVersion = "2.2.7"
        )
        val json = Json { ignoreUnknownKeys = true }
        val settingsRepo = SettingsRepositoryImpl(context, KeystoreCryptoManager(context), config)
        val importRepo = SchoolImportRepositoryImpl(context, db, timetableRepo, settingsRepo, OkHttpClient(), config, json, fixedClock)

        // 先写入一次包含旧课表/脏课程的导入
        importRepo.batchImportCourses(
            semesterId = semester.id,
            candidates = listOf(
                ParsedCourseCandidate(name = "旧残留假课", dayOfWeek = 1, startPeriod = 1, endPeriod = 1, startWeek = 1, endWeek = 16)
            ),
            sourceType = ImportSourceType.SCHOOL_ADAPTER,
            sourceName = "https://jwxt.example.edu.cn/eams/courseTableForStd.action",
            replaceExisting = false
        )
        assertEquals(1, timetableRepo.getCoursesWithDetails(semester.id).size)

        // 以「覆盖当前学期旧课表」重新导入，旧课必须被清除，绝不残留造成假冲突
        val history = importRepo.batchImportCourses(
            semesterId = semester.id,
            candidates = listOf(
                ParsedCourseCandidate(name = "高级程序设计", dayOfWeek = 1, startPeriod = 1, endPeriod = 2, startWeek = 9, endWeek = 16)
            ),
            sourceType = ImportSourceType.SCHOOL_ADAPTER,
            sourceName = "https://jwxt.example.edu.cn/eams/courseTableForStd.action",
            replaceExisting = true
        )
        assertEquals(1, history.importedCount)
        assertEquals(0, history.conflictCount)
        val current = timetableRepo.getCoursesWithDetails(semester.id)
        assertEquals(1, current.size)
        assertEquals("高级程序设计", current.first().course.name)
        assertEquals(2, current.first().schedules.first().endPeriod)
    }

    @Test
    fun `import flow updates semester startDate, official bell periods and preserves multi-teacher same-name courses`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val timetableRepo = TimetableRepositoryImpl(db, fixedClock)
        timetableRepo.ensureGuestInitialized()
        val semester = timetableRepo.getCurrentSemester()!!

        val config = AppRuntimeConfig(
            apiBaseUrl = mockServer.url("/").toString(),
            qweatherApiHost = "",
            qweatherApiKey = "",
            holidayRemoteUrlTemplate = mockServer.url("/holidays/{year}.json").toString(),
            appVersion = "2.2.8"
        )
        val json = Json { ignoreUnknownKeys = true }
        val settingsRepo = SettingsRepositoryImpl(context, KeystoreCryptoManager(context), config)
        val importRepo = SchoolImportRepositoryImpl(context, db, timetableRepo, settingsRepo, OkHttpClient(), config, json, fixedClock)

        // 1. 更新开学第一天 + 官网 12 节作息时间
        val updatedSemester = semester.copy(startDate = "2026-08-31")
        timetableRepo.saveSemester(updatedSemester)
        val officialPeriods = listOf(
            1 to ("08:00" to "08:45"), 2 to ("08:50" to "09:35"),
            3 to ("10:05" to "10:50"), 4 to ("10:55" to "11:40"),
            5 to ("14:00" to "14:45"), 6 to ("14:50" to "15:35"),
            7 to ("16:05" to "16:50"), 8 to ("16:55" to "17:40"),
            9 to ("18:40" to "19:25"), 10 to ("19:30" to "20:15"),
            11 to ("20:25" to "21:10"), 12 to ("21:15" to "22:00")
        ).map { (num, t) ->
            ClassPeriod(id = "${semester.id}_d0_p$num", semesterId = semester.id, dayOfWeek = 0, periodNumber = num, startTime = t.first, endTime = t.second)
        }
        timetableRepo.applyClassPeriodsToDays(semester.id, listOf(0), officialPeriods)

        // 2. 批量导入同课名但不同老师/教室的 3 条电工学
        val candidates = listOf(
            ParsedCourseCandidate(name = "电工学", teacher = "李老师", location = "一教202(主校区)", dayOfWeek = 1, startPeriod = 5, endPeriod = 6, startWeek = 1, endWeek = 16),
            ParsedCourseCandidate(name = "电工学", teacher = "王老师", location = "综合楼305(主校区)", dayOfWeek = 4, startPeriod = 1, endPeriod = 4, startWeek = 1, endWeek = 16),
            ParsedCourseCandidate(name = "电工学", teacher = "李老师", location = "一教202(主校区)", dayOfWeek = 4, startPeriod = 7, endPeriod = 8, startWeek = 1, endWeek = 16)
        )
        val history = importRepo.batchImportCourses(semester.id, candidates, ImportSourceType.SCHOOL_ADAPTER, "https://jwxt.example.edu.cn/eams/courseTableForStd.action", replaceExisting = true)
        assertEquals(3, history.importedCount)
        assertEquals(0, history.conflictCount)

        val reloadedSem = timetableRepo.getCurrentSemester()!!
        assertEquals("2026-08-31", reloadedSem.startDate)
        val periods = timetableRepo.getClassPeriods(semester.id)
        assertEquals("08:50", periods.single { it.periodNumber == 2 }.startTime)
        assertEquals("16:05", periods.single { it.periodNumber == 7 }.startTime)

        val loadedCourses = timetableRepo.getCoursesWithDetails(semester.id)
        val thuSessions = com.campus.timetable.domain.engine.ScheduleResolutionEngine.resolveDaySessions(
            reloadedSem,
            java.time.LocalDate.of(2026, 11, 26),
            loadedCourses,
            periods
        ).second
        assertEquals(2, thuSessions.size)
        assertEquals("王老师", thuSessions.first { it.startPeriod == 1 }.effectiveTeacher)
        assertEquals(1 to 4, thuSessions.first { it.startPeriod == 1 }.let { it.startPeriod to it.endPeriod })
        assertEquals("16:05", thuSessions.first { it.startPeriod == 7 }.startTime.toString())
    }

    @Test
    fun `observeSchools sorts strictly by pinyin A-Z regardless of support status and provinces sort by Chinese pinyin`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val timetableRepo = TimetableRepositoryImpl(db, fixedClock)
        val config = AppRuntimeConfig(
            apiBaseUrl = mockServer.url("/").toString(),
            qweatherApiHost = "",
            qweatherApiKey = "",
            holidayRemoteUrlTemplate = mockServer.url("/holidays/{year}.json").toString(),
            appVersion = "2.2.9"
        )
        val json = Json { ignoreUnknownKeys = true }
        val settingsRepo = SettingsRepositoryImpl(context, KeystoreCryptoManager(context), config)
        val importRepo = SchoolImportRepositoryImpl(context, db, timetableRepo, settingsRepo, OkHttpClient(), config, json, fixedClock)

        db.schoolDao().replaceCatalog(
            listOf(
                com.campus.timetable.data.local.entity.SchoolEntity(
                    id = "s-z", code = "1", name = "浙江大学", shortName = "浙大", aliases = "",
                    pinyin = "zhejiangdaxue", pinyinInitials = "zjdx",
                    province = "浙江省", city = "杭州市", schoolType = "公办", schoolLevel = "UNDERGRADUATE",
                    campuses = "", systemType = "ZHENGFANG", systemVersion = "", adapterId = "zf_standard",
                    portalUrl = "https://jwxt.zju.edu.cn", supportStatus = "VERIFIED", verified = true,
                    lastVerifiedAt = null, statusNote = "", updatedAt = 1L
                ),
                com.campus.timetable.data.local.entity.SchoolEntity(
                    id = "s-a", code = "2", name = "安徽大学", shortName = "安大", aliases = "",
                    pinyin = "anhuidaxue", pinyinInitials = "ahdx",
                    province = "安徽省", city = "合肥市", schoolType = "公办", schoolLevel = "UNDERGRADUATE",
                    campuses = "", systemType = "UNKNOWN", systemVersion = "", adapterId = null,
                    portalUrl = "", supportStatus = "UNSUPPORTED", verified = false,
                    lastVerifiedAt = null, statusNote = "", updatedAt = 1L
                ),
                com.campus.timetable.data.local.entity.SchoolEntity(
                    id = "s-d", code = "3", name = "大连理工大学", shortName = "大工", aliases = "",
                    pinyin = "dalianligongdaxue", pinyinInitials = "dllgdx",
                    province = "辽宁省", city = "大连市", schoolType = "公办", schoolLevel = "UNDERGRADUATE",
                    campuses = "", systemType = "EAMS", systemVersion = "", adapterId = "eams_standard",
                    portalUrl = "https://jwxt.example.edu.cn/eams/localLogin.action", supportStatus = "ASSISTED_LOGIN", verified = false,
                    lastVerifiedAt = null, statusNote = "", updatedAt = 1L
                )
            )
        )

        val schools = importRepo.observeSchools("", null, null).first()
        assertEquals(listOf("安徽大学", "大连理工大学", "浙江大学"), schools.map { it.name })

        val provinces = importRepo.getAvailableProvinces()
        assertEquals(listOf("安徽省", "辽宁省", "浙江省"), provinces)
    }
}
