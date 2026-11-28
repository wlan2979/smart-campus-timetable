package com.campus.timetable.domain

import com.campus.timetable.domain.engine.BellScheduleRuleEngine
import com.campus.timetable.domain.engine.ChineseCourseTextParser
import com.campus.timetable.domain.engine.HolidayMergeEngine
import com.campus.timetable.domain.engine.ScheduleResolutionEngine
import com.campus.timetable.domain.engine.TimetablePeriodNormalizer
import com.campus.timetable.domain.model.BellScheduleRule
import com.campus.timetable.domain.model.HolidayType
import com.campus.timetable.domain.model.ParsedCourseCandidate
import org.junit.Assert.*
import org.junit.Test

class BellAndHolidayEnginesTest {

    @Test
    fun `rule engine generates full day timetable with small break, big break, lunch and evening`() {
        val rule = BellScheduleRule(
            firstClassStartTime = "08:00",
            defaultPeriodDurationMinutes = 45,
            defaultSmallBreakMinutes = 10,
            bigBreakMinutes = 20,
            bigBreakEveryNPeriods = 2,
            morningEndPeriod = 4,
            lunchBreakMinutes = 90,
            useFixedAfternoonStart = true,
            afternoonStartTime = "14:00",
            afternoonEndPeriod = 8,
            eveningBreakMinutes = 60,
            useFixedEveningStart = true,
            eveningStartTime = "19:00",
            totalPeriods = 12
        )

        assertTrue(BellScheduleRuleEngine.validateRule(rule).isValid)

        val periods = BellScheduleRuleEngine.generatePeriods("sem-1", 1, rule)
        assertEquals(12, periods.size)
        assertTrue(periods.all { it.dayOfWeek == 1 })

        val byNo = periods.associateBy { it.periodNumber }
        // 第1、2节：小课间 10 分钟
        assertEquals("08:00", byNo.getValue(1).startTime)
        assertEquals("08:45", byNo.getValue(1).endTime)
        assertEquals("08:55", byNo.getValue(2).startTime)
        assertEquals("09:40", byNo.getValue(2).endTime)
        // 第2节后：大课间 20 分钟
        assertEquals("10:00", byNo.getValue(3).startTime)
        assertEquals("10:55", byNo.getValue(4).startTime)
        // 上午 4 节后：固定 14:00 下午开始
        assertEquals("14:00", byNo.getValue(5).startTime)
        assertEquals("14:45", byNo.getValue(5).endTime)
        // 下午 8 节后：固定 19:00 晚间开始
        assertEquals("19:00", byNo.getValue(9).startTime)
        assertEquals("22:40", byNo.getValue(12).endTime)
    }

    @Test
    fun `rule engine respects custom per-period duration and custom break`() {
        val rule = BellScheduleRule(
            firstClassStartTime = "09:00",
            defaultPeriodDurationMinutes = 40,
            defaultSmallBreakMinutes = 5,
            bigBreakMinutes = 0,
            bigBreakEveryNPeriods = 0,
            morningEndPeriod = 99,
            afternoonEndPeriod = 99,
            useFixedAfternoonStart = false,
            useFixedEveningStart = false,
            totalPeriods = 3,
            customPeriodDurations = mapOf(2 to 50),
            customBreaksAfterPeriod = mapOf(1 to 30)
        )

        val periods = BellScheduleRuleEngine.generatePeriods("sem-2", 6, rule)
        assertEquals(listOf("09:00", "10:10", "11:05"), periods.map { it.startTime })
        assertEquals(listOf("09:40", "11:00", "11:45"), periods.map { it.endTime })
    }

    @Test
    fun `rule engine supports a day without any class and rejects invalid input`() {
        val noClassDay = BellScheduleRule(totalPeriods = 0)
        assertTrue(BellScheduleRuleEngine.generatePeriods("sem-3", 7, noClassDay).isEmpty())

        val badTime = BellScheduleRule(firstClassStartTime = "8点")
        assertFalse(BellScheduleRuleEngine.validateRule(badTime).isValid)

        val badDuration = BellScheduleRule(defaultPeriodDurationMinutes = 5)
        assertFalse(BellScheduleRuleEngine.validateRule(badDuration).isValid)
    }

    @Test
    fun `holiday merge collapses a continuous vacation into one event with a single reminder`() {
        val cny = (5..11).map {
            HolidayMergeEngine.RawHolidayDay(
                date = "2027-02-%02d".format(it),
                name = "春节",
                isOffDay = true
            )
        }
        val makeUpWork = listOf(
            HolidayMergeEngine.RawHolidayDay("2027-02-13", "春节", false),
            HolidayMergeEngine.RawHolidayDay("2027-02-14", "春节", false)
        )

        val merged = HolidayMergeEngine.mergeDailyRecords(
            year = 2027,
            rawDays = cny + makeUpWork,
            source = "国务院办公厅",
            version = "test"
        )

        assertEquals(2, merged.size)

        val vacation = merged.first { it.isOffDay }
        assertEquals("2027-02-05", vacation.date)
        assertEquals("2027-02-11", vacation.endDate)
        assertEquals(7, vacation.totalDays)
        assertEquals("春节假期", vacation.name)
        assertEquals("2027-02-04", vacation.reminderDate)
        assertEquals(HolidayType.LEGAL_HOLIDAY, vacation.holidayType)

        val workdays = merged.first { !it.isOffDay }
        assertEquals(HolidayType.STATUTORY_WORKDAY, workdays.holidayType)
        assertEquals(2, workdays.totalDays)
        assertNull(workdays.reminderDate)
    }

    @Test
    fun `holiday merge keeps non-contiguous dates apart and is idempotent on existing records`() {
        val raw = listOf(
            HolidayMergeEngine.RawHolidayDay("2027-05-01", "劳动节", true),
            HolidayMergeEngine.RawHolidayDay("2027-05-02", "劳动节", true),
            HolidayMergeEngine.RawHolidayDay("2027-05-05", "劳动节", true)
        )
        val merged = HolidayMergeEngine.mergeDailyRecords(2027, raw, "国务院办公厅", "test")
        assertEquals(2, merged.size)

        // 对已合并记录再次合并，结果必须保持幂等，不会逐日拆条或重复
        val again = HolidayMergeEngine.mergeExistingHolidays(merged)
        assertEquals(merged.map { it.date to it.endDate }, again.map { it.date to it.endDate })
        assertEquals(merged.map { it.totalDays }, again.map { it.totalDays })
        assertEquals(1, again.count { it.isOffDay && it.totalDays == 2 })
    }

    @Test
    fun `rule engine encodes decodes and infers rule from saved periods`() {
        val original = BellScheduleRule(
            firstClassStartTime = "08:30",
            defaultPeriodDurationMinutes = 50,
            defaultSmallBreakMinutes = 10,
            bigBreakMinutes = 25,
            afternoonStartTime = "14:30",
            eveningStartTime = "19:30",
            totalPeriods = 10
        )
        val encoded = BellScheduleRuleEngine.encodeRuleJson(original)
        val decoded = BellScheduleRuleEngine.decodeRuleJson(encoded)
        assertEquals(original, decoded)

        val periods = BellScheduleRuleEngine.generatePeriods("sem-infer", 0, original)
        val inferred = BellScheduleRuleEngine.inferRuleFromPeriods(periods)
        assertEquals("08:30", inferred.firstClassStartTime)
        assertEquals(50, inferred.defaultPeriodDurationMinutes)
        assertEquals(10, inferred.defaultSmallBreakMinutes)
        assertEquals(25, inferred.bigBreakMinutes)
        assertEquals("14:30", inferred.afternoonStartTime)
        assertEquals("19:30", inferred.eveningStartTime)
        assertEquals(10, inferred.totalPeriods)
    }

    @Test
    fun `rule engine infers and round-trips compact 95-minute official bell schedule byte-for-byte`() {
        val compactPeriods = listOf(
            1 to ("08:00" to "08:45"),
            2 to ("08:50" to "09:35"),
            3 to ("10:05" to "10:50"),
            4 to ("10:55" to "11:40"),
            5 to ("14:00" to "14:45"),
            6 to ("14:50" to "15:35"),
            7 to ("16:05" to "16:50"),
            8 to ("16:55" to "17:40"),
            9 to ("18:40" to "19:25"),
            10 to ("19:30" to "20:15"),
            11 to ("20:25" to "21:10"),
            12 to ("21:15" to "22:00")
        ).map { (num, times) ->
            com.campus.timetable.domain.model.ClassPeriod(
                semesterId = "sem-compact",
                dayOfWeek = 0,
                periodNumber = num,
                startTime = times.first,
                endTime = times.second
            )
        }
        val inferred = BellScheduleRuleEngine.inferRuleFromPeriods(compactPeriods)
        assertEquals(5, inferred.defaultSmallBreakMinutes)
        assertEquals(30, inferred.bigBreakMinutes)
        assertEquals("14:00", inferred.afternoonStartTime)
        assertEquals("18:40", inferred.eveningStartTime)

        val roundTripped = BellScheduleRuleEngine.generatePeriods("sem-compact", 0, inferred)
        assertEquals(compactPeriods.map { it.startTime to it.endTime }, roundTripped.map { it.startTime to it.endTime })
    }

    @Test
    fun `big period normalizer expands odd single slots across generic schools and merges 4-period blocks`() {
        val oddBigPeriodRaw = listOf(
            ParsedCourseCandidate(name = "中国特色社会主义理论", location = "一教101", dayOfWeek = 1, startPeriod = 1, endPeriod = 1),
            ParsedCourseCandidate(name = "大学物理", location = "一教202", dayOfWeek = 1, startPeriod = 3, endPeriod = 3),
            ParsedCourseCandidate(name = "电工学", teacher = "王老师", location = "实验楼502", dayOfWeek = 4, startPeriod = 1, endPeriod = 1),
            ParsedCourseCandidate(name = "电工学", teacher = "王老师", location = "实验楼502", dayOfWeek = 4, startPeriod = 3, endPeriod = 3),
            ParsedCourseCandidate(name = "创业基础", teacher = "张老师", location = "综合楼116", dayOfWeek = 5, startPeriod = 7, endPeriod = 7)
        )
        assertTrue(TimetablePeriodNormalizer.detectOddSlotBigPeriodPattern(oddBigPeriodRaw))

        val normalized = TimetablePeriodNormalizer.normalize(oddBigPeriodRaw, expandBigPeriods = true)
        assertEquals(4, normalized.size)
        val mon1 = normalized.single { it.dayOfWeek == 1 && it.name == "中国特色社会主义理论" }
        assertEquals(1 to 2, mon1.startPeriod to mon1.endPeriod)
        val thu4 = normalized.single { it.dayOfWeek == 4 && it.name == "电工学" }
        assertEquals(1 to 4, thu4.startPeriod to thu4.endPeriod)
        val fri7 = normalized.single { it.dayOfWeek == 5 && it.name == "创业基础" }
        assertEquals(7 to 8, fri7.startPeriod to fri7.endPeriod)

        // 含偶数节的正常小节制课表不应误触发奇数大节展开，但相邻同课仍会合并
        val normalPeriodsRaw = listOf(
            ParsedCourseCandidate(name = "高等数学", dayOfWeek = 1, startPeriod = 1, endPeriod = 2),
            ParsedCourseCandidate(name = "线性代数", dayOfWeek = 2, startPeriod = 2, endPeriod = 2),
            ParsedCourseCandidate(name = "大学英语", dayOfWeek = 3, startPeriod = 3, endPeriod = 4)
        )
        assertFalse(TimetablePeriodNormalizer.detectOddSlotBigPeriodPattern(normalPeriodsRaw))
        val nonExpanded = TimetablePeriodNormalizer.normalize(normalPeriodsRaw, expandBigPeriods = false)
        assertEquals(2 to 2, nonExpanded.single { it.name == "线性代数" }.let { it.startPeriod to it.endPeriod })
    }

    @Test
    fun `big period header parsing and 95-minute compact rule calculation`() {
        assertEquals(1 to 2, ChineseCourseTextParser.parsePeriods("第一大节"))
        assertEquals(5 to 6, ChineseCourseTextParser.parsePeriods("第3大节"))

        val rule95 = BellScheduleRuleEngine.defaultTemplates().first { it.id == "tpl_compact_95" }.rule
        assertEquals(95, BellScheduleRuleEngine.bigPeriodDurationMinutes(rule95))
        val periods = BellScheduleRuleEngine.generatePeriods("s1", 0, rule95)
        val (start1, end2) = ScheduleResolutionEngine.resolvePeriodTimeRange(periods, 1, 1, 2)
        assertEquals("08:00" to "09:35", start1.toString() to end2.toString())
        assertEquals(95L, java.time.Duration.between(start1, end2).toMinutes())
    }
}
