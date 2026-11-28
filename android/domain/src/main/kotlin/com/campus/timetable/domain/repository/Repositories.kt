package com.campus.timetable.domain.repository

import com.campus.timetable.domain.model.*
import kotlinx.coroutines.flow.Flow

interface TimetableRepository {
    suspend fun ensureGuestInitialized(): User
    fun observeCurrentUser(): Flow<User?>
    suspend fun getCurrentUser(): User?

    fun observeSemesters(): Flow<List<Semester>>
    fun observeCurrentSemester(): Flow<Semester?>
    suspend fun getCurrentSemester(): Semester?
    suspend fun saveSemester(semester: Semester, periods: List<ClassPeriod>? = null)
    suspend fun switchCurrentSemester(semesterId: String)
    suspend fun deleteSemester(semesterId: String)

    fun observeClassPeriods(semesterId: String): Flow<List<ClassPeriod>>
    suspend fun getClassPeriods(semesterId: String): List<ClassPeriod>
    suspend fun saveClassPeriods(semesterId: String, periods: List<ClassPeriod>)

    /** 作息模板管理 */
    fun observeBellTemplates(): Flow<List<BellScheduleTemplate>>
    suspend fun saveBellTemplate(template: BellScheduleTemplate)
    suspend fun deleteBellTemplate(templateId: String)

    /** 批量应用作息时间到指定星期列表（0=全周默认，1..7=对应星期），绝不破坏课程临时调课记录 */
    suspend fun applyClassPeriodsToDays(semesterId: String, targetDays: List<Int>, templatePeriods: List<ClassPeriod>)

    fun observeCoursesWithDetails(semesterId: String): Flow<List<CourseWithDetails>>
    suspend fun getCoursesWithDetails(semesterId: String): List<CourseWithDetails>
    suspend fun getCourseWithDetails(courseId: String): CourseWithDetails?
    suspend fun saveCourseWithSchedules(course: Course, schedules: List<CourseSchedule>)
    suspend fun deleteCourse(courseId: String)

    suspend fun saveCourseException(exception: CourseException)
    suspend fun deleteCourseException(exceptionId: String)
}

interface HolidayRepository {
    fun observeHolidaysByYear(year: Int): Flow<List<Holiday>>
    suspend fun getHolidaysByYear(year: Int): List<Holiday>
    suspend fun syncYearHolidays(year: Int, reminderAdvanceDays: Int = 1): HolidayYearStatus
    suspend fun updateHolidayReminderDays(year: Int, reminderAdvanceDays: Int)

    fun observeSchoolOverrides(semesterId: String): Flow<List<SchoolCalendarOverride>>
    suspend fun getSchoolOverrides(semesterId: String): List<SchoolCalendarOverride>
    suspend fun saveSchoolOverride(override: SchoolCalendarOverride)
    suspend fun deleteSchoolOverride(overrideId: String)
}

interface AlarmRepository {
    fun observeGlobalSetting(): Flow<AlarmSetting>
    suspend fun getGlobalSetting(): AlarmSetting
    fun observeCourseSetting(courseId: String): Flow<AlarmSetting?>
    suspend fun getCourseSetting(courseId: String): AlarmSetting?
    suspend fun getAllAlarmSettings(): List<AlarmSetting>
    suspend fun saveAlarmSetting(setting: AlarmSetting)
    suspend fun muteTodayAll(dateIso: String)
    suspend fun clearMuteToday()
}

interface WeatherRepository {
    fun observeWeatherSetting(): Flow<WeatherSetting>
    suspend fun getWeatherSetting(): WeatherSetting
    suspend fun saveWeatherSetting(setting: WeatherSetting)
    suspend fun fetchLiveWeather(setting: WeatherSetting): Result<WeatherSnapshot>
    suspend fun searchCities(query: String, provider: WeatherProviderType): Result<List<CityOption>>
    fun getCachedWeather(setting: WeatherSetting): WeatherSnapshot?
}

interface SchoolImportRepository {
    fun observeSchools(
        query: String = "",
        province: String? = null,
        level: SchoolLevel? = null
    ): Flow<List<School>>

    suspend fun getAvailableProvinces(): List<String>
    suspend fun getSchoolCatalogStats(): SchoolCatalogStats

    /** 该校教务处直达地址（仅收录经公开访问校验的真实页面），未收录时返回 null。 */
    suspend fun getAcademicAffairsUrl(schoolCode: String): String? = null

    suspend fun ensureDefaultSchoolCatalog()
    suspend fun refreshSchoolCatalogFromRemote(serverBaseUrl: String? = null): Result<SchoolCatalogStats>

    suspend fun submitAdaptationRequest(request: SchoolAdaptationRequest, serverBaseUrl: String? = null): Result<SchoolAdaptationRequest>
    fun observeAdaptationRequests(): Flow<List<SchoolAdaptationRequest>>

    suspend fun recordImportHistory(history: ImportHistory)
    fun observeImportHistories(semesterId: String): Flow<List<ImportHistory>>
    suspend fun batchImportCourses(
        semesterId: String,
        candidates: List<ParsedCourseCandidate>,
        sourceType: ImportSourceType,
        sourceName: String,
        schoolCode: String? = null,
        replaceExisting: Boolean = false
    ): ImportHistory
}

interface SyncRepository {
    fun observeSyncRecords(): Flow<List<SyncRecord>>
    suspend fun exportLocalBackupJson(): String
    suspend fun parseBackupPreview(json: String): Result<BackupBundle>
    suspend fun importLocalBackupJson(json: String, mergeWithExisting: Boolean): Result<BackupMergeResult>

    suspend fun sendSmsCode(phone: String, serverBaseUrl: String? = null): Result<String>
    suspend fun loginWithPhoneCode(phone: String, code: String, serverBaseUrl: String? = null): Result<User>
    suspend fun logoutToGuest(): Result<User>
    suspend fun deleteCloudAccountAndData(serverBaseUrl: String? = null): Result<Unit>
    suspend fun syncWithCloud(mergeGuestData: Boolean = false, serverBaseUrl: String? = null): Result<SyncRecord>
    suspend fun getAuthSessionSummary(): AuthSessionSummary
}

data class AuthSessionSummary(
    val isLoggedIn: Boolean,
    val phone: String?,
    val nickname: String,
    val serverBaseUrl: String,
    val lastSyncedAt: Long?,
    val syncVersion: Long
)

interface SettingsRepository {
    fun observeThemeMode(): Flow<String>
    fun observeSeedColor(): Flow<String>
    fun observeCustomServerUrl(): Flow<String>
    fun observeHolidayReminderAdvanceDays(): Flow<Int> = kotlinx.coroutines.flow.flowOf(1)
    suspend fun setThemeMode(mode: String)
    suspend fun setSeedColor(colorKey: String)
    suspend fun setCustomServerUrl(url: String)
    suspend fun setHolidayReminderAdvanceDays(days: Int) = Unit
    suspend fun getSavedBellRuleJson(semesterId: String, dayOfWeek: Int): String? = null
    suspend fun saveBellRuleJson(semesterId: String, dayOfWeek: Int, ruleJson: String) = Unit
    suspend fun clearSensitiveLocalSessions()
}

/** 跨模块解耦端口：当课表、作息、闹钟或校历发生变更时触发闹钟重调度 */
interface AlarmSchedulerPort {
    suspend fun rescheduleAllUpcomingAlarms(reason: String = "data_changed")
    suspend fun cancelAllAlarms()
    fun canScheduleExactAlarms(): Boolean
}

/** 跨模块解耦端口：当课表、作息或天气发生变更时刷新全部 4 种桌面小组件 */
interface WidgetRefresherPort {
    suspend fun refreshAllWidgets()
}

/** 跨模块解耦端口：当课表或天气设置变更时重新安排体育课前一天与当天天气检查 */
interface WeatherSchedulerPort {
    suspend fun schedulePeWeatherChecks()
    suspend fun runImmediateWeatherCheck(): PeWeatherAdvice?
}
