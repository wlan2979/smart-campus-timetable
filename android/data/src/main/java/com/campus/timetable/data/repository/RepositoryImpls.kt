package com.campus.timetable.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.core.config.AppRuntimeConfig
import com.campus.timetable.core.security.KeystoreCryptoManager
import com.campus.timetable.core.theme.CourseColorPalette
import com.campus.timetable.data.local.CampusDatabase
import com.campus.timetable.data.local.mapper.toDomain
import com.campus.timetable.data.local.mapper.toEntity
import com.campus.timetable.data.remote.holiday.HolidayRemoteDataSource
import com.campus.timetable.data.remote.sync.BackendSyncClient
import com.campus.timetable.data.remote.sync.HttpStatusException
import com.campus.timetable.data.remote.sync.SyncPushRequestDto
import com.campus.timetable.data.remote.weather.OpenMeteoWeatherProvider
import com.campus.timetable.data.remote.weather.QWeatherProvider
import com.campus.timetable.domain.engine.*
import com.campus.timetable.domain.model.*
import com.campus.timetable.domain.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "campus_app_settings")

@Singleton
class TimetableRepositoryImpl @Inject constructor(
    private val db: CampusDatabase,
    private val clock: AppClock
) : TimetableRepository {

    override suspend fun ensureGuestInitialized(): User {
        val existingUser = db.userDao().getCurrentUser()?.toDomain()
        val user = existingUser ?: User(
            id = "guest-${UUID.randomUUID()}",
            phone = null,
            nickname = "游客同学",
            isGuest = true,
            createdAt = clock.currentTimeMillis(),
            updatedAt = clock.currentTimeMillis()
        ).also { db.userDao().upsert(it.toEntity()) }

        val currentSem = db.semesterDao().getCurrentSemester()
        if (currentSem == null) {
            val today = clock.today()
            val academicYear = if (today.monthValue >= 8) {
                "${today.year}-${today.year + 1} 第一学期"
            } else {
                "${today.year - 1}-${today.year} 第二学期"
            }
            val startMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val sem = Semester(
                userId = user.id,
                name = academicYear,
                startDate = startMonday.toString(),
                totalWeeks = 20,
                periodsPerDay = 12,
                isCurrent = true,
                createdAt = clock.currentTimeMillis(),
                updatedAt = clock.currentTimeMillis()
            )
            db.semesterDao().upsert(sem.toEntity())
            val defaultPeriods = DefaultClassPeriodsFactory.createForSemester(sem.id, sem.periodsPerDay)
            db.classPeriodDao().upsertAll(defaultPeriods.map { it.toEntity() })
        }

        val existingTemplates = db.bellScheduleTemplateDao().getAll()
        val existingIds = existingTemplates.map { it.id }.toSet()
        val missingPresets = BellScheduleRuleEngine.defaultTemplates()
            .filter { it.id !in existingIds }
            .map { it.toEntity() }
        if (missingPresets.isNotEmpty()) {
            db.bellScheduleTemplateDao().upsertAll(missingPresets)
        }

        if (db.alarmSettingDao().getGlobal() == null) {
            db.alarmSettingDao().upsert(
                AlarmSetting(
                    id = "global_alarm",
                    scopeType = AlarmScopeType.GLOBAL,
                    updatedAt = clock.currentTimeMillis()
                ).toEntity()
            )
        }

        if (db.weatherSettingDao().getDefault() == null) {
            db.weatherSettingDao().upsert(
                WeatherSetting(updatedAt = clock.currentTimeMillis()).toEntity()
            )
        }

        return user
    }

    override fun observeCurrentUser(): Flow<User?> =
        db.userDao().observeCurrentUser().map { it?.toDomain() }

    override suspend fun getCurrentUser(): User? =
        db.userDao().getCurrentUser()?.toDomain()

    override fun observeSemesters(): Flow<List<Semester>> =
        db.semesterDao().observeActiveSemesters().map { list -> list.map { it.toDomain() } }

    override fun observeCurrentSemester(): Flow<Semester?> =
        db.semesterDao().observeCurrentSemester().map { it?.toDomain() }

    override suspend fun getCurrentSemester(): Semester? =
        db.semesterDao().getCurrentSemester()?.toDomain()

    override suspend fun saveSemester(semester: Semester, periods: List<ClassPeriod>?) {
        val now = clock.currentTimeMillis()
        if (semester.isCurrent) {
            db.semesterDao().clearCurrentFlag(now)
        }
        db.semesterDao().upsert(semester.copy(updatedAt = now).toEntity())
        val existingPeriods = db.classPeriodDao().getBySemester(semester.id)
        if (periods != null) {
            db.classPeriodDao().deleteBySemester(semester.id)
            db.classPeriodDao().upsertAll(periods.map { it.copy(semesterId = semester.id, updatedAt = now).toEntity() })
        } else if (existingPeriods.isEmpty()) {
            val defaults = DefaultClassPeriodsFactory.createForSemester(semester.id, semester.periodsPerDay)
            db.classPeriodDao().upsertAll(defaults.map { it.toEntity() })
        }
    }

    override suspend fun switchCurrentSemester(semesterId: String) {
        val now = clock.currentTimeMillis()
        db.semesterDao().clearCurrentFlag(now)
        db.semesterDao().markAsCurrent(semesterId, now)
    }

    override suspend fun deleteSemester(semesterId: String) {
        val now = clock.currentTimeMillis()
        db.semesterDao().softDelete(semesterId, now)
        if (db.semesterDao().getCurrentSemester() == null) {
            val remaining = db.semesterDao().observeActiveSemesters().first()
            remaining.firstOrNull()?.let { db.semesterDao().markAsCurrent(it.id, now) }
        }
    }

    override fun observeClassPeriods(semesterId: String): Flow<List<ClassPeriod>> =
        db.classPeriodDao().observeBySemester(semesterId).map { list -> list.map { it.toDomain() } }

    override suspend fun getClassPeriods(semesterId: String): List<ClassPeriod> =
        db.classPeriodDao().getBySemester(semesterId).map { it.toDomain() }

    override suspend fun saveClassPeriods(semesterId: String, periods: List<ClassPeriod>) {
        val now = clock.currentTimeMillis()
        db.classPeriodDao().deleteBySemester(semesterId)
        db.classPeriodDao().upsertAll(periods.map { it.copy(semesterId = semesterId, updatedAt = now).toEntity() })
    }

    override fun observeBellTemplates(): Flow<List<BellScheduleTemplate>> =
        db.bellScheduleTemplateDao().observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun saveBellTemplate(template: BellScheduleTemplate) {
        db.bellScheduleTemplateDao().upsert(template.copy(updatedAt = clock.currentTimeMillis()).toEntity())
    }

    override suspend fun deleteBellTemplate(templateId: String) {
        db.bellScheduleTemplateDao().deleteById(templateId)
    }

    override suspend fun applyClassPeriodsToDays(
        semesterId: String,
        targetDays: List<Int>,
        templatePeriods: List<ClassPeriod>
    ) {
        val now = clock.currentTimeMillis()
        val distinctDays = targetDays.distinct()
        for (dow in distinctDays) {
            db.classPeriodDao().deleteBySemesterAndDay(semesterId, dow)
            if (templatePeriods.isNotEmpty()) {
                val mapped = templatePeriods.map { p ->
                    p.copy(
                        id = "${semesterId}_d${dow}_p${p.periodNumber}",
                        semesterId = semesterId,
                        dayOfWeek = dow,
                        updatedAt = now
                    ).toEntity()
                }
                db.classPeriodDao().upsertAll(mapped)
            }
        }
        // 如果批量应用到全周通用（dayOfWeek=0）且最大节数变化，同步更新当前学期 periodsPerDay，绝不触碰临时调课表
        if (0 in distinctDays && templatePeriods.isNotEmpty()) {
            val current = db.semesterDao().getCurrentSemester()
            if (current != null && current.id == semesterId) {
                val newCount = templatePeriods.maxOf { it.periodNumber }.coerceIn(4, 20)
                if (newCount != current.periodsPerDay) {
                    db.semesterDao().upsert(current.copy(periodsPerDay = newCount, updatedAt = now))
                }
            }
        }
    }

    override fun observeCoursesWithDetails(semesterId: String): Flow<List<CourseWithDetails>> {
        return combine(
            db.courseDao().observeCourses(semesterId),
            db.courseDao().observeSchedulesBySemester(semesterId),
            db.courseDao().observeExceptionsBySemester(semesterId)
        ) { courses, schedules, exceptions ->
            val schMap = schedules.groupBy { it.courseId }
            val exMap = exceptions.groupBy { it.courseId }
            courses.map { c ->
                CourseWithDetails(
                    course = c.toDomain(),
                    schedules = schMap[c.id].orEmpty().map { it.toDomain() },
                    exceptions = exMap[c.id].orEmpty().map { it.toDomain() }
                )
            }
        }
    }

    override suspend fun getCoursesWithDetails(semesterId: String): List<CourseWithDetails> {
        val courses = db.courseDao().getCourses(semesterId)
        return courses.map { c ->
            CourseWithDetails(
                course = c.toDomain(),
                schedules = db.courseDao().getSchedulesByCourse(c.id).map { it.toDomain() },
                exceptions = db.courseDao().getExceptionsByCourse(c.id).map { it.toDomain() },
                alarmSetting = db.alarmSettingDao().getForCourse(c.id)?.toDomain()
            )
        }
    }

    override suspend fun getCourseWithDetails(courseId: String): CourseWithDetails? {
        val c = db.courseDao().getCourseById(courseId) ?: return null
        return CourseWithDetails(
            course = c.toDomain(),
            schedules = db.courseDao().getSchedulesByCourse(courseId).map { it.toDomain() },
            exceptions = db.courseDao().getExceptionsByCourse(courseId).map { it.toDomain() },
            alarmSetting = db.alarmSettingDao().getForCourse(courseId)?.toDomain()
        )
    }

    override suspend fun saveCourseWithSchedules(course: Course, schedules: List<CourseSchedule>) {
        val now = clock.currentTimeMillis()
        db.courseDao().replaceCourseAndSchedules(
            course = course.copy(updatedAt = now).toEntity(),
            schedules = schedules.map { it.copy(courseId = course.id, updatedAt = now).toEntity() },
            now = now
        )
    }

    override suspend fun deleteCourse(courseId: String) {
        val now = clock.currentTimeMillis()
        db.courseDao().softDeleteSchedulesByCourse(courseId, now)
        db.courseDao().softDeleteCourse(courseId, now)
    }

    override suspend fun saveCourseException(exception: CourseException) {
        val now = clock.currentTimeMillis()
        db.courseDao().upsertException(exception.copy(updatedAt = now).toEntity())
    }

    override suspend fun deleteCourseException(exceptionId: String) {
        db.courseDao().softDeleteException(exceptionId, clock.currentTimeMillis())
    }
}

@Singleton
class HolidayRepositoryImpl @Inject constructor(
    private val db: CampusDatabase,
    private val remoteDataSource: HolidayRemoteDataSource,
    private val clock: AppClock
) : HolidayRepository {

    override fun observeHolidaysByYear(year: Int): Flow<List<Holiday>> =
        db.holidayDao().observeByYear(year).map { list ->
            val domainList = list.map { it.toDomain() }
            // 若命中旧版逐日缓存数据，实时在内存合并为完整连续区间
            if (domainList.size > 1 && domainList.all { it.totalDays == 1 }) {
                HolidayMergeEngine.mergeExistingHolidays(domainList)
            } else domainList
        }

    override suspend fun getHolidaysByYear(year: Int): List<Holiday> {
        val list = db.holidayDao().getByYear(year).map { it.toDomain() }
        return if (list.size > 1 && list.all { it.totalDays == 1 }) {
            HolidayMergeEngine.mergeExistingHolidays(list)
        } else list
    }

    override suspend fun syncYearHolidays(year: Int, reminderAdvanceDays: Int): HolidayYearStatus {
        val remoteStatus = remoteDataSource.fetchYearHolidays(year, reminderAdvanceDays)
        if (remoteStatus.isPublished && remoteStatus.holidays.isNotEmpty()) {
            db.holidayDao().deleteByYear(year)
            db.holidayDao().upsertAll(remoteStatus.holidays.map { it.toEntity() })
            return remoteStatus
        }
        val cached = getHolidaysByYear(year)
        return if (cached.isNotEmpty()) {
            remoteStatus.copy(
                isPublished = true,
                holidays = cached,
                source = cached.first().source,
                version = cached.first().version,
                updatedAt = cached.first().updatedAt,
                notice = "${remoteStatus.notice}（当前展示本地缓存，版本 ${cached.first().version}）"
            )
        } else {
            remoteStatus
        }
    }

    override suspend fun updateHolidayReminderDays(year: Int, reminderAdvanceDays: Int) {
        val existing = getHolidaysByYear(year)
        if (existing.isEmpty()) return
        val updated = existing.map { h ->
            val reminder = if (h.isOffDay && reminderAdvanceDays > 0) {
                h.parsedStartDate().minusDays(reminderAdvanceDays.toLong()).toString()
            } else null
            h.copy(reminderDate = reminder, updatedAt = clock.currentTimeMillis())
        }
        db.holidayDao().deleteByYear(year)
        db.holidayDao().upsertAll(updated.map { it.toEntity() })
    }

    override fun observeSchoolOverrides(semesterId: String): Flow<List<SchoolCalendarOverride>> =
        db.holidayDao().observeOverrides(semesterId).map { list -> list.map { it.toDomain() } }

    override suspend fun getSchoolOverrides(semesterId: String): List<SchoolCalendarOverride> =
        db.holidayDao().getOverrides(semesterId).map { it.toDomain() }

    override suspend fun saveSchoolOverride(override: SchoolCalendarOverride) {
        db.holidayDao().upsertOverride(override.copy(updatedAt = clock.currentTimeMillis()).toEntity())
    }

    override suspend fun deleteSchoolOverride(overrideId: String) {
        db.holidayDao().softDeleteOverride(overrideId, clock.currentTimeMillis())
    }
}

@Singleton
class AlarmRepositoryImpl @Inject constructor(
    private val db: CampusDatabase,
    private val clock: AppClock
) : AlarmRepository {

    override fun observeGlobalSetting(): Flow<AlarmSetting> =
        db.alarmSettingDao().observeGlobal().map { it?.toDomain() ?: AlarmSetting(id = "global_alarm") }

    override suspend fun getGlobalSetting(): AlarmSetting =
        db.alarmSettingDao().getGlobal()?.toDomain() ?: AlarmSetting(id = "global_alarm")

    override fun observeCourseSetting(courseId: String): Flow<AlarmSetting?> =
        db.alarmSettingDao().observeForCourse(courseId).map { it?.toDomain() }

    override suspend fun getCourseSetting(courseId: String): AlarmSetting? =
        db.alarmSettingDao().getForCourse(courseId)?.toDomain()

    override suspend fun getAllAlarmSettings(): List<AlarmSetting> =
        db.alarmSettingDao().getAll().map { it.toDomain() }

    override suspend fun saveAlarmSetting(setting: AlarmSetting) {
        db.alarmSettingDao().upsert(setting.copy(updatedAt = clock.currentTimeMillis()).toEntity())
    }

    override suspend fun muteTodayAll(dateIso: String) {
        val current = getGlobalSetting()
        saveAlarmSetting(current.copy(muteDate = dateIso))
    }

    override suspend fun clearMuteToday() {
        val current = getGlobalSetting()
        saveAlarmSetting(current.copy(muteDate = null))
    }
}

@Singleton
class WeatherRepositoryImpl @Inject constructor(
    private val db: CampusDatabase,
    private val openMeteoProvider: OpenMeteoWeatherProvider,
    private val qWeatherProvider: QWeatherProvider,
    private val json: Json,
    private val clock: AppClock
) : WeatherRepository {

    private fun providerFor(type: WeatherProviderType) = when (type) {
        WeatherProviderType.OPEN_METEO -> openMeteoProvider
        WeatherProviderType.QWEATHER -> qWeatherProvider
    }

    override fun observeWeatherSetting(): Flow<WeatherSetting> =
        db.weatherSettingDao().observeDefault().map { it?.toDomain() ?: WeatherSetting() }

    override suspend fun getWeatherSetting(): WeatherSetting =
        db.weatherSettingDao().getDefault()?.toDomain() ?: WeatherSetting()

    override suspend fun saveWeatherSetting(setting: WeatherSetting) {
        db.weatherSettingDao().upsert(setting.copy(updatedAt = clock.currentTimeMillis()).toEntity())
    }

    override suspend fun fetchLiveWeather(setting: WeatherSetting): Result<WeatherSnapshot> {
        val result = providerFor(setting.provider).fetchWeather(setting)
        result.onSuccess { snapshot ->
            val snapshotJson = json.encodeToString(snapshot)
            saveWeatherSetting(
                setting.copy(
                    lastWeatherJson = snapshotJson,
                    lastUpdatedAt = clock.currentTimeMillis()
                )
            )
        }
        return result
    }

    override suspend fun searchCities(query: String, provider: WeatherProviderType): Result<List<CityOption>> {
        return providerFor(provider).searchCities(query)
    }

    override fun getCachedWeather(setting: WeatherSetting): WeatherSnapshot? {
        val raw = setting.lastWeatherJson ?: return null
        return runCatching { json.decodeFromString<WeatherSnapshot>(raw) }.getOrNull()
    }
}

@Serializable
private data class SchoolCatalogFileDto(
    val catalogVersion: String = "moe-mainland-2026.2",
    val source: String = "中华人民共和国教育部《全国普通高等学校名单》",
    val updatedAt: Long = 0L,
    val totalCount: Int = 0,
    val schools: List<School> = emptyList()
)

@Singleton
class SchoolImportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CampusDatabase,
    private val timetableRepository: TimetableRepository,
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val config: AppRuntimeConfig,
    private val json: Json,
    private val clock: AppClock
) : SchoolImportRepository {

    private var cachedCatalogVersion: String = "moe-mainland-2026.4"
    private var cachedCatalogSource: String = "中华人民共和国教育部《全国普通高等学校名单》+ 桃课配置层"

    override fun observeSchools(
        query: String,
        province: String?,
        level: SchoolLevel?
    ): Flow<List<School>> {
        val cleanQuery = query.trim().lowercase()
        val cleanProvince = province?.takeIf { it.isNotBlank() && it != "全部省份" }
        val zhCollator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        return db.schoolDao().searchFiltered(cleanQuery, cleanProvince, level?.name)
            .map { list ->
                list.map { it.toDomain() }
                    .sortedWith { a, b ->
                        val keyA = a.pinyin.trim().lowercase().ifBlank { a.name }
                        val keyB = b.pinyin.trim().lowercase().ifBlank { b.name }
                        val cmp = keyA.compareTo(keyB)
                        if (cmp != 0) cmp else zhCollator.compare(a.name, b.name)
                    }
            }
    }

    override suspend fun getAvailableProvinces(): List<String> {
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        return db.schoolDao().getAllProvinces().sortedWith(collator)
    }

    @Volatile
    private var academicUrlCache: Map<String, String>? = null

    private fun academicUrls(): Map<String, String> {
        academicUrlCache?.let { return it }
        val loaded = runCatching {
            context.assets.open("school_academic_urls.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                .let { json.decodeFromString<Map<String, String>>(it) }
        }.getOrDefault(emptyMap())
        academicUrlCache = loaded
        return loaded
    }

    override suspend fun getAcademicAffairsUrl(schoolCode: String): String? =
        academicUrls()[schoolCode]?.takeIf { it.isNotBlank() }

    override suspend fun getSchoolCatalogStats(): SchoolCatalogStats = withContext(Dispatchers.IO) {
        val all = db.schoolDao().getAllSnapshot().map { it.toDomain() }
        val bySystem = all.groupingBy { it.systemType }.eachCount()
        SchoolCatalogStats(
            totalCatalogCount = all.size,
            undergradCount = all.count { it.schoolLevel == SchoolLevel.UNDERGRADUATE },
            vocationalCount = all.count { it.schoolLevel == SchoolLevel.VOCATIONAL },
            configuredPortalCount = all.count { it.portalUrl.isNotBlank() || academicUrls().containsKey(it.code) },
            genericAdapterCount = all.count { it.supportStatus == SchoolSupportStatus.GENERIC_ADAPTER },
            assistedLoginCount = all.count { it.supportStatus == SchoolSupportStatus.ASSISTED_LOGIN },
            verifiedCount = all.count { it.verified && it.supportStatus == SchoolSupportStatus.VERIFIED },
            countsBySystem = bySystem,
            catalogVersion = cachedCatalogVersion,
            catalogSource = cachedCatalogSource
        )
    }

    override suspend fun ensureDefaultSchoolCatalog() = withContext(Dispatchers.IO) {
        val rawText = context.assets.open("schools_catalog_v2.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val dto = json.decodeFromString<SchoolCatalogFileDto>(rawText)
        cachedCatalogVersion = dto.catalogVersion
        cachedCatalogSource = dto.source
        // 覆盖安装时也要同步内置目录的版本与教务入口修正；否则旧库会一直停在升级前的首页地址。
        val current = db.schoolDao().getAllSnapshot()
        val bundledById = dto.schools.associateBy { it.id }
        val catalogChanged = current.isEmpty() ||
            current.size != dto.schools.size ||
            current.any { entity ->
                val bundled = bundledById[entity.id]
                bundled == null ||
                    entity.portalUrl != bundled.portalUrl ||
                    entity.systemType != bundled.systemType.name ||
                    entity.adapterId != bundled.adapterId ||
                    entity.supportStatus != bundled.supportStatus.name ||
                    entity.verified != bundled.verified
            }
        if (!catalogChanged) return@withContext
        val now = clock.currentTimeMillis()
        val entities = dto.schools.map { it.copy(updatedAt = now).toEntity() }
        db.schoolDao().replaceCatalog(entities)
    }

    override suspend fun refreshSchoolCatalogFromRemote(serverBaseUrl: String?): Result<SchoolCatalogStats> =
        withContext(Dispatchers.IO) {
            runCatching {
                val targetUrl = config.schoolCatalogRemoteUrl.takeIf { it.isNotBlank() }
                    ?: run {
                        val base = (serverBaseUrl?.takeIf { it.isNotBlank() }
                            ?: settingsRepository.observeCustomServerUrl().first()).trimEnd('/')
                        "$base/api/v1/schools/catalog"
                    }
                val req = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "TaokeTimetable/2.0")
                    .get()
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("远程高校目录服务返回 HTTP ${resp.code}（如未部署自建后端，本地已缓存 ${db.schoolDao().count()} 所教育部高校目录可离线检索）")
                    }
                    val body = resp.body?.string().orEmpty()
                    val dto = json.decodeFromString<SchoolCatalogFileDto>(body)
                    if (dto.schools.isEmpty()) {
                        throw IllegalStateException("远程返回的高校目录为空")
                    }
                    cachedCatalogVersion = dto.catalogVersion
                    cachedCatalogSource = dto.source
                    val now = clock.currentTimeMillis()
                    db.schoolDao().replaceCatalog(dto.schools.map { it.copy(updatedAt = now).toEntity() })
                    getSchoolCatalogStats()
                }
            }
        }

    override suspend fun submitAdaptationRequest(
        request: SchoolAdaptationRequest,
        serverBaseUrl: String?
    ): Result<SchoolAdaptationRequest> = withContext(Dispatchers.IO) {
        runCatching {
            require(request.schoolName.isNotBlank()) { "请填写学校全称" }
            require(request.userAuthorized) { "必须经您明确授权后才能提交学校适配申请" }
            // 安全隐私校验：绝不允许提交任何疑似账号密码信息
            val combined = "${request.portalUrl} ${request.pageSummary}".lowercase()
            require(!combined.contains("password=") && !combined.contains("passwd=")) {
                "安全策略禁止在适配申请中附带密码参数，请仅填写公开教务入口或页面结构说明"
            }

            var finalStatus = "LOCAL_SAVED"
            val base = (serverBaseUrl?.takeIf { it.isNotBlank() }
                ?: settingsRepository.observeCustomServerUrl().first()).trimEnd('/')
            runCatching {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val reqBody = json.encodeToString(request).toRequestBody(mediaType)
                val httpReq = Request.Builder()
                    .url("$base/api/v1/schools/adapt-requests")
                    .post(reqBody)
                    .build()
                okHttpClient.newCall(httpReq).execute().use { resp ->
                    if (resp.isSuccessful) finalStatus = "SUBMITTED_TO_SERVER"
                }
            }
            val saved = request.copy(status = finalStatus, createdAt = clock.currentTimeMillis())
            db.schoolAdaptationRequestDao().upsert(saved.toEntity())
            saved
        }
    }

    override fun observeAdaptationRequests(): Flow<List<SchoolAdaptationRequest>> =
        db.schoolAdaptationRequestDao().observeRecent().map { list -> list.map { it.toDomain() } }

    override suspend fun recordImportHistory(history: ImportHistory) {
        db.importHistoryDao().insert(history.toEntity())
    }

    override fun observeImportHistories(semesterId: String): Flow<List<ImportHistory>> =
        db.importHistoryDao().observeBySemester(semesterId).map { list -> list.map { it.toDomain() } }

    override suspend fun batchImportCourses(
        semesterId: String,
        candidates: List<ParsedCourseCandidate>,
        sourceType: ImportSourceType,
        sourceName: String,
        schoolCode: String?,
        replaceExisting: Boolean
    ): ImportHistory {
        val now = clock.currentTimeMillis()
        val semester = db.semesterDao().getCurrentSemester()?.toDomain()
        val periods = timetableRepository.getClassPeriods(semesterId)

        val grouped = candidates.filter { it.name.isNotBlank() }.groupBy { it.name.trim() }
        var importedCount = 0

        db.withTransaction {
            if (replaceExisting && grouped.isNotEmpty()) {
                db.courseDao().softDeleteExceptionsBySemester(semesterId, now)
                db.courseDao().softDeleteSchedulesBySemester(semesterId, now)
                db.courseDao().softDeleteCoursesBySemester(semesterId, now)
            }

            for ((name, items) in grouped) {
                val first = items.first()
                val isPe = first.courseType == CourseType.PHYSICAL_EDUCATION || PeCourseDetector.isLikelyPeCourse(name)
                val course = Course(
                    semesterId = semesterId,
                    name = name,
                    teacher = first.teacher,
                    location = first.location,
                    courseType = if (isPe) CourseType.PHYSICAL_EDUCATION else CourseType.NORMAL,
                    colorHex = CourseColorPalette.pickForCourseName(name),
                    isAutoDetectedPe = PeCourseDetector.isLikelyPeCourse(name),
                    createdAt = now,
                    updatedAt = now
                )
                val schedules = items.map { c ->
                    CourseSchedule(
                        courseId = course.id,
                        dayOfWeek = c.dayOfWeek.coerceIn(1, 7),
                        startPeriod = c.startPeriod.coerceAtLeast(1),
                        endPeriod = c.endPeriod.coerceAtLeast(c.startPeriod),
                        weekRuleType = c.weekRuleType,
                        startWeek = c.startWeek.coerceAtLeast(1),
                        endWeek = c.endWeek.coerceAtLeast(c.startWeek),
                        customWeeks = c.customWeeks,
                        teacherOverride = c.teacher.takeIf { it.isNotBlank() && it != course.teacher },
                        locationOverride = c.location.takeIf { it.isNotBlank() && it != course.location },
                        updatedAt = now
                    )
                }
                timetableRepository.saveCourseWithSchedules(course, schedules)
                importedCount += items.size
            }
        }

        val conflictCount = if (semester != null) {
            val allCourses = timetableRepository.getCoursesWithDetails(semesterId)
            ConflictDetectionEngine.detectConflictsInSemester(semester, allCourses, periods).size
        } else 0

        val history = ImportHistory(
            semesterId = semesterId,
            sourceType = sourceType,
            sourceName = sourceName,
            schoolCode = schoolCode,
            totalParsed = candidates.size,
            importedCount = importedCount,
            conflictCount = conflictCount,
            status = when {
                importedCount == 0 -> ImportStatus.FAILED
                importedCount < candidates.size -> ImportStatus.PARTIAL
                else -> ImportStatus.SUCCESS
            },
            createdAt = now
        )
        recordImportHistory(history)
        return history
    }
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: KeystoreCryptoManager,
    private val config: AppRuntimeConfig
) : SettingsRepository {
    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_SEED_COLOR = stringPreferencesKey("seed_color")
        private val KEY_SERVER_URL = stringPreferencesKey("custom_server_url")
        private val KEY_HOLIDAY_REMINDER_DAYS = intPreferencesKey("holiday_reminder_advance_days")
        private fun bellRuleKey(semesterId: String, dayOfWeek: Int) =
            stringPreferencesKey("bell_rule_${semesterId}_d${dayOfWeek}")
    }

    override fun observeThemeMode(): Flow<String> =
        context.settingsDataStore.data.map { it[KEY_THEME_MODE] ?: "SYSTEM" }

    override fun observeSeedColor(): Flow<String> =
        context.settingsDataStore.data.map { it[KEY_SEED_COLOR] ?: "ocean_blue" }

    override fun observeCustomServerUrl(): Flow<String> =
        context.settingsDataStore.data.map { it[KEY_SERVER_URL]?.takeIf { u -> u.isNotBlank() } ?: config.apiBaseUrl }

    override fun observeHolidayReminderAdvanceDays(): Flow<Int> =
        context.settingsDataStore.data.map { (it[KEY_HOLIDAY_REMINDER_DAYS] ?: 1).coerceIn(0, 7) }

    override suspend fun setThemeMode(mode: String) {
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    override suspend fun setSeedColor(colorKey: String) {
        context.settingsDataStore.edit { it[KEY_SEED_COLOR] = colorKey }
    }

    override suspend fun setCustomServerUrl(url: String) {
        context.settingsDataStore.edit { it[KEY_SERVER_URL] = url.trim() }
    }

    override suspend fun setHolidayReminderAdvanceDays(days: Int) {
        context.settingsDataStore.edit { it[KEY_HOLIDAY_REMINDER_DAYS] = days.coerceIn(0, 7) }
    }

    override suspend fun getSavedBellRuleJson(semesterId: String, dayOfWeek: Int): String? {
        if (semesterId.isBlank()) return null
        return context.settingsDataStore.data.first()[bellRuleKey(semesterId, dayOfWeek)]
    }

    override suspend fun saveBellRuleJson(semesterId: String, dayOfWeek: Int, ruleJson: String) {
        if (semesterId.isBlank()) return
        context.settingsDataStore.edit { it[bellRuleKey(semesterId, dayOfWeek)] = ruleJson }
    }

    override suspend fun clearSensitiveLocalSessions() {
        cryptoManager.clearAll()
    }
}

@Singleton
class SyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CampusDatabase,
    private val backendClient: BackendSyncClient,
    private val cryptoManager: KeystoreCryptoManager,
    private val settingsRepository: SettingsRepository,
    private val timetableRepository: TimetableRepository,
    private val config: AppRuntimeConfig,
    private val json: Json,
    private val clock: AppClock
) : SyncRepository {

    companion object {
        private const val SECURE_KEY_ACCESS_TOKEN = "sync_access_token"
        private const val SECURE_KEY_REFRESH_TOKEN = "sync_refresh_token"
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_LAST_SYNCED_AT = longPreferencesKey("last_synced_at")
    }

    private suspend fun getOrCreateDeviceId(): String {
        val existing = context.settingsDataStore.data.first()[KEY_DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val created = "android-${UUID.randomUUID()}"
        context.settingsDataStore.edit { it[KEY_DEVICE_ID] = created }
        return created
    }

    private suspend fun resolveServerUrl(override: String?): String {
        return override?.takeIf { it.isNotBlank() }
            ?: settingsRepository.observeCustomServerUrl().first()
    }

    override fun observeSyncRecords(): Flow<List<SyncRecord>> =
        db.syncRecordDao().observeRecent().map { list -> list.map { it.toDomain() } }

    suspend fun buildLocalBackupBundle(): BackupBundle {
        return BackupBundle(
            schemaVersion = 2,
            appVersion = config.appVersion,
            exportedAt = clock.currentTimeMillis(),
            semesters = db.semesterDao().getAllRaw().map { it.toDomain() },
            classPeriods = db.classPeriodDao().getAllRaw().map { it.toDomain() },
            bellTemplates = db.bellScheduleTemplateDao().getAll().map { it.toDomain() },
            courses = db.courseDao().getAllCoursesRaw().map { it.toDomain() },
            schedules = db.courseDao().getAllSchedulesRaw().map { it.toDomain() },
            exceptions = db.courseDao().getAllExceptionsRaw().map { it.toDomain() },
            schoolOverrides = db.holidayDao().getAllOverridesRaw().map { it.toDomain() },
            alarmSettings = db.alarmSettingDao().getAll().map { it.toDomain() },
            weatherSetting = db.weatherSettingDao().getDefault()?.toDomain()
        )
    }

    private suspend fun applyBundleToLocalDb(bundle: BackupBundle, targetUserId: String) {
        val semesters = bundle.semesters.map { it.copy(userId = targetUserId) }
        val templatesToSave = bundle.bellTemplates.ifEmpty { BellScheduleRuleEngine.defaultTemplates() }
        // 使用 Room 单事务原子替换，防止中途异常导致本地课表全空或残留脏记录
        db.withTransaction {
            db.semesterDao().clearAll()
            db.alarmSettingDao().clearAll()
            db.bellScheduleTemplateDao().clearAll()
            if (semesters.isNotEmpty()) db.semesterDao().upsertAll(semesters.map { it.toEntity() })
            if (bundle.classPeriods.isNotEmpty()) db.classPeriodDao().upsertAll(bundle.classPeriods.map { it.toEntity() })
            db.bellScheduleTemplateDao().upsertAll(templatesToSave.map { it.toEntity() })
            if (bundle.courses.isNotEmpty()) db.courseDao().upsertCourses(bundle.courses.map { it.toEntity() })
            if (bundle.schedules.isNotEmpty()) db.courseDao().upsertSchedules(bundle.schedules.map { it.toEntity() })
            if (bundle.exceptions.isNotEmpty()) db.courseDao().upsertExceptions(bundle.exceptions.map { it.toEntity() })
            if (bundle.schoolOverrides.isNotEmpty()) db.holidayDao().upsertOverrides(bundle.schoolOverrides.map { it.toEntity() })
            if (bundle.alarmSettings.isNotEmpty()) {
                db.alarmSettingDao().upsertAll(bundle.alarmSettings.map { it.toEntity() })
            } else {
                db.alarmSettingDao().upsert(AlarmSetting(id = "global_alarm", scopeType = AlarmScopeType.GLOBAL, updatedAt = clock.currentTimeMillis()).toEntity())
            }
            bundle.weatherSetting?.let { db.weatherSettingDao().upsert(it.toEntity()) }
        }
    }

    /**
     * 执行需要 Bearer Token 的后端请求：若遇到 HTTP 401/403（Access Token 过期），
     * 自动调用 /api/v1/auth/refresh 换发新令牌并重试一次。
     */
    private suspend fun <T> withValidAccessToken(
        serverUrl: String,
        action: suspend (accessToken: String) -> Result<T>
    ): Result<T> {
        val token = cryptoManager.getDecrypted(SECURE_KEY_ACCESS_TOKEN)
            ?: return Result.failure(IllegalStateException("请先使用手机号验证码登录后再执行云端操作"))
        val first = action(token)
        if (first.isSuccess) return first
        val err = first.exceptionOrNull()
        val isAuthExpired = err is HttpStatusException && (err.statusCode == 401 || err.statusCode == 403)
        if (!isAuthExpired) return first

        val refresh = cryptoManager.getDecrypted(SECURE_KEY_REFRESH_TOKEN)
            ?: return Result.failure(IllegalStateException("登录凭证已过期，请重新获取验证码登录"))
        val refreshed = backendClient.refreshToken(serverUrl, refresh)
            .getOrElse {
                return Result.failure(IllegalStateException("登录凭证已过期且续期失败，请重新登录：${it.localizedMessage ?: "认证失效"}"))
            }
        cryptoManager.putEncrypted(SECURE_KEY_ACCESS_TOKEN, refreshed.accessToken)
        cryptoManager.putEncrypted(SECURE_KEY_REFRESH_TOKEN, refreshed.refreshToken)
        return action(refreshed.accessToken)
    }

    override suspend fun exportLocalBackupJson(): String {
        val bundle = buildLocalBackupBundle()
        val encoded = json.encodeToString(bundle)
        val user = timetableRepository.ensureGuestInitialized()
        db.syncRecordDao().insert(
            SyncRecord(
                userId = user.id,
                deviceId = getOrCreateDeviceId(),
                direction = SyncDirection.BACKUP_EXPORT,
                status = SyncStatus.SUCCESS,
                localVersion = user.syncVersion,
                remoteVersion = user.syncVersion,
                syncedEntitiesCount = bundle.courses.size + bundle.semesters.size,
                syncedAt = clock.currentTimeMillis()
            ).toEntity()
        )
        return encoded
    }

    override suspend fun parseBackupPreview(jsonText: String): Result<BackupBundle> = runCatching {
        val bundle = json.decodeFromString<BackupBundle>(jsonText)
        require(bundle.schemaVersion in 1..3) { "不支持的备份文件版本：v${bundle.schemaVersion}" }
        bundle
    }

    override suspend fun importLocalBackupJson(
        jsonText: String,
        mergeWithExisting: Boolean
    ): Result<BackupMergeResult> = runCatching {
        val incoming = parseBackupPreview(jsonText).getOrThrow()
        val user = timetableRepository.ensureGuestInitialized()
        val result = if (mergeWithExisting) {
            val local = buildLocalBackupBundle()
            BackupMergeEngine.merge(local, incoming)
        } else {
            BackupMergeResult(
                merged = incoming,
                addedCount = incoming.courses.size + incoming.semesters.size,
                updatedCount = 0,
                conflicts = emptyList()
            )
        }
        applyBundleToLocalDb(result.merged, user.id)
        db.syncRecordDao().insert(
            SyncRecord(
                userId = user.id,
                deviceId = getOrCreateDeviceId(),
                direction = SyncDirection.BACKUP_IMPORT,
                status = if (result.conflicts.isEmpty()) SyncStatus.SUCCESS else SyncStatus.CONFLICT,
                localVersion = user.syncVersion,
                remoteVersion = user.syncVersion,
                syncedEntitiesCount = result.addedCount + result.updatedCount,
                conflictSummary = result.conflicts.takeIf { it.isNotEmpty() }
                    ?.joinToString("；") { "${it.entityType}:${it.title}(${it.resolution})" },
                syncedAt = clock.currentTimeMillis()
            ).toEntity()
        )
        result
    }

    override suspend fun sendSmsCode(phone: String, serverBaseUrl: String?): Result<String> {
        val cleanPhone = phone.trim()
        if (!Regex("^1[3-9]\\d{9}$").matches(cleanPhone)) {
            return Result.failure(IllegalArgumentException("请输入有效的 11 位中国大陆手机号"))
        }
        return backendClient.sendSmsCode(resolveServerUrl(serverBaseUrl), cleanPhone).map { it.message }
    }

    override suspend fun loginWithPhoneCode(
        phone: String,
        code: String,
        serverBaseUrl: String?
    ): Result<User> = runCatching {
        val url = resolveServerUrl(serverBaseUrl)
        val prevUser = timetableRepository.ensureGuestInitialized()
        val wasGuest = prevUser.isGuest
        val auth = backendClient.verifySmsCode(url, phone.trim(), code.trim()).getOrThrow()

        cryptoManager.putEncrypted(SECURE_KEY_ACCESS_TOKEN, auth.accessToken)
        cryptoManager.putEncrypted(SECURE_KEY_REFRESH_TOKEN, auth.refreshToken)

        val formalUser = User(
            id = auth.userId,
            phone = auth.phone,
            nickname = auth.nickname,
            isGuest = false,
            syncVersion = auth.syncVersion,
            createdAt = prevUser.createdAt,
            updatedAt = clock.currentTimeMillis()
        )
        db.userDao().clearAll()
        db.userDao().upsert(formalUser.toEntity())

        val localBundle = buildLocalBackupBundle()
        applyBundleToLocalDb(localBundle, formalUser.id)
        if (wasGuest) {
            // 绝不静默吞掉游客合并同步异常：若云端同步失败，如实向调用方抛出提示重试
            syncWithCloud(mergeGuestData = true, serverBaseUrl = url).getOrElse { e ->
                throw IllegalStateException(
                    "账号已登录（${formalUser.phone}），但游客课表合并同步失败：${e.localizedMessage ?: "网络异常"}。请稍后点击「立即与云端同步」重试。",
                    e
                )
            }
        }
        formalUser
    }

    override suspend fun logoutToGuest(): Result<User> = runCatching {
        cryptoManager.remove(SECURE_KEY_ACCESS_TOKEN)
        cryptoManager.remove(SECURE_KEY_REFRESH_TOKEN)
        db.userDao().clearAll()
        timetableRepository.ensureGuestInitialized()
    }

    override suspend fun deleteCloudAccountAndData(serverBaseUrl: String?): Result<Unit> = runCatching {
        val url = resolveServerUrl(serverBaseUrl)
        withValidAccessToken(url) { token ->
            backendClient.deleteAccount(url, token)
        }.getOrThrow()
        logoutToGuest().getOrThrow()
    }

    override suspend fun syncWithCloud(
        mergeGuestData: Boolean,
        serverBaseUrl: String?
    ): Result<SyncRecord> = runCatching {
        val user = db.userDao().getCurrentUser()?.toDomain()
            ?: throw IllegalStateException("未找到当前账号信息")
        val url = resolveServerUrl(serverBaseUrl)
        val deviceId = getOrCreateDeviceId()
        val localBundle = buildLocalBackupBundle()

        val pull = withValidAccessToken(url) { token ->
            backendClient.pullSync(url, token)
        }.getOrThrow()
        val mergeResult = if (pull.hasSnapshot && pull.bundle != null) {
            BackupMergeEngine.merge(localBundle, pull.bundle)
        } else {
            BackupMergeResult(localBundle, addedCount = 0, updatedCount = 0, conflicts = emptyList())
        }

        val pushReq = SyncPushRequestDto(
            deviceId = deviceId,
            baseVersion = pull.syncVersion,
            bundle = mergeResult.merged
        )
        val pushResp = withValidAccessToken(url) { token ->
            if (mergeGuestData) {
                backendClient.mergeGuest(url, token, pushReq)
            } else {
                backendClient.pushSync(url, token, pushReq)
            }
        }.getOrThrow()

        val finalBundle = pushResp.serverBundle ?: mergeResult.merged
        applyBundleToLocalDb(finalBundle, user.id)
        val now = clock.currentTimeMillis()
        db.userDao().upsert(user.copy(syncVersion = pushResp.syncVersion, updatedAt = now).toEntity())
        context.settingsDataStore.edit { it[KEY_LAST_SYNCED_AT] = now }

        val record = SyncRecord(
            userId = user.id,
            deviceId = deviceId,
            direction = if (mergeGuestData) SyncDirection.MERGE else SyncDirection.PUSH,
            status = if (mergeResult.conflicts.isEmpty()) SyncStatus.SUCCESS else SyncStatus.CONFLICT,
            localVersion = user.syncVersion,
            remoteVersion = pushResp.syncVersion,
            syncedEntitiesCount = finalBundle.courses.size + finalBundle.semesters.size,
            conflictSummary = mergeResult.conflicts.takeIf { it.isNotEmpty() }
                ?.joinToString("；") { "${it.entityType}:${it.title}(${it.resolution})" },
            syncedAt = now
        )
        db.syncRecordDao().insert(record.toEntity())
        record
    }

    override suspend fun getAuthSessionSummary(): AuthSessionSummary {
        val user = timetableRepository.ensureGuestInitialized()
        val hasToken = !cryptoManager.getDecrypted(SECURE_KEY_ACCESS_TOKEN).isNullOrBlank()
        val serverUrl = settingsRepository.observeCustomServerUrl().first()
        val lastSynced = context.settingsDataStore.data.first()[KEY_LAST_SYNCED_AT]
        return AuthSessionSummary(
            isLoggedIn = !user.isGuest && hasToken,
            phone = user.phone,
            nickname = user.nickname,
            serverBaseUrl = serverUrl,
            lastSyncedAt = lastSynced,
            syncVersion = user.syncVersion
        )
    }
}
