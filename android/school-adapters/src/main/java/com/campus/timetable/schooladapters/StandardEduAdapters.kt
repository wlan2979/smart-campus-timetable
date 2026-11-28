package com.campus.timetable.schooladapters

import com.campus.timetable.domain.engine.ChineseCourseTextParser
import com.campus.timetable.domain.engine.PeCourseDetector
import com.campus.timetable.domain.model.CourseType
import com.campus.timetable.domain.model.EduSystemType
import com.campus.timetable.domain.model.ParsedCourseCandidate
import com.campus.timetable.domain.model.School
import com.campus.timetable.domain.model.WeekRuleType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 正方教务系统（新版 JSON kbList 接口 + 经典 HTML 课表表格）通用适配器框架。
 * 安全约束：仅解析用户官方授权登录后主动加载的课表页面或接口 JSON，不保存密码、不绕过验证码。
 */
@Singleton
class ZhengfangSchoolAdapter @Inject constructor() : SchoolAdapter {

    override val adapterId: String = "zhengfang_standard"
    override val systemType: EduSystemType = EduSystemType.ZHENGFANG
    override val displayName: String = "正方教务系统通用适配器"
    override val verifiedSchoolCodes: Set<String> = emptySet()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun buildAuthSpec(school: School): AdapterAuthSpec = AdapterAuthSpec(
        loginUrl = school.portalUrl,
        instruction = "请在学校官方正方教务页面自行完成登录与验证码校验，进入「个人课表查询」页面后点击提取课表。本应用不会读取或保存您的教务密码。"
    )

    override fun parseTimetablePayload(rawPayload: String, isJson: Boolean): AdapterParseResult {
        if (rawPayload.isBlank()) {
            return AdapterParseResult.Failure("EMPTY_PAYLOAD", "教务系统返回内容为空")
        }
        if (looksLikeLoginPage(rawPayload)) {
            return AdapterParseResult.AuthExpired("检测到教务登录状态已失效或尚未登录，请在官方页面重新登录。")
        }
        return if (isJson || rawPayload.trimStart().startsWith("{")) {
            parseZhengfangJson(rawPayload)
        } else {
            parseZhengfangHtml(rawPayload)
        }
    }

    private fun parseZhengfangJson(body: String): AdapterParseResult {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val kbList = root["kbList"]?.jsonArray
                ?: return AdapterParseResult.Failure("FORMAT_MISMATCH", "未在正方 JSON 中找到 kbList 字段")
            val candidates = kbList.mapNotNull { el ->
                val obj = el.jsonObject
                val name = obj["kcmc"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                val teacher = obj["xm"]?.jsonPrimitive?.content?.trim().orEmpty()
                val location = obj["cdmc"]?.jsonPrimitive?.content?.trim().orEmpty()
                val dow = obj["xqj"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                val jcsRaw = obj["jcs"]?.jsonPrimitive?.content?.trim().orEmpty()
                val periodPair = ChineseCourseTextParser.parsePeriods("${jcsRaw}节")
                    ?: jcsRaw.split("-").mapNotNull { it.toIntOrNull() }.let {
                        if (it.size >= 2) it[0] to it[1] else if (it.size == 1) it[0] to it[0] else 1 to 2
                    }
                val zcdRaw = obj["zcd"]?.jsonPrimitive?.content?.trim().orEmpty()
                val weekSpec = ChineseCourseTextParser.parseWeeks(zcdRaw)

                ParsedCourseCandidate(
                    name = name,
                    teacher = teacher,
                    location = location,
                    dayOfWeek = dow.coerceIn(1, 7),
                    startPeriod = periodPair.first,
                    endPeriod = periodPair.second,
                    weekRuleType = weekSpec?.ruleType ?: WeekRuleType.ALL,
                    startWeek = weekSpec?.startWeek ?: 1,
                    endWeek = weekSpec?.endWeek ?: 16,
                    customWeeks = weekSpec?.customWeeks ?: emptyList(),
                    courseType = if (PeCourseDetector.isLikelyPeCourse(name)) {
                        CourseType.PHYSICAL_EDUCATION
                    } else CourseType.NORMAL,
                    rawSnippet = "$name | $teacher | $location | 周$dow | $jcsRaw | $zcdRaw",
                    needsManualReview = weekSpec == null || location.isBlank(),
                    reviewWarning = if (weekSpec == null) "未能识别周次($zcdRaw)，请人工核对" else null
                )
            }
            if (candidates.isEmpty()) {
                AdapterParseResult.Failure("NO_COURSES", "已读取正方课表接口，但当前学期课程列表为空")
            } else {
                AdapterParseResult.Success(candidates, "通过正方 JSON 适配器解析出 ${candidates.size} 条排课记录")
            }
        } catch (e: Exception) {
            AdapterParseResult.Failure("JSON_PARSE_ERROR", "正方课表 JSON 解析异常：${e.localizedMessage}")
        }
    }

    private fun parseZhengfangHtml(html: String): AdapterParseResult {
        val doc = Jsoup.parse(html)
        val table = doc.selectFirst("#Table1, #kbgrid_table_0, table.timetable, table")
            ?: return AdapterParseResult.Failure("NO_TABLE", "未在正方网页中识别到课表表格")
        val candidates = WebTimetableParser.parseHtmlTableToCandidates(table)
        return if (candidates.isEmpty()) {
            AdapterParseResult.Failure("NO_COURSES", "未从正方网页中提取到有效课程，请确认已打开完整周课表页面")
        } else {
            AdapterParseResult.Success(candidates, "通过正方 HTML 适配器解析出 ${candidates.size} 条排课记录")
        }
    }

    private fun looksLikeLoginPage(payload: String): Boolean {
        val lower = payload.lowercase()
        return lower.contains("type=\"password\"") ||
            lower.contains("type='password'") ||
            payload.contains("统一身份认证") && !payload.contains("kbList") && !payload.contains("星期一")
    }
}

/**
 * 强智教务系统通用适配器框架（支持标准 #kbtable / .kbcontent 表格解析）。
 */
@Singleton
class QiangzhiSchoolAdapter @Inject constructor() : SchoolAdapter {

    override val adapterId: String = "qiangzhi_standard"
    override val systemType: EduSystemType = EduSystemType.QIANGZHI
    override val displayName: String = "强智教务系统通用适配器"
    override val verifiedSchoolCodes: Set<String> = emptySet()

    override fun buildAuthSpec(school: School): AdapterAuthSpec = AdapterAuthSpec(
        loginUrl = school.portalUrl,
        instruction = "请在学校官方强智教务系统自行登录并进入「学期理论课表」页面，然后点击提取课表。本应用绝不保存您的账号密码。"
    )

    override fun parseTimetablePayload(rawPayload: String, isJson: Boolean): AdapterParseResult {
        if (rawPayload.isBlank()) {
            return AdapterParseResult.Failure("EMPTY_PAYLOAD", "强智课表页面内容为空")
        }
        val doc = Jsoup.parse(rawPayload)
        if (doc.select("input[type=password]").isNotEmpty() && doc.select("#kbtable").isEmpty()) {
            return AdapterParseResult.AuthExpired("强智教务登录已失效，请重新完成官方登录。")
        }

        val kbTable = doc.selectFirst("#kbtable") ?: doc.selectFirst("table")
            ?: return AdapterParseResult.Failure("NO_TABLE", "未在页面中找到强智课表表格 (#kbtable)")

        val candidates = mutableListOf<ParsedCourseCandidate>()
        val rows = kbTable.select("tr")
        for ((rowIdx, row) in rows.withIndex()) {
            if (rowIdx == 0) continue
            val cells = row.select("th, td")
            if (cells.size < 2) continue
            val periodHeader = cells.first()?.text().orEmpty()
            val rowPeriods = ChineseCourseTextParser.parsePeriods(periodHeader)
                ?: (((rowIdx - 1) * 2 + 1) to ((rowIdx - 1) * 2 + 2))

            for (colIdx in 1 until cells.size.coerceAtMost(8)) {
                val cell = cells[colIdx]
                val contentDiv = cell.selectFirst(".kbcontent") ?: cell
                val htmlBlocks = contentDiv.html().split(Regex("-{4,}|<br\\s*/?>\\s*-{3,}"))
                for (blockHtml in htmlBlocks) {
                    val frag = Jsoup.parseBodyFragment(blockHtml)
                    val text = frag.text().trim()
                    if (text.length < 2) continue

                    val teacher = frag.select("font[title*=老师], font[title*=教师]").text().trim()
                    val weekRaw = frag.select("font[title*=周次]").text().trim()
                    val room = frag.select("font[title*=教室], font[title*=地点]").text().trim()

                    if (teacher.isNotEmpty() || weekRaw.isNotEmpty() || room.isNotEmpty()) {
                        val name = frag.body().ownText().trim().ifBlank {
                            text.substringBefore(teacher).substringBefore(weekRaw).trim()
                        }
                        if (name.isBlank()) continue
                        val weekSpec = ChineseCourseTextParser.parseWeeks(weekRaw)
                        val periodSpec = ChineseCourseTextParser.parsePeriods(weekRaw) ?: rowPeriods
                        candidates += ParsedCourseCandidate(
                            name = name,
                            teacher = teacher,
                            location = room,
                            dayOfWeek = colIdx,
                            startPeriod = periodSpec.first,
                            endPeriod = periodSpec.second,
                            weekRuleType = weekSpec?.ruleType ?: WeekRuleType.ALL,
                            startWeek = weekSpec?.startWeek ?: 1,
                            endWeek = weekSpec?.endWeek ?: 16,
                            customWeeks = weekSpec?.customWeeks ?: emptyList(),
                            courseType = if (PeCourseDetector.isLikelyPeCourse(name)) {
                                CourseType.PHYSICAL_EDUCATION
                            } else CourseType.NORMAL,
                            rawSnippet = text,
                            needsManualReview = weekSpec == null || room.isBlank(),
                            reviewWarning = if (weekSpec == null) "未能完整解析强智周次($weekRaw)" else null
                        )
                    } else {
                        val normalizedCell = blockHtml
                            .replace(Regex("[\\r\\n]+"), "")
                            .replace(Regex("(<br\\s*/?>\\s*){2,}|<hr\\s*/?>", RegexOption.IGNORE_CASE), "\n\n")
                            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                        val plain = Jsoup.parse(normalizedCell).wholeText()
                        candidates += ChineseCourseTextParser.parseGridCell(
                            cellText = plain,
                            dayOfWeek = colIdx,
                            defaultStartPeriod = rowPeriods.first,
                            defaultEndPeriod = rowPeriods.second
                        )
                    }
                }
            }
        }

        return if (candidates.isEmpty()) {
            AdapterParseResult.Failure("NO_COURSES", "未从强智页面提取到有效课程")
        } else {
            AdapterParseResult.Success(candidates, "通过强智适配器解析出 ${candidates.size} 条排课记录")
        }
    }
}
