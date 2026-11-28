package com.campus.timetable.core.clock

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一时钟抽象，便于业务模块获取当前日期时间，并在单元测试中使用可控模拟日期验证课表、闹钟、天气与节假日逻辑。
 */
interface AppClock {
    fun zoneId(): ZoneId
    fun instant(): Instant
    fun today(): LocalDate = instant().atZone(zoneId()).toLocalDate()
    fun now(): LocalDateTime = instant().atZone(zoneId()).toLocalDateTime()
    fun currentTime(): LocalTime = now().toLocalTime()
    fun currentTimeMillis(): Long = instant().toEpochMilli()
}

@Singleton
class SystemAppClock @Inject constructor() : AppClock {
    override fun zoneId(): ZoneId = ZoneId.systemDefault()
    override fun instant(): Instant = Instant.now()
}
