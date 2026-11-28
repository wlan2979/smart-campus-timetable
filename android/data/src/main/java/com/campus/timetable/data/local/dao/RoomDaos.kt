package com.campus.timetable.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.campus.timetable.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users LIMIT 1")
    fun observeCurrentUser(): Flow<UserEntity?>

    @Query("SELECT * FROM users LIMIT 1")
    suspend fun getCurrentUser(): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    @Query("DELETE FROM users")
    suspend fun clearAll()
}

@Dao
interface SemesterDao {
    @Query("SELECT * FROM semesters WHERE isDeleted = 0 ORDER BY isCurrent DESC, createdAt DESC")
    fun observeActiveSemesters(): Flow<List<SemesterEntity>>

    @Query("SELECT * FROM semesters")
    suspend fun getAllRaw(): List<SemesterEntity>

    @Query("SELECT * FROM semesters WHERE isDeleted = 0 AND isCurrent = 1 LIMIT 1")
    fun observeCurrentSemester(): Flow<SemesterEntity?>

    @Query("SELECT * FROM semesters WHERE isDeleted = 0 AND isCurrent = 1 LIMIT 1")
    suspend fun getCurrentSemester(): SemesterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(semester: SemesterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(semesters: List<SemesterEntity>)

    @Query("UPDATE semesters SET isCurrent = 0, updatedAt = :now")
    suspend fun clearCurrentFlag(now: Long)

    @Query("UPDATE semesters SET isCurrent = 1, updatedAt = :now WHERE id = :semesterId")
    suspend fun markAsCurrent(semesterId: String, now: Long)

    @Query("UPDATE semesters SET isDeleted = 1, isCurrent = 0, updatedAt = :now WHERE id = :semesterId")
    suspend fun softDelete(semesterId: String, now: Long)

    @Query("DELETE FROM semesters")
    suspend fun clearAll()
}

@Dao
interface ClassPeriodDao {
    @Query("SELECT * FROM class_periods WHERE semesterId = :semesterId ORDER BY dayOfWeek ASC, periodNumber ASC")
    fun observeBySemester(semesterId: String): Flow<List<ClassPeriodEntity>>

    @Query("SELECT * FROM class_periods WHERE semesterId = :semesterId ORDER BY dayOfWeek ASC, periodNumber ASC")
    suspend fun getBySemester(semesterId: String): List<ClassPeriodEntity>

    @Query("SELECT * FROM class_periods")
    suspend fun getAllRaw(): List<ClassPeriodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(periods: List<ClassPeriodEntity>)

    @Query("DELETE FROM class_periods WHERE semesterId = :semesterId")
    suspend fun deleteBySemester(semesterId: String)

    @Query("DELETE FROM class_periods WHERE semesterId = :semesterId AND dayOfWeek = :dayOfWeek")
    suspend fun deleteBySemesterAndDay(semesterId: String, dayOfWeek: Int)

    @Query("DELETE FROM class_periods")
    suspend fun clearAll()
}

@Dao
interface BellScheduleTemplateDao {
    @Query("SELECT * FROM bell_schedule_templates ORDER BY isPreset DESC, updatedAt DESC")
    fun observeAll(): Flow<List<BellScheduleTemplateEntity>>

    @Query("SELECT * FROM bell_schedule_templates")
    suspend fun getAll(): List<BellScheduleTemplateEntity>

    @Query("SELECT COUNT(*) FROM bell_schedule_templates")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: BellScheduleTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(templates: List<BellScheduleTemplateEntity>)

    @Query("DELETE FROM bell_schedule_templates WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bell_schedule_templates")
    suspend fun clearAll()
}

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses WHERE semesterId = :semesterId AND isDeleted = 0 ORDER BY createdAt ASC")
    fun observeCourses(semesterId: String): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE semesterId = :semesterId AND isDeleted = 0 ORDER BY createdAt ASC")
    suspend fun getCourses(semesterId: String): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :courseId LIMIT 1")
    suspend fun getCourseById(courseId: String): CourseEntity?

    @Query("SELECT * FROM courses")
    suspend fun getAllCoursesRaw(): List<CourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCourse(course: CourseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCourses(courses: List<CourseEntity>)

    @Query("UPDATE courses SET isDeleted = 1, updatedAt = :now WHERE id = :courseId")
    suspend fun softDeleteCourse(courseId: String, now: Long)

    @Query("UPDATE course_exceptions SET isDeleted = 1, updatedAt = :now WHERE courseId IN (SELECT id FROM courses WHERE semesterId = :semesterId) AND isDeleted = 0")
    suspend fun softDeleteExceptionsBySemester(semesterId: String, now: Long)

    @Query("UPDATE course_schedules SET isDeleted = 1, updatedAt = :now WHERE courseId IN (SELECT id FROM courses WHERE semesterId = :semesterId) AND isDeleted = 0")
    suspend fun softDeleteSchedulesBySemester(semesterId: String, now: Long)

    @Query("UPDATE courses SET isDeleted = 1, updatedAt = :now WHERE semesterId = :semesterId AND isDeleted = 0")
    suspend fun softDeleteCoursesBySemester(semesterId: String, now: Long)

    @Query("SELECT * FROM course_schedules WHERE courseId IN (SELECT id FROM courses WHERE semesterId = :semesterId AND isDeleted = 0) AND isDeleted = 0")
    fun observeSchedulesBySemester(semesterId: String): Flow<List<CourseScheduleEntity>>

    @Query("SELECT * FROM course_schedules WHERE courseId = :courseId AND isDeleted = 0")
    suspend fun getSchedulesByCourse(courseId: String): List<CourseScheduleEntity>

    @Query("SELECT * FROM course_schedules")
    suspend fun getAllSchedulesRaw(): List<CourseScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedules(schedules: List<CourseScheduleEntity>)

    @Query("UPDATE course_schedules SET isDeleted = 1, updatedAt = :now WHERE courseId = :courseId")
    suspend fun softDeleteSchedulesByCourse(courseId: String, now: Long)

    @Query("SELECT * FROM course_exceptions WHERE courseId IN (SELECT id FROM courses WHERE semesterId = :semesterId AND isDeleted = 0) AND isDeleted = 0")
    fun observeExceptionsBySemester(semesterId: String): Flow<List<CourseExceptionEntity>>

    @Query("SELECT * FROM course_exceptions WHERE courseId = :courseId AND isDeleted = 0")
    suspend fun getExceptionsByCourse(courseId: String): List<CourseExceptionEntity>

    @Query("SELECT * FROM course_exceptions")
    suspend fun getAllExceptionsRaw(): List<CourseExceptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertException(exception: CourseExceptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExceptions(exceptions: List<CourseExceptionEntity>)

    @Query("UPDATE course_exceptions SET isDeleted = 1, updatedAt = :now WHERE id = :exceptionId")
    suspend fun softDeleteException(exceptionId: String, now: Long)

    @Transaction
    suspend fun replaceCourseAndSchedules(course: CourseEntity, schedules: List<CourseScheduleEntity>, now: Long) {
        upsertCourse(course)
        softDeleteSchedulesByCourse(course.id, now)
        if (schedules.isNotEmpty()) {
            upsertSchedules(schedules)
        }
    }

    @Query("DELETE FROM courses")
    suspend fun clearAllCourses()
}

@Dao
interface HolidayDao {
    @Query("SELECT * FROM holidays WHERE year = :year ORDER BY date ASC")
    fun observeByYear(year: Int): Flow<List<HolidayEntity>>

    @Query("SELECT * FROM holidays WHERE year = :year ORDER BY date ASC")
    suspend fun getByYear(year: Int): List<HolidayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(holidays: List<HolidayEntity>)

    @Query("DELETE FROM holidays WHERE year = :year")
    suspend fun deleteByYear(year: Int)

    @Query("SELECT * FROM school_calendar_overrides WHERE semesterId = :semesterId AND isDeleted = 0 ORDER BY date ASC")
    fun observeOverrides(semesterId: String): Flow<List<SchoolCalendarOverrideEntity>>

    @Query("SELECT * FROM school_calendar_overrides WHERE semesterId = :semesterId AND isDeleted = 0 ORDER BY date ASC")
    suspend fun getOverrides(semesterId: String): List<SchoolCalendarOverrideEntity>

    @Query("SELECT * FROM school_calendar_overrides")
    suspend fun getAllOverridesRaw(): List<SchoolCalendarOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverride(override: SchoolCalendarOverrideEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverrides(overrides: List<SchoolCalendarOverrideEntity>)

    @Query("UPDATE school_calendar_overrides SET isDeleted = 1, updatedAt = :now WHERE id = :overrideId")
    suspend fun softDeleteOverride(overrideId: String, now: Long)

    @Query("DELETE FROM school_calendar_overrides")
    suspend fun clearAllOverrides()
}

@Dao
interface AlarmSettingDao {
    @Query("SELECT * FROM alarm_settings WHERE scopeType = 'GLOBAL' LIMIT 1")
    fun observeGlobal(): Flow<AlarmSettingEntity?>

    @Query("SELECT * FROM alarm_settings WHERE scopeType = 'GLOBAL' LIMIT 1")
    suspend fun getGlobal(): AlarmSettingEntity?

    @Query("SELECT * FROM alarm_settings WHERE scopeType = 'COURSE' AND courseId = :courseId LIMIT 1")
    fun observeForCourse(courseId: String): Flow<AlarmSettingEntity?>

    @Query("SELECT * FROM alarm_settings WHERE scopeType = 'COURSE' AND courseId = :courseId LIMIT 1")
    suspend fun getForCourse(courseId: String): AlarmSettingEntity?

    @Query("SELECT * FROM alarm_settings")
    suspend fun getAll(): List<AlarmSettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: AlarmSettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(settings: List<AlarmSettingEntity>)

    @Query("DELETE FROM alarm_settings")
    suspend fun clearAll()
}

@Dao
interface WeatherSettingDao {
    @Query("SELECT * FROM weather_settings WHERE id = 'default' LIMIT 1")
    fun observeDefault(): Flow<WeatherSettingEntity?>

    @Query("SELECT * FROM weather_settings WHERE id = 'default' LIMIT 1")
    suspend fun getDefault(): WeatherSettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: WeatherSettingEntity)
}

@Dao
interface SchoolDao {
    /**
     * 高性能本地多维搜索：支持学校全称、简称、别名、全拼、拼音首字母、省份筛选、本科/高职筛选。
     * 按学校全拼 A–Z 升序排列。
     */
    @Query(
        """
        SELECT * FROM schools
        WHERE (:province IS NULL OR province = :province)
          AND (:level IS NULL OR schoolLevel = :level)
          AND (
            :query = ''
            OR name LIKE '%' || :query || '%'
            OR shortName LIKE '%' || :query || '%'
            OR aliases LIKE '%' || :query || '%'
            OR pinyin LIKE '%' || :query || '%'
            OR pinyinInitials LIKE '%' || :query || '%'
            OR city LIKE '%' || :query || '%'
            OR province LIKE '%' || :query || '%'
          )
        ORDER BY
          LOWER(CASE WHEN TRIM(pinyin) != '' THEN pinyin ELSE name END) ASC,
          name ASC
        """
    )
    fun searchFiltered(query: String, province: String?, level: String?): Flow<List<SchoolEntity>>

    @Query("SELECT DISTINCT province FROM schools ORDER BY province ASC")
    suspend fun getAllProvinces(): List<String>

    @Query("SELECT * FROM schools")
    suspend fun getAllSnapshot(): List<SchoolEntity>

    @Query("SELECT COUNT(*) FROM schools")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(schools: List<SchoolEntity>)

    @Query("DELETE FROM schools")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceCatalog(schools: List<SchoolEntity>) {
        clearAll()
        schools.chunked(400).forEach { batch -> upsertAll(batch) }
    }
}

@Dao
interface SchoolAdaptationRequestDao {
    @Query("SELECT * FROM school_adaptation_requests ORDER BY createdAt DESC LIMIT 50")
    fun observeRecent(): Flow<List<SchoolAdaptationRequestEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(req: SchoolAdaptationRequestEntity)
}

@Dao
interface ImportHistoryDao {
    @Query("SELECT * FROM import_histories WHERE semesterId = :semesterId ORDER BY createdAt DESC LIMIT 50")
    fun observeBySemester(semesterId: String): Flow<List<ImportHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: ImportHistoryEntity)
}

@Dao
interface SyncRecordDao {
    @Query("SELECT * FROM sync_records ORDER BY syncedAt DESC LIMIT 50")
    fun observeRecent(): Flow<List<SyncRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SyncRecordEntity)
}
