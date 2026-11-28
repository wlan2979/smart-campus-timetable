package com.campus.timetable.data.local.mapper

import com.campus.timetable.data.local.entity.*
import com.campus.timetable.domain.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val mapperJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun UserEntity.toDomain() = User(id, phone, nickname, isGuest, syncVersion, createdAt, updatedAt)
fun User.toEntity() = UserEntity(id, phone, nickname, isGuest, syncVersion, createdAt, updatedAt)

fun SemesterEntity.toDomain() = Semester(
    id, userId, name, startDate, totalWeeks, periodsPerDay, isCurrent, syncVersion, createdAt, updatedAt, isDeleted
)
fun Semester.toEntity() = SemesterEntity(
    id, userId, name, startDate, totalWeeks, periodsPerDay, isCurrent, syncVersion, createdAt, updatedAt, isDeleted
)

fun ClassPeriodEntity.toDomain() = ClassPeriod(id, semesterId, dayOfWeek, periodNumber, startTime, endTime, updatedAt)
fun ClassPeriod.toEntity() = ClassPeriodEntity(id, semesterId, dayOfWeek, periodNumber, startTime, endTime, updatedAt)

fun BellScheduleTemplateEntity.toDomain() = BellScheduleTemplate(
    id = id,
    name = name,
    category = runCatching { BellTemplateCategory.valueOf(category) }.getOrDefault(BellTemplateCategory.CUSTOM),
    rule = runCatching { mapperJson.decodeFromString<BellScheduleRule>(ruleJson) }.getOrDefault(BellScheduleRule()),
    isPreset = isPreset,
    updatedAt = updatedAt
)
fun BellScheduleTemplate.toEntity() = BellScheduleTemplateEntity(
    id = id,
    name = name,
    category = category.name,
    ruleJson = mapperJson.encodeToString(rule),
    isPreset = isPreset,
    updatedAt = updatedAt
)

fun CourseEntity.toDomain() = Course(
    id = id,
    semesterId = semesterId,
    name = name,
    teacher = teacher,
    location = location,
    courseType = runCatching { CourseType.valueOf(courseType) }.getOrDefault(CourseType.NORMAL),
    colorHex = colorHex,
    credit = credit,
    note = note,
    isAutoDetectedPe = isAutoDetectedPe,
    syncVersion = syncVersion,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted
)
fun Course.toEntity() = CourseEntity(
    id, semesterId, name, teacher, location, courseType.name, colorHex, credit, note, isAutoDetectedPe, syncVersion, createdAt, updatedAt, isDeleted
)

fun CourseScheduleEntity.toDomain() = CourseSchedule(
    id = id,
    courseId = courseId,
    dayOfWeek = dayOfWeek,
    startPeriod = startPeriod,
    endPeriod = endPeriod,
    weekRuleType = runCatching { WeekRuleType.valueOf(weekRuleType) }.getOrDefault(WeekRuleType.ALL),
    startWeek = startWeek,
    endWeek = endWeek,
    customWeeks = runCatching { mapperJson.decodeFromString<List<Int>>(customWeeksJson) }.getOrDefault(emptyList()),
    teacherOverride = teacherOverride,
    locationOverride = locationOverride,
    updatedAt = updatedAt,
    isDeleted = isDeleted
)
fun CourseSchedule.toEntity() = CourseScheduleEntity(
    id = id,
    courseId = courseId,
    dayOfWeek = dayOfWeek,
    startPeriod = startPeriod,
    endPeriod = endPeriod,
    weekRuleType = weekRuleType.name,
    startWeek = startWeek,
    endWeek = endWeek,
    customWeeksJson = mapperJson.encodeToString(customWeeks),
    teacherOverride = teacherOverride,
    locationOverride = locationOverride,
    updatedAt = updatedAt,
    isDeleted = isDeleted
)

fun CourseExceptionEntity.toDomain() = CourseException(
    id = id,
    courseId = courseId,
    scheduleId = scheduleId,
    exceptionType = runCatching { CourseExceptionType.valueOf(exceptionType) }.getOrDefault(CourseExceptionType.MODIFY_LOCATION),
    originalDate = originalDate,
    originalWeek = originalWeek,
    targetDate = targetDate,
    targetDayOfWeek = targetDayOfWeek,
    targetStartPeriod = targetStartPeriod,
    targetEndPeriod = targetEndPeriod,
    newLocation = newLocation,
    newTeacher = newTeacher,
    reason = reason,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted
)
fun CourseException.toEntity() = CourseExceptionEntity(
    id, courseId, scheduleId, exceptionType.name, originalDate, originalWeek,
    targetDate, targetDayOfWeek, targetStartPeriod, targetEndPeriod,
    newLocation, newTeacher, reason, createdAt, updatedAt, isDeleted
)

fun HolidayEntity.toDomain() = Holiday(
    id = id,
    year = year,
    date = date,
    endDate = endDate,
    totalDays = totalDays,
    name = name,
    holidayType = runCatching { HolidayType.valueOf(holidayType) }.getOrDefault(HolidayType.LEGAL_HOLIDAY),
    isOffDay = isOffDay,
    source = source,
    version = version,
    reminderDate = reminderDate,
    updatedAt = updatedAt
)
fun Holiday.toEntity() = HolidayEntity(
    id, year, date, endDate, totalDays, name, holidayType.name, isOffDay, source, version, reminderDate, updatedAt
)

fun SchoolCalendarOverrideEntity.toDomain() = SchoolCalendarOverride(
    id = id,
    semesterId = semesterId,
    date = date,
    overrideType = runCatching { CalendarOverrideType.valueOf(overrideType) }.getOrDefault(CalendarOverrideType.HOLIDAY),
    mappedDayOfWeek = mappedDayOfWeek,
    mappedWeekNumber = mappedWeekNumber,
    title = title,
    note = note,
    enableAlarmOverride = enableAlarmOverride,
    updatedAt = updatedAt,
    isDeleted = isDeleted
)
fun SchoolCalendarOverride.toEntity() = SchoolCalendarOverrideEntity(
    id, semesterId, date, overrideType.name, mappedDayOfWeek, mappedWeekNumber, title, note, enableAlarmOverride, updatedAt, isDeleted
)

fun AlarmSettingEntity.toDomain() = AlarmSetting(
    id = id,
    scopeType = runCatching { AlarmScopeType.valueOf(scopeType) }.getOrDefault(AlarmScopeType.GLOBAL),
    courseId = courseId,
    enabled = enabled,
    mode = runCatching { AlarmMode.valueOf(mode) }.getOrDefault(AlarmMode.ALARM_CLOCK),
    advanceMinutes = advanceMinutes,
    enableClassEndReminder = enableClassEndReminder,
    ringtoneUri = ringtoneUri,
    vibrate = vibrate,
    muteDate = muteDate,
    updatedAt = updatedAt
)
fun AlarmSetting.toEntity() = AlarmSettingEntity(
    id, scopeType.name, courseId, enabled, mode.name, advanceMinutes, enableClassEndReminder,
    ringtoneUri, vibrate, muteDate, updatedAt
)

fun WeatherSettingEntity.toDomain() = WeatherSetting(
    id = id,
    provider = when (provider) {
        // 旧版本保存过已下线的「手机系统天气」选项，升级后统一回落到 Open-Meteo
        "SYSTEM_VENDOR" -> WeatherProviderType.OPEN_METEO
        else -> runCatching { WeatherProviderType.valueOf(provider) }.getOrDefault(WeatherProviderType.OPEN_METEO)
    },
    locationMode = runCatching { LocationMode.valueOf(locationMode) }.getOrDefault(LocationMode.MANUAL),
    cityName = cityName,
    cityId = cityId,
    latitude = latitude,
    longitude = longitude,
    enablePeWeatherReminder = enablePeWeatherReminder,
    prevDayNotifyHour = prevDayNotifyHour,
    prevDayNotifyMinute = prevDayNotifyMinute,
    sameDayAdvanceMinutes = sameDayAdvanceMinutes,
    highTempThresholdC = highTempThresholdC,
    lowTempThresholdC = lowTempThresholdC,
    aqiThreshold = aqiThreshold,
    remindRain = remindRain,
    remindSnow = remindSnow,
    remindThunder = remindThunder,
    lastWeatherJson = lastWeatherJson,
    lastUpdatedAt = lastUpdatedAt,
    updatedAt = updatedAt
)
fun WeatherSetting.toEntity() = WeatherSettingEntity(
    id, provider.name, locationMode.name, cityName, cityId, latitude, longitude,
    enablePeWeatherReminder, prevDayNotifyHour, prevDayNotifyMinute, sameDayAdvanceMinutes,
    highTempThresholdC, lowTempThresholdC, aqiThreshold, remindRain, remindSnow, remindThunder,
    lastWeatherJson, lastUpdatedAt, updatedAt
)

fun SchoolEntity.toDomain() = School(
    id = id,
    code = code,
    name = name,
    shortName = shortName,
    aliases = aliases,
    pinyin = pinyin,
    pinyinInitials = pinyinInitials,
    province = province,
    city = city,
    schoolType = schoolType,
    schoolLevel = runCatching { SchoolLevel.valueOf(schoolLevel) }.getOrDefault(SchoolLevel.UNDERGRADUATE),
    campuses = campuses,
    systemType = runCatching { EduSystemType.valueOf(systemType) }.getOrDefault(EduSystemType.UNKNOWN),
    systemVersion = systemVersion,
    adapterId = adapterId,
    portalUrl = portalUrl,
    supportStatus = runCatching { SchoolSupportStatus.valueOf(supportStatus) }.getOrDefault(SchoolSupportStatus.UNSUPPORTED),
    verified = verified,
    lastVerifiedAt = lastVerifiedAt,
    statusNote = statusNote,
    updatedAt = updatedAt
)
fun School.toEntity() = SchoolEntity(
    id, code, name, shortName, aliases, pinyin, pinyinInitials, province, city, schoolType,
    schoolLevel.name, campuses, systemType.name, systemVersion, adapterId, portalUrl,
    supportStatus.name, verified, lastVerifiedAt, statusNote, updatedAt
)

fun SchoolAdaptationRequestEntity.toDomain() = SchoolAdaptationRequest(
    id = id,
    schoolName = schoolName,
    eduSystemType = runCatching { EduSystemType.valueOf(eduSystemType) }.getOrDefault(EduSystemType.UNKNOWN),
    portalUrl = portalUrl,
    pageSummary = pageSummary,
    userAuthorized = userAuthorized,
    status = status,
    createdAt = createdAt
)
fun SchoolAdaptationRequest.toEntity() = SchoolAdaptationRequestEntity(
    id, schoolName, eduSystemType.name, portalUrl, pageSummary, userAuthorized, status, createdAt
)

fun ImportHistoryEntity.toDomain() = ImportHistory(
    id = id,
    semesterId = semesterId,
    sourceType = runCatching { ImportSourceType.valueOf(sourceType) }.getOrDefault(ImportSourceType.EXCEL),
    sourceName = sourceName,
    schoolCode = schoolCode,
    totalParsed = totalParsed,
    importedCount = importedCount,
    conflictCount = conflictCount,
    status = runCatching { ImportStatus.valueOf(status) }.getOrDefault(ImportStatus.SUCCESS),
    errorMessage = errorMessage,
    createdAt = createdAt
)
fun ImportHistory.toEntity() = ImportHistoryEntity(
    id, semesterId, sourceType.name, sourceName, schoolCode, totalParsed, importedCount, conflictCount, status.name, errorMessage, createdAt
)

fun SyncRecordEntity.toDomain() = SyncRecord(
    id = id,
    userId = userId,
    deviceId = deviceId,
    direction = runCatching { SyncDirection.valueOf(direction) }.getOrDefault(SyncDirection.PUSH),
    status = runCatching { SyncStatus.valueOf(status) }.getOrDefault(SyncStatus.SUCCESS),
    localVersion = localVersion,
    remoteVersion = remoteVersion,
    syncedEntitiesCount = syncedEntitiesCount,
    conflictSummary = conflictSummary,
    errorMessage = errorMessage,
    syncedAt = syncedAt
)
fun SyncRecord.toEntity() = SyncRecordEntity(
    id, userId, deviceId, direction.name, status.name, localVersion, remoteVersion, syncedEntitiesCount, conflictSummary, errorMessage, syncedAt
)
