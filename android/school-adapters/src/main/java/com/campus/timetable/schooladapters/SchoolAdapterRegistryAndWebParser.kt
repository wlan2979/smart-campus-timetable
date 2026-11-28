package com.campus.timetable.schooladapters

import com.campus.timetable.domain.engine.ChineseCourseTextParser
import com.campus.timetable.domain.engine.PeCourseDetector
import com.campus.timetable.domain.model.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 学校适配器注册表：支持正方、强智、新 URP、旧 URP、树维 EAMS 五大通用教务适配器共享机制 + 学校专属配置层。
 * 严禁将未经过实校导入验证的学校标记为「已验证支持」。
 */
@Singleton
class SchoolAdapterRegistry @Inject constructor(
    zhengfangAdapter: ZhengfangSchoolAdapter,
    qiangzhiAdapter: QiangzhiSchoolAdapter,
    newUrpAdapter: NewUrpSchoolAdapter,
    oldUrpAdapter: OldUrpSchoolAdapter,
    eamsAdapter: EamsSchoolAdapter
) {
    private val adaptersById = linkedMapOf<String, SchoolAdapter>()

    init {
        register(zhengfangAdapter)
        register(qiangzhiAdapter)
        register(newUrpAdapter)
        register(oldUrpAdapter)
        register(eamsAdapter)
    }

    fun register(adapter: SchoolAdapter) {
        adaptersById[adapter.adapterId] = adapter
    }

    fun allAdapters(): List<SchoolAdapter> = adaptersById.values.toList()

    fun findById(adapterId: String): SchoolAdapter? = adaptersById[adapterId]

    fun resolveForSchool(school: School): AdapterLookupResult {
        val candidate = school.adapterId?.let { adaptersById[it] }
            ?: adaptersById.values.firstOrNull { it.systemType == school.systemType && school.systemType != EduSystemType.UNKNOWN }

        if (candidate != null && candidate.isVerifiedFor(school)) {
            return AdapterLookupResult.Verified(candidate, candidate.buildAuthSpec(school))
        }

        if (school.portalUrl.isNotBlank() &&
            (school.supportStatus == SchoolSupportStatus.GENERIC_ADAPTER || school.supportStatus == SchoolSupportStatus.ASSISTED_LOGIN)
        ) {
            val authSpec = candidate?.buildAuthSpec(school) ?: AdapterAuthSpec(
                loginUrl = school.portalUrl,
                instruction = "请在本应用内置受控浏览器中自行完成「${school.name}」官方门户登录与验证码校验，进入个人课表页后点击右上角「提取课表」。本应用绝不保存您的教务密码。"
            )
            val msg = if (candidate != null &&
                (school.supportStatus == SchoolSupportStatus.GENERIC_ADAPTER || school.supportStatus == SchoolSupportStatus.ASSISTED_LOGIN)
            ) {
                "「${school.name}」已匹配【${candidate.displayName}】与官方入口，但尚未完成实校账号终验；可立即进入受控浏览器辅助登录提取课表，或使用 Excel/PDF 导入。"
            } else {
                "「${school.name}」为自研/统一门户教务，已配置官方入口；请使用受控浏览器自行完成登录后尝试提取，或导出 Excel/PDF 导入。"
            }
            return AdapterLookupResult.GenericOrAssisted(candidate, school, authSpec, msg)
        }

        val reason = buildString {
            append("「${school.name}」已收录于全国高校目录，当前状态：${school.supportStatus.label}。")
            if (school.statusNote.isNotBlank()) {
                append(school.statusNote)
            } else {
                append("建议从教务系统导出 Excel（.xlsx/.xls）或 PDF 课表文件导入，也可通过网页辅助导入或提交适配申请。")
            }
        }
        return AdapterLookupResult.UnverifiedFramework(candidate, school, reason)
    }
}

sealed interface WebParseOutcome {
    data class Success(
        val candidates: List<ParsedCourseCandidate>,
        val detectedEncoding: String,
        val summary: String,
        /** 解析可信度分级：3=结构化运行时/JSON/脚本，2=专用教务 HTML 适配器，1=通用 HTML 表格，0=自由文本兜底 */
        val qualityTier: Int = 1,
        /** 从学校官网课表网页提取到的真实节次上课/休息时间 */
        val detectedPeriods: List<ExtractedClassPeriod> = emptyList(),
        /** 从学校官网教务页提取到的当前教学周提示（如第 13 周） */
        val detectedCurrentWeek: Int? = null
    ) : WebParseOutcome

    data class LoginRequired(
        val url: String,
        val reason: String
    ) : WebParseOutcome

    data class DynamicJsPageHint(
        val url: String,
        val reason: String
    ) : WebParseOutcome

    data class Failed(val message: String) : WebParseOutcome
}

@Singleton
class WebTimetableParser @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val zhengfangAdapter: ZhengfangSchoolAdapter,
    private val qiangzhiAdapter: QiangzhiSchoolAdapter,
    private val newUrpAdapter: NewUrpSchoolAdapter,
    private val oldUrpAdapter: OldUrpSchoolAdapter,
    private val eamsAdapter: EamsSchoolAdapter
) {

    suspend fun fetchAndParseUrl(rawUrl: String): WebParseOutcome = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext WebParseOutcome.Failed("请输入以 http:// 或 https:// 开头的有效网页链接")
        }

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    return@withContext WebParseOutcome.LoginRequired(url, "目标网页返回 HTTP ${resp.code}，需要校园账号登录授权。请使用受控浏览器完成官方登录后提取。")
                }
                if (!resp.isSuccessful) {
                    return@withContext WebParseOutcome.Failed("网页访问失败（HTTP ${resp.code} ${resp.message}）")
                }

                val bytes = resp.body?.bytes() ?: return@withContext WebParseOutcome.Failed("网页返回空内容")
                val headerCharset = resp.body?.contentType()?.charset()?.name()
                val (html, encoding) = decodeWithSmartCharset(bytes, headerCharset)
                val finalUrl = resp.request.url.toString()

                parseRenderedHtml(html, finalUrl, encoding, fromWebView = false)
            }
        } catch (e: Exception) {
            WebParseOutcome.Failed("无法访问该网页链接：${e.localizedMessage ?: "网络连接失败"}。如该地址仅限校园内网访问，请先连接校园网或使用文件导入。")
        }
    }

    /**
     * 批量解析主文档及所有可访问的同源 iframe/frame 文档，选取课表候选最完整的一帧。
     * 解决正方/强智/旧 URP/树维 EAMS 将学生课表嵌在 iframe 或 frameset 中导致顶层 outerHTML 抓空的问题。
     */
    fun parseRenderedDocuments(
        documents: List<Pair<String, String>>,
        encoding: String = "UTF-8",
        fromWebView: Boolean = true
    ): WebParseOutcome {
        val validDocs = documents.filter { it.second.isNotBlank() }
        if (validDocs.isEmpty()) {
            return WebParseOutcome.Failed("页面内容为空，无法解析课程")
        }
        if (validDocs.size == 1) {
            return parseRenderedHtml(
                html = validDocs.first().second,
                sourceUrl = validDocs.first().first,
                encoding = encoding,
                fromWebView = fromWebView
            )
        }

        val outcomes = validDocs.map { (url, html) ->
            parseRenderedHtml(html = html, sourceUrl = url, encoding = encoding, fromWebView = fromWebView)
        }
        // 择优排序：专用教务适配器/运行时对象永远优先于通用 HTML 表格兜底；
        // 同级别内优先选无需人工复核且非括号假课、含真实连堂节次的结果，防止拆碎假课凭条数多反超精准解析。
        val bestSuccess = outcomes.filterIsInstance<WebParseOutcome.Success>()
            .maxWithOrNull(
                compareBy<WebParseOutcome.Success> { it.qualityTier }
                    .thenBy { s -> s.candidates.count { !it.needsManualReview && !it.name.startsWith("(") && !it.name.startsWith("（") } }
                    .thenBy { s ->
                        val total = s.candidates.size.coerceAtLeast(1)
                        (s.candidates.count { !it.name.startsWith("(") && !it.name.startsWith("（") } * 100) / total
                    }
                    .thenBy { s -> s.candidates.count { it.endPeriod > it.startPeriod } }
                    .thenBy { it.candidates.size }
            )
        if (bestSuccess != null) {
            val allPeriods = OfficialBellScheduleExtractor.extractBellPeriodsFromDocuments(validDocs)
            val allWeekHint = bestSuccess.detectedCurrentWeek
                ?: OfficialBellScheduleExtractor.extractCurrentWeekHintFromDocuments(validDocs)
            return bestSuccess.copy(
                detectedPeriods = allPeriods.ifEmpty { bestSuccess.detectedPeriods },
                detectedCurrentWeek = allWeekHint
            )
        }
        outcomes.filterIsInstance<WebParseOutcome.LoginRequired>().firstOrNull()?.let { return it }
        outcomes.filterIsInstance<WebParseOutcome.DynamicJsPageHint>().firstOrNull()?.let { return it }
        val firstFailure = outcomes.filterIsInstance<WebParseOutcome.Failed>().firstOrNull()
        return WebParseOutcome.Failed(
            firstFailure?.message ?: "已检查主页面及 ${validDocs.size - 1} 个内嵌框架，均未发现有效课表结构，请确认已进入「学生课表 / 本学期课表」页面或改用 Excel / PDF 导入。"
        )
    }

    fun parseRenderedHtml(
        html: String,
        sourceUrl: String,
        encoding: String = "UTF-8",
        fromWebView: Boolean = true
    ): WebParseOutcome {
        val trimmed = html.trim()
        if (trimmed.isEmpty()) {
            return WebParseOutcome.Failed("页面内容为空，无法解析课程")
        }

        // 1. JSON 教务响应自动路由（树维 EAMS 运行时课表 / 正方 kbList / 新 URP dateList）
        if (trimmed.startsWith("{")) {
            if (trimmed.contains("__eamsRuntimeTable")) {
                return when (val r = eamsAdapter.parseTimetablePayload(trimmed, isJson = true)) {
                    is AdapterParseResult.Success -> WebParseOutcome.Success(r.candidates, encoding, r.notice, qualityTier = 3)
                    is AdapterParseResult.AuthExpired -> WebParseOutcome.LoginRequired(sourceUrl, r.message)
                    is AdapterParseResult.Failure -> WebParseOutcome.Failed(r.message)
                }
            }
            if (trimmed.contains("kbList")) {
                return when (val r = zhengfangAdapter.parseTimetablePayload(trimmed, isJson = true)) {
                    is AdapterParseResult.Success -> WebParseOutcome.Success(r.candidates, encoding, r.notice, qualityTier = 3)
                    is AdapterParseResult.AuthExpired -> WebParseOutcome.LoginRequired(sourceUrl, r.message)
                    is AdapterParseResult.Failure -> WebParseOutcome.Failed(r.message)
                }
            }
            if (trimmed.contains("dateList") && trimmed.contains("selectCourseList")) {
                return when (val r = newUrpAdapter.parseTimetablePayload(trimmed, isJson = true)) {
                    is AdapterParseResult.Success -> WebParseOutcome.Success(r.candidates, encoding, r.notice, qualityTier = 3)
                    is AdapterParseResult.AuthExpired -> WebParseOutcome.LoginRequired(sourceUrl, r.message)
                    is AdapterParseResult.Failure -> WebParseOutcome.Failed(r.message)
                }
            }
        }

        val doc = Jsoup.parse(trimmed, sourceUrl)
        val bodyText = doc.body()?.text().orEmpty()
        val singleDocList = listOf(sourceUrl to trimmed)
        val detectedPeriods = OfficialBellScheduleExtractor.extractBellPeriodsFromDocuments(singleDocList)
        val detectedWeekHint = OfficialBellScheduleExtractor.extractCurrentWeekHintFromDocuments(singleDocList)

        // 2. 登录墙与登录态失效检测
        val hasPasswordInput = doc.select("input[type=password]").isNotEmpty()
        val hasEamsTimetableSignal = trimmed.contains("TaskActivity") ||
            doc.select("#manualArrangeCourseTable, table[id^=manualArrangeCourseTable]").isNotEmpty() ||
            doc.select("td[id~=^(?i)TD\\d+(_\\d+)?$]").size >= 10
        val hasTimetableSignal = hasEamsTimetableSignal ||
            bodyText.contains("星期一") || bodyText.contains("周一") ||
            doc.select("#Table1, #kbtable, #kbgrid_table_0, table.displayTag, #courseTable").isNotEmpty()
        if ((hasPasswordInput || sourceUrl.contains("login", true) || sourceUrl.contains("authserver", true)) && !hasTimetableSignal) {
            return WebParseOutcome.LoginRequired(
                url = sourceUrl,
                reason = "当前页面仍是教务登录页（或登录状态已失效）。请先在页内完成登录与验证码，进入「学生课表 / 本学期课表」后再点「提取课表」；本应用不保存、不拦截您的教务密码。"
            )
        }

        // 3. 按主流教务特征依次尝试共享适配器（树维 EAMS -> 强智 -> 正方 -> 旧 URP -> 新 URP）
        if (hasEamsTimetableSignal) {
            val eams = eamsAdapter.parseTimetablePayload(trimmed, isJson = false)
            if (eams is AdapterParseResult.Success) {
                val tier = if (trimmed.contains("TaskActivity")) 3 else 2
                return WebParseOutcome.Success(eams.candidates, encoding, eams.notice, qualityTier = tier, detectedPeriods = detectedPeriods, detectedCurrentWeek = detectedWeekHint)
            }
        }
        if (doc.select("#kbtable").isNotEmpty() || doc.select(".kbcontent").isNotEmpty()) {
            val qz = qiangzhiAdapter.parseTimetablePayload(trimmed, isJson = false)
            if (qz is AdapterParseResult.Success) return WebParseOutcome.Success(qz.candidates, encoding, qz.notice, qualityTier = 2, detectedPeriods = detectedPeriods, detectedCurrentWeek = detectedWeekHint)
        }
        if (doc.select("#Table1, #kbgrid_table_0").isNotEmpty()) {
            val zf = zhengfangAdapter.parseTimetablePayload(trimmed, isJson = false)
            if (zf is AdapterParseResult.Success) return WebParseOutcome.Success(zf.candidates, encoding, zf.notice, qualityTier = 2, detectedPeriods = detectedPeriods, detectedCurrentWeek = detectedWeekHint)
        }
        if (doc.select("table.displayTag").isNotEmpty()) {
            val oldU = oldUrpAdapter.parseTimetablePayload(trimmed, isJson = false)
            if (oldU is AdapterParseResult.Success) return WebParseOutcome.Success(oldU.candidates, encoding, oldU.notice, qualityTier = 2, detectedPeriods = detectedPeriods, detectedCurrentWeek = detectedWeekHint)
        }
        if (doc.select("#courseTable").isNotEmpty()) {
            val newU = newUrpAdapter.parseTimetablePayload(trimmed, isJson = false)
            if (newU is AdapterParseResult.Success) return WebParseOutcome.Success(newU.candidates, encoding, newU.notice, qualityTier = 2, detectedPeriods = detectedPeriods, detectedCurrentWeek = detectedWeekHint)
        }

        // 4. 通用 HTML 表格解析：教务处页面常混有导航、新闻、页脚表格，只采用最像课表的那一张，
        //    避免把新闻列表等无关表格误识别成课程。
        val tableResults = doc.select("table")
            .map { parseHtmlTableToCandidates(it) }
            .filter { it.isNotEmpty() }
        val bestTable = tableResults.maxByOrNull { it.size }
        if (bestTable != null) {
            return WebParseOutcome.Success(
                candidates = bestTable,
                detectedEncoding = encoding,
                summary = "已从网页课表表格提取 ${bestTable.size} 条课程记录（编码：$encoding），请核对预览后保存",
                qualityTier = 1,
                detectedPeriods = detectedPeriods,
                detectedCurrentWeek = detectedWeekHint
            )
        }

        // 5. 自由文本兜底：仅当页面确实具备「星期 + 节次 + 周次」结构时才启用，
        //    否则教务处首页、通知页的零散文字会被误识别成假课程。
        val pageText = doc.body()?.wholeText().orEmpty()
        val freeText = if (looksLikeWeeklyTimetableText(pageText)) {
            ChineseCourseTextParser.parseFreeTextLines(pageText)
        } else {
            emptyList()
        }
        if (freeText.isNotEmpty()) {
            return WebParseOutcome.Success(
                candidates = freeText,
                detectedEncoding = encoding,
                summary = "已从网页文本识别 ${freeText.size} 条课程记录，部分字段可能需要人工校正",
                qualityTier = 0,
                detectedPeriods = detectedPeriods,
                detectedCurrentWeek = detectedWeekHint
            )
        }

        // 6. JS 动态渲染检测
        val isSpaShell = !fromWebView && (
            doc.select("script").size >= 2 && bodyText.length < 180 ||
                doc.select("#app, #root").isNotEmpty() && bodyText.length < 240
            )
        if (isSpaShell) {
            return WebParseOutcome.DynamicJsPageHint(
                url = sourceUrl,
                reason = "该页面依赖 JavaScript 动态渲染，直接 HTTP 读取无法获取课程内容。请使用内置受控浏览器打开页面，等待课表加载完成后再点击提取。"
            )
        }

        // 6.5 精准区分「已登录树维 EAMS 首页」与「未登录教务处首页」，避免已登录用户看到「请先登录」感到困惑
        if (EduPortalShortcutResolver.isLikelyLoggedInEamsHome(sourceUrl, doc)) {
            return WebParseOutcome.Failed(
                "检测到您已登录树维 EAMS 教务系统，当前仍停留在教务首页（而非课表页）。" +
                    "请点击下方「前往我的课表页」一键直达学生课表，待课表完整显示后再点击右上角「提取课表」。"
            )
        }
        if (sourceUrl.lowercase().contains("coursetableforstd")) {
            return WebParseOutcome.Failed(
                "已进入树维 EAMS 课表页，但尚未检测到课程数据：请稍等 1-2 秒待课表表格加载完毕，或确认页面上方已选择正确学期后再点击「提取课表」。"
            )
        }

        val tableCount = doc.select("table").size
        return WebParseOutcome.Failed(
            "未在当前页面识别出课表：已检查 $tableCount 张表格与页面文本，未发现「星期 + 节次 + 周次」的排课结构（编码：$encoding）。" +
                "如果您仍停留在教务处首页，请先登录教务系统并打开「学生课表 / 本学期课表」页面后再点「提取课表」；" +
                "若课程页由网页内部框架加载，请等课表完整显示后再提取，或改用 Excel / PDF 导入。"
        )
    }

    private fun decodeWithSmartCharset(bytes: ByteArray, headerCharset: String?): Pair<String, String> {
        val probeAscii = String(bytes, 0, bytes.size.coerceAtMost(4096), Charsets.ISO_8859_1)
        val metaMatch = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9\\-_]+)", RegexOption.IGNORE_CASE).find(probeAscii)
        val chosenName = metaMatch?.groupValues?.getOrNull(1)
            ?: headerCharset
            ?: "UTF-8"
        val charset = runCatching { Charset.forName(chosenName) }.getOrElse { Charsets.UTF_8 }
        val decoded = String(bytes, charset)
        if (charset == Charsets.UTF_8 && decoded.count { it == '\uFFFD' } >= 5) {
            val gbk = runCatching { Charset.forName("GBK") }.getOrNull()
            if (gbk != null) return String(bytes, gbk) to "GBK"
        }
        return decoded to charset.name()
    }

    companion object {
        /**
         * 把 HTML 表格展开成二维单元格网格，正确处理 rowspan / colspan 与嵌套表格。
         */
        private fun expandGrid(table: Element): List<List<Element?>> {
            val rows = table.select("tr").filter { row ->
                row.parents().firstOrNull { it.tagName() == "table" } == table
            }
            val grid = mutableListOf<MutableList<Element?>>()
            val pendingRowSpans = mutableMapOf<Int, Pair<Element, Int>>()

            fun ensureSize(line: MutableList<Element?>, col: Int) {
                while (line.size <= col) line.add(null)
            }

            // 仅消耗当前列及后续连续被上一行 rowspan 占用的列，绝不跳过前面的空闲列
            fun consumePendingAt(line: MutableList<Element?>, startCol: Int): Int {
                var col = startCol
                while (pendingRowSpans.containsKey(col)) {
                    val pending = pendingRowSpans[col]!!
                    ensureSize(line, col)
                    line[col] = pending.first
                    if (pending.second <= 1) pendingRowSpans.remove(col)
                    else pendingRowSpans[col] = pending.first to (pending.second - 1)
                    col++
                }
                return col
            }

            for (row in rows) {
                val line = mutableListOf<Element?>()
                var col = 0
                val cells = row.select("th, td").filter { cell ->
                    cell.parents().firstOrNull { it.tagName() == "tr" } == row
                }
                for (cell in cells) {
                    col = consumePendingAt(line, col)
                    val colspan = maxOf(
                        cell.attr("colspan").toIntOrNull()?.coerceIn(1, 12) ?: 1,
                        cell.attr("data-agy-colspan").toIntOrNull()?.coerceIn(1, 12) ?: 1
                    )
                    val rowspan = maxOf(
                        cell.attr("rowspan").toIntOrNull()?.coerceIn(1, 12) ?: 1,
                        cell.attr("data-agy-rowspan").toIntOrNull()?.coerceIn(1, 12) ?: 1
                    )
                    for (offset in 0 until colspan) {
                        val targetCol = col + offset
                        ensureSize(line, targetCol)
                        line[targetCol] = cell
                        if (rowspan > 1) {
                            pendingRowSpans[targetCol] = cell to (rowspan - 1)
                        }
                    }
                    col += colspan
                }
                val remainingTailCols = pendingRowSpans.keys.filter { it >= col }.sorted()
                for (tailCol in remainingTailCols) {
                    val pending = pendingRowSpans[tailCol] ?: continue
                    ensureSize(line, tailCol)
                    line[tailCol] = pending.first
                    if (pending.second <= 1) pendingRowSpans.remove(tailCol)
                    else pendingRowSpans[tailCol] = pending.first to (pending.second - 1)
                }
                grid.add(line)
            }
            return grid
        }

        private val barePeriodRangeRegex = Regex("^\\s*(\\d{1,2})(?:\\s*[-~至、,]\\s*(\\d{1,2}))?\\s*$")

        private fun parseRowHeaderPeriods(row: List<Element?>, firstDayCol: Int): Pair<Int, Int>? {
            val scanEnd = firstDayCol.coerceAtLeast(1).coerceAtMost(row.size)
            for (c in 0 until scanEnd) {
                val text = row[c]?.text()?.trim().orEmpty()
                if (text.isEmpty()) continue
                ChineseCourseTextParser.parsePeriods(text)?.let { return it }
            }
            // 兼容 EAMS / URP 仅在节次列写纯数字「1」「2」或「1-2」的情况
            for (c in 0 until scanEnd) {
                val text = row[c]?.text()?.trim().orEmpty()
                val m = barePeriodRangeRegex.matchEntire(text) ?: continue
                val start = m.groupValues[1].toIntOrNull() ?: continue
                val end = m.groupValues[2].toIntOrNull() ?: start
                if (start in 1..20 && end in start..20) return start to end
            }
            return null
        }

        fun parseHtmlTableToCandidates(table: Element): List<ParsedCourseCandidate> {
            val grid = expandGrid(table)
            if (grid.size < 2) return emptyList()

            var headerRowIndex = -1
            val colToDay = mutableMapOf<Int, Int>()
            for (rIdx in 0 until grid.size.coerceAtMost(4)) {
                val tempMap = mutableMapOf<Int, Int>()
                grid[rIdx].forEachIndexed { cIdx, cell ->
                    val dow = cell?.text()?.let { ChineseCourseTextParser.parseDayOfWeek(it) }
                    if (dow != null) tempMap[cIdx] = dow
                }
                if (tempMap.values.distinct().size >= 3) {
                    headerRowIndex = rIdx
                    colToDay.putAll(tempMap)
                    break
                }
            }

            val candidates = mutableListOf<ParsedCourseCandidate>()
            if (headerRowIndex >= 0 && colToDay.isNotEmpty()) {
                val firstDayCol = colToDay.keys.minOrNull() ?: 1
                val bodyRowCount = grid.size - (headerRowIndex + 1)
                val defaultRowSpanSize = if (bodyRowCount >= 8) 1 else 2

                // 先预计算每一行对应的节次范围，确保跨行 rowspan 单元格能准确取到跨越最后一行的结束节次
                val rowPeriodRanges = mutableMapOf<Int, Pair<Int, Int>>()
                var runningPeriod = 1
                for (rIdx in (headerRowIndex + 1) until grid.size) {
                    val parsedPeriods = parseRowHeaderPeriods(grid[rIdx], firstDayCol)
                    val startP = parsedPeriods?.first ?: runningPeriod
                    val endP = parsedPeriods?.second ?: (startP + defaultRowSpanSize - 1)
                    rowPeriodRanges[rIdx] = startP to endP
                    runningPeriod = endP + 1
                }

                // 跨行课程单元格只在其首次出现的行解析一次，避免同一门课被重复导入
                val consumedCells = mutableSetOf<Element>()
                for (rIdx in (headerRowIndex + 1) until grid.size) {
                    val row = grid[rIdx]
                    val (startP, endP) = rowPeriodRanges[rIdx] ?: continue

                    row.forEachIndexed { cIdx, cell ->
                        if (cell == null) return@forEachIndexed
                        val dow = colToDay[cIdx] ?: return@forEachIndexed
                        if (!consumedCells.add(cell)) return@forEachIndexed
                        val htmlWithNewlines = cell.html()
                            // HTML 源码换行与 Jsoup pretty-print 自动插入的换行不代表空行，先去除再按真实 <br>/<hr> 断行
                            .replace(Regex("[\\r\\n]+"), "")
                            .replace(Regex("(<br\\s*/?>\\s*){2,}|<hr\\s*/?>", RegexOption.IGNORE_CASE), "\n\n")
                            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                            .replace(Regex("</p>|</div>", RegexOption.IGNORE_CASE), "\n")
                        val cellText = Jsoup.parse(htmlWithNewlines).wholeText()
                        val rowSpan = maxOf(
                            cell.attr("rowspan").toIntOrNull()?.coerceIn(1, 12) ?: 1,
                            cell.attr("data-agy-rowspan").toIntOrNull()?.coerceIn(1, 12) ?: 1
                        )
                        val lastCoveredRow = (rIdx + rowSpan - 1).coerceAtMost(grid.lastIndex)
                        val cellEnd = if (rowSpan > 1) {
                            rowPeriodRanges[lastCoveredRow]?.second ?: (endP + rowSpan - 1)
                        } else {
                            endP
                        }.coerceAtLeast(startP)
                        candidates += ChineseCourseTextParser.parseGridCell(cellText, dow, startP, cellEnd)
                    }
                }
                return candidates
            }

            // 兼容横置课表（常见于树维 EAMS：行头为星期一..星期日，列头为第 1..N 节，跨节次用 colspan 表示）
            val rowToDay = mutableMapOf<Int, Int>()
            for (rIdx in grid.indices) {
                val dow = grid[rIdx].firstOrNull()?.text()?.let { ChineseCourseTextParser.parseDayOfWeek(it) }
                if (dow != null) rowToDay[rIdx] = dow
            }
            if (rowToDay.values.distinct().size >= 3) {
                val firstDayRow = rowToDay.keys.minOrNull() ?: 1
                val colToPeriod = mutableMapOf<Int, Pair<Int, Int>>()
                for (hRow in 0 until firstDayRow.coerceAtLeast(1)) {
                    grid.getOrNull(hRow)?.forEachIndexed { cIdx, cell ->
                        if (cIdx == 0 || cell == null) return@forEachIndexed
                        val text = cell.text().trim()
                        val parsed = ChineseCourseTextParser.parsePeriods(text)
                            ?: barePeriodRangeRegex.matchEntire(text)?.let { m ->
                                val s = m.groupValues[1].toIntOrNull()
                                val e = m.groupValues[2].toIntOrNull() ?: s
                                if (s != null && e != null && s in 1..20 && e in s..20) s to e else null
                            }
                        if (parsed != null) colToPeriod[cIdx] = parsed
                    }
                }
                val consumedCells = mutableSetOf<Element>()
                for ((rIdx, dow) in rowToDay) {
                    val row = grid[rIdx]
                    row.forEachIndexed { cIdx, cell ->
                        if (cIdx == 0 || cell == null) return@forEachIndexed
                        if (!consumedCells.add(cell)) return@forEachIndexed
                        val startP = colToPeriod[cIdx]?.first ?: cIdx
                        val colSpan = maxOf(
                            cell.attr("colspan").toIntOrNull()?.coerceIn(1, 12) ?: 1,
                            cell.attr("data-agy-colspan").toIntOrNull()?.coerceIn(1, 12) ?: 1
                        )
                        val lastCol = (cIdx + colSpan - 1).coerceAtMost(row.lastIndex)
                        val endP = if (colSpan > 1) {
                            colToPeriod[lastCol]?.second ?: (startP + colSpan - 1)
                        } else {
                            colToPeriod[cIdx]?.second ?: startP
                        }.coerceIn(startP.coerceIn(1, 20), 20)
                        val htmlWithNewlines = cell.html()
                            .replace(Regex("[\\r\\n]+"), "")
                            .replace(Regex("(<br\\s*/?>\\s*){2,}|<hr\\s*/?>", RegexOption.IGNORE_CASE), "\n\n")
                            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                            .replace(Regex("</p>|</div>", RegexOption.IGNORE_CASE), "\n")
                        val cellText = Jsoup.parse(htmlWithNewlines).wholeText()
                        candidates += ChineseCourseTextParser.parseGridCell(cellText, dow, startP.coerceIn(1, 20), endP)
                    }
                }
                if (candidates.isNotEmpty()) return candidates
            }

            val headerCells = grid.firstOrNull()?.map { it?.text()?.trim().orEmpty() }.orEmpty()
            val nameIdx = headerCells.indexOfFirst { it.contains("课程") }
            if (nameIdx >= 0) {
                val teacherIdx = headerCells.indexOfFirst { it.contains("教师") || it.contains("老师") }
                val locIdx = headerCells.indexOfFirst { it.contains("地点") || it.contains("教室") }
                val dayIdx = headerCells.indexOfFirst { it.contains("星期") || it.contains("周几") }
                val periodIdx = headerCells.indexOfFirst { it.contains("节次") || it.contains("时间") }
                val weekIdx = headerCells.indexOfFirst { it.contains("周次") || it.contains("起止周") }

                for (row in grid.drop(1)) {
                    val cells = row.map { it?.text()?.trim().orEmpty() }
                    val name = cells.getOrNull(nameIdx).orEmpty()
                    if (name.isBlank()) continue
                    val teacher = cells.getOrNull(teacherIdx).orEmpty()
                    val loc = cells.getOrNull(locIdx).orEmpty()
                    val dayText = cells.getOrNull(dayIdx).orEmpty()
                    val periodText = cells.getOrNull(periodIdx).orEmpty()
                    val weekText = cells.getOrNull(weekIdx).orEmpty()
                    val dow = ChineseCourseTextParser.parseDayOfWeek(dayText) ?: dayText.toIntOrNull()?.takeIf { it in 1..7 }
                    val periods = ChineseCourseTextParser.parsePeriods(periodText)
                    val weeks = ChineseCourseTextParser.parseWeeks(weekText)
                    if (name.length > 40) continue
                    candidates += ParsedCourseCandidate(
                        name = name,
                        teacher = teacher,
                        location = loc,
                        dayOfWeek = dow ?: 1,
                        startPeriod = periods?.first ?: 1,
                        endPeriod = periods?.second ?: 2,
                        weekRuleType = weeks?.ruleType ?: WeekRuleType.ALL,
                        startWeek = weeks?.startWeek ?: 1,
                        endWeek = weeks?.endWeek ?: 16,
                        customWeeks = weeks?.customWeeks ?: emptyList(),
                        courseType = if (PeCourseDetector.isLikelyPeCourse(name)) CourseType.PHYSICAL_EDUCATION else CourseType.NORMAL,
                        rawSnippet = cells.joinToString(" | "),
                        needsManualReview = dow == null || periods == null || weeks == null,
                        reviewWarning = if (dow == null || periods == null || weeks == null) "部分排课字段需人工确认" else null
                    )
                }
            }
            return candidates
        }

        /** 页面文本是否具备整周课表的三个核心特征，避免把教务处首页等普通文本页误判成课表。 */
        private fun looksLikeWeeklyTimetableText(text: String): Boolean {
            if (text.length > 30_000) return false
            val distinctDays = Regex("(?:星期|周)[一二三四五六日天]").findAll(text)
                .map { it.value.trim() }.distinct().count()
            val hasPeriod = Regex("第?\\s*\\d{1,2}\\s*(?:[-~至]\\s*\\d{1,2})?\\s*节").containsMatchIn(text)
            val hasWeek = Regex("\\d{1,2}\\s*[-~至]\\s*\\d{1,2}\\s*周|第\\s*\\d+\\s*周").containsMatchIn(text)
            return distinctDays >= 3 && hasPeriod && hasWeek
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SchoolAdaptersModule {
    @Provides
    @Singleton
    fun provideAdapterRegistry(
        zf: ZhengfangSchoolAdapter,
        qz: QiangzhiSchoolAdapter,
        newUrp: NewUrpSchoolAdapter,
        oldUrp: OldUrpSchoolAdapter,
        eams: EamsSchoolAdapter
    ): SchoolAdapterRegistry = SchoolAdapterRegistry(zf, qz, newUrp, oldUrp, eams)
}
