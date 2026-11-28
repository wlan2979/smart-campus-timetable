package com.campus.timetable.schooladapters

import com.campus.timetable.domain.engine.ChineseCourseTextParser
import com.campus.timetable.domain.engine.PeCourseDetector
import com.campus.timetable.domain.model.CourseType
import com.campus.timetable.domain.model.EduSystemType
import com.campus.timetable.domain.model.ParsedCourseCandidate
import com.campus.timetable.domain.model.School
import com.campus.timetable.domain.model.WeekRuleType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 教务系统登录后课表页直达快捷入口。
 * 用于解决树维 EAMS / 强智等桌面端教务首页在手机 WebView 下顶部导航菜单过小或依赖鼠标悬停而无法点击的问题。
 * 严格依赖用户本人在受控浏览器内已完成的合法会话 Cookie，绝不绕过登录或读取密码。
 */
data class EduTimetableShortcut(
    val label: String,
    val targetUrl: String,
    val systemHint: String,
    val description: String
)

object EduPortalShortcutResolver {

    private val authOrLoginHints = listOf(
        "login", "authserver", "/cas/", "/sso", "captcha", "resetpassword", "logout", "passport"
    )

    /** 根据当前受控浏览器 URL 推断可用的课表页直达入口（仅对已知标准路径生效，绝不盲目猜测）。 */
    fun resolveTimetableShortcut(currentUrl: String): EduTimetableShortcut? {
        val trimmed = currentUrl.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val rawPath = uri.rawPath.orEmpty()
        val pathLower = rawPath.lowercase()
        val fullLower = trimmed.lowercase()

        if (host.isBlank()) return null
        if (authOrLoginHints.any { fullLower.contains(it) }) return null

        val origin = buildString {
            append(uri.scheme.lowercase())
            append("://")
            append(uri.host)
            if (uri.port != -1) {
                append(":")
                append(uri.port)
            }
        }

        // 1. 树维 EAMS（如 https://jwxt.example.edu.cn/eams/homeExt.action -> .../eams/courseTableForStd.action）
        val eamsPrefixMatch = Regex("^(/(?:[a-z0-9_\\-]+/)*eams[a-z0-9_\\-]*)(?:/|$)", RegexOption.IGNORE_CASE)
            .find(rawPath)
        val isEamsHost = host.startsWith("eams.") || host.contains(".eams.")
        if (eamsPrefixMatch != null || isEamsHost) {
            if (pathLower.contains("coursetableforstd")) return null
            val prefix = eamsPrefixMatch?.groupValues?.getOrNull(1)
                ?: if (pathLower.startsWith("/eams")) "/eams" else "/eams"
            val target = "$origin$prefix/courseTableForStd.action"
            return EduTimetableShortcut(
                label = "前往我的课表页",
                targetUrl = target,
                systemHint = "树维 EAMS 教务",
                description = "检测到您已进入树维 EAMS 教务页面，若上方菜单难以点击，可一键直达「我的课表」页后再提取。"
            )
        }

        // 2. 强智教务（如 .../jsxsd/framework/xsMain.jsp -> .../jsxsd/xskb/xskb_list.do）
        val jsxsdMatch = Regex("^(/(?:[a-z0-9_\\-]+/)*jsxsd)(?:/|$)", RegexOption.IGNORE_CASE)
            .find(rawPath)
        if (jsxsdMatch != null && !pathLower.contains("/xskb/")) {
            val prefix = jsxsdMatch.groupValues[1]
            if (pathLower.contains("xsmain") || pathLower.contains("framework") || pathLower.endsWith("/jsxsd") || pathLower.endsWith("/jsxsd/")) {
                val target = "$origin$prefix/xskb/xskb_list.do"
                return EduTimetableShortcut(
                    label = "前往学期理论课表",
                    targetUrl = target,
                    systemHint = "强智教务",
                    description = "检测到您已进入强智教务首页，可一键直达「学期理论课表」页后再提取。"
                )
            }
        }

        return null
    }

    /** 从任意树维 EAMS 登录页地址推导对应的学生课表页地址（用于 AdapterAuthSpec 提示）。 */
    fun deriveEamsCourseTableUrl(portalUrl: String): String {
        val trimmed = portalUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return ""
        val host = uri.host ?: return ""
        val origin = buildString {
            append(uri.scheme ?: "https")
            append("://")
            append(host)
            if (uri.port != -1) append(":${uri.port}")
        }
        val rawPath = uri.rawPath.orEmpty()
        val prefix = Regex("^(/(?:[a-z0-9_\\-]+/)*eams[a-z0-9_\\-]*)(?:/|$)", RegexOption.IGNORE_CASE)
            .find(rawPath)?.groupValues?.getOrNull(1)
            ?: "/eams"
        return "$origin$prefix/courseTableForStd.action"
    }

    /** 判断是否处于「已登录树维 EAMS 首页/非课表页」（例如 homeExt.action / home.action）。 */
    fun isLikelyLoggedInEamsHome(url: String, doc: Document): Boolean {
        val lowerUrl = url.lowercase()
        val isEamsUrl = lowerUrl.contains("/eams") || lowerUrl.contains("://eams.")
        if (!isEamsUrl) return false
        if (authOrLoginHints.any { lowerUrl.contains(it) }) return false
        if (lowerUrl.contains("coursetableforstd")) return false
        if (doc.select("input[type=password]").isNotEmpty()) return false
        if (doc.select("#manualArrangeCourseTable").isNotEmpty()) return false
        val bodyText = doc.body()?.text().orEmpty()
        return lowerUrl.contains("homeext") ||
            lowerUrl.contains("home.action") ||
            lowerUrl.contains("home!index") ||
            bodyText.contains("快捷入口") ||
            bodyText.contains("任务列表") ||
            bodyText.contains("教学周") ||
            bodyText.contains("退出")
    }
}

@Serializable
internal data class EamsRuntimeActivityDto(
    val teacherName: String = "",
    val courseName: String = "",
    val roomName: String = "",
    val vaildWeeks: String = "",
    val remark: String = "",
    val assistantName: String = "",
    val experiItemName: String = "",
    val schGroupNo: String = ""
)

@Serializable
internal data class EamsRuntimeTablePayload(
    val __eamsRuntimeTable: Boolean = false,
    val url: String = "",
    val unitCounts: Int = 0,
    val unitsPerDay: Int = 0,
    val endAtSat: Boolean? = null,
    val fromOffset: Int? = null,
    val startWeek: Int? = null,
    val endWeek: Int? = null,
    val marshalContents: List<String?> = emptyList(),
    val activities: List<List<EamsRuntimeActivityDto>> = emptyList()
)

/**
 * 树维 EAMS 教务系统通用适配器（适用于国内采用树维 EAMS 4.x/5.x 的各类高校）。
 * 三层递增解析策略：
 * 1. 受控浏览器运行时 `window.table0`（CourseTable / TaskActivity 对象 + EAMS 已按学期偏移算好的 marshalContents）；
 * 2. 网页源码 `<script>` 中的 `new TaskActivity(...)` + `table0.marshalTable(from, start, end)` 位图解码；
 * 3. 已渲染的 `#manualArrangeCourseTable` / `TD{k}` 表格解析（同时兼容横置表 `colspan` 与纵置表 `rowspan` 及无「周」字括号格式）。
 */
@Singleton
class EamsSchoolAdapter @Inject constructor() : SchoolAdapter {

    override val adapterId: String = "eams_standard"
    override val systemType: EduSystemType = EduSystemType.EAMS
    override val displayName: String = "树维 EAMS 教务通用适配器"
    override val verifiedSchoolCodes: Set<String> = emptySet()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun buildAuthSpec(school: School): AdapterAuthSpec = AdapterAuthSpec(
        loginUrl = school.portalUrl,
        timetableHintUrl = EduPortalShortcutResolver.deriveEamsCourseTableUrl(school.portalUrl),
        instruction = "请在「${school.name}」官方树维 EAMS 页面完成登录与验证码后，点击顶部「前往我的课表页」进入学生课表，待课表加载完毕后点击右上角「提取课表」。本应用绝不保存您的教务密码。"
    )

    override fun parseTimetablePayload(rawPayload: String, isJson: Boolean): AdapterParseResult {
        if (rawPayload.isBlank()) {
            return AdapterParseResult.Failure("EMPTY_PAYLOAD", "树维 EAMS 页面内容为空")
        }
        val trimmed = rawPayload.trim()
        if (isJson || trimmed.startsWith("{")) {
            if (trimmed.contains("__eamsRuntimeTable")) {
                return parseRuntimeTableJson(trimmed)
            }
        }

        val doc = Jsoup.parse(trimmed)
        val hasEamsSignal = trimmed.contains("TaskActivity") ||
            doc.select("#manualArrangeCourseTable, table[id^=manualArrangeCourseTable]").isNotEmpty() ||
            doc.select("td[id~=^(?i)TD\\d+(_\\d+)?$]").size >= 10
        if (doc.select("input[type=password]").isNotEmpty() && !hasEamsSignal) {
            return AdapterParseResult.AuthExpired("树维 EAMS 登录已失效或尚未登录，请在官方登录页重新登录。")
        }

        // 策略 2：优先解析内联 <script> 中的 TaskActivity 数据
        val fromScript = parseTaskActivityScript(trimmed)
        if (fromScript.isNotEmpty()) {
            return AdapterParseResult.Success(
                candidates = fromScript,
                notice = "通过树维 EAMS 课表脚本解析出 ${fromScript.size} 条排课记录，请核对预览后导入"
            )
        }

        // 策略 3：解析已渲染的 #manualArrangeCourseTable / TD{k} 表格（兼容横置 colspan 与纵置 rowspan）
        val fromTdTable = parseRenderedEamsTable(doc)
        if (fromTdTable.isNotEmpty()) {
            return AdapterParseResult.Success(
                candidates = fromTdTable,
                notice = "通过树维 EAMS 课表表格解析出 ${fromTdTable.size} 条排课记录，请核对预览后导入"
            )
        }

        return AdapterParseResult.Failure(
            "NO_COURSES",
            "未在当前树维 EAMS 页面中提取到课程，请确认已进入「我的课表 (courseTableForStd)」页面并等待课表表格加载完成。"
        )
    }

    /** 策略 1：解析受控浏览器直接导出的运行时 `window.table0` 快照。 */
    private fun parseRuntimeTableJson(rawJson: String): AdapterParseResult {
        return try {
            val payload = json.decodeFromString<EamsRuntimeTablePayload>(rawJson)
            if (!payload.__eamsRuntimeTable) {
                return AdapterParseResult.Failure("FORMAT_MISMATCH", "非树维 EAMS 运行时课表数据")
            }
            val unitsPerDay = resolveUnitsPerDay(payload.unitsPerDay, payload.unitCounts)
            val slots = mutableListOf<RawEamsSlot>()
            val totalSlots = maxOf(payload.activities.size, payload.marshalContents.size).coerceAtMost(7 * unitsPerDay)

            // 预解析所有单元格的 marshalContents，并反推出整张表统一的自然周偏移 fromOffset
            val parsedMarshals = Array<List<ParsedEamsCellEntry>>(totalSlots) { idx ->
                val mh = payload.marshalContents.getOrNull(idx).orEmpty()
                if (mh.isNotBlank()) parseEamsCellHtmlEntries(mh) else emptyList()
            }
            val resolvedFromOffset = payload.fromOffset?.takeIf { it in 1..53 }
                ?: inferCalendarFromOffset(payload.activities, parsedMarshals, totalSlots, payload.endAtSat)
            val startWeekParam = payload.startWeek?.coerceIn(1, 30) ?: 1
            val endWeekParam = payload.endWeek?.coerceIn(startWeekParam, 30) ?: 25

            for (index in 0 until totalSlots) {
                val cellActivities = payload.activities.getOrNull(index).orEmpty()
                val marshalHtml = payload.marshalContents.getOrNull(index).orEmpty()
                val dayOfWeek = (index / unitsPerDay) + 1
                val period = (index % unitsPerDay) + 1
                if (dayOfWeek !in 1..7 || period !in 1..20) continue

                if (cellActivities.isNotEmpty()) {
                    val marshaledEntries = parsedMarshals[index]

                    for (act in cellActivities) {
                        val rawName = act.courseName.trim()
                        if (rawName.isBlank()) continue
                        val cleanName = cleanEamsCourseName(rawName)
                        val teacher = act.teacherName.trim()
                        val room = act.roomName.trim()

                        // 当 EAMS 页面已亲自算出 marshalContents 且能一对一匹配到课程名与教室时，
                        // 优先采用官网渲染周次（避免跨年或 endAtSat 左移导致的 ±1 周偏移），其次用位图解码。
                        val exactCourseMatches = marshaledEntries.filter { entry ->
                            entry.courseName == cleanName ||
                                entry.rawTitle.contains(cleanName) ||
                                cleanName.contains(entry.courseName)
                        }
                        val matchedMarshaled = exactCourseMatches.firstOrNull { entry ->
                            room.isNotBlank() &&
                                (entry.location == room || entry.location.contains(room) || room.contains(entry.location))
                        } ?: exactCourseMatches.singleOrNull()
                            ?: marshaledEntries.singleOrNull()

                        val weekSpec = if (exactCourseMatches.size == 1 && matchedMarshaled?.weekSpec != null) {
                            matchedMarshaled.weekSpec
                        } else {
                            decodeEamsBitmask(
                                validWeeks = act.vaildWeeks,
                                fromOffset = resolvedFromOffset,
                                startWeek = startWeekParam,
                                endWeek = endWeekParam,
                                endAtSat = payload.endAtSat
                            ) ?: matchedMarshaled?.weekSpec
                                ?: decodeEamsBitmask(act.vaildWeeks, fromOffset = null)
                        }

                        slots += RawEamsSlot(
                            courseName = cleanName,
                            teacher = teacher.ifBlank { matchedMarshaled?.teacher.orEmpty() },
                            location = room.ifBlank { matchedMarshaled?.location.orEmpty() },
                            dayOfWeek = dayOfWeek,
                            period = period,
                            weekSpec = weekSpec,
                            rawSnippet = "$rawName | $teacher | $room"
                        )
                    }
                } else if (marshalHtml.isNotBlank()) {
                    for (entry in parsedMarshals[index]) {
                        slots += RawEamsSlot(
                            courseName = entry.courseName,
                            teacher = entry.teacher,
                            location = entry.location,
                            dayOfWeek = dayOfWeek,
                            period = period,
                            weekSpec = entry.weekSpec,
                            rawSnippet = entry.rawSnippet
                        )
                    }
                }
            }

            val merged = mergeContiguousSlots(slots)
            if (merged.isEmpty()) {
                AdapterParseResult.Failure("NO_COURSES", "树维 EAMS 运行时课表为空，请确认当前所选学期存在课程。")
            } else {
                AdapterParseResult.Success(
                    candidates = merged,
                    notice = "已从树维 EAMS 课表对象精准提取 ${merged.size} 条排课记录，请核对预览后导入"
                )
            }
        } catch (e: Exception) {
            AdapterParseResult.Failure("JSON_ERROR", "树维 EAMS 课表对象解析异常：${e.localizedMessage}")
        }
    }

    /** 策略 2：解析 EAMS 页面 `<script>` 中的 `TaskActivity` 与 `table0.activities[index]`。 */
    internal fun parseTaskActivityScript(html: String): List<ParsedCourseCandidate> {
        if (!html.contains("TaskActivity")) return emptyList()

        val unitCountFromVar = Regex("var\\s+unitCount\\s*=\\s*(\\d{1,2})").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val courseTableMatch = Regex("new\\s+CourseTable\\s*\\(\\s*(\\d{4})\\s*,\\s*(\\d{2,3})").find(html)
        val tableYear = courseTableMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val endAtSat = tableYear?.let { year ->
            java.time.LocalDate.of(year, 12, 31).dayOfWeek == java.time.DayOfWeek.SATURDAY
        }
        val unitCountsFromTable = courseTableMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
        val unitsPerDay = resolveUnitsPerDay(unitCountFromVar ?: 0, unitCountsFromTable ?: 0)

        // 解析 table0.marshalTable(from, startWeek, endWeek) 中的 from 偏移参数
        val marshalCall = Regex("\\.marshalTable(?:ForAdminclass)?\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").find(html)
        val fromOffset = marshalCall?.groupValues?.getOrNull(1)?.toIntOrNull()
        val startWeekParam = marshalCall?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1
        val endWeekParam = marshalCall?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 25

        // 先使用引号+括号平衡扫描所有 new TaskActivity(...) 调用，防止参数中包含分号 ;（如 &nbsp;）导致整门课被正则截断丢失
        val taskCalls = findBalancedTaskActivityCalls(html)

        val events = mutableListOf<EamsScriptEvent>()
        val teacherNameInObjRegex = Regex("""name\s*:\s*["']([^"']+)["']""")

        Regex("""var\s+(?:actTeachers|teachers)\s*=\s*\[(.*?)\]\s*;?""", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .forEach { events += EamsScriptEvent.ActTeachers(it.range.first, it.groupValues[1]) }

        taskCalls.forEach { (pos, argsRaw) ->
            events += EamsScriptEvent.NewActivity(pos, argsRaw)
        }

        Regex("""\bindex\s*=\s*(\d+)\s*\*\s*(?:unitCount|\d+)\s*\+\s*(\d+)""")
            .findAll(html)
            .forEach { m ->
                val d = m.groupValues[1].toIntOrNull() ?: return@forEach
                val u = m.groupValues[2].toIntOrNull() ?: return@forEach
                events += EamsScriptEvent.SetDayUnit(m.range.first, d, u)
            }

        Regex("""\bindex\s*=\s*(\d+)\s*;""")
            .findAll(html)
            .forEach { m ->
                val idx = m.groupValues[1].toIntOrNull() ?: return@forEach
                events += EamsScriptEvent.SetDirectIndex(m.range.first, idx)
            }

        Regex("""\bindex\s*(?:\+\+|=\s*index\s*\+\s*(\d+)|\+=\s*(\d+))|\+\+\s*index\b""")
            .findAll(html)
            .forEach { m ->
                val delta = m.groupValues[1].toIntOrNull()
                    ?: m.groupValues[2].toIntOrNull()
                    ?: 1
                events += EamsScriptEvent.ShiftIndex(m.range.first, delta)
            }

        Regex("""\.activities\s*\[\s*(?:(\d+)|index\s*\+\s*(\d+))\s*\]""")
            .findAll(html)
            .forEach { m ->
                val absIdx = m.groupValues[1].toIntOrNull()
                val offset = m.groupValues[2].toIntOrNull()
                events += EamsScriptEvent.DirectActivitiesAccess(m.range.first, absIdx, offset)
            }

        events.sortBy { it.pos }

        var latestActTeachers = ""
        var currentActivity: ParsedTaskActivityDecl? = null
        var lastSlotIndex: Int? = null
        val slots = mutableListOf<RawEamsSlot>()

        fun emitSlot(slotIndex: Int, act: ParsedTaskActivityDecl) {
            val dayOfWeek = (slotIndex / unitsPerDay) + 1
            val period = (slotIndex % unitsPerDay) + 1
            if (dayOfWeek !in 1..7 || period !in 1..20 || act.courseName.isBlank()) return
            slots += RawEamsSlot(
                courseName = act.courseName,
                teacher = act.teacher,
                location = act.room,
                dayOfWeek = dayOfWeek,
                period = period,
                weekSpec = decodeEamsBitmask(act.validWeeks, fromOffset, startWeekParam, endWeekParam, endAtSat),
                rawSnippet = "${act.rawCourseName} | ${act.teacher} | ${act.room}"
            )
        }

        for (ev in events) {
            when (ev) {
                is EamsScriptEvent.ActTeachers -> {
                    latestActTeachers = teacherNameInObjRegex.findAll(ev.body)
                        .map { it.groupValues[1].trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString(",")
                }
                is EamsScriptEvent.NewActivity -> {
                    val args = splitJsArguments(ev.argsRaw)
                    if (args.size >= 7) {
                        val teacherArg = decodeJsArg(args[1])
                        val teacher = if (teacherArg.contains("actTeacherName", ignoreCase = true) ||
                            teacherArg.contains("teacherName", ignoreCase = true) ||
                            teacherArg.isBlank()
                        ) {
                            latestActTeachers
                        } else {
                            teacherArg
                        }
                        val rawCourseName = decodeJsArg(args[3])
                        val roomName = decodeJsArg(args[5])
                        val validWeeks = decodeJsArg(args[6])
                        currentActivity = ParsedTaskActivityDecl(
                            courseName = cleanEamsCourseName(rawCourseName),
                            rawCourseName = rawCourseName,
                            teacher = teacher,
                            room = roomName,
                            validWeeks = validWeeks
                        )
                    }
                }
                is EamsScriptEvent.SetDayUnit -> {
                    val act = currentActivity ?: continue
                    val idx = ev.day * unitsPerDay + ev.unit
                    lastSlotIndex = idx
                    emitSlot(idx, act)
                }
                is EamsScriptEvent.SetDirectIndex -> {
                    val act = currentActivity ?: continue
                    lastSlotIndex = ev.idx
                    emitSlot(ev.idx, act)
                }
                is EamsScriptEvent.ShiftIndex -> {
                    val act = currentActivity ?: continue
                    val prev = lastSlotIndex ?: continue
                    val idx = prev + ev.delta
                    lastSlotIndex = idx
                    emitSlot(idx, act)
                }
                is EamsScriptEvent.DirectActivitiesAccess -> {
                    val act = currentActivity ?: continue
                    val idx = when {
                        ev.idx != null -> ev.idx
                        ev.deltaFromIndex != null && lastSlotIndex != null -> lastSlotIndex + ev.deltaFromIndex
                        else -> null
                    } ?: continue
                    emitSlot(idx, act)
                }
            }
        }

        return mergeContiguousSlots(slots)
    }

    private sealed interface EamsScriptEvent {
        val pos: Int
        data class ActTeachers(override val pos: Int, val body: String) : EamsScriptEvent
        data class NewActivity(override val pos: Int, val argsRaw: String) : EamsScriptEvent
        data class SetDayUnit(override val pos: Int, val day: Int, val unit: Int) : EamsScriptEvent
        data class SetDirectIndex(override val pos: Int, val idx: Int) : EamsScriptEvent
        data class ShiftIndex(override val pos: Int, val delta: Int) : EamsScriptEvent
        data class DirectActivitiesAccess(override val pos: Int, val idx: Int?, val deltaFromIndex: Int?) : EamsScriptEvent
    }

    private fun findBalancedTaskActivityCalls(source: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        val headerRegex = Regex("""new\s+TaskActivity\s*\(""")
        for (match in headerRegex.findAll(source)) {
            val openParenIdx = match.range.last
            var i = openParenIdx + 1
            var depth = 1
            var quote: Char? = null
            var escaped = false
            while (i < source.length && depth > 0) {
                val ch = source[i]
                if (escaped) {
                    escaped = false
                } else if (quote != null) {
                    if (ch == '\\') escaped = true
                    else if (ch == quote) quote = null
                } else {
                    when (ch) {
                        '\'', '"' -> quote = ch
                        '(' -> depth++
                        ')' -> depth--
                    }
                }
                i++
            }
            if (depth == 0) {
                val argsRaw = source.substring(openParenIdx + 1, i - 1)
                out += match.range.first to argsRaw
            }
        }
        return out
    }

    /**
     * 策略 3：解析已由 EAMS JS 渲染完成的 HTML 课表（`#manualArrangeCourseTable` 或带 `id="TD{k}"` / `TD{k}_0` 单元格）。
     * 核心优势：同时结合 `colspan`/`rowspan`、`data-agy-colspan`/`data-agy-rowspan` 与现存 `TD` 槽位缺口双重还原连堂跨度，
     * 彻底避免 2 节或 4 节连堂课被截断为 1 节。
     */
    internal fun parseRenderedEamsTable(doc: Document): List<ParsedCourseCandidate> {
        val table = doc.selectFirst("#manualArrangeCourseTable, table[id^=manualArrangeCourseTable]")
            ?: doc.select("table").firstOrNull { it.select("td[id~=^(?i)TD\\d+(_\\d+)?$]").size >= 10 }
            ?: return emptyList()

        val tdCells = table.select("td[id~=^(?i)TD\\d+(_\\d+)?$]")
        if (tdCells.isNotEmpty()) {
            val unitsPerDay = inferUnitsPerDayFromTable(table, tdCells)
            val underscoreFormatIsSlotWithTableIdx = detectUnderscoreIsSlotIndex(tdCells)
            val isVerticalLayout = isVerticalEamsLayout(table, tdCells)

            fun resolveSlotIndex(td: Element): Int? {
                val rawSuffix = td.id().substring(2)
                return if (rawSuffix.contains("_")) {
                    val parts = rawSuffix.split("_")
                    val p0 = parts.getOrNull(0)?.toIntOrNull() ?: return null
                    val p1 = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    if (underscoreFormatIsSlotWithTableIdx) p0 else (p0 * unitsPerDay + p1)
                } else {
                    rawSuffix.toIntOrNull()
                }
            }

            // 预收集所有仍存在于 DOM 中的槽位编号：fillTable() 合并连堂格时会把后续 TD{idx+1} 从 DOM 删除，
            // 因此相邻两个现存 TD 之间的编号差同样可用于 100% 精准反推真实连堂节数！
            val existingSlotIndices = tdCells.mapNotNull(::resolveSlotIndex).toSet()
            val candidates = mutableListOf<ParsedCourseCandidate>()

            for (td in tdCells) {
                val htmlContent = td.html().trim()
                if (htmlContent.isEmpty() || td.text().trim().isEmpty()) continue

                val slotIdx = resolveSlotIndex(td) ?: continue
                val dayZero = slotIdx / unitsPerDay
                val unitZero = slotIdx % unitsPerDay
                val dayOfWeek = dayZero + 1
                val startPeriod = unitZero + 1
                if (dayOfWeek !in 1..7 || startPeriod !in 1..20) continue

                val colSpan = td.attr("colspan").toIntOrNull()?.coerceIn(1, 12) ?: 1
                val rowSpan = td.attr("rowspan").toIntOrNull()?.coerceIn(1, 12) ?: 1
                val agyColSpan = td.attr("data-agy-colspan").toIntOrNull()?.coerceIn(1, 12) ?: 1
                val agyRowSpan = td.attr("data-agy-rowspan").toIntOrNull()?.coerceIn(1, 12) ?: 1
                val domSpan = maxOf(colSpan, rowSpan, agyColSpan, agyRowSpan)

                var removedGapSpan = 1
                if (!isVerticalLayout && domSpan == 1) {
                    var probeUnit = unitZero + 1
                    while (probeUnit < unitsPerDay && (dayZero * unitsPerDay + probeUnit) !in existingSlotIndices) {
                        removedGapSpan++
                        probeUnit++
                    }
                }
                val span = maxOf(domSpan, removedGapSpan).coerceIn(1, 12)
                val endPeriod = (startPeriod + span - 1).coerceAtMost(20)

                val entries = parseEamsCellHtmlEntries(htmlContent)
                for (entry in entries) {
                    candidates += buildCandidate(
                        courseName = entry.courseName,
                        teacher = entry.teacher,
                        location = entry.location,
                        dayOfWeek = dayOfWeek,
                        startPeriod = startPeriod,
                        endPeriod = endPeriod,
                        weekSpec = entry.weekSpec,
                        rawSnippet = entry.rawSnippet
                    )
                }
            }
            if (candidates.isNotEmpty()) return candidates
        }

        // 兜底：退回通用表格解析（现已同时支持纵置表、横置表、相邻节次表头与 EAMS 括号周次）
        return WebTimetableParser.parseHtmlTableToCandidates(table)
    }

    /**
     * 区分树维 `TD{day}_{unit}`（如 TD0_0..TD6_11）与 `TD{slot}_{tableIdx}`（如 TD0_0..TD83_0）。
     */
    private fun detectUnderscoreIsSlotIndex(tdCells: List<Element>): Boolean {
        val pairs = tdCells.mapNotNull { td ->
            val raw = td.id().substring(2)
            if (!raw.contains("_")) return@mapNotNull null
            val parts = raw.split("_")
            val a = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val b = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            a to b
        }
        if (pairs.isEmpty()) return false
        val maxFirst = pairs.maxOf { it.first }
        val distinctSecond = pairs.map { it.second }.distinct().size
        return maxFirst > 6 || (distinctSecond == 1 && pairs.size >= 4)
    }

    private fun isVerticalEamsLayout(table: Element, tdCells: List<Element>): Boolean {
        val headerRow = table.select("tr").firstOrNull() ?: return false
        val headerDays = headerRow.select("th, td")
            .mapNotNull { ChineseCourseTextParser.parseDayOfWeek(it.text()) }
            .distinct()
            .size
        if (headerDays >= 3) return true
        val tdRows = table.select("tr").count { it.select("td[id~=^(?i)TD\\d+(_\\d+)?$]").isNotEmpty() }
        return tdRows >= 8 && tdCells.size >= 16
    }

    /**
     * 通过全表已知 `marshalContents` 反推出统一的 `from` 自然周偏移参数（例如秋季学期 `from = 36`），
     * 使得 `decodeEamsBitmask(vaildWeeks, from)` 对每一门课都能精准还原真实起止周（不遗漏已结课课程）。
     */
    private fun inferCalendarFromOffset(
        activities: List<List<EamsRuntimeActivityDto>>,
        parsedMarshals: Array<List<ParsedEamsCellEntry>>,
        totalSlots: Int,
        endAtSat: Boolean? = null
    ): Int? {
        val votes = mutableMapOf<Int, Int>()
        for (i in 0 until totalSlots) {
            val acts = activities.getOrNull(i).orEmpty()
            val entries = parsedMarshals[i]
            if (acts.size != 1 || entries.size != 1) continue
            val act = acts.first()
            val spec = entries.first().weekSpec ?: continue
            val mask = act.vaildWeeks.trim()
            if (mask.length < 20 || !mask.all { it == '0' || it == '1' }) continue
            val firstSemWeek = spec.customWeeks.firstOrNull() ?: spec.startWeek
            val lastHalfIdx = mask.indexOf('1', startIndex = 20)
            val firstOneIdx = if (lastHalfIdx >= 0) lastHalfIdx else mask.indexOf('1')
            if (firstOneIdx < 0) continue
            val candidateFrom = firstOneIdx - firstSemWeek + 2
            if (candidateFrom in 1..53) {
                val decoded = decodeEamsBitmask(mask, candidateFrom, endAtSat = endAtSat)
                if (decoded == spec) {
                    votes[candidateFrom] = (votes[candidateFrom] ?: 0) + 1
                }
            }
        }
        return votes.maxByOrNull { it.value }?.key
    }

    private fun inferUnitsPerDayFromTable(table: Element, tdCells: List<Element>): Int {
        fun readColSpan(td: Element): Int = maxOf(
            td.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1,
            td.attr("data-agy-colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
        )
        fun readRowSpan(td: Element): Int = maxOf(
            td.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1,
            td.attr("data-agy-rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
        )

        val underscoreIsSlot = detectUnderscoreIsSlotIndex(tdCells)
        fun slotNumOf(td: Element): Int? {
            val raw = td.id().substring(2)
            return if (raw.contains("_") && !underscoreIsSlot) {
                raw.substringAfter('_').toIntOrNull()
            } else {
                raw.substringBefore('_').toIntOrNull()
            }
        }

        // 1. 横置表：优先根据相邻星期行首格 TD 编号差（如 星期二 TD12 - 星期一 TD0 = 12）或最大 TD 编号推算，
        //    不受个别单元格缺失 colspan 属性的影响；再辅以星期一行 tdSum 取可靠值。
        val rows = table.select("tr")
        val dayRowFirstSlots = mutableListOf<Pair<Int, Int>>()
        var mondayTdSum: Int? = null
        for (row in rows) {
            val cells = row.select("th, td")
            val firstText = cells.firstOrNull()?.text().orEmpty()
            val dow = ChineseCourseTextParser.parseDayOfWeek(firstText)
            if (dow != null) {
                val rowTds = row.select("td[id~=^(?i)TD\\d+(_\\d+)?$]")
                val firstSlot = rowTds.firstOrNull()?.let(::slotNumOf)
                if (firstSlot != null && (underscoreIsSlot || !rowTds.first().id().contains("_"))) {
                    dayRowFirstSlots += dow to firstSlot
                }
                if (dow == 1) {
                    val tdSum = rowTds.sumOf(::readColSpan)
                    if (tdSum in 6..20) mondayTdSum = tdSum
                }
            }
        }
        if (dayRowFirstSlots.size >= 2) {
            val sortedDaySlots = dayRowFirstSlots.distinctBy { it.first }.sortedBy { it.first }
            val stepEstimates = sortedDaySlots.zipWithNext().mapNotNull { (a, b) ->
                val dayDelta = b.first - a.first
                val slotDelta = b.second - a.second
                if (dayDelta > 0 && slotDelta > 0 && slotDelta % dayDelta == 0) {
                    (slotDelta / dayDelta).takeIf { it in 6..20 }
                } else null
            }
            val majorityStep = stepEstimates.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            if (majorityStep != null) return majorityStep
        }

        // 2. 若是纵置表（表头含星期一..星期日），非表头且含 TD 的行数即为每天节数
        val tdRows = rows.count { it.select("td[id~=^(?i)TD\\d+(_\\d+)?$]").isNotEmpty() }
        if (tdRows in 6..20 && tdRows != 7 && dayRowFirstSlots.isEmpty()) {
            return tdRows
        }

        // 3. 根据最大 TD 槽位编号 + 跨度推算 totalUnits / 7，并与 mondayTdSum 取较大合法值
        val maxEnd = tdCells.maxOfOrNull { td ->
            val n = slotNumOf(td) ?: 0
            val span = maxOf(readColSpan(td), readRowSpan(td))
            n + span
        } ?: 84
        val inferredFromMax = if (!underscoreIsSlot && tdCells.any { it.id().contains("_") }) {
            maxEnd
        } else {
            (maxEnd + 6) / 7
        }.coerceIn(6, 20)
        return maxOf(inferredFromMax, mondayTdSum ?: 0).takeIf { it in 6..20 } ?: 12
    }

    /**
     * 解析 EAMS `marshalByTeacherCourse` 生成的单元格 HTML，例如：
     * - `高等数学A(10101) (张老师)<br><br><br>(1-16,教A101)`
     * - `大学体育 (王教练)<br><br><br>(单1-15,体育场)`
     * - `高等数学 (张老师)<br>(1-8,教A101)<br>(9-16,教B202)`
     * - 多门课复用同一格：`高等数学 (张老师)<br>(单1-15,教A101)<br>线性代数 (李老师)<br>(双2-16,教B202)`
     */
    internal fun parseEamsCellHtmlEntries(cellHtml: String): List<ParsedEamsCellEntry> {
        val normalized = cellHtml
            .replace(Regex("[\\r\\n]+"), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>|</div>", RegexOption.IGNORE_CASE), "\n")
        val lines = Jsoup.parse(normalized).wholeText()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        val results = mutableListOf<ParsedEamsCellEntry>()
        var currentTitle = ""
        var currentCourseName = ""
        var currentTeacher = ""
        var currentFallbackLocation = ""
        var currentHasScheduleLine = false

        fun flushUnscheduledIfNeeded() {
            if (currentCourseName.isNotBlank() && !currentHasScheduleLine) {
                results += ParsedEamsCellEntry(
                    rawTitle = currentTitle,
                    courseName = currentCourseName,
                    teacher = currentTeacher,
                    location = currentFallbackLocation,
                    weekSpec = null,
                    rawSnippet = currentTitle
                )
            }
        }

        for (line in lines) {
            val scheduleParsed = ChineseCourseTextParser.parseEamsScheduleParenLine(line)
            if (scheduleParsed != null) {
                if (currentCourseName.isNotBlank()) {
                    results += ParsedEamsCellEntry(
                        rawTitle = currentTitle,
                        courseName = currentCourseName,
                        teacher = currentTeacher,
                        location = scheduleParsed.second.ifBlank { currentFallbackLocation },
                        weekSpec = scheduleParsed.first,
                        rawSnippet = "$currentTitle $line"
                    )
                    currentHasScheduleLine = true
                }
                continue
            }

            // 单独的括号助教名/实验项行，如 `(力学综合实验室)`：不冲掉课程标题；若含地点词则作为无教室时的备用地点
            if (currentCourseName.isNotBlank() && isStandaloneParenthesizedLine(line)) {
                val inner = line.trim().drop(1).dropLast(1).trim()
                if (currentFallbackLocation.isBlank() && listOf("室", "楼", "馆", "场", "实验", "中心", "机房").any { inner.contains(it) }) {
                    currentFallbackLocation = inner
                }
                continue
            }

            // 新课程标题行
            flushUnscheduledIfNeeded()
            val (name, teacher) = splitEamsTitleAndTeacher(line)
            currentTitle = line
            currentCourseName = name
            currentTeacher = teacher
            currentFallbackLocation = ""
            currentHasScheduleLine = false
        }
        flushUnscheduledIfNeeded()
        return results
    }

    private fun isStandaloneParenthesizedLine(line: String): Boolean {
        val t = line.trim()
        return (t.startsWith("(") && t.endsWith(")")) || (t.startsWith("（") && t.endsWith("）"))
    }

    /**
     * 拆分树维 EAMS 标题行中的课程名与教师名：
     * - `高等数学A(10101) (张老师)` -> name=`高等数学A`, teacher=`张老师`
     * - `大学体育 (王教练,李教练)` -> name=`大学体育`, teacher=`王教练,李教练`
     * - `数据结构(必修)` -> name=`数据结构`, teacher=``
     */
    private fun splitEamsTitleAndTeacher(line: String): Pair<String, String> {
        val trimmed = line.trim()
        val trailingParen = Regex("""^(.+?)\s*[（(]([^()（）]{1,24})[)）]\s*$""").matchEntire(trimmed)
        if (trailingParen != null) {
            val left = trailingParen.groupValues[1].trim()
            val inside = trailingParen.groupValues[2].trim()
            val looksLikeCodeOrTag = inside.all { it.isDigit() || it == '.' || it == '-' || it == '_' } ||
                inside in setOf("必修", "选修", "限选", "任选", "通识", "实验", "上机", "实践") ||
                Regex("^[A-Za-z0-9上中下甲乙丙一二三四ⅠⅡⅢIV]+$").matches(inside)
            if (!looksLikeCodeOrTag && left.isNotBlank()) {
                return cleanEamsCourseName(left) to inside
            }
        }
        return cleanEamsCourseName(trimmed) to ""
    }

    /** 清理 EAMS 课程名末尾常见的课程序号，例如 `高等数学A(10101.01)` -> `高等数学A`。 */
    private fun cleanEamsCourseName(raw: String): String {
        return raw
            .replace("&nbsp;", " ", ignoreCase = true)
            .trim()
            .replace(Regex("""\s*[（(][A-Za-z0-9._\-]{3,20}[)）]\s*$"""), "")
            .replace(Regex("""[（(]?(?:必修|选修)[)）]?"""), "")
            .replace(Regex("""\s*[（(]\d{1,2}[)）]\s*$"""), "")
            .trim()
            .ifBlank { raw.trim() }
    }

    /**
     * 1:1 复现树维 `TaskActivity.js` 的 53 位自然周位图解码逻辑：
     * - 已知 `from`（来自 `table0.marshalTable(from, startWeek, endWeek)`）：
     *   支持 `endAtSat == false` 时的 `leftShift()` 以及跨年回绕，并按 `semesterWeek = idx - from + 2` 映射学期周。
     * - 未知 `from`：若所有 `'1'` 均集中在下标 `1..25`，默认按 EAMS `idx` 即第 `idx` 周（第 0 位占位）；否则返回 null 交给人工复核。
     */
    internal fun decodeEamsBitmask(
        validWeeks: String,
        fromOffset: Int?,
        startWeek: Int = 1,
        endWeek: Int = 25,
        endAtSat: Boolean? = null
    ): ChineseCourseTextParser.ParsedWeekSpec? {
        val raw = validWeeks.trim()
        if (raw.length < 10 || !raw.all { it == '0' || it == '1' }) return null

        if (fromOffset != null && fromOffset >= 1) {
            var base = raw
            val validStart = (fromOffset + startWeek - 2).coerceIn(0, base.length)
            if (endAtSat == false && validStart > 0 &&
                base.substring(0, validStart).contains('1') &&
                !base.substring(validStart).contains('1')
            ) {
                base = base.substring(1) + "0"
            }
            var occupy = base
            if (fromOffset > 1 && fromOffset - 1 < occupy.length) {
                val before = occupy.substring(0, fromOffset - 1)
                if (before.contains('1')) {
                    occupy += before
                }
            }
            val startIdx = (fromOffset + startWeek - 2).coerceAtLeast(0)
            val endIdxExclusive = (fromOffset + endWeek - 1).coerceAtMost(occupy.length)
            val weeks = mutableListOf<Int>()
            for (i in startIdx until endIdxExclusive) {
                if (occupy[i] == '1') {
                    val semWeek = i - fromOffset + 2
                    if (semWeek in 1..30) weeks += semWeek
                }
            }
            return ChineseCourseTextParser.buildWeekSpecFromSortedWeeks(weeks)
        }

        // 未指定 fromOffset 时：仅在位图直接表示学期周（第 0 位为 0，1..25 位存在上课周且后续全 0）时直接解码
        val activeIndices = raw.mapIndexedNotNull { idx, ch -> if (ch == '1') idx else null }
        if (activeIndices.isEmpty()) return null
        if (activeIndices.first() >= 1 && activeIndices.last() <= 26) {
            return ChineseCourseTextParser.buildWeekSpecFromSortedWeeks(activeIndices)
        }
        return null
    }

    private fun resolveUnitsPerDay(explicitUnitsPerDay: Int, unitCounts: Int): Int {
        if (explicitUnitsPerDay in 6..20) return explicitUnitsPerDay
        if (unitCounts >= 42 && unitCounts % 7 == 0) {
            val divided = unitCounts / 7
            if (divided in 6..20) return divided
        }
        return 12
    }

    private fun mergeContiguousSlots(slots: List<RawEamsSlot>): List<ParsedCourseCandidate> {
        if (slots.isEmpty()) return emptyList()
        data class MergeKey(
            val dayOfWeek: Int,
            val courseName: String,
            val teacher: String,
            val location: String,
            val weekSpec: ChineseCourseTextParser.ParsedWeekSpec?
        )

        val grouped = slots.groupBy {
            MergeKey(it.dayOfWeek, it.courseName, it.teacher, it.location, it.weekSpec)
        }
        val candidates = mutableListOf<ParsedCourseCandidate>()
        for ((key, items) in grouped) {
            val sortedPeriods = items.map { it.period }.distinct().sorted()
            var runStart = sortedPeriods.first()
            var runEnd = runStart
            for (p in sortedPeriods.drop(1)) {
                if (p == runEnd + 1) {
                    runEnd = p
                } else {
                    candidates += buildCandidate(
                        courseName = key.courseName,
                        teacher = key.teacher,
                        location = key.location,
                        dayOfWeek = key.dayOfWeek,
                        startPeriod = runStart,
                        endPeriod = runEnd,
                        weekSpec = key.weekSpec,
                        rawSnippet = items.first().rawSnippet
                    )
                    runStart = p
                    runEnd = p
                }
            }
            candidates += buildCandidate(
                courseName = key.courseName,
                teacher = key.teacher,
                location = key.location,
                dayOfWeek = key.dayOfWeek,
                startPeriod = runStart,
                endPeriod = runEnd,
                weekSpec = key.weekSpec,
                rawSnippet = items.first().rawSnippet
            )
        }
        return candidates.sortedWith(compareBy<ParsedCourseCandidate> { it.dayOfWeek }.thenBy { it.startPeriod }.thenBy { it.name })
    }

    private fun buildCandidate(
        courseName: String,
        teacher: String,
        location: String,
        dayOfWeek: Int,
        startPeriod: Int,
        endPeriod: Int,
        weekSpec: ChineseCourseTextParser.ParsedWeekSpec?,
        rawSnippet: String
    ): ParsedCourseCandidate {
        val warnings = mutableListOf<String>()
        if (weekSpec == null) warnings += "未识别树维周次，默认1-16周"
        if (location.isBlank()) warnings += "未识别教室"
        return ParsedCourseCandidate(
            name = courseName,
            teacher = teacher,
            location = location,
            dayOfWeek = dayOfWeek.coerceIn(1, 7),
            startPeriod = startPeriod.coerceIn(1, 20),
            endPeriod = endPeriod.coerceIn(startPeriod.coerceIn(1, 20), 20),
            weekRuleType = weekSpec?.ruleType ?: WeekRuleType.ALL,
            startWeek = weekSpec?.startWeek ?: 1,
            endWeek = weekSpec?.endWeek ?: 16,
            customWeeks = weekSpec?.customWeeks ?: emptyList(),
            courseType = if (PeCourseDetector.isLikelyPeCourse(courseName)) {
                CourseType.PHYSICAL_EDUCATION
            } else {
                CourseType.NORMAL
            },
            rawSnippet = rawSnippet,
            needsManualReview = warnings.isNotEmpty(),
            reviewWarning = warnings.joinToString("；").ifBlank { null }
        )
    }

    private fun splitJsArguments(rawArgs: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var parenDepth = 0
        for (ch in rawArgs) {
            if (escaped) {
                sb.append(ch)
                escaped = false
                continue
            }
            if (quote != null) {
                if (ch == '\\') {
                    sb.append(ch)
                    escaped = true
                } else {
                    sb.append(ch)
                    if (ch == quote) quote = null
                }
            } else {
                when (ch) {
                    '\'', '"' -> {
                        quote = ch
                        sb.append(ch)
                    }
                    '(' -> {
                        parenDepth++
                        sb.append(ch)
                    }
                    ')' -> {
                        if (parenDepth > 0) parenDepth--
                        sb.append(ch)
                    }
                    ',' -> {
                        if (parenDepth == 0) {
                            out += sb.toString().trim()
                            sb.clear()
                        } else {
                            sb.append(ch)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }
        if (sb.isNotEmpty()) out += sb.toString().trim()
        return out
    }

    private fun decodeJsArg(arg: String): String {
        val t = arg.trim()
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .trim()
        }
        return if (t == "null" || t == "undefined") "" else t
    }

    private data class ParsedTaskActivityDecl(
        val courseName: String,
        val rawCourseName: String,
        val teacher: String,
        val room: String,
        val validWeeks: String
    )

    private data class RawEamsSlot(
        val courseName: String,
        val teacher: String,
        val location: String,
        val dayOfWeek: Int,
        val period: Int,
        val weekSpec: ChineseCourseTextParser.ParsedWeekSpec?,
        val rawSnippet: String
    )

    internal data class ParsedEamsCellEntry(
        val rawTitle: String,
        val courseName: String,
        val teacher: String,
        val location: String,
        val weekSpec: ChineseCourseTextParser.ParsedWeekSpec?,
        val rawSnippet: String
    )
}
