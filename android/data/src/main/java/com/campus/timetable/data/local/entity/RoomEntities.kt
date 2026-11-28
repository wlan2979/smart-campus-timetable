package com.campus.timetable.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val phone: String?,
    val nickname: String,
    val isGuest: Boolean,
    val syncVersion: Long,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "semesters",
    indices = [Index("userId"), Index("isCurrent")]
)
data class SemesterEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val startDate: String,
    val totalWeeks: Int,
    val periodsPerDay: Int,
    val isCurrent: Boolean,
    val syncVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean
)

@Entity(
    tableName = "class_periods",
    foreignKeys = [
        ForeignKey(
            entity = SemesterEntity::class,
            parentColumns = ["id"],
            childColumns = ["semesterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("semesterId"),
        Index(value = ["semesterId", "dayOfWeek", "periodNumber"], unique = true)
    ]
)
data class ClassPeriodEntity(
    @PrimaryKey val id: String,
    val semesterId: String,
    val dayOfWeek: Int,
    val periodNumber: Int,
    val startTime: String,
    val endTime: String,
    val updatedAt: Long
)

@Entity(tableName = "bell_schedule_templates")
data class BellScheduleTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val ruleJson: String,
    val isPreset: Boolean,
    val updatedAt: Long
)

@Entity(
    tableName = "courses",
    foreignKeys = [
        ForeignKey(
            entity = SemesterEntity::class,
            parentColumns = ["id"],
            childColumns = ["semesterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("semesterId"), Index("courseType")]
)
data class CourseEntity(
    @PrimaryKey val id: String,
    val semesterId: String,
    val name: String,
    val teacher: String,
    val location: String,
    val courseType: String,
    val colorHex: String,
    val credit: Double,
    val note: String,
    val isAutoDetectedPe: Boolean,
    val syncVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean
)

@Entity(
    tableName = "course_schedules",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("courseId"), Index("dayOfWeek")]
)
data class CourseScheduleEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weekRuleType: String,
    val startWeek: Int,
    val endWeek: Int,
    val customWeeksJson: String,
    val teacherOverride: String?,
    val locationOverride: String?,
    val updatedAt: Long,
    val isDeleted: Boolean
)

@Entity(
    tableName = "course_exceptions",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("courseId"), Index("scheduleId"), Index("originalDate"), Index("targetDate")]
)
data class CourseExceptionEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val scheduleId: String,
    val exceptionType: String,
    val originalDate: String,
    val originalWeek: Int,
    val targetDate: String?,
    val targetDayOfWeek: Int?,
    val targetStartPeriod: Int?,
    val targetEndPeriod: Int?,
    val newLocation: String?,
    val newTeacher: String?,
    val reason: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean
)

@Entity(
    tableName = "holidays",
    indices = [Index("year"), Index("date")]
)
data class HolidayEntity(
    @PrimaryKey val id: String,
    val year: Int,
    val date: String,
    val endDate: String,
    val totalDays: Int,
    val name: String,
    val holidayType: String,
    val isOffDay: Boolean,
    val source: String,
    val version: String,
    val reminderDate: String?,
    val updatedAt: Long
)

@Entity(
    tableName = "school_calendar_overrides",
    foreignKeys = [
        ForeignKey(
            entity = SemesterEntity::class,
            parentColumns = ["id"],
            childColumns = ["semesterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("semesterId"), Index(value = ["semesterId", "date"])]
)
data class SchoolCalendarOverrideEntity(
    @PrimaryKey val id: String,
    val semesterId: String,
    val date: String,
    val overrideType: String,
    val mappedDayOfWeek: Int?,
    val mappedWeekNumber: Int?,
    val title: String,
    val note: String,
    val enableAlarmOverride: Boolean,
    val updatedAt: Long,
    val isDeleted: Boolean
)

@Entity(
    tableName = "alarm_settings",
    indices = [Index("scopeType"), Index("courseId")]
)
data class AlarmSettingEntity(
    @PrimaryKey val id: String,
    val scopeType: String,
    val courseId: String?,
    val enabled: Boolean,
    val mode: String,
    val advanceMinutes: Int,
    val enableClassEndReminder: Boolean,
    val ringtoneUri: String?,
    val vibrate: Boolean,
    val muteDate: String?,
    val updatedAt: Long
)

@Entity(tableName = "weather_settings")
data class WeatherSettingEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val locationMode: String,
    val cityName: String,
    val cityId: String,
    val latitude: Double,
    val longitude: Double,
    val enablePeWeatherReminder: Boolean,
    val prevDayNotifyHour: Int,
    val prevDayNotifyMinute: Int,
    val sameDayAdvanceMinutes: Int,
    val highTempThresholdC: Double,
    val lowTempThresholdC: Double,
    val aqiThreshold: Int,
    val remindRain: Boolean,
    val remindSnow: Boolean,
    val remindThunder: Boolean,
    val lastWeatherJson: String?,
    val lastUpdatedAt: Long?,
    val updatedAt: Long
)

@Entity(
    tableName = "schools",
    indices = [
        Index(value = ["code"], unique = true),
        Index("name"),
        Index("province"),
        Index("schoolLevel"),
        Index("pinyin"),
        Index("pinyinInitials"),
        Index("supportStatus")
    ]
)
data class SchoolEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val shortName: String,
    val aliases: String,
    val pinyin: String,
    val pinyinInitials: String,
    val province: String,
    val city: String,
    val schoolType: String,
    val schoolLevel: String,
    val campuses: String,
    val systemType: String,
    val systemVersion: String,
    val adapterId: String?,
    val portalUrl: String,
    val supportStatus: String,
    val verified: Boolean,
    val lastVerifiedAt: Long?,
    val statusNote: String,
    val updatedAt: Long
)

@Entity(tableName = "school_adaptation_requests")
data class SchoolAdaptationRequestEntity(
    @PrimaryKey val id: String,
    val schoolName: String,
    val eduSystemType: String,
    val portalUrl: String,
    val pageSummary: String,
    val userAuthorized: Boolean,
    val status: String,
    val createdAt: Long
)

@Entity(
    tableName = "import_histories",
    indices = [Index("semesterId"), Index("createdAt")]
)
data class ImportHistoryEntity(
    @PrimaryKey val id: String,
    val semesterId: String,
    val sourceType: String,
    val sourceName: String,
    val schoolCode: String?,
    val totalParsed: Int,
    val importedCount: Int,
    val conflictCount: Int,
    val status: String,
    val errorMessage: String?,
    val createdAt: Long
)

@Entity(
    tableName = "sync_records",
    indices = [Index("userId"), Index("syncedAt")]
)
data class SyncRecordEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val deviceId: String,
    val direction: String,
    val status: String,
    val localVersion: Long,
    val remoteVersion: Long,
    val syncedEntitiesCount: Int,
    val conflictSummary: String?,
    val errorMessage: String?,
    val syncedAt: Long
)
