package com.campus.timetable.domain.engine

import com.campus.timetable.domain.model.CourseType
import com.campus.timetable.domain.model.ParsedCourseCandidate
import com.campus.timetable.domain.model.WeekRuleType

/**
 * 面向中国高校教务系统与课表文件的通用中文排课文本解析引擎。
 * 供 Excel、PDF、OCR、网页解析及各学校教务适配器复用。
 */
object ChineseCourseTextParser {

    data class ParsedWeekSpec(
        val ruleType: WeekRuleType,
        val startWeek: Int,
        val endWeek: Int,
        val customWeeks: List<Int>
    )

    private val dayRegex = Regex("(?:星期|周)([一二三四五六日天1-7])")
    private val bigPeriodRegex = Regex("第?\\s*([1-9]|10|[一二三四五六七八九十])\\s*大节")
    private val periodRegex = Regex("第?\\s*(\\d{1,2})\\s*(?:[-~至、,]\\s*(\\d{1,2}))?\\s*小?节")
    private val zhSinglePeriodRegex = Regex("第?\\s*([一二三四五六七八九十]{1,3})\\s*(?:[-~至、,]\\s*([一二三四五六七八九十]{1,3}))?\\s*大?小?节")
    private val weekBlockRegex = Regex("第?([0-9,\\-~至、]+)\\s*周(?:\\s*[（(\\[]?([单双])周?[)）\\]]?)?")
    private val oddHintRegex = Regex("单周|\\(单\\)|（单）|\\[单]")
    private val evenHintRegex = Regex("双周|\\(双\\)|（双）|\\[双]")

    fun parseDayOfWeek(text: String): Int? {
        val m = dayRegex.find(text) ?: return null
        return when (m.groupValues[1]) {
            "一", "1" -> 1
            "二", "2" -> 2
            "三", "3" -> 3
            "四", "4" -> 4
            "五", "5" -> 5
            "六", "6" -> 6
            "日", "天", "7" -> 7
            else -> null
        }
    }

    private fun parseChineseSmallNumber(raw: String): Int? {
        return when (raw.trim()) {
            "一" -> 1
            "二", "两" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "七" -> 7
            "八" -> 8
            "九" -> 9
            "十" -> 10
            "十一" -> 11
            "十二" -> 12
            "十三" -> 13
            "十四" -> 14
            "十五" -> 15
            "十六" -> 16
            else -> null
        }
    }

    fun parsePeriods(text: String): Pair<Int, Int>? {
        // 优先识别明确「大节」标记（第 1 大节 -> 1..2 节，第 2 大节 -> 3..4 节，通用 2 小节/大节映射）
        val bigMatches = bigPeriodRegex.findAll(text).toList()
        if (bigMatches.isNotEmpty() && !Regex("\\d+\\s*[-~至]\\s*\\d+").containsMatchIn(text)) {
            val bigIndices = bigMatches.mapNotNull { m ->
                val token = m.groupValues[1]
                token.toIntOrNull() ?: parseChineseSmallNumber(token)
            }.filter { it in 1..10 }
            if (bigIndices.isNotEmpty()) {
                var start = bigIndices.first() * 2 - 1
                var end = bigIndices.first() * 2
                for (b in bigIndices.drop(1)) {
                    val ns = b * 2 - 1
                    val ne = b * 2
                    if (ns <= end + 1 && ne >= start) {
                        start = minOf(start, ns)
                        end = maxOf(end, ne)
                    }
                }
                return start to end
            }
        }
        val numMatches = periodRegex.findAll(text).toList()
        if (numMatches.isNotEmpty()) {
            // 兼容「第1节 第2节」「第1节\n第2节」「第1小节 第2小节」等相邻多节表头，避免只取第一个变成 1-1 节
            val first = numMatches.first()
            var start = first.groupValues[1].toIntOrNull() ?: return null
            var end = first.groupValues[2].toIntOrNull() ?: start
            if (start !in 1..20 || end !in start..20) return null
            for (next in numMatches.drop(1)) {
                val ns = next.groupValues[1].toIntOrNull() ?: continue
                val ne = next.groupValues[2].toIntOrNull() ?: ns
                if (ns in 1..20 && ne in ns..20 && ns <= end + 1 && ne >= start) {
                    start = minOf(start, ns)
                    end = maxOf(end, ne)
                }
            }
            return start to end
        }
        val zhMatches = zhSinglePeriodRegex.findAll(text).toList()
        if (zhMatches.isNotEmpty()) {
            val first = zhMatches.first()
            var start = parseChineseSmallNumber(first.groupValues[1]) ?: return null
            var end = first.groupValues[2].takeIf { it.isNotEmpty() }?.let(::parseChineseSmallNumber) ?: start
            if (start !in 1..20 || end !in start..20) return null
            for (next in zhMatches.drop(1)) {
                val ns = parseChineseSmallNumber(next.groupValues[1]) ?: continue
                val ne = next.groupValues[2].takeIf { it.isNotEmpty() }?.let(::parseChineseSmallNumber) ?: ns
                if (ns in 1..20 && ne in ns..20 && ns <= end + 1 && ne >= start) {
                    start = minOf(start, ns)
                    end = maxOf(end, ne)
                }
            }
            return start to end
        }
        return null
    }

    fun parseWeeks(text: String): ParsedWeekSpec? {
        val normalized = text.replace("，", ",").replace("～", "-").replace("—", "-")
        val matches = weekBlockRegex.findAll(normalized).toList()
        if (matches.isEmpty()) return null

        val allWeeks = sortedSetOf<Int>()
        var explicitOdd = oddHintRegex.containsMatchIn(normalized)
        var explicitEven = evenHintRegex.containsMatchIn(normalized)
        var simpleSingleRange: Pair<Int, Int>? = null

        for (match in matches) {
            val expr = match.groupValues[1]
            val parity = match.groupValues[2]
            if (parity == "单") explicitOdd = true
            if (parity == "双") explicitEven = true

            val segments = expr.split(",", "、").map { it.trim() }.filter { it.isNotEmpty() }
            if (matches.size == 1 && segments.size == 1 && (segments[0].contains("-") || segments[0].contains("~") || segments[0].contains("至"))) {
                val parts = segments[0].split("-", "~", "至").mapNotNull { it.trim().toIntOrNull() }
                if (parts.size == 2 && parts[0] in 1..30 && parts[1] in parts[0]..30) {
                    simpleSingleRange = parts[0] to parts[1]
                }
            }
            for (seg in segments) {
                val rangeParts = seg.split("-", "~", "至").mapNotNull { it.trim().toIntOrNull() }
                if (rangeParts.size == 2) {
                    val s = rangeParts[0].coerceIn(1, 30)
                    val e = rangeParts[1].coerceIn(s, 30)
                    for (w in s..e) {
                        if (parity == "单" && w % 2 == 0) continue
                        if (parity == "双" && w % 2 == 1) continue
                        allWeeks += w
                    }
                } else if (rangeParts.size == 1 && rangeParts[0] in 1..30) {
                    allWeeks += rangeParts[0]
                }
            }
        }

        if (allWeeks.isEmpty()) return null

        if (simpleSingleRange != null) {
            val (s, e) = simpleSingleRange
            return when {
                explicitOdd && !explicitEven -> ParsedWeekSpec(WeekRuleType.ODD, s, e, emptyList())
                explicitEven && !explicitOdd -> ParsedWeekSpec(WeekRuleType.EVEN, s, e, emptyList())
                else -> ParsedWeekSpec(WeekRuleType.ALL, s, e, emptyList())
            }
        }

        val list = allWeeks.toList()
        val minW = list.first()
        val maxW = list.last()
        val isExplicitEnumOnly = !normalized.contains("-") && !normalized.contains("~") && !normalized.contains("至")
        if (isExplicitEnumOnly && list.size >= 2) {
            return ParsedWeekSpec(WeekRuleType.CUSTOM, minW, maxW, list)
        }
        val isContiguousAll = list == (minW..maxW).toList()
        val isContiguousOdd = list == (minW..maxW).filter { it % 2 == 1 } && list.size >= 2
        val isContiguousEven = list == (minW..maxW).filter { it % 2 == 0 } && list.size >= 2

        return when {
            isContiguousAll && !explicitOdd && !explicitEven -> ParsedWeekSpec(WeekRuleType.ALL, minW, maxW, emptyList())
            isContiguousOdd -> ParsedWeekSpec(WeekRuleType.ODD, minW, maxW, emptyList())
            isContiguousEven -> ParsedWeekSpec(WeekRuleType.EVEN, minW, maxW, emptyList())
            else -> ParsedWeekSpec(WeekRuleType.CUSTOM, minW, maxW, list)
        }
    }

    /**
     * 解析树维 EAMS 课表单元格中不含「周」字的周次表达，例如：
     * - "1-16" / "1-16周"
     * - "单1-15" / "1-15单" / "Odd1-15" / "1-15Odd"
     * - "双2-16" / "2-16双" / "Even2-16" / "2-16Even"
     * - "1-8 10-16" / "3 5 7" / "1-5,7-12" / "5"
     */
    fun parseEamsWeekExpression(raw: String): ParsedWeekSpec? {
        val cleaned = raw
            .replace("，", ",")
            .replace("～", "-")
            .replace("—", "-")
            .replace("至", "-")
            .replace("周", "")
            .trim()
        if (cleaned.isEmpty()) return null

        val tokenRegex = Regex(
            pattern = "^(?:(单|双|odd|even)\\s*)?(\\d{1,2})(?:\\s*-\\s*(\\d{1,2}))?(?:\\s*[（(\\[]?(单|双|odd|even)[)）\\]]?)?$",
            option = RegexOption.IGNORE_CASE
        )
        val tokens = cleaned.split(Regex("[\\s,、;；]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val weeks = sortedSetOf<Int>()
        var singleRange: Pair<Int, Int>? = null
        var singleParity = ""
        for (tok in tokens) {
            val m = tokenRegex.matchEntire(tok) ?: return null
            val preParity = m.groupValues[1].lowercase()
            val start = m.groupValues[2].toIntOrNull() ?: return null
            val end = m.groupValues[3].toIntOrNull() ?: start
            val postParity = m.groupValues[4].lowercase()
            if (start !in 1..30 || end !in start..30) return null

            val parity = preParity.ifEmpty { postParity }
            if (tokens.size == 1 && m.groupValues[3].isNotEmpty()) {
                singleRange = start to end
                singleParity = parity
            }
            for (w in start..end) {
                if ((parity == "单" || parity == "odd") && w % 2 == 0) continue
                if ((parity == "双" || parity == "even") && w % 2 == 1) continue
                weeks += w
            }
        }
        if (weeks.isEmpty()) return null

        if (singleRange != null) {
            val (s, e) = singleRange
            return when (singleParity) {
                "单", "odd" -> ParsedWeekSpec(WeekRuleType.ODD, s, e, emptyList())
                "双", "even" -> ParsedWeekSpec(WeekRuleType.EVEN, s, e, emptyList())
                else -> ParsedWeekSpec(WeekRuleType.ALL, s, e, emptyList())
            }
        }
        return buildWeekSpecFromSortedWeeks(weeks.toList())
    }

    /** 将已排序去重的周次列表归约为 ALL / ODD / EVEN / CUSTOM。 */
    fun buildWeekSpecFromSortedWeeks(sortedWeeks: List<Int>): ParsedWeekSpec? {
        val list = sortedWeeks.filter { it in 1..30 }.distinct().sorted()
        if (list.isEmpty()) return null
        val minW = list.first()
        val maxW = list.last()
        if (list.size == 1) {
            return ParsedWeekSpec(WeekRuleType.ALL, minW, maxW, emptyList())
        }
        val isContiguousAll = list == (minW..maxW).toList()
        val isContiguousOdd = list == (minW..maxW).filter { it % 2 == 1 } && list.size >= 2
        val isContiguousEven = list == (minW..maxW).filter { it % 2 == 0 } && list.size >= 2
        return when {
            isContiguousAll -> ParsedWeekSpec(WeekRuleType.ALL, minW, maxW, emptyList())
            isContiguousOdd -> ParsedWeekSpec(WeekRuleType.ODD, minW, maxW, emptyList())
            isContiguousEven -> ParsedWeekSpec(WeekRuleType.EVEN, minW, maxW, emptyList())
            else -> ParsedWeekSpec(WeekRuleType.CUSTOM, minW, maxW, list)
        }
    }

    /**
     * 解析树维 EAMS 括号排课行，例如：
     * - `(1-16,教A201)` / `(单1-15,田径场)` / `(1-15单,教A101)` / `(1-4,6-12,教A201)` / `(13)` / `(8-12,综合楼 组1)`
     */
    fun parseEamsScheduleParenLine(line: String): Pair<ParsedWeekSpec, String>? {
        val t = line.trim()
        val hasParens = (t.startsWith("(") && t.endsWith(")")) ||
            (t.startsWith("（") && t.endsWith("）")) ||
            (t.startsWith("(") && t.endsWith("）")) ||
            (t.startsWith("（") && t.endsWith(")"))
        val stripped = if (hasParens) {
            t.substring(1, t.length - 1).trim()
        } else {
            t
        }
        if (stripped.isEmpty()) return null

        val parts = stripped.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        // 从左到右贪心收集所有合法周次段（兼容 "1-4,6-12,教A201" 与 "8,10,12,物理实验中心"）
        val weekSegments = mutableListOf<String>()
        var roomStartIndex = 0
        for ((idx, part) in parts.withIndex()) {
            val cleanedPart = part.replace(Regex("\\s+组\\S+$"), "").trim()
            if (parseEamsWeekExpression(cleanedPart) != null || parseWeeks(cleanedPart) != null) {
                weekSegments += cleanedPart
                roomStartIndex = idx + 1
            } else {
                break
            }
        }
        if (weekSegments.isEmpty()) return null

        val combinedWeekExpr = weekSegments.joinToString(",")
        val weekSpec = parseEamsWeekExpression(combinedWeekExpr)
            ?: parseWeeks(combinedWeekExpr)
            ?: return null

        val roomPart = if (roomStartIndex < parts.size) {
            parts.drop(roomStartIndex).joinToString(",")
                .replace(Regex("\\s+组\\S+$"), "")
                .trim()
                .let(::stripOuterParens)
        } else {
            ""
        }
        return weekSpec to roomPart
    }

    private fun stripOuterParens(text: String): String {
        var cur = text.trim()
        while (cur.length >= 2 && ((cur.startsWith("(") && cur.endsWith(")")) ||
                (cur.startsWith("（") && cur.endsWith("）")) ||
                (cur.startsWith("(") && cur.endsWith("）")) ||
                (cur.startsWith("（") && cur.endsWith(")")))
        ) {
            cur = cur.substring(1, cur.length - 1).trim()
        }
        return cur
    }

    /**
     * 解析网格单元格中的课程块（常见于正方/强智/树维 EAMS/Excel 网格课表），
     * 支持空行/横线分隔的多门课，也支持树维 EAMS 单 `<br>` 紧密换行排列的多门课或多段周次。
     */
    fun parseGridCell(
        cellText: String,
        dayOfWeek: Int,
        defaultStartPeriod: Int,
        defaultEndPeriod: Int
    ): List<ParsedCourseCandidate> {
        val cleaned = cellText.replace("\r\n", "\n").trim()
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == "无") return emptyList()

        // 按空行 / ---- 切分多门课，但纯周次、节次、教师、教室、以及 EAMS 括号排课行是上一门课的字段，
        // 绝不能单独拆成以 "(" 开头的假课程。
        val rawBlocks = mutableListOf<String>()
        for (block in cleaned.split(Regex("\\n\\s*\\n|-{3,}")).map { it.trim() }.filter { it.isNotEmpty() }) {
            val firstLine = block.lines().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            if (isMetaOnlyLine(firstLine)) {
                if (rawBlocks.isNotEmpty()) {
                    rawBlocks[rawBlocks.lastIndex] = rawBlocks.last() + "\n" + block
                }
            } else {
                rawBlocks += block
            }
        }

        // 二次拆分：同一 block 内部如果用单换行连续写着多门课程（上一门课已拿到周次/排课信息，后又出现新标题行），
        // 按课程边界拆成独立块，避免同格多门课被整格吞并只保留第一门。
        val courseBlocks = mutableListOf<List<String>>()
        for (block in rawBlocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            var currentLines = mutableListOf<String>()
            var hasScheduleMetaInCurrent = false

            for (line in lines) {
                val isEamsParen = parseEamsScheduleParenLine(line) != null
                val hasMeta = isEamsParen ||
                    weekBlockRegex.containsMatchIn(line) ||
                    periodRegex.containsMatchIn(line) ||
                    isMetaOnlyLine(line)

                val startsNewCourse = currentLines.isNotEmpty() &&
                    hasScheduleMetaInCurrent &&
                    !hasMeta &&
                    !isLikelyLocation(line) &&
                    line.length > 1

                if (startsNewCourse) {
                    courseBlocks += currentLines
                    currentLines = mutableListOf(line)
                    hasScheduleMetaInCurrent = false
                } else {
                    currentLines += line
                    if (isEamsParen || weekBlockRegex.containsMatchIn(line)) {
                        hasScheduleMetaInCurrent = true
                    }
                }
            }
            if (currentLines.isNotEmpty()) {
                courseBlocks += currentLines
            }
        }

        val candidates = mutableListOf<ParsedCourseCandidate>()
        for (lines in courseBlocks) {
            val block = lines.joinToString("\n")
            val rawTitle = lines.first()
            var courseName = rawTitle
                .replace(Regex("[（(]?必修[)）]?|[（(]?选修[)）]?"), "")
                .trim()
            if (courseName.isBlank()) continue

            var teacher = ""
            var fallbackLocation = ""

            // 兼容树维 EAMS 标题行格式 "高等数学A(10101) (张老师)"
            val trailingTeacher = Regex("""^(.+?)\s+[（(]([^()（）]{1,20})[)）]\s*$""").matchEntire(courseName)
            if (trailingTeacher != null) {
                val left = trailingTeacher.groupValues[1].trim()
                val inside = trailingTeacher.groupValues[2].trim()
                val isCode = inside.all { it.isDigit() || it == '.' || it == '-' || it == '_' }
                val looksLikeEdition = Regex("^[A-Za-z0-9上中下甲乙丙一二三四ⅠⅡⅢIV]+$").matches(inside)
                if (!isCode && !looksLikeEdition && left.isNotBlank()) {
                    courseName = left.replace(Regex("""\s*[（(][A-Za-z0-9._\-]{3,20}[)）]\s*$"""), "").trim().ifBlank { left }
                    teacher = inside
                }
            }

            val periodPair = parsePeriods(block) ?: (defaultStartPeriod to defaultEndPeriod)
            val eamsSchedules = mutableListOf<Pair<ParsedWeekSpec, String>>()

            for (line in lines.drop(1)) {
                val eamsParsed = parseEamsScheduleParenLine(line)
                if (eamsParsed != null) {
                    eamsSchedules += eamsParsed
                    continue
                }
                val hasWeek = weekBlockRegex.containsMatchIn(line)
                val hasPeriod = periodRegex.containsMatchIn(line)
                if (fallbackLocation.isEmpty() && isLikelyLocation(line)) {
                    fallbackLocation = cleanLocation(line)
                } else if (teacher.isEmpty() && !hasWeek && !hasPeriod && !isLikelyLocation(line) && line.length <= 16) {
                    teacher = stripOuterParens(line.removePrefix("教师:").removePrefix("教师：").removePrefix("任课教师：").trim())
                } else if (hasWeek && fallbackLocation.isEmpty()) {
                    val stripped = line.replace(weekBlockRegex, "").replace(periodRegex, "").trim(' ', '/', ',', '，', '@')
                    if (stripped.isNotBlank() && isLikelyLocation(stripped)) {
                        fallbackLocation = cleanLocation(stripped)
                    }
                }
            }

            if (eamsSchedules.isNotEmpty()) {
                for ((spec, roomRaw) in eamsSchedules) {
                    val loc = cleanLocation(roomRaw).ifBlank { fallbackLocation }
                    val warnings = mutableListOf<String>()
                    if (loc.isBlank()) warnings += "未识别教室"
                    if (teacher.isBlank()) warnings += "未识别教师"
                    candidates += ParsedCourseCandidate(
                        name = courseName,
                        teacher = teacher,
                        location = loc,
                        dayOfWeek = dayOfWeek,
                        startPeriod = periodPair.first,
                        endPeriod = periodPair.second,
                        weekRuleType = spec.ruleType,
                        startWeek = spec.startWeek,
                        endWeek = spec.endWeek,
                        customWeeks = spec.customWeeks,
                        courseType = if (PeCourseDetector.isLikelyPeCourse(courseName)) {
                            CourseType.PHYSICAL_EDUCATION
                        } else {
                            CourseType.NORMAL
                        },
                        rawSnippet = block,
                        needsManualReview = warnings.isNotEmpty(),
                        reviewWarning = warnings.joinToString("；").ifBlank { null }
                    )
                }
                continue
            }

            val weekSpec = parseWeeks(block)
            val warnings = mutableListOf<String>()
            if (weekSpec == null) warnings += "未识别周次，默认1-16周"
            if (fallbackLocation.isBlank()) warnings += "未识别教室"
            if (teacher.isBlank()) warnings += "未识别教师"

            candidates += ParsedCourseCandidate(
                name = courseName,
                teacher = teacher,
                location = fallbackLocation,
                dayOfWeek = dayOfWeek,
                startPeriod = periodPair.first,
                endPeriod = periodPair.second,
                weekRuleType = weekSpec?.ruleType ?: WeekRuleType.ALL,
                startWeek = weekSpec?.startWeek ?: 1,
                endWeek = weekSpec?.endWeek ?: 16,
                customWeeks = weekSpec?.customWeeks ?: emptyList(),
                courseType = if (PeCourseDetector.isLikelyPeCourse(courseName)) {
                    CourseType.PHYSICAL_EDUCATION
                } else {
                    CourseType.NORMAL
                },
                rawSnippet = block,
                needsManualReview = warnings.isNotEmpty(),
                reviewWarning = warnings.joinToString("；").ifBlank { null }
            )
        }
        return candidates
    }

    /** 判断一行是否是课程的附属字段（周次/节次/教师/教室/EAMS 括号行），而不是课程名。 */
    private fun isMetaOnlyLine(line: String): Boolean {
        val trimmed = line.trim()
        val compact = trimmed.replace(Regex("\\s"), "")
        if (compact.isEmpty()) return false
        if (compact.startsWith("教师") || compact.startsWith("老师") || compact.startsWith("任课教师") ||
            compact.startsWith("教室") || compact.startsWith("地点")
        ) {
            return true
        }
        // 树维 EAMS 排课括号行 "(1-16,教A201)" 或单独助教/实验项括号行 "(力学综合实验室)" 绝不能另起假课程
        if (parseEamsScheduleParenLine(trimmed) != null) return true
        if ((trimmed.startsWith("(") && trimmed.endsWith(")")) || (trimmed.startsWith("（") && trimmed.endsWith("）"))) {
            return true
        }
        if (parseWeeks(compact) == null && parsePeriods(compact) == null) return false
        // 去掉排课字段常用字后仍有普通汉字，说明这行包含课程名之类的正文
        val allowed = "周至单双第节星期一二三四五六日天上下午晚早时间节次".toSet()
        return compact.none { ch -> ch in '一'..'龥' && ch !in allowed }
    }

    /**
     * 解析自由文本行列表（用于 PDF 文本提取、ML Kit OCR 输出或纯文本课表）。
     */
    fun parseFreeTextLines(rawText: String): List<ParsedCourseCandidate> {
        val lines = rawText.replace("\r\n", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.length >= 4 }

        val candidates = mutableListOf<ParsedCourseCandidate>()
        for (line in lines) {
            val dow = parseDayOfWeek(line)
            val periods = parsePeriods(line)
            val weeks = parseWeeks(line)
            if (dow == null && periods == null && weeks == null) continue

            val tokens = line.split(Regex("[\\s|\\t]+")).filter { it.isNotBlank() }
            val name = tokens.firstOrNull { tok ->
                !dayRegex.containsMatchIn(tok) &&
                    !periodRegex.containsMatchIn(tok) &&
                    !weekBlockRegex.containsMatchIn(tok) &&
                    !isLikelyLocation(tok)
            } ?: continue

            val location = tokens.firstOrNull { isLikelyLocation(it) }?.let(::cleanLocation) ?: ""
            val teacher = tokens.firstOrNull { tok ->
                tok != name &&
                    tok != location &&
                    !dayRegex.containsMatchIn(tok) &&
                    !periodRegex.containsMatchIn(tok) &&
                    !weekBlockRegex.containsMatchIn(tok) &&
                    tok.length in 2..6 &&
                    !tok.any { it.isDigit() }
            } ?: ""

            val warnings = mutableListOf<String>()
            if (dow == null) warnings += "未识别星期，请人工确认"
            if (periods == null) warnings += "未识别节次，请人工确认"
            if (weeks == null) warnings += "未识别周次，默认1-16周"

            candidates += ParsedCourseCandidate(
                name = name,
                teacher = teacher,
                location = location,
                dayOfWeek = dow ?: 1,
                startPeriod = periods?.first ?: 1,
                endPeriod = periods?.second ?: 2,
                weekRuleType = weeks?.ruleType ?: WeekRuleType.ALL,
                startWeek = weeks?.startWeek ?: 1,
                endWeek = weeks?.endWeek ?: 16,
                customWeeks = weeks?.customWeeks ?: emptyList(),
                courseType = if (PeCourseDetector.isLikelyPeCourse(name)) {
                    CourseType.PHYSICAL_EDUCATION
                } else {
                    CourseType.NORMAL
                },
                rawSnippet = line,
                needsManualReview = warnings.isNotEmpty() || location.isBlank(),
                reviewWarning = warnings.joinToString("；").ifBlank { if (location.isBlank()) "未识别教室" else null }
            )
        }
        return candidates
    }

    private fun isLikelyLocation(text: String): Boolean {
        val keywords = listOf(
            "教", "楼", "室", "馆", "场", "区", "栋", "座", "实验", "实训", "机房", "阶梯", "操场", "体育馆", "体育场", "泳池", "@"
        )
        return keywords.any { text.contains(it) } || Regex("^[A-Za-z]-?\\d{3,4}$").matches(text)
    }

    private fun cleanLocation(text: String): String {
        val trimmed = text.removePrefix("@")
            .removePrefix("地点:")
            .removePrefix("地点：")
            .removePrefix("教室:")
            .removePrefix("教室：")
            .replace(Regex("/undefined$", RegexOption.IGNORE_CASE), "")
            .trim()
        return stripOuterParens(trimmed)
    }
}
