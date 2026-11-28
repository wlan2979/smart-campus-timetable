package com.campus.timetable.domain.engine

import com.campus.timetable.domain.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 统一作息时间规则自动计算引擎。
 * 支持：自由单节时长、单节差异时长、默认小课间、自定义每 N 节大课间、自定义任意节间休息、
 * 午休时长或固定下午开始时间、晚间休息或固定晚间开始时间、以及周日无课（totalPeriods=0）。
 */
object BellScheduleRuleEngine {

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val ruleJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeRuleJson(rule: BellScheduleRule): String = ruleJson.encodeToString(rule)

    fun decodeRuleJson(raw: String?): BellScheduleRule? {
        if (raw.isNullOrBlank()) return null
        return runCatching { ruleJson.decodeFromString<BellScheduleRule>(raw) }.getOrNull()
    }

    /** 计算标准一大节（两节连续小课 + 之间 1 次小课间）的总时长（分钟），如 45×2 + 5 = 95 分钟。 */
    fun bigPeriodDurationMinutes(rule: BellScheduleRule): Int =
        rule.defaultPeriodDurationMinutes * 2 + rule.defaultSmallBreakMinutes

    /**
     * 当尚无显式持久化规则时，从已保存的 ClassPeriod 列表反推出尽量一致的 BellScheduleRule，
     * 避免重新进入编辑器时上方输入框与下方已保存节次时间脱节，并确保按此规则可 100% 精确还原各节时间。
     */
    fun inferRuleFromPeriods(periods: List<ClassPeriod>, fallbackPeriodsPerDay: Int = 12): BellScheduleRule {
        val sorted = periods.distinctBy { it.periodNumber }.sortedBy { it.periodNumber }
        if (sorted.isEmpty()) return BellScheduleRule(totalPeriods = fallbackPeriodsPerDay)
        val first = sorted.first()
        val durations = sorted.map { p ->
            p.periodNumber to Duration.between(p.parsedStartTime(), p.parsedEndTime())
                .toMinutes().toInt().coerceIn(10, 180)
        }
        val defaultDuration = durations
            .groupingBy { it.second }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key == durations.first().second })
            ?.key ?: durations.first().second

        val gaps = sorted.zipWithNext().map { (a, b) ->
            Triple(
                a.periodNumber,
                a.parsedEndTime(),
                Duration.between(a.parsedEndTime(), b.parsedStartTime()).toMinutes().toInt().coerceAtLeast(0)
            )
        }

        // 识别上午结束节次（优先 11:00..13:45 之间结束且后接 >=50 分钟午休）
        val morningEndPeriod = gaps.firstOrNull { (_, endT, gap) ->
            gap >= 50 && !endT.isBefore(LocalTime.of(11, 0)) && !endT.isAfter(LocalTime.of(13, 45))
        }?.first ?: sorted.firstOrNull { it.periodNumber == 4 }?.periodNumber ?: 4

        // 识别下午结束节次（在午休之后，16:30..18:45 之间结束且后接 >=40 分钟晚间休息）
        val afternoonEndPeriod = gaps.firstOrNull { (p, endT, gap) ->
            p > morningEndPeriod && gap >= 40 && !endT.isBefore(LocalTime.of(16, 30)) && !endT.isAfter(LocalTime.of(18, 45))
        }?.first ?: sorted.firstOrNull { it.periodNumber == 8 }?.periodNumber ?: 8

        val lunchGap = gaps.firstOrNull { it.first == morningEndPeriod }?.third ?: 140
        val eveningGap = gaps.firstOrNull { it.first == afternoonEndPeriod }?.third ?: 80

        val regularGaps = gaps
            .filter { (p, _, _) -> p != morningEndPeriod && p != afternoonEndPeriod }
            .map { it.third }
        val smallBreakCandidates = regularGaps.filter { it in 1..19 }
        val smallBreak = smallBreakCandidates
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }?.key
            ?: regularGaps.filter { it in 0..19 }.minOrNull()
            ?: 10

        val bigBreakCandidates = regularGaps.filter { it in 20..45 }
        val bigBreak = bigBreakCandidates
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }?.key ?: 20

        val afternoonPeriod = sorted.firstOrNull { it.periodNumber == morningEndPeriod + 1 }
        val eveningPeriod = sorted.firstOrNull { it.periodNumber == afternoonEndPeriod + 1 }
        val afternoonStart = afternoonPeriod?.startTime ?: "14:00"
        val eveningStart = eveningPeriod?.startTime ?: "19:00"

        val customDurations = durations
            .filter { (_, d) -> d != defaultDuration }
            .toMap()

        val baseRule = BellScheduleRule(
            firstClassStartTime = first.startTime,
            defaultPeriodDurationMinutes = defaultDuration,
            defaultSmallBreakMinutes = smallBreak,
            bigBreakMinutes = bigBreak,
            bigBreakEveryNPeriods = 2,
            morningEndPeriod = morningEndPeriod,
            lunchBreakMinutes = lunchGap,
            useFixedAfternoonStart = afternoonPeriod != null,
            afternoonStartTime = afternoonStart,
            afternoonEndPeriod = afternoonEndPeriod,
            eveningBreakMinutes = eveningGap,
            useFixedEveningStart = eveningPeriod != null,
            eveningStartTime = eveningStart,
            totalPeriods = sorted.maxOf { it.periodNumber }.coerceIn(1, 20),
            customPeriodDurations = customDurations
        )

        // 对照试算结果：若任意相邻节次间存在差异休息时间（如非固定周期大课间或缺失节次），精准写入 customBreaksAfterPeriod
        val simulated = generatePeriods(first.semesterId.ifBlank { "infer" }, 0, baseRule)
            .associateBy { it.periodNumber }
        val customBreaks = mutableMapOf<Int, Int>()
        for ((prevP, _, actualGap) in gaps) {
            val simPrev = simulated[prevP]
            val simNext = simulated[prevP + 1]
            val simGap = if (simPrev != null && simNext != null) {
                Duration.between(simPrev.parsedEndTime(), simNext.parsedStartTime()).toMinutes().toInt()
            } else -1
            if (simGap != actualGap) {
                customBreaks[prevP] = actualGap
            }
        }
        return baseRule.copy(customBreaksAfterPeriod = customBreaks)
    }

    data class ValidationResult(val isValid: Boolean, val errorMessage: String? = null)

    fun validateRule(rule: BellScheduleRule): ValidationResult {
        if (rule.totalPeriods !in 0..20) {
            return ValidationResult(false, "每天课程节数需在 0 至 20 节之间")
        }
        if (rule.totalPeriods == 0) return ValidationResult(true)
        if (runCatching { LocalTime.parse(rule.firstClassStartTime) }.isFailure) {
            return ValidationResult(false, "首节开始时间格式无效，请使用 HH:mm（如 08:00）")
        }
        if (rule.useFixedAfternoonStart && runCatching { LocalTime.parse(rule.afternoonStartTime) }.isFailure) {
            return ValidationResult(false, "下午开始时间格式无效，请使用 HH:mm（如 14:00）")
        }
        if (rule.useFixedEveningStart && runCatching { LocalTime.parse(rule.eveningStartTime) }.isFailure) {
            return ValidationResult(false, "晚间开始时间格式无效，请使用 HH:mm（如 19:00）")
        }
        if (rule.defaultPeriodDurationMinutes !in 10..180) {
            return ValidationResult(false, "每节课时长需在 10 至 180 分钟之间")
        }
        if (rule.defaultSmallBreakMinutes < 0 || rule.bigBreakMinutes < 0 || rule.lunchBreakMinutes < 0 || rule.eveningBreakMinutes < 0) {
            return ValidationResult(false, "课间及休息时长不能为负数")
        }
        return ValidationResult(true)
    }

    /**
     * 根据用户自定义作息规则实时推算整天各节次起止时间。
     */
    fun generatePeriods(
        semesterId: String,
        dayOfWeek: Int,
        rule: BellScheduleRule,
        updatedAt: Long = System.currentTimeMillis()
    ): List<ClassPeriod> {
        if (rule.totalPeriods <= 0) return emptyList()

        val firstStart = runCatching { LocalTime.parse(rule.firstClassStartTime) }.getOrElse { LocalTime.of(8, 0) }
        val fixedAfternoon = runCatching { LocalTime.parse(rule.afternoonStartTime) }.getOrElse { LocalTime.of(14, 0) }
        val fixedEvening = runCatching { LocalTime.parse(rule.eveningStartTime) }.getOrElse { LocalTime.of(19, 0) }

        val result = mutableListOf<ClassPeriod>()
        var prevEnd: LocalTime? = null

        for (p in 1..rule.totalPeriods) {
            val start: LocalTime = if (p == 1 || prevEnd == null) {
                firstStart
            } else {
                val prevP = p - 1
                val customGap = rule.customBreaksAfterPeriod[prevP]
                when {
                    customGap != null -> prevEnd.plusMinutes(customGap.coerceAtLeast(0).toLong())
                    prevP == rule.morningEndPeriod -> {
                        if (rule.useFixedAfternoonStart) {
                            if (fixedAfternoon.isAfter(prevEnd)) fixedAfternoon else prevEnd.plusMinutes(rule.lunchBreakMinutes.coerceAtLeast(5).toLong())
                        } else {
                            prevEnd.plusMinutes(rule.lunchBreakMinutes.coerceAtLeast(0).toLong())
                        }
                    }
                    prevP == rule.afternoonEndPeriod -> {
                        if (rule.useFixedEveningStart) {
                            if (fixedEvening.isAfter(prevEnd)) fixedEvening else prevEnd.plusMinutes(rule.eveningBreakMinutes.coerceAtLeast(5).toLong())
                        } else {
                            prevEnd.plusMinutes(rule.eveningBreakMinutes.coerceAtLeast(0).toLong())
                        }
                    }
                    rule.bigBreakEveryNPeriods > 0 && prevP % rule.bigBreakEveryNPeriods == 0 -> {
                        prevEnd.plusMinutes(rule.bigBreakMinutes.coerceAtLeast(0).toLong())
                    }
                    else -> {
                        prevEnd.plusMinutes(rule.defaultSmallBreakMinutes.coerceAtLeast(0).toLong())
                    }
                }
            }

            val duration = (rule.customPeriodDurations[p] ?: rule.defaultPeriodDurationMinutes).coerceIn(10, 180)
            val end = start.plusMinutes(duration.toLong())
            result += ClassPeriod(
                id = "${semesterId}_d${dayOfWeek}_p${p}",
                semesterId = semesterId,
                dayOfWeek = dayOfWeek,
                periodNumber = p,
                startTime = start.format(timeFmt),
                endTime = end.format(timeFmt),
                updatedAt = updatedAt
            )
            prevEnd = end
        }
        return result
    }

    /**
     * 内置五套可编辑起始模板（含一大节95分紧凑型、标准型、夏季、冬季、周末），用户可自由修改或另存自定义模板。
     */
    fun defaultTemplates(): List<BellScheduleTemplate> = listOf(
        BellScheduleTemplate(
            id = "tpl_compact_95",
            name = "紧凑作息（45分/小课间5分/大课间30分 · 一大节95分）",
            category = BellTemplateCategory.NORMAL,
            rule = BellScheduleRule(
                firstClassStartTime = "08:00",
                defaultPeriodDurationMinutes = 45,
                defaultSmallBreakMinutes = 5,
                bigBreakMinutes = 30,
                bigBreakEveryNPeriods = 2,
                morningEndPeriod = 4,
                lunchBreakMinutes = 140,
                useFixedAfternoonStart = true,
                afternoonStartTime = "14:00",
                afternoonEndPeriod = 8,
                eveningBreakMinutes = 60,
                useFixedEveningStart = true,
                eveningStartTime = "18:40",
                totalPeriods = 12
            ),
            isPreset = true
        ),
        BellScheduleTemplate(
            id = "tpl_normal",
            name = "标准作息（45分/小课间10分/大课间20分 · 一大节100分）",
            category = BellTemplateCategory.NORMAL,
            rule = BellScheduleRule(
                firstClassStartTime = "08:00",
                defaultPeriodDurationMinutes = 45,
                defaultSmallBreakMinutes = 10,
                bigBreakMinutes = 20,
                bigBreakEveryNPeriods = 2,
                morningEndPeriod = 4,
                lunchBreakMinutes = 140,
                useFixedAfternoonStart = true,
                afternoonStartTime = "14:00",
                afternoonEndPeriod = 8,
                eveningBreakMinutes = 80,
                useFixedEveningStart = true,
                eveningStartTime = "19:00",
                totalPeriods = 12
            ),
            isPreset = true
        ),
        BellScheduleTemplate(
            id = "tpl_summer",
            name = "夏季作息（下午 14:30 / 晚间 19:30）",
            category = BellTemplateCategory.SUMMER,
            rule = BellScheduleRule(
                firstClassStartTime = "08:00",
                defaultPeriodDurationMinutes = 45,
                defaultSmallBreakMinutes = 10,
                bigBreakMinutes = 20,
                bigBreakEveryNPeriods = 2,
                morningEndPeriod = 4,
                lunchBreakMinutes = 170,
                useFixedAfternoonStart = true,
                afternoonStartTime = "14:30",
                afternoonEndPeriod = 8,
                eveningBreakMinutes = 80,
                useFixedEveningStart = true,
                eveningStartTime = "19:30",
                totalPeriods = 12
            ),
            isPreset = true
        ),
        BellScheduleTemplate(
            id = "tpl_winter",
            name = "冬季作息（08:20首节 / 下午 13:30 / 晚间 18:30）",
            category = BellTemplateCategory.WINTER,
            rule = BellScheduleRule(
                firstClassStartTime = "08:20",
                defaultPeriodDurationMinutes = 45,
                defaultSmallBreakMinutes = 10,
                bigBreakMinutes = 20,
                bigBreakEveryNPeriods = 2,
                morningEndPeriod = 4,
                lunchBreakMinutes = 90,
                useFixedAfternoonStart = true,
                afternoonStartTime = "13:30",
                afternoonEndPeriod = 8,
                eveningBreakMinutes = 80,
                useFixedEveningStart = true,
                eveningStartTime = "18:30",
                totalPeriods = 12
            ),
            isPreset = true
        ),
        BellScheduleTemplate(
            id = "tpl_weekend",
            name = "周末作息（09:00首节 / 8节制）",
            category = BellTemplateCategory.WEEKEND,
            rule = BellScheduleRule(
                firstClassStartTime = "09:00",
                defaultPeriodDurationMinutes = 45,
                defaultSmallBreakMinutes = 10,
                bigBreakMinutes = 15,
                bigBreakEveryNPeriods = 2,
                morningEndPeriod = 4,
                lunchBreakMinutes = 90,
                useFixedAfternoonStart = true,
                afternoonStartTime = "14:00",
                afternoonEndPeriod = 8,
                useFixedEveningStart = false,
                totalPeriods = 8
            ),
            isPreset = true
        )
    )
}

/**
 * 法定节假日连续日期自动合并引擎。
 * 将国务院每日原始数据（如连续 7 天春节）自动聚合成单条完整假期区间事件，
 * 并确保每个连续假期只生成一条节前提醒，杜绝重复逐日拆条与重复提醒。
 */
object HolidayMergeEngine {

    data class RawHolidayDay(
        val date: String,
        val name: String,
        val isOffDay: Boolean
    )

    fun mergeDailyRecords(
        year: Int,
        rawDays: List<RawHolidayDay>,
        source: String,
        version: String,
        reminderAdvanceDays: Int = 1,
        updatedAt: Long = System.currentTimeMillis()
    ): List<Holiday> {
        if (rawDays.isEmpty()) return emptyList()

        val sorted = rawDays
            .distinctBy { it.date }
            .sortedBy { it.date }

        val merged = mutableListOf<Holiday>()
        var groupName = sorted.first().name.trim()
        var groupIsOffDay = sorted.first().isOffDay
        var groupStart = LocalDate.parse(sorted.first().date)
        var groupEnd = groupStart

        fun flushGroup() {
            val totalDays = (java.time.temporal.ChronoUnit.DAYS.between(groupStart, groupEnd).toInt() + 1).coerceAtLeast(1)
            val type = if (groupIsOffDay) HolidayType.LEGAL_HOLIDAY else HolidayType.STATUTORY_WORKDAY
            val displayName = if (groupIsOffDay && totalDays > 1 && !groupName.endsWith("假期")) {
                "${groupName}假期"
            } else {
                groupName
            }
            val reminderDate = if (groupIsOffDay && reminderAdvanceDays > 0) {
                groupStart.minusDays(reminderAdvanceDays.toLong()).toString()
            } else null

            merged += Holiday(
                id = "holiday_${year}_${groupStart}_${if (groupIsOffDay) "off" else "work"}",
                year = year,
                date = groupStart.toString(),
                endDate = groupEnd.toString(),
                totalDays = totalDays,
                name = displayName,
                holidayType = type,
                isOffDay = groupIsOffDay,
                source = source,
                version = version,
                reminderDate = reminderDate,
                updatedAt = updatedAt
            )
        }

        for (day in sorted.drop(1)) {
            val d = LocalDate.parse(day.date)
            val cleanName = day.name.trim()
            val isContiguousSameGroup = cleanName == groupName &&
                day.isOffDay == groupIsOffDay &&
                groupEnd.plusDays(1) == d

            if (isContiguousSameGroup) {
                groupEnd = d
            } else {
                flushGroup()
                groupName = cleanName
                groupIsOffDay = day.isOffDay
                groupStart = d
                groupEnd = d
            }
        }
        flushGroup()
        return merged
    }

    /**
     * 对已落库的单日记录执行幂等合并（例如 v1 数据库升级至 v2 或兼容旧缓存时）。
     */
    fun mergeExistingHolidays(holidays: List<Holiday>, reminderAdvanceDays: Int = 1): List<Holiday> {
        if (holidays.isEmpty()) return emptyList()
        val expanded = mutableListOf<RawHolidayDay>()
        for (h in holidays) {
            val baseName = h.name.removeSuffix("假期").trim()
            var cur = h.parsedStartDate()
            val end = h.parsedEndDate()
            while (!cur.isAfter(end)) {
                expanded += RawHolidayDay(cur.toString(), baseName, h.isOffDay)
                cur = cur.plusDays(1)
            }
        }
        val first = holidays.first()
        return mergeDailyRecords(
            year = first.year,
            rawDays = expanded,
            source = first.source,
            version = first.version,
            reminderAdvanceDays = reminderAdvanceDays,
            updatedAt = first.updatedAt
        )
    }
}
