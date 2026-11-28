package com.campus.timetable.domain.engine

import com.campus.timetable.domain.model.ParsedCourseCandidate
import com.campus.timetable.domain.model.WeekRuleType

/**
 * 面向全国高校的通用节次规整与连堂合并引擎（不针对任何单一高校硬编码）：
 * 1. 自动识别「大节奇数占位制」课表（如部分高校 EAMS / 强智 / 正方 / Excel 将 1-2 节仅记为第 1 节、
 *    3-4 节仅记为第 3 节、5-6 节仅记为第 5 节，导致全表偶数节全部留空）；
 * 2. 开启大节展开时，将奇数单节 (1-1, 3-3, 5-5...) 还原为连续两小节 (1-2, 3-4, 5-6...)；
 * 3. 对同星期、同课程、同教师、同教室、同周次规则的相邻/重叠节次自动合并（如周四 1-2 + 3-4 -> 1-4 四节连堂）。
 */
object TimetablePeriodNormalizer {

    /**
     * 判断解析出的一批课程是否属于「每大节仅占奇数起始小节」的排课模式：
     * - 至少 3 条候选课程；
     * - 没有任何课程覆盖偶数节（p % 2 == 0）；
     * - 绝大多数课程（>= 90%）表现为奇数单节（start == end && start % 2 == 1）。
     */
    fun detectOddSlotBigPeriodPattern(candidates: List<ParsedCourseCandidate>): Boolean {
        if (candidates.size < 3) return false
        val hasAnyEvenCoverage = candidates.any { c ->
            (c.startPeriod..c.endPeriod).any { p -> p % 2 == 0 }
        }
        if (hasAnyEvenCoverage) return false
        val oddSingleCount = candidates.count { c ->
            c.startPeriod == c.endPeriod && c.startPeriod % 2 == 1 && c.startPeriod < 20
        }
        return oddSingleCount * 10 >= candidates.size * 9
    }

    /**
     * 规整节次与合并同课连堂块。
     * @param expandBigPeriods 为 true 时将所有「奇数单节 (1-1, 3-3...)」展开为两小节 (1-2, 3-4...)。
     */
    fun normalize(
        candidates: List<ParsedCourseCandidate>,
        expandBigPeriods: Boolean
    ): List<ParsedCourseCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val expanded = if (expandBigPeriods) {
            candidates.map { c ->
                if (c.startPeriod == c.endPeriod && c.startPeriod % 2 == 1 && c.startPeriod < 20) {
                    c.copy(endPeriod = c.startPeriod + 1)
                } else {
                    c
                }
            }
        } else {
            candidates
        }

        val grouped = expanded.groupBy { c ->
            MergeGroupKey(
                dayOfWeek = c.dayOfWeek,
                name = c.name.trim(),
                teacher = c.teacher.trim(),
                location = c.location.trim(),
                weekRuleType = c.weekRuleType,
                startWeek = c.startWeek,
                endWeek = c.endWeek,
                customWeeks = c.customWeeks.distinct().sorted()
            )
        }

        val merged = mutableListOf<ParsedCourseCandidate>()
        for ((_, groupItems) in grouped) {
            val ordered = groupItems.sortedWith(
                compareBy<ParsedCourseCandidate> { it.startPeriod }.thenBy { it.endPeriod }
            )
            var current = ordered.first()
            for (next in ordered.drop(1)) {
                if (next.startPeriod <= current.endPeriod + 1) {
                    val combinedStart = minOf(current.startPeriod, next.startPeriod)
                    val combinedEnd = maxOf(current.endPeriod, next.endPeriod)
                    val warnings = listOfNotNull(current.reviewWarning, next.reviewWarning)
                        .flatMap { it.split("；") }
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                    current = current.copy(
                        startPeriod = combinedStart,
                        endPeriod = combinedEnd,
                        needsManualReview = current.needsManualReview || next.needsManualReview,
                        reviewWarning = warnings.joinToString("；").ifBlank { null }
                    )
                } else {
                    merged += current
                    current = next
                }
            }
            merged += current
        }

        return merged.sortedWith(
            compareBy<ParsedCourseCandidate> { it.dayOfWeek }
                .thenBy { it.startPeriod }
                .thenBy { it.endPeriod }
                .thenBy { it.name }
        )
    }

    private data class MergeGroupKey(
        val dayOfWeek: Int,
        val name: String,
        val teacher: String,
        val location: String,
        val weekRuleType: WeekRuleType,
        val startWeek: Int,
        val endWeek: Int,
        val customWeeks: List<Int>
    )
}
