package com.campus.timetable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.core.config.AppRuntimeConfig
import com.campus.timetable.core.security.KeystoreCryptoManager
import com.campus.timetable.data.local.CampusDatabase
import com.campus.timetable.data.remote.sync.BackendSyncClient
import com.campus.timetable.data.remote.sync.HttpStatusException
import com.campus.timetable.data.repository.SettingsRepositoryImpl
import com.campus.timetable.data.repository.SyncRepositoryImpl
import com.campus.timetable.data.repository.TimetableRepositoryImpl
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
@Config(sdk = [26, 33])
class SyncAndThemePersistenceTest {

    private lateinit var context: Context
    private lateinit var db: CampusDatabase

    private val fixedClock = object : AppClock {
        override fun zoneId(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun instant(): Instant = Instant.parse("2026-09-14T01:00:00Z")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `backup export, versioned preview, merge import, and dark mode persistence work on API 26 and 33`() = runTest {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val config = AppRuntimeConfig(
            apiBaseUrl = "http://127.0.0.1:8080/",
            qweatherApiHost = "",
            qweatherApiKey = "",
            holidayRemoteUrlTemplate = "",
            schoolCatalogRemoteUrl = "",
            appVersion = "1.0.0"
        )
        val crypto = KeystoreCryptoManager(context)
        val settingsRepo = SettingsRepositoryImpl(context, crypto, config)
        val timetableRepo = TimetableRepositoryImpl(db, fixedClock)
        val syncRepo = SyncRepositoryImpl(
            context, db, BackendSyncClient(OkHttpClient(), json), crypto, settingsRepo, timetableRepo, config, json, fixedClock
        )

        // 1. 深色模式、主色、节假日提前提醒天数与作息规则持久化
        settingsRepo.setThemeMode("DARK")
        settingsRepo.setSeedColor("emerald")
        settingsRepo.setHolidayReminderAdvanceDays(3)
        settingsRepo.saveBellRuleJson("sem-test", 0, "{\"firstClassStartTime\":\"08:30\"}")
        assertEquals("DARK", settingsRepo.observeThemeMode().first())
        assertEquals("emerald", settingsRepo.observeSeedColor().first())
        assertEquals(3, settingsRepo.observeHolidayReminderAdvanceDays().first())
        assertEquals("{\"firstClassStartTime\":\"08:30\"}", settingsRepo.getSavedBellRuleJson("sem-test", 0))

        // 2. 游客模式创建课程并手动导出版本化备份 JSON
        timetableRepo.ensureGuestInitialized()
        val sem = timetableRepo.getCurrentSemester()!!
        val course = Course(id = "c-backup-1", semesterId = sem.id, name = "离散数学", location = "理教108")
        val sch = CourseSchedule(id = "s-backup-1", courseId = course.id, dayOfWeek = 3, startPeriod = 1, endPeriod = 2, startWeek = 1, endWeek = 16)
        timetableRepo.saveCourseWithSchedules(course, listOf(sch))

        val exportedJson = syncRepo.exportLocalBackupJson()
        assertFalse(exportedJson.contains("eduPassword"))

        // 3. 校验预览版本信息
        val preview = syncRepo.parseBackupPreview(exportedJson).getOrThrow()
        assertEquals(2, preview.schemaVersion)
        assertEquals(1, preview.courses.size)
        assertEquals("离散数学", preview.courses.first().name)

        // 4. 清空课程后从备份恢复并验证数据完整还原
        timetableRepo.deleteCourse(course.id)
        assertTrue(timetableRepo.getCoursesWithDetails(sem.id).isEmpty())

        val importResult = syncRepo.importLocalBackupJson(exportedJson, mergeWithExisting = false).getOrThrow()
        assertTrue(importResult.addedCount >= 1)
        val restored = timetableRepo.getCoursesWithDetails(sem.id)
        assertEquals(1, restored.size)
        assertEquals("离散数学", restored.first().course.name)
    }

    @Test
    fun `backend sync client exposes 401 HttpStatusException and refreshes JWT cleanly`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val client = BackendSyncClient(OkHttpClient(), json)
            val baseUrl = server.url("/").toString()

            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"code\":\"UNAUTHORIZED\",\"message\":\"token expired\"}"))
            val pullErr = client.pullSync(baseUrl, "expired-token").exceptionOrNull()
            assertTrue(pullErr is HttpStatusException)
            assertEquals(401, (pullErr as HttpStatusException).statusCode)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"accessToken\":\"new-access-jwt\",\"refreshToken\":\"new-refresh-jwt\"}")
            )
            val refreshed = client.refreshToken(baseUrl, "old-refresh-jwt").getOrThrow()
            assertEquals("new-access-jwt", refreshed.accessToken)
            assertEquals("new-refresh-jwt", refreshed.refreshToken)
        } finally {
            server.shutdown()
        }
    }
}
