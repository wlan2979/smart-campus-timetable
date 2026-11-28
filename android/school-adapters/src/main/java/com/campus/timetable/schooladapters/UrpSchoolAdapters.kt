package com.campus.timetable.schooladapters

import com.campus.timetable.domain.engine.ChineseCourseTextParser
import com.campus.timetable.domain.engine.PeCourseDetector
import com.campus.timetable.domain.model.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 新 URP 综合教务系统（常见于川大、中农、东北农大、河北工大、太原理工等）通用适配器。
 * 支持新 URP 课表接口 JSON（dateList / selectCourseList / timeAndPlaceList）及标准 HTML 课表。
 */
@Singleton
class NewUrpSchoolAdapter @Inject constructor() : SchoolAdapter {

    override val adapterId: String = "new_urp_standard"
    override val systemType: EduSystemType = EduSystemType.NEW_URP
    override val displayName: String = "新 URP 综合教务通用适配器"
    override val verifiedSchoolCodes: Set<String> = emptySet()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun buildAuthSpec(school: School): AdapterAuthSpec = AdapterAuthSpec(
        loginUrl = school.portalUrl,
        instruction = "请在学校官方新 URP 综合教务页面自行完成登录与验证码校验，进入「本学期课表」后点击提取课表。本应用绝不保存您的密码。"
    )

    override fun parseTimetablePayload(rawPayload: String, isJson: Boolean): AdapterParseResult {
        if (rawPayload.isBlank()) return AdapterParseResult.Failure("EMPTY_PAYLOAD", "新 URP 返回内容为空")
        if (rawPayload.contains("type=\"password\"", true) && !rawPayload.contains("dateList")) {
            return AdapterParseResult.AuthExpired("新 URP 登录态已失效，请在官方登录页重新登录后再提取。")
        }
        if (isJson || rawPayload.trimStart().startsWith("{")) {
            return parseNewUrpJson(rawPayload)
        }
        val doc = Jsoup.parse(rawPayload)
        val table = doc.selectFirst("#courseTable, table.table-bordered, table")
            ?: return AdapterParseResult.Failure("NO_TABLE", "未在新 URP 页面中发现课表表格")
        val candidates = WebTimetableParser.parseHtmlTableToCandidates(table)
        return if (candidates.isEmpty()) {
            AdapterParseResult.Failure("NO_COURSES", "新 URP 页面未解析到有效课程")
        } else {
            AdapterParseResult.Success(candidates, "通过新 URP HTML 适配器提取 ${candidates.size} 条课程")
        }
    }

    private fun parseNewUrpJson(raw: String): AdapterParseResult {
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val dateList = root["dateList"]?.jsonArray ?: return AdapterParseResult.Failure("FORMAT_MISMATCH", "未发现新 URP dateList 字段")
            val candidates = mutableListOf<ParsedCourseCandidate>()
            for (termEl in dateList) {
                val courses = termEl.jsonObject["selectCourseList"]?.jsonArray ?: continue
                for (cEl in courses) {
                    val cObj = cEl.jsonObject
                    val courseName = cObj["courseName"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (courseName.isBlank()) continue
                    val teacher = cObj["attendClassTeacher"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val slots = cObj["timeAndPlaceList"]?.jsonArray ?: continue
                    for (slotEl in slots) {
                        val sObj = slotEl.jsonObject
                        val dow = sObj["classDay"]?.jsonPrimitive?.intOrNull ?: 1
                        val startP = sObj["classSessions"]?.jsonPrimitive?.intOrNull ?: 1
                        val span = sObj["continuingSession"]?.jsonPrimitive?.intOrNull ?: 2
                        val endP = (startP + span - 1).coerceAtLeast(startP)
                        val building = sObj["teachingBuildingName"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val room = sObj["classroomName"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val loc = "$building$room".trim()
                        val weekDesc = sObj["weekDescription"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val weekMask = sObj["classWeek"]?.jsonPrimitive?.content?.trim().orEmpty()

                        val weekSpec = ChineseCourseTextParser.parseWeeks(weekDesc)
                            ?: parseUrpWeekBitmask(weekMask)

                        candidates += ParsedCourseCandidate(
                            name = courseName,
                            teacher = teacher,
                            location = loc,
                            dayOfWeek = dow.coerceIn(1, 7),
                            startPeriod = startP,
                            endPeriod = endP,
                            weekRuleType = weekSpec?.ruleType ?: WeekRuleType.ALL,
                            startWeek = weekSpec?.startWeek ?: 1,
                            endWeek = weekSpec?.endWeek ?: 16,
                            customWeeks = weekSpec?.customWeeks ?: emptyList(),
                            courseType = if (PeCourseDetector.isLikelyPeCourse(courseName)) CourseType.PHYSICAL_EDUCATION else CourseType.NORMAL,
                            rawSnippet = "$courseName | $teacher | $loc | 周$dow $startP-$endP | $weekDesc",
                            needsManualReview = weekSpec == null || loc.isBlank(),
                            reviewWarning = if (weekSpec == null) "未能完整解析新URP周次，请人工核对" else null
                        )
                    }
                }
            }
            if (candidates.isEmpty()) {
                AdapterParseResult.Failure("NO_COURSES", "新 URP JSON 中未包含排课记录")
            } else {
                AdapterParseResult.Success(candidates, "通过新 URP JSON 适配器提取 ${candidates.size} 条排课")
            }
        } catch (e: Exception) {
            AdapterParseResult.Failure("JSON_ERROR", "新 URP JSON 解析失败：${e.localizedMessage}")
        }
    }

    /** 新 URP 使用 "11111111111111110000" 二进制掩码表示第 1..20 周上课情况 */
    private fun parseUrpWeekBitmask(mask: String): ChineseCourseTextParser.ParsedWeekSpec? {
        if (mask.isBlank() || !mask.all { it == '0' || it == '1' }) return null
        val weeks = mask.mapIndexedNotNull { idx, ch -> if (ch == '1') idx + 1 else null }
        if (weeks.isEmpty()) return null
        val minW = weeks.first()
        val maxW = weeks.last()
        return when {
            weeks == (minW..maxW).toList() -> ChineseCourseTextParser.ParsedWeekSpec(WeekRuleType.ALL, minW, maxW, emptyList())
            weeks == (minW..maxW).filter { it % 2 == 1 } && weeks.size >= 2 -> ChineseCourseTextParser.ParsedWeekSpec(WeekRuleType.ODD, minW, maxW, emptyList())
            weeks == (minW..maxW).filter { it % 2 == 0 } && weeks.size >= 2 -> ChineseCourseTextParser.ParsedWeekSpec(WeekRuleType.EVEN, minW, maxW, emptyList())
            else -> ChineseCourseTextParser.ParsedWeekSpec(WeekRuleType.CUSTOM, minW, maxW, weeks)
        }
    }
}

/**
 * 旧 URP 教务系统（经典 displayTag / xkAction 表格结构）通用适配器。
 */
@Singleton
class OldUrpSchoolAdapter @Inject constructor() : SchoolAdapter {

    override val adapterId: String = "old_urp_standard"
    override val systemType: EduSystemType = EduSystemType.OLD_URP
    override val displayName: String = "旧 URP 教务系统通用适配器"
    override val verifiedSchoolCodes: Set<String> = emptySet()

    override fun buildAuthSpec(school: School): AdapterAuthSpec = AdapterAuthSpec(
        loginUrl = school.portalUrl,
        instruction = "请在学校官方旧版 URP 教务页面完成账号与验证码登录后，打开「本学期课表」并点击提取。本应用绝不保存您的教务密码。"
    )

    override fun parseTimetablePayload(rawPayload: String, isJson: Boolean): AdapterParseResult {
        if (rawPayload.isBlank()) return AdapterParseResult.Failure("EMPTY_PAYLOAD", "旧 URP 页面为空")
        val doc = Jsoup.parse(rawPayload)
        if (doc.select("input[type=password]").isNotEmpty() && doc.select("table.displayTag").isEmpty()) {
            return AdapterParseResult.AuthExpired("旧 URP 登录态已失效，请重新登录。")
        }
        val tables = doc.select("table.displayTag, table#user, table")
        // 页面里混有导航/页脚表格时只取课程最多的一张，避免导入无关内容
        val best = tables
            .map { WebTimetableParser.parseHtmlTableToCandidates(it) }
            .filter { it.isNotEmpty() }
            .maxByOrNull { it.size }
            .orEmpty()
        return if (best.isEmpty()) {
            AdapterParseResult.Failure("NO_COURSES", "未能从旧 URP 页面表格提取到课程")
        } else {
            AdapterParseResult.Success(best, "通过旧 URP 表格适配器提取 ${best.size} 条课程")
        }
    }
}
