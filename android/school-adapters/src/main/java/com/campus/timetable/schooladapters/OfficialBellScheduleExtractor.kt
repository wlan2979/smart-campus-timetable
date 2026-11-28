package com.campus.timetable.schooladapters

import com.campus.timetable.domain.engine.ChineseCourseTextParser
import com.campus.timetable.domain.model.ExtractedClassPeriod
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 从高校官网课表页面（树维 EAMS / 正方 / 强智 / 新旧 URP / 通用 HTML / JSON）中提取真实上下课时间与当前教学周提示。
 * 严格以当前打开的学校官网页面数据为准，不写死、不伪造任何学校作息。
 */
object OfficialBellScheduleExtractor {

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val periodNumberRegex = Regex("""第?\s*(\d{1,2})\s*(?:小|大)?节|^(\d{1,2})(?:\s*[-~至、]\s*(\d{1,2}))?\b""")
    private val timeRangeRegex = Regex("""(\d{1,2})[:：](\d{2})\s*[-~～—至到\n\r\s]{1,5}\s*(\d{1,2})[:：](\d{2})""")
    private val bareTextPeriodTimeRegex = Regex(
        """第\s*(\d{1,2})\s*小?节[^\d\n]{0,16}(\d{1,2})[:：](\d{2})\s*[-~～—至到]\s*(\d{1,2})[:：](\d{2})"""
    )
    private val currentWeekRegex = Regex(
        """(?:第?\s*(\d{1,2})\s*教学周|(?:当前|本)\s*周(?:次)?(?:为|是|：|:)?\s*第?\s*(\d{1,2})\s*周)"""
    )

    private val jsonStartKeys = listOf("kssj", "startTime", "beginTime", "qssj", "start_time")
    private val jsonEndKeys = listOf("jssj", "endTime", "overTime", "end_time")
    private val jsonPeriodKeys = listOf("jcs", "jc", "period", "periodNumber", "section", "node", "xqh", "unit")

    fun extractBellPeriodsFromDocuments(documents: List<Pair<String, String>>): List<ExtractedClassPeriod> {
        val candidates = mutableListOf<List<ExtractedClassPeriod>>()
        for ((_, raw) in documents) {
            if (raw.isBlank()) continue
            val trimmed = raw.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                extractFromJsonLike(trimmed).takeIf { it.size >= 4 }?.let { candidates += it }
                continue
            }
            val doc = Jsoup.parse(trimmed)
            candidates += extractCandidatesFromDocument(doc)
        }
        return candidates
            .map(::validateAndNormalizeSeries)
            .filter { it.size >= 4 }
            .maxWithOrNull(
                compareBy<List<ExtractedClassPeriod>> { it.size }
                    .thenBy { series -> if (series.first().periodNumber == 1) 1 else 0 }
                    .thenBy { series -> -series.first().periodNumber }
            )
            .orEmpty()
    }

    fun extractCurrentWeekHintFromDocuments(documents: List<Pair<String, String>>): Int? {
        for ((_, raw) in documents) {
            if (raw.isBlank()) continue
            val trimmed = raw.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) continue
            val text = Jsoup.parse(trimmed).text()
            for (match in currentWeekRegex.findAll(text)) {
                val wk = (match.groupValues[1].ifBlank { match.groupValues[2] }).toIntOrNull()
                if (wk != null && wk in 1..25) return wk
            }
        }
        return null
    }

    private fun extractCandidatesFromDocument(doc: Document): List<List<ExtractedClassPeriod>> {
        val out = mutableListOf<List<ExtractedClassPeriod>>()

        for (table in doc.select("table")) {
            val rows = table.select("tr").filter { tr ->
                tr.parents().firstOrNull { it.tagName() == "table" } == table
            }
            if (rows.isEmpty()) continue

            // 方式 A：横置表表头（如 树维 EAMS：第 1 行是节次号与时间，每个 th/td 含 1 08:00-08:45 或 title="08:00-08:45"）
            val dayRows = rows.count { row ->
                val firstText = row.selectFirst("th, td")?.text().orEmpty()
                ChineseCourseTextParser.parseDayOfWeek(firstText) != null
            }
            if (dayRows >= 3 || rows.size >= 2) {
                for (rIdx in 0 until rows.size.coerceAtMost(4)) {
                    val cells = rows[rIdx].select("th, td")
                    val fromRow = extractHorizontalHeaderRowPeriods(cells.map { cell ->
                        buildString {
                            append(cell.text().trim())
                            val t1 = cell.attr("data-agy-title").trim()
                            val t2 = cell.attr("title").trim()
                            if (t1.isNotBlank()) append(" ").append(t1)
                            if (t2.isNotBlank() && t2 != t1) append(" ").append(t2)
                        }.trim()
                    })
                    if (fromRow.size >= 4) out += fromRow
                }
            }

            // 方式 B：纵置表行头或双列表头（每行第 1/2 列写第 1 节 + 08:00-08:45，或独立节次作息说明表）
            val verticalFound = mutableListOf<ExtractedClassPeriod>()
            var seqPeriod = 1
            for (row in rows) {
                val cells = row.select("th, td")
                if (cells.isEmpty()) continue
                val scanLimit = cells.size.coerceAtMost(3)
                val rowLeftText = (0 until scanLimit).joinToString(" ") { idx ->
                    val c = cells[idx]
                    listOf(c.text().trim(), c.attr("data-agy-title").trim(), c.attr("title").trim())
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" ")
                }.trim()
                if (rowLeftText.isBlank()) continue
                val timePair = parseTimeRange(rowLeftText) ?: continue
                val explicitPeriod = parseSinglePeriodNumber(rowLeftText)
                val pNum = explicitPeriod ?: seqPeriod
                if (pNum in 1..20) {
                    verticalFound += ExtractedClassPeriod(
                        periodNumber = pNum,
                        startTime = timePair.first.format(timeFmt),
                        endTime = timePair.second.format(timeFmt)
                    )
                    seqPeriod = pNum + 1
                }
            }
            if (verticalFound.size >= 4) out += verticalFound
        }

        // 方式 C：页面脚本/正文中的 `第X节 HH:mm-HH:mm` 或 JSON 配置对象
        val bodyText = doc.body()?.wholeText().orEmpty()
        val inlineMatches = bareTextPeriodTimeRegex.findAll(bodyText).mapNotNull { m ->
            val p = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val s = parseHm(m.groupValues[2], m.groupValues[3]) ?: return@mapNotNull null
            val e = parseHm(m.groupValues[4], m.groupValues[5]) ?: return@mapNotNull null
            if (p in 1..20 && isValidSinglePeriod(s, e)) {
                ExtractedClassPeriod(p, s.format(timeFmt), e.format(timeFmt))
            } else null
        }.toList()
        if (inlineMatches.size >= 4) out += inlineMatches

        for (script in doc.select("script")) {
            val content = script.data()
            if (content.contains("kssj", true) || content.contains("startTime", true) || content.contains("beginTime", true)) {
                val fromJson = extractFromJsonLike(content)
                if (fromJson.size >= 4) out += fromJson
            }
        }
        return out
    }

    private fun extractHorizontalHeaderRowPeriods(cellTexts: List<String>): List<ExtractedClassPeriod> {
        val rawPairs = mutableListOf<Pair<Int?, Pair<LocalTime, LocalTime>>>()
        for (text in cellTexts) {
            val range = parseTimeRange(text) ?: continue
            val withoutTime = timeRangeRegex.replace(text, " ").trim()
            val explicitPeriod = parseSinglePeriodNumber(withoutTime)
            rawPairs += explicitPeriod to range
        }
        if (rawPairs.size < 4) return emptyList()
        var nextAuto = 1
        return rawPairs.mapNotNull { (explicitP, range) ->
            val pNum = explicitP ?: nextAuto
            if (pNum !in 1..20) return@mapNotNull null
            nextAuto = pNum + 1
            ExtractedClassPeriod(
                periodNumber = pNum,
                startTime = range.first.format(timeFmt),
                endTime = range.second.format(timeFmt)
            )
        }
    }

    private fun extractFromJsonLike(raw: String): List<ExtractedClassPeriod> {
        val objectPattern = Regex("""\{[^{}]{10,360}\}""")
        val found = mutableListOf<ExtractedClassPeriod>()
        var autoNum = 1
        for (obj in objectPattern.findAll(raw).map { it.value }) {
            val sVal = extractJsonField(obj, jsonStartKeys) ?: continue
            val eVal = extractJsonField(obj, jsonEndKeys) ?: continue
            val s = parseFlexibleTime(sVal) ?: continue
            val e = parseFlexibleTime(eVal) ?: continue
            if (!isValidSinglePeriod(s, e)) continue
            val pField = extractJsonField(obj, jsonPeriodKeys)
            val pNum = pField?.let { Regex("\\d{1,2}").find(it)?.value?.toIntOrNull() } ?: autoNum
            if (pNum in 1..20) {
                found += ExtractedClassPeriod(pNum, s.format(timeFmt), e.format(timeFmt))
                autoNum = pNum + 1
            }
        }
        return found
    }

    private fun extractJsonField(obj: String, keys: List<String>): String? {
        for (key in keys) {
            val regex = Regex("""["']?$key["']?\s*:\s*["']?([^"',}\s]+)["']?""", RegexOption.IGNORE_CASE)
            val value = regex.find(obj)?.groupValues?.getOrNull(1)?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun validateAndNormalizeSeries(items: List<ExtractedClassPeriod>): List<ExtractedClassPeriod> {
        if (items.isEmpty()) return emptyList()
        val deduped = items
            .filter { it.periodNumber in 1..20 }
            .distinctBy { it.periodNumber }
            .sortedBy { it.periodNumber }

        val result = mutableListOf<ExtractedClassPeriod>()
        var prevEnd: LocalTime? = null
        var prevStart: LocalTime? = null
        for (item in deduped) {
            val s = runCatching { LocalTime.parse(item.startTime) }.getOrNull() ?: continue
            val e = runCatching { LocalTime.parse(item.endTime) }.getOrNull() ?: continue
            if (!isValidSinglePeriod(s, e)) continue
            if (prevEnd != null && prevStart != null) {
                // 相邻节次必须时间先后合法且单调递增
                if (!s.isAfter(prevStart) || s.isBefore(prevEnd)) continue
            }
            result += item
            prevStart = s
            prevEnd = e
        }
        // 确保是连续的节次序列（允许中间不缺或至少首节起点合乎常规课表 1..4）
        return if (result.size >= 4) result else emptyList()
    }

    private fun parseTimeRange(text: String): Pair<LocalTime, LocalTime>? {
        val m = timeRangeRegex.find(text) ?: return null
        val s = parseHm(m.groupValues[1], m.groupValues[2]) ?: return null
        val e = parseHm(m.groupValues[3], m.groupValues[4]) ?: return null
        return if (isValidSinglePeriod(s, e)) s to e else null
    }

    private fun parseSinglePeriodNumber(text: String): Int? {
        val cleaned = text.trim()
        val m = periodNumberRegex.find(cleaned) ?: return null
        val first = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() ?: return null
        val second = m.groupValues[3].toIntOrNull()
        if (second != null && second != first) return null
        return first.takeIf { it in 1..20 }
    }

    private fun parseHm(hourStr: String, minStr: String): LocalTime? {
        val h = hourStr.toIntOrNull() ?: return null
        val m = minStr.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    private fun parseFlexibleTime(raw: String): LocalTime? {
        val trimmed = raw.trim()
        val colonMatch = Regex("""^(\d{1,2})[:：](\d{2})(?::\d{2})?$""").matchEntire(trimmed)
        if (colonMatch != null) {
            return parseHm(colonMatch.groupValues[1], colonMatch.groupValues[2])
        }
        val compact = trimmed.filter(Char::isDigit)
        if (compact.length == 3) {
            return parseHm(compact.substring(0, 1), compact.substring(1, 3))
        }
        if (compact.length == 4) {
            return parseHm(compact.substring(0, 2), compact.substring(2, 4))
        }
        return null
    }

    private fun isValidSinglePeriod(start: LocalTime, end: LocalTime): Boolean {
        if (!end.isAfter(start)) return false
        val minutes = Duration.between(start, end).toMinutes()
        return minutes in 20..120
    }
}
