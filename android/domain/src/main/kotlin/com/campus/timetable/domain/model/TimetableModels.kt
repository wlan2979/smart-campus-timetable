package com.campus.timetable.domain.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable
data class User(
    val id: String = newId(),
    val phone: String? = null,
    val nickname: String = "游客同学",
    val isGuest: Boolean = true,
    val syncVersion: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class Semester(
    val id: String = newId(),
    val userId: String,
    val name: String,
    /** ISO-8601 格式，如 2026-09-07（建议设为第一周周一） */
    val startDate: String,
    val totalWeeks: Int = 20,
    val periodsPerDay: Int = 12,
    val isCurrent: Boolean = true,
    val syncVersion: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
) {
    fun parsedStartDate(): LocalDate = LocalDate.parse(startDate)
}

@Serializable
data class ClassPeriod(
    val id: String = newId(),
    val semesterId: String,
    /** 0 表示全周默认节次时间；1..7 表示周一至周日独立覆盖作息 */
    val dayOfWeek: Int = 0,
    val periodNumber: Int,
    /** HH:mm */
    val startTime: String,
    /** HH:mm */
    val endTime: String,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun parsedStartTime(): LocalTime = LocalTime.parse(startTime)
    fun parsedEndTime(): LocalTime = LocalTime.parse(endTime)
}

/**
 * 从高校教务课表网页提取的真实官网节次作息时间。
 */
@Serializable
data class ExtractedClassPeriod(
    val periodNumber: Int,
    /** HH:mm */
    val startTime: String,
    /** HH:mm */
    val endTime: String
)

/**
 * 自由可定制的作息时间规则配置：全部参数均由用户自由设定，绝不写死课时与课间长度。
 */
@Serializable
data class BellScheduleRule(
    val firstClassStartTime: String = "08:00",
    val defaultPeriodDurationMinutes: Int = 45,
    val defaultSmallBreakMinutes: Int = 10,
    val bigBreakMinutes: Int = 20,
    /** 每上完 N 节课安排一次大课间；0 表示不自动安排大课间 */
    val bigBreakEveryNPeriods: Int = 2,
    val morningEndPeriod: Int = 4,
    val lunchBreakMinutes: Int = 140,
    val useFixedAfternoonStart: Boolean = true,
    val afternoonStartTime: String = "14:00",
    val afternoonEndPeriod: Int = 8,
    val eveningBreakMinutes: Int = 80,
    val useFixedEveningStart: Boolean = true,
    val eveningStartTime: String = "19:00",
    /** 当天总节数，0 表示该星期无课 */
    val totalPeriods: Int = 12,
    /** 单节差异化时长，例如 {5: 50} 表示第 5 节为 50 分钟 */
    val customPeriodDurations: Map<Int, Int> = emptyMap(),
    /** 任意两节之间自定义休息时长，例如 {2: 30} 表示第 2 节后休息 30 分钟 */
    val customBreaksAfterPeriod: Map<Int, Int> = emptyMap()
)

@Serializable
enum class BellTemplateCategory(val label: String) {
    NORMAL("普通作息"),
    SUMMER("夏季作息"),
    WINTER("冬季作息"),
    WEEKEND("周末作息"),
    CUSTOM("自定义作息")
}

@Serializable
data class BellScheduleTemplate(
    val id: String = newId(),
    val name: String,
    val category: BellTemplateCategory = BellTemplateCategory.CUSTOM,
    val rule: BellScheduleRule = BellScheduleRule(),
    val isPreset: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
enum class CourseType(val label: String) {
    NORMAL("普通课程"),
    PHYSICAL_EDUCATION("体育课程")
}

@Serializable
data class Course(
    val id: String = newId(),
    val semesterId: String,
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val courseType: CourseType = CourseType.NORMAL,
    val colorHex: String = "#1A73E8",
    val credit: Double = 0.0,
    val note: String = "",
    val isAutoDetectedPe: Boolean = false,
    val syncVersion: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

@Serializable
enum class WeekRuleType(val label: String) {
    ALL("每周"),
    ODD("单周"),
    EVEN("双周"),
    CUSTOM("自定义周")
}

@Serializable
data class CourseSchedule(
    val id: String = newId(),
    val courseId: String,
    /** 1 = 周一 ... 7 = 周日 */
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weekRuleType: WeekRuleType = WeekRuleType.ALL,
    val startWeek: Int = 1,
    val endWeek: Int = 16,
    val customWeeks: List<Int> = emptyList(),
    val teacherOverride: String? = null,
    val locationOverride: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

@Serializable
enum class CourseExceptionType(val label: String) {
    RESCHEDULE("临时调课"),
    CANCEL("临时停课"),
    MODIFY_LOCATION("临时换教室"),
    MODIFY_TEACHER("临时换教师")
}

@Serializable
data class CourseException(
    val id: String = newId(),
    val courseId: String,
    val scheduleId: String,
    val exceptionType: CourseExceptionType,
    /** 原上课日期 ISO-8601 */
    val originalDate: String,
    val originalWeek: Int,
    /** 调课目标日期 ISO-8601，仅 RESCHEDULE 有效 */
    val targetDate: String? = null,
    val targetDayOfWeek: Int? = null,
    val targetStartPeriod: Int? = null,
    val targetEndPeriod: Int? = null,
    val newLocation: String? = null,
    val newTeacher: String? = null,
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

@Serializable
enum class HolidayType(val label: String) {
    LEGAL_HOLIDAY("法定节假日"),
    STATUTORY_WORKDAY("国务院调休工作日")
}

/**
 * 完整假期区间事件（同一个连续假期合并为一条记录，绝不按天拆成多条独立节日）。
 */
@Serializable
data class Holiday(
    val id: String = newId(),
    val year: Int,
    /** 假期开始日期 ISO-8601 */
    val date: String,
    /** 假期结束日期 ISO-8601（连续假期为最后一天，单日事件与 date 相同） */
    val endDate: String = date,
    val totalDays: Int = 1,
    val name: String,
    val holidayType: HolidayType,
    val isOffDay: Boolean,
    val source: String,
    val version: String,
    /** 节前提醒日期 ISO-8601，每个连续假期仅生成一条提醒 */
    val reminderDate: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun parsedStartDate(): LocalDate = LocalDate.parse(date)
    fun parsedEndDate(): LocalDate = LocalDate.parse(endDate)

    fun containsDate(target: LocalDate): Boolean {
        val s = parsedStartDate()
        val e = parsedEndDate()
        return !target.isBefore(s) && !target.isAfter(e)
    }

    fun formatDateRangeZh(): String {
        val s = parsedStartDate()
        val e = parsedEndDate()
        return if (s == e) {
            "${s.monthValue}月${s.dayOfMonth}日"
        } else {
            "${s.monthValue}月${s.dayOfMonth}日—${e.monthValue}月${e.dayOfMonth}日"
        }
    }
}

data class HolidayYearStatus(
    val year: Int,
    val isPublished: Boolean,
    val source: String,
    val version: String,
    val updatedAt: Long?,
    val holidays: List<Holiday>,
    val notice: String
)

@Serializable
enum class CalendarOverrideType(val label: String) {
    HOLIDAY("学校放假"),
    NORMAL_CLASS("照常上课"),
    MAKEUP_CLASS("学校补课（按指定星期）"),
    SUSPENDED("全校临时停课")
}

@Serializable
data class SchoolCalendarOverride(
    val id: String = newId(),
    val semesterId: String,
    /** ISO-8601 */
    val date: String,
    val overrideType: CalendarOverrideType,
    /** 仅 MAKEUP_CLASS 有效：例如 3 表示当天按周三课表上课 */
    val mappedDayOfWeek: Int? = null,
    val mappedWeekNumber: Int? = null,
    val title: String,
    val note: String = "",
    /** 若为节假日但学校安排照常/补课，允许恢复普通课程闹钟 */
    val enableAlarmOverride: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

@Serializable
enum class AlarmMode(val label: String) {
    NOTIFICATION("普通通知模式"),
    ALARM_CLOCK("真正闹钟模式")
}

@Serializable
enum class AlarmScopeType {
    GLOBAL,
    COURSE
}

/**
 * 课程提醒设置（第二轮已彻底移除不可靠的 TTS 语音播报，专注保障通知、铃声、震动与上下课提醒可靠运行）。
 */
@Serializable
data class AlarmSetting(
    val id: String = newId(),
    val scopeType: AlarmScopeType = AlarmScopeType.GLOBAL,
    val courseId: String? = null,
    val enabled: Boolean = true,
    val mode: AlarmMode = AlarmMode.ALARM_CLOCK,
    val advanceMinutes: Int = 15,
    val enableClassEndReminder: Boolean = false,
    val ringtoneUri: String? = null,
    val vibrate: Boolean = true,
    /** ISO-8601：如果等于今天日期，则今天一键静音全部课程提醒 */
    val muteDate: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
enum class WeatherProviderType(val label: String) {
    OPEN_METEO("Open-Meteo 实时开放气象（免密钥）"),
    QWEATHER("和风天气 QWeather（需配置 API Key）")
}

@Serializable
enum class LocationMode(val label: String) {
    AUTO("自动获取当前位置"),
    MANUAL("手动选择城市")
}

@Serializable
data class WeatherSetting(
    val id: String = "default",
    val provider: WeatherProviderType = WeatherProviderType.OPEN_METEO,
    val locationMode: LocationMode = LocationMode.MANUAL,
    val cityName: String = "北京",
    val cityId: String = "101010100",
    val latitude: Double = 39.9042,
    val longitude: Double = 116.4074,
    val enablePeWeatherReminder: Boolean = true,
    val prevDayNotifyHour: Int = 20,
    val prevDayNotifyMinute: Int = 0,
    val sameDayAdvanceMinutes: Int = 90,
    val highTempThresholdC: Double = 33.0,
    val lowTempThresholdC: Double = 5.0,
    val aqiThreshold: Int = 150,
    val remindRain: Boolean = true,
    val remindSnow: Boolean = true,
    val remindThunder: Boolean = true,
    val lastWeatherJson: String? = null,
    val lastUpdatedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class WeatherHourlyPoint(
    val hour: Int,
    val tempC: Double,
    val weatherCode: Int,
    val conditionText: String,
    val precipitationProb: Int,
    val isRain: Boolean,
    val isSnow: Boolean,
    val isThunder: Boolean,
    val aqi: Int? = null
)

@Serializable
data class WeatherForecastDay(
    val date: String,
    val tempMinC: Double,
    val tempMaxC: Double,
    val conditionText: String,
    val weatherCode: Int,
    val precipitationProbMax: Int,
    val isRain: Boolean,
    val isSnow: Boolean,
    val isThunder: Boolean,
    val aqiMax: Int? = null,
    val hourly: List<WeatherHourlyPoint> = emptyList()
)

@Serializable
data class WeatherSnapshot(
    val providerName: String,
    val cityName: String,
    val currentTempC: Double,
    val currentCondition: String,
    val currentAqi: Int?,
    val fetchedAt: Long,
    val daily: List<WeatherForecastDay>
)

@Serializable
enum class WeatherAlertType(val label: String) {
    RAIN("降雨"),
    SNOW("降雪"),
    THUNDER("雷电"),
    HIGH_TEMP("高温"),
    LOW_TEMP("低温"),
    POOR_AQI("空气质量较差")
}

data class PeWeatherAdvice(
    val date: LocalDate,
    val peCourseNames: List<String>,
    val timeRangeText: String,
    val alertTypes: List<WeatherAlertType>,
    val summary: String,
    val detail: String,
    val needsAlert: Boolean
)

@Serializable
enum class SchoolLevel(val label: String) {
    UNDERGRADUATE("本科院校"),
    VOCATIONAL("高职高专")
}

@Serializable
enum class EduSystemType(val label: String) {
    ZHENGFANG("正方教务系统"),
    QIANGZHI("强智教务系统"),
    NEW_URP("新 URP 综合教务"),
    OLD_URP("旧 URP 教务系统"),
    EAMS("树维 EAMS 教务系统"),
    CUSTOM("高校自研/门户教务"),
    UNKNOWN("待识别教务系统")
}

/**
 * 诚实区分学校适配进展状态，严禁因进入目录就冒充「已验证支持」。
 */
@Serializable
enum class SchoolSupportStatus(val label: String) {
    VERIFIED("已验证支持"),
    GENERIC_ADAPTER("支持通用教务导入"),
    ASSISTED_LOGIN("需要用户辅助登录"),
    UNSUPPORTED("暂未适配"),
    LAST_VERIFY_FAILED("最近验证失败")
}

@Serializable
data class School(
    val id: String = newId(),
    val code: String,
    val name: String,
    val shortName: String = "",
    val aliases: String = "",
    val pinyin: String = "",
    val pinyinInitials: String = "",
    val province: String,
    val city: String,
    val schoolType: String = "公办",
    val schoolLevel: SchoolLevel,
    val campuses: String = "",
    val systemType: EduSystemType = EduSystemType.UNKNOWN,
    val systemVersion: String = "",
    val adapterId: String? = null,
    val portalUrl: String = "",
    val supportStatus: SchoolSupportStatus = SchoolSupportStatus.UNSUPPORTED,
    /** 只有经过真实学校实测导入验证通过的学校才允许为 true */
    val verified: Boolean = false,
    val lastVerifiedAt: Long? = null,
    val statusNote: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

data class SchoolCatalogStats(
    val totalCatalogCount: Int,
    val undergradCount: Int,
    val vocationalCount: Int,
    val configuredPortalCount: Int,
    val genericAdapterCount: Int,
    val assistedLoginCount: Int,
    val verifiedCount: Int,
    val countsBySystem: Map<EduSystemType, Int>,
    val catalogVersion: String = "moe-mainland-2026.2",
    val catalogSource: String = "中华人民共和国教育部《全国普通高等学校名单》"
)

@Serializable
data class SchoolAdaptationRequest(
    val id: String = newId(),
    val schoolName: String,
    val eduSystemType: EduSystemType = EduSystemType.UNKNOWN,
    val portalUrl: String = "",
    val pageSummary: String = "",
    val userAuthorized: Boolean = false,
    val status: String = "LOCAL_SAVED",
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class ImportSourceType(val label: String) {
    SCHOOL_ADAPTER("学校教务自动导入"),
    EXCEL("Excel 文件导入"),
    PDF("PDF 文本导入"),
    PDF_OCR("PDF 扫描件 OCR 识别"),
    WEB_LINK("网页链接导入"),
    BACKUP_FILE("备份文件恢复")
}

@Serializable
enum class ImportStatus(val label: String) {
    SUCCESS("导入成功"),
    PARTIAL("部分导入"),
    FAILED("导入失败")
}

@Serializable
data class ImportHistory(
    val id: String = newId(),
    val semesterId: String,
    val sourceType: ImportSourceType,
    val sourceName: String,
    val schoolCode: String? = null,
    val totalParsed: Int,
    val importedCount: Int,
    val conflictCount: Int,
    val status: ImportStatus,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class ParsedCourseCandidate(
    val tempId: String = newId(),
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val dayOfWeek: Int = 1,
    val startPeriod: Int = 1,
    val endPeriod: Int = 2,
    val weekRuleType: WeekRuleType = WeekRuleType.ALL,
    val startWeek: Int = 1,
    val endWeek: Int = 16,
    val customWeeks: List<Int> = emptyList(),
    val courseType: CourseType = CourseType.NORMAL,
    val rawSnippet: String = "",
    val needsManualReview: Boolean = false,
    val reviewWarning: String? = null
)

@Serializable
enum class SyncDirection(val label: String) {
    PUSH("上传至云端"),
    PULL("从云端恢复"),
    MERGE("游客数据合并"),
    BACKUP_EXPORT("手动导出备份"),
    BACKUP_IMPORT("手动导入备份")
}

@Serializable
enum class SyncStatus(val label: String) {
    SUCCESS("成功"),
    CONFLICT("存在冲突"),
    FAILED("失败")
}

@Serializable
data class SyncRecord(
    val id: String = newId(),
    val userId: String,
    val deviceId: String,
    val direction: SyncDirection,
    val status: SyncStatus,
    val localVersion: Long,
    val remoteVersion: Long,
    val syncedEntitiesCount: Int,
    val conflictSummary: String? = null,
    val errorMessage: String? = null,
    val syncedAt: Long = System.currentTimeMillis()
)

data class CourseWithDetails(
    val course: Course,
    val schedules: List<CourseSchedule>,
    val exceptions: List<CourseException>,
    val alarmSetting: AlarmSetting? = null
)

data class DayCalendarStatus(
    val date: LocalDate,
    val weekNumber: Int,
    val holiday: Holiday? = null,
    val schoolOverride: SchoolCalendarOverride? = null,
    val isHolidayOffDay: Boolean = false,
    val shouldPauseRegularAlarms: Boolean = false,
    val effectiveDayOfWeekForRegularClasses: Int = date.dayOfWeek.value,
    val effectiveWeekForRegularClasses: Int = weekNumber,
    val badgeText: String? = null,
    val detailText: String? = null
)

data class ResolvedCourseSession(
    val course: Course,
    val scheduleId: String,
    val date: LocalDate,
    val weekNumber: Int,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val effectiveTeacher: String,
    val effectiveLocation: String,
    val isRescheduled: Boolean = false,
    val isCancelled: Boolean = false,
    val isModified: Boolean = false,
    val exceptionNote: String? = null,
    val holidayPaused: Boolean = false,
    val conflictingCourseNames: List<String> = emptyList()
) {
    val hasConflict: Boolean get() = conflictingCourseNames.isNotEmpty()
    val isPhysicalEducation: Boolean get() = course.courseType == CourseType.PHYSICAL_EDUCATION
}

data class CourseConflict(
    val date: LocalDate,
    val weekNumber: Int,
    val dayOfWeek: Int,
    val courseAId: String,
    val courseAName: String,
    val courseBId: String,
    val courseBName: String,
    val overlapStartPeriod: Int,
    val overlapEndPeriod: Int,
    val reason: String
)

@Serializable
data class BackupBundle(
    val schemaVersion: Int = 2,
    val appVersion: String = "2.0.0",
    val exportedAt: Long = System.currentTimeMillis(),
    val semesters: List<Semester> = emptyList(),
    val classPeriods: List<ClassPeriod> = emptyList(),
    val bellTemplates: List<BellScheduleTemplate> = emptyList(),
    val courses: List<Course> = emptyList(),
    val schedules: List<CourseSchedule> = emptyList(),
    val exceptions: List<CourseException> = emptyList(),
    val schoolOverrides: List<SchoolCalendarOverride> = emptyList(),
    val alarmSettings: List<AlarmSetting> = emptyList(),
    val weatherSetting: WeatherSetting? = null
)

data class MergeConflictItem(
    val entityType: String,
    val entityId: String,
    val title: String,
    val resolution: String
)

data class BackupMergeResult(
    val merged: BackupBundle,
    val addedCount: Int,
    val updatedCount: Int,
    val conflicts: List<MergeConflictItem>
)

data class CityOption(
    val name: String,
    val province: String,
    val latitude: Double,
    val longitude: Double,
    val cityId: String = ""
)
