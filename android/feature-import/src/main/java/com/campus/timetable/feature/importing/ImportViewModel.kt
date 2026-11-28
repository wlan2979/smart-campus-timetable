package com.campus.timetable.feature.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campus.timetable.domain.engine.BellScheduleRuleEngine
import com.campus.timetable.domain.engine.ConflictDetectionEngine
import com.campus.timetable.domain.engine.PeCourseDetector
import com.campus.timetable.domain.engine.ScheduleResolutionEngine
import com.campus.timetable.domain.engine.TimetablePeriodNormalizer
import com.campus.timetable.domain.model.*
import com.campus.timetable.domain.repository.*
import com.campus.timetable.feature.importing.parser.ExcelTimetableParser
import com.campus.timetable.feature.importing.parser.PdfTimetableParser
import com.campus.timetable.schooladapters.AdapterLookupResult
import com.campus.timetable.schooladapters.SchoolAdapterRegistry
import com.campus.timetable.schooladapters.WebParseOutcome
import com.campus.timetable.schooladapters.WebTimetableParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class ImportTab(val label: String) {
    SCHOOL("学校教务"),
    FILE("文件导入"),
    WEB("网页链接"),
    HISTORY("导入记录")
}

enum class SchoolEntryKind {
    /** 指向教务系统登录页或直连业务系统（如 jwxt/eams/jwglxt/login） */
    LOGIN_SYSTEM,
    /** 指向校内统一信息门户（如 info./portal./ehall.） */
    PORTAL_HOMEPAGE,
    /** 指向教务处网站首页（如 jwc./.../jwc/） */
    ACADEMIC_HOMEPAGE,
    /** 未收录直达地址，退回教务处检索 */
    SEARCH
}

/**
 * 校级教务入口地址分类器：
 * 精确区分「教务系统登录页 / 校内统一门户 / 教务处网站 / 搜索回退」，
 * 避免裸子串 "cas" 误命中 ucass.edu.cn，也避免把校内门户误标为「教务处首页」。
 */
object SchoolEntryClassifier {
    private val loginHostLabels = setOf(
        "jwxt", "jwgl", "jwglxt", "urp", "eams", "authserver", "sso",
        "cas", "ids", "passport", "zfjw", "newjw", "zhjw", "jwmis", "xk", "bkjw"
    )
    private val portalHostLabels = setOf("info", "portal", "ehall", "my", "one", "i", "ehallapp")
    private val academicHostLabels = setOf("jwc", "dean", "jiaowu", "jwb", "jwc1")
    private val loginPathHints = listOf(
        "login", "/jwglxt", "/eams", "/authserver", "/sso", "/urp", "/xtgl", "/cas/", "zfc", "newca"
    )

    fun classify(entryUrl: String, academicUrl: String = "", isSearch: Boolean = false): SchoolEntryKind {
        if (isSearch || entryUrl.isBlank()) return SchoolEntryKind.SEARCH
        val uri = runCatching { java.net.URI(entryUrl.trim()) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val path = (uri?.path.orEmpty() + "?" + uri?.query.orEmpty()).lowercase()
        val hostLabels = host.split('.').filter { it.isNotBlank() }
        val firstLabel = hostLabels.firstOrNull().orEmpty()

        val hasLoginPath = loginPathHints.any { path.contains(it) }
        if (hasLoginPath || hostLabels.any { it in loginHostLabels }) {
            return SchoolEntryKind.LOGIN_SYSTEM
        }
        if (firstLabel in portalHostLabels) {
            return SchoolEntryKind.PORTAL_HOMEPAGE
        }
        val isJwcPath = path.contains("/jwc") || path.contains("/dean") || path.contains("/jiaowu")
        if (firstLabel in academicHostLabels || isJwcPath) {
            return SchoolEntryKind.ACADEMIC_HOMEPAGE
        }
        if (academicUrl.isNotBlank() && entryUrl.trim().trimEnd('/') == academicUrl.trim().trimEnd('/')) {
            return SchoolEntryKind.ACADEMIC_HOMEPAGE
        }
        return SchoolEntryKind.PORTAL_HOMEPAGE
    }

    fun looksLikeLoginEntry(url: String): Boolean =
        classify(url) == SchoolEntryKind.LOGIN_SYSTEM
}

data class SchoolFilter(
    val query: String = "",
    val province: String? = null,
    val pinyinLetter: String? = null
)

data class ImportUiState(
    val selectedTab: ImportTab = ImportTab.SCHOOL,
    val schoolQuery: String = "",
    val selectedProvince: String? = null,
    val selectedPinyinLetter: String? = null,
    val availableProvinces: List<String> = emptyList(),
    val catalogStats: SchoolCatalogStats? = null,
    val schools: List<School> = emptyList(),
    val selectedSchool: School? = null,
    val selectedSchoolNotice: String? = null,
    val selectedSchoolCanLaunchWebView: Boolean = false,
    val selectedSchoolPortalUrl: String = "",
    val selectedSchoolAcademicUrl: String = "",
    val selectedSchoolEntryIsSearch: Boolean = false,
    /** 入口地址精细分类（教务登录 / 校内统一门户 / 教务处首页 / 搜索） */
    val selectedSchoolEntryKind: SchoolEntryKind = SchoolEntryKind.LOGIN_SYSTEM,
    val selectedSchoolEntryLooksLikeLogin: Boolean = true,
    val adaptationRequests: List<SchoolAdaptationRequest> = emptyList(),
    val webUrlInput: String = "",
    val showControlledWebView: Boolean = false,
    val webViewInitialUrl: String = "",
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val previewSourceType: ImportSourceType? = null,
    val previewSourceName: String = "",
    val previewSchoolCode: String? = null,
    /** 原始未展开大节的解析结果，用于无损切换「一大节 = 两小节」开关 */
    val rawCandidates: List<ParsedCourseCandidate> = emptyList(),
    val candidates: List<ParsedCourseCandidate> = emptyList(),
    /** 是否自动识别出全表按大节奇数起始节占位 */
    val autoDetectedBigPeriodMode: Boolean = false,
    /** 是否将奇数单节大课自动展开为连续两小节（如 1-1 -> 1-2）并合并连续大节（如 1-2 + 3-4 -> 1-4） */
    val expandBigPeriodPairs: Boolean = false,
    val previewConflicts: List<CourseConflict> = emptyList(),
    /** 导入时是否先清空当前学期旧课表（参考 WakeUp 课程表，默认开启以避免重导产生重复/假冲突） */
    val replaceExistingOnImport: Boolean = true,
    /** 当前已保存学期的开学第一天 ISO 日期 */
    val currentSemesterStartDate: String = "",
    /** 本次导入将写入的开学第一天 ISO 日期（第 1 周周一） */
    val importSemesterStartDate: String = "",
    /** 用户是否已在本次预览中确认学期第一天 */
    val hasConfirmedSemesterStartDate: Boolean = false,
    /** 是否显示“确认本学期第一天是几月几号”对话框 */
    val showStartDateConfirmDialog: Boolean = false,
    /** 是否在确认学期第一天后自动继续完成入库 */
    val pendingConfirmImportAfterDate: Boolean = false,
    /** 从官网网页自动识别出的当前教学周提示 */
    val detectedCurrentWeekHint: Int? = null,
    /** 从官网网页课表识别出的真实节次上下课时间 */
    val detectedOfficialPeriods: List<ExtractedClassPeriod> = emptyList(),
    /** 导入课表时是否按官网识别的作息时间同步修正本学期课程与休息时间 */
    val applyOfficialPeriodsOnImport: Boolean = true,
    /** 预览当前作息规则（官网反推、或用户在导入时调整/选用的一套通用作息） */
    val previewBellRule: BellScheduleRule = BellScheduleRule(),
    /** 当网页未自带官网时间时，用户是否已选择了一套新预设/自定义作息随导入同步生效 */
    val overrideBellRuleOnImport: Boolean = false,
    /** 预览界面每门课实际时间预览所需的有效节次表 */
    val currentEffectivePeriods: List<ClassPeriod> = emptyList(),
    val histories: List<ImportHistory> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timetableRepository: TimetableRepository,
    private val schoolImportRepository: SchoolImportRepository,
    private val settingsRepository: SettingsRepository,
    private val adapterRegistry: SchoolAdapterRegistry,
    private val webTimetableParser: WebTimetableParser,
    private val excelParser: ExcelTimetableParser,
    private val pdfParser: PdfTimetableParser,
    private val alarmScheduler: AlarmSchedulerPort,
    private val weatherScheduler: WeatherSchedulerPort,
    private val widgetRefresher: WidgetRefresherPort
) : ViewModel() {

    private val _filter = MutableStateFlow(SchoolFilter())
    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            timetableRepository.ensureGuestInitialized()
            schoolImportRepository.ensureDefaultSchoolCatalog()
            val provinces = schoolImportRepository.getAvailableProvinces()
            val stats = schoolImportRepository.getSchoolCatalogStats()
            _uiState.update { it.copy(availableProvinces = provinces, catalogStats = stats) }
        }
        viewModelScope.launch {
            _filter.flatMapLatest { f ->
                schoolImportRepository.observeSchools(f.query, f.province, null)
                    .map { list ->
                        val letter = f.pinyinLetter?.uppercase()
                        if (letter.isNullOrBlank()) {
                            list
                        } else {
                            list.filter { s ->
                                val pyHead = s.pinyin.trim().firstOrNull()?.uppercaseChar()?.toString()
                                    ?: s.pinyinInitials.trim().firstOrNull()?.uppercaseChar()?.toString()
                                pyHead == letter
                            }
                        }
                    }
            }.collect { schools ->
                _uiState.update { it.copy(schools = schools) }
            }
        }
        viewModelScope.launch {
            schoolImportRepository.observeAdaptationRequests().collect { reqs ->
                _uiState.update { it.copy(adaptationRequests = reqs) }
            }
        }
        viewModelScope.launch {
            timetableRepository.observeCurrentSemester()
                .flatMapLatest { sem ->
                    _uiState.update { state ->
                        val start = sem?.startDate.orEmpty()
                        state.copy(
                            currentSemesterStartDate = start,
                            importSemesterStartDate = state.importSemesterStartDate.ifBlank { start }
                        )
                    }
                    if (sem == null) flowOf(emptyList()) else schoolImportRepository.observeImportHistories(sem.id)
                }
                .collect { histories ->
                    _uiState.update { it.copy(histories = histories) }
                }
        }
    }

    fun selectTab(tab: ImportTab) {
        _uiState.update { it.copy(selectedTab = tab, errorMessage = null) }
    }

    fun updateSchoolQuery(q: String) {
        _filter.update { it.copy(query = q) }
        _uiState.update { it.copy(schoolQuery = q) }
    }

    fun selectProvince(province: String?) {
        val clean = province?.takeIf { it != "全部省份" }
        _filter.update { it.copy(province = clean) }
        _uiState.update { it.copy(selectedProvince = clean) }
    }

    fun selectPinyinLetter(letter: String?) {
        val clean = letter?.trim()?.takeIf { it.isNotBlank() && it != "全部" }?.uppercase()
        _filter.update { it.copy(pinyinLetter = clean) }
        _uiState.update { it.copy(selectedPinyinLetter = clean) }
    }

    fun refreshRemoteCatalog() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, statusMessage = "正在检查远程高校目录与适配配置更新...") }
            schoolImportRepository.refreshSchoolCatalogFromRemote()
                .onSuccess { stats ->
                    val provinces = schoolImportRepository.getAvailableProvinces()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            availableProvinces = provinces,
                            catalogStats = stats,
                            statusMessage = "远程目录更新成功：当前共收录 ${stats.totalCatalogCount} 所高校（已配置教务入口 ${stats.configuredPortalCount} 所）"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = e.localizedMessage ?: "远程目录更新失败，当前继续使用本地缓存的教育部高校目录",
                            statusMessage = null
                        )
                    }
                }
        }
    }

    fun selectSchool(school: School) {
        viewModelScope.launch {
            when (val lookup = adapterRegistry.resolveForSchool(school)) {
                is AdapterLookupResult.Verified -> {
                    val academic = schoolImportRepository.getAcademicAffairsUrl(school.code).orEmpty()
                    val entryKind = SchoolEntryClassifier.classify(lookup.authSpec.loginUrl, academic, isSearch = false)
                    _uiState.update {
                        it.copy(
                            selectedSchool = school,
                            selectedSchoolNotice = lookup.authSpec.instruction,
                            selectedSchoolCanLaunchWebView = true,
                            selectedSchoolPortalUrl = lookup.authSpec.loginUrl,
                            selectedSchoolAcademicUrl = academic,
                            selectedSchoolEntryIsSearch = false,
                            selectedSchoolEntryKind = entryKind,
                            selectedSchoolEntryLooksLikeLogin = entryKind == SchoolEntryKind.LOGIN_SYSTEM,
                            webUrlInput = lookup.authSpec.loginUrl,
                            webViewInitialUrl = lookup.authSpec.loginUrl,
                            showControlledWebView = true,
                            previewSchoolCode = school.code,
                            errorMessage = null
                        )
                    }
                }
                is AdapterLookupResult.GenericOrAssisted -> {
                    val academic = schoolImportRepository.getAcademicAffairsUrl(school.code).orEmpty()
                    // 优先直达真实教务系统入口；只有没有系统入口时才退回教务处网站，最后才是检索。
                    val entry = lookup.authSpec.loginUrl.ifBlank { academic }.ifBlank { searchAcademicUrl(school.name) }
                    val isSearch = lookup.authSpec.loginUrl.isBlank() && academic.isBlank()
                    val entryKind = SchoolEntryClassifier.classify(entry, academic, isSearch)
                    _uiState.update {
                        it.copy(
                            selectedSchool = school,
                            selectedSchoolNotice = lookup.message,
                            selectedSchoolCanLaunchWebView = entry.isNotBlank(),
                            selectedSchoolPortalUrl = entry,
                            selectedSchoolAcademicUrl = academic,
                            selectedSchoolEntryIsSearch = isSearch,
                            selectedSchoolEntryKind = entryKind,
                            selectedSchoolEntryLooksLikeLogin = entryKind == SchoolEntryKind.LOGIN_SYSTEM,
                            webUrlInput = entry,
                            previewSchoolCode = school.code,
                            errorMessage = null
                        )
                    }
                }
                is AdapterLookupResult.UnverifiedFramework -> {
                    // 优先直达该校教务处；未收录时才退回「学校名 + 教务处」检索，绝不把学校官网首页当作教务入口
                    val academic = schoolImportRepository.getAcademicAffairsUrl(school.code).orEmpty()
                    val entry = academic.ifBlank { school.portalUrl }.ifBlank { searchAcademicUrl(school.name) }
                    val isSearch = academic.isBlank() && school.portalUrl.isBlank()
                    val entryKind = SchoolEntryClassifier.classify(entry, academic, isSearch)
                    _uiState.update {
                        it.copy(
                            selectedSchool = school,
                            selectedSchoolNotice = lookup.message,
                            selectedSchoolCanLaunchWebView = entry.isNotBlank(),
                            selectedSchoolPortalUrl = entry,
                            selectedSchoolAcademicUrl = academic,
                            selectedSchoolEntryIsSearch = isSearch,
                            selectedSchoolEntryKind = entryKind,
                            selectedSchoolEntryLooksLikeLogin = entryKind == SchoolEntryKind.LOGIN_SYSTEM,
                            webUrlInput = entry,
                            previewSchoolCode = school.code,
                            errorMessage = null
                        )
                    }
                }
            }
        }
    }

    private fun searchAcademicUrl(schoolName: String): String {
        val keyword = java.net.URLEncoder.encode("$schoolName 教务处 课表", "UTF-8")
        return "https://www.bing.com/search?q=$keyword"
    }

    fun launchSelectedSchoolWebView() {
        launchSchoolWebView(_uiState.value.selectedSchoolPortalUrl)
    }

    /** 打开指定地址的受控浏览器（如另开学校教务处网站），不影响当前已选学校。 */
    fun launchSchoolWebView(url: String) {
        if (url.isBlank()) return
        _uiState.update {
            it.copy(
                webViewInitialUrl = url,
                webUrlInput = url,
                showControlledWebView = true,
                errorMessage = null,
                statusMessage = null
            )
        }
    }

    fun submitAdaptationRequest(
        schoolName: String,
        systemType: EduSystemType,
        portalUrl: String,
        pageSummary: String,
        userAuthorized: Boolean
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            schoolImportRepository.submitAdaptationRequest(
                SchoolAdaptationRequest(
                    schoolName = schoolName.trim(),
                    eduSystemType = systemType,
                    portalUrl = portalUrl.trim(),
                    pageSummary = pageSummary.trim(),
                    userAuthorized = userAuthorized
                )
            ).onSuccess { saved ->
                val msg = if (saved.status == "SUBMITTED_TO_SERVER") {
                    "已将「${saved.schoolName}」的适配申请提交至服务端（不含任何密码信息）"
                } else {
                    "已记录「${saved.schoolName}」的适配申请（未连接后端服务器，已安全暂存本地）"
                }
                _uiState.update { it.copy(isBusy = false, statusMessage = msg) }
            }.onFailure { e ->
                _uiState.update { it.copy(isBusy = false, errorMessage = e.localizedMessage ?: "提交申请失败") }
            }
        }
    }

    fun updateWebUrl(url: String) {
        _uiState.update { it.copy(webUrlInput = url, errorMessage = null) }
    }

    fun fetchWebUrl() {
        val url = _uiState.value.webUrlInput.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请先粘贴教务课表网页链接") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, statusMessage = "正在读取并识别网页编码与课表结构...") }
            when (val outcome = webTimetableParser.fetchAndParseUrl(url)) {
                is WebParseOutcome.Success -> {
                    setPreviewCandidates(
                        candidates = outcome.candidates,
                        sourceType = ImportSourceType.WEB_LINK,
                        sourceName = url,
                        summary = outcome.summary,
                        detectedPeriods = outcome.detectedPeriods,
                        detectedCurrentWeek = outcome.detectedCurrentWeek
                    )
                }
                is WebParseOutcome.LoginRequired -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = outcome.reason,
                            webViewInitialUrl = outcome.url,
                            showControlledWebView = true
                        )
                    }
                }
                is WebParseOutcome.DynamicJsPageHint -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = outcome.reason,
                            webViewInitialUrl = outcome.url,
                            showControlledWebView = true
                        )
                    }
                }
                is WebParseOutcome.Failed -> {
                    _uiState.update { it.copy(isBusy = false, errorMessage = outcome.message, statusMessage = null) }
                }
            }
        }
    }

    fun openControlledWebView() {
        val typed = _uiState.value.webUrlInput.trim().trimEnd('/')
        val schoolPortal = _uiState.value.selectedSchoolPortalUrl.trim()
        val raw = when {
            typed.isNotBlank() && typed != "http:" && typed != "https:" -> typed
            schoolPortal.isNotBlank() -> schoolPortal
            else -> ""
        }
        if (raw.isBlank()) {
            _uiState.update {
                it.copy(
                    errorMessage = "请先输入教务系统网址（例如 https://jwxt.example.edu.cn），或先在「学校教务」中选择学校后再打开受控浏览器。",
                    statusMessage = null
                )
            }
            return
        }
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        _uiState.update {
            it.copy(
                webViewInitialUrl = url,
                showControlledWebView = true,
                errorMessage = null
            )
        }
    }

    fun closeControlledWebView() {
        _uiState.update {
            it.copy(showControlledWebView = false, errorMessage = null, statusMessage = null)
        }
    }

    fun clearControlledWebViewError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun parseHtmlFromControlledWebView(url: String, renderedHtml: String) {
        parseDocumentsFromControlledWebView(url, listOf(url to renderedHtml))
    }

    /** 接收受控浏览器收集的主页面及所有同源 iframe/frame 文档并统一提取最完整课表。 */
    fun parseDocumentsFromControlledWebView(url: String, documents: List<Pair<String, String>>) {
        val activeSchoolCode = _uiState.value.previewSchoolCode
        viewModelScope.launch {
            // 提取期间保留浏览器：解析失败用户仍可继续进入课表页，不能把登录会话一起关掉。
            _uiState.update {
                it.copy(isBusy = true, errorMessage = null, statusMessage = "正在识别当前页面及内嵌框架的课表结构...")
            }
            val outcome = webTimetableParser.parseRenderedDocuments(
                documents = documents.ifEmpty { listOf(url to "") },
                fromWebView = true
            )
            when (outcome) {
                is WebParseOutcome.Success -> {
                    _uiState.update { it.copy(showControlledWebView = false) }
                    setPreviewCandidates(
                        candidates = outcome.candidates,
                        sourceType = if (activeSchoolCode != null) ImportSourceType.SCHOOL_ADAPTER else ImportSourceType.WEB_LINK,
                        sourceName = url,
                        summary = outcome.summary,
                        detectedPeriods = outcome.detectedPeriods,
                        detectedCurrentWeek = outcome.detectedCurrentWeek
                    )
                }
                is WebParseOutcome.LoginRequired -> {
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = outcome.reason, statusMessage = null)
                    }
                }
                is WebParseOutcome.DynamicJsPageHint -> {
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = outcome.reason, statusMessage = null)
                    }
                }
                is WebParseOutcome.Failed -> {
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = outcome.message, statusMessage = null)
                    }
                }
            }
        }
    }

    fun importFileUri(uri: Uri) {
        viewModelScope.launch {
            val fileName = resolveFileName(uri)
            _uiState.update { it.copy(isBusy = true, errorMessage = null, statusMessage = "正在解析文件「$fileName」...") }
            try {
                val lower = fileName.lowercase()
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取所选文件，请重新授权选择")

                stream.use { input ->
                    when {
                        lower.endsWith(".pdf") -> {
                            val pdfResult = pdfParser.parse(input).getOrThrow()
                            setPreviewCandidates(
                                candidates = pdfResult.candidates,
                                sourceType = pdfResult.sourceType,
                                sourceName = fileName,
                                summary = pdfResult.summary
                            )
                        }
                        lower.endsWith(".xlsx") || lower.endsWith(".xls") -> {
                            val list = excelParser.parse(input, fileName).getOrThrow()
                            setPreviewCandidates(
                                candidates = list,
                                sourceType = ImportSourceType.EXCEL,
                                sourceName = fileName,
                                summary = if (list.isNotEmpty()) {
                                    "已从 Excel 解析出 ${list.size} 条课程，请核对预览后导入"
                                } else {
                                    "已读取 Excel 文件，但未自动识别出排课记录，您可点击下方「补录」录入"
                                }
                            )
                        }
                        else -> {
                            _uiState.update {
                                it.copy(isBusy = false, errorMessage = "暂仅支持 .xlsx、.xls 与 .pdf 格式文件，当前文件：$fileName")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = "文件解析失败：${e.localizedMessage ?: "格式异常"}",
                        statusMessage = null
                    )
                }
            }
        }
    }

    fun updateCandidate(updated: ParsedCourseCandidate) {
        val next = _uiState.value.candidates.map { if (it.tempId == updated.tempId) updated.copy(needsManualReview = false, reviewWarning = null) else it }
        _uiState.update { it.copy(candidates = next, rawCandidates = next) }
        recheckPreviewConflicts(next)
    }

    fun removeCandidate(tempId: String) {
        val next = _uiState.value.candidates.filterNot { it.tempId == tempId }
        _uiState.update { it.copy(candidates = next, rawCandidates = next) }
        recheckPreviewConflicts(next)
    }

    fun addManualBlankCandidate() {
        val blank = ParsedCourseCandidate(
            name = "未命名课程",
            dayOfWeek = 1,
            startPeriod = 1,
            endPeriod = 2,
            needsManualReview = true,
            reviewWarning = "手动补录项，请点击编辑完善信息"
        )
        val next = _uiState.value.candidates + blank
        _uiState.update {
            it.copy(
                rawCandidates = next,
                candidates = next,
                previewSourceType = it.previewSourceType ?: ImportSourceType.EXCEL,
                previewSourceName = it.previewSourceName.ifBlank { "人工补录" }
            )
        }
        recheckPreviewConflicts(next)
    }

    fun clearPreview() {
        _uiState.update {
            it.copy(
                rawCandidates = emptyList(),
                candidates = emptyList(),
                autoDetectedBigPeriodMode = false,
                expandBigPeriodPairs = false,
                previewConflicts = emptyList(),
                previewSourceType = null,
                previewSourceName = "",
                hasConfirmedSemesterStartDate = false,
                showStartDateConfirmDialog = false,
                pendingConfirmImportAfterDate = false,
                detectedCurrentWeekHint = null,
                detectedOfficialPeriods = emptyList(),
                overrideBellRuleOnImport = false,
                currentEffectivePeriods = emptyList(),
                statusMessage = null,
                errorMessage = null
            )
        }
    }

    fun setReplaceExistingOnImport(replace: Boolean) {
        _uiState.update { it.copy(replaceExistingOnImport = replace) }
        recheckPreviewConflicts(_uiState.value.candidates)
    }

    fun setExpandBigPeriodPairs(expand: Boolean) {
        val raw = _uiState.value.rawCandidates.ifEmpty { _uiState.value.candidates }
        val normalized = TimetablePeriodNormalizer.normalize(raw, expandBigPeriods = expand)
        _uiState.update {
            it.copy(
                expandBigPeriodPairs = expand,
                candidates = normalized
            )
        }
        recheckPreviewConflicts(normalized)
    }

    fun setApplyOfficialPeriodsOnImport(apply: Boolean) {
        _uiState.update { it.copy(applyOfficialPeriodsOnImport = apply) }
        recheckPreviewConflicts(_uiState.value.candidates)
    }

    fun applyQuickBellPreset(rule: BellScheduleRule) {
        if (!BellScheduleRuleEngine.validateRule(rule).isValid) {
            _uiState.update { it.copy(errorMessage = BellScheduleRuleEngine.validateRule(rule).errorMessage) }
            return
        }
        _uiState.update {
            it.copy(
                previewBellRule = rule,
                overrideBellRuleOnImport = true,
                applyOfficialPeriodsOnImport = false,
                errorMessage = null
            )
        }
        recheckPreviewConflicts(_uiState.value.candidates)
    }

    fun openStartDateDialog(proceedImportAfter: Boolean = false) {
        _uiState.update {
            it.copy(
                showStartDateConfirmDialog = true,
                pendingConfirmImportAfterDate = proceedImportAfter,
                errorMessage = null
            )
        }
    }

    fun dismissStartDateDialog() {
        _uiState.update {
            it.copy(showStartDateConfirmDialog = false, pendingConfirmImportAfterDate = false)
        }
    }

    fun updateImportSemesterStartDate(isoDate: String) {
        val parsed = runCatching { LocalDate.parse(isoDate.trim()) }.getOrNull() ?: return
        _uiState.update { it.copy(importSemesterStartDate = parsed.toString()) }
    }

    fun applyStartDateFromCurrentWeek(weekNumber: Int) {
        val safeWeek = weekNumber.coerceIn(1, 25)
        val mondayOfWeek1 = LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .minusWeeks((safeWeek - 1).toLong())
        _uiState.update { it.copy(importSemesterStartDate = mondayOfWeek1.toString()) }
    }

    fun confirmStartDateSelection(onProceedImport: (() -> Unit)? = null) {
        val state = _uiState.value
        val validDate = runCatching { LocalDate.parse(state.importSemesterStartDate) }.getOrNull()
        if (validDate == null) {
            _uiState.update { it.copy(errorMessage = "请选择有效的学期第一天日期") }
            return
        }
        val shouldProceedImport = state.pendingConfirmImportAfterDate
        _uiState.update {
            it.copy(
                importSemesterStartDate = validDate.toString(),
                hasConfirmedSemesterStartDate = true,
                showStartDateConfirmDialog = false,
                pendingConfirmImportAfterDate = false,
                errorMessage = null
            )
        }
        if (shouldProceedImport && onProceedImport != null) {
            confirmImport(onProceedImport)
        } else {
            recheckPreviewConflicts(_uiState.value.candidates)
        }
    }

    fun confirmImport(onCompleted: () -> Unit) {
        val state = _uiState.value
        if (state.candidates.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "没有可导入的课程") }
            return
        }
        if (!state.hasConfirmedSemesterStartDate) {
            openStartDateDialog(proceedImportAfter = true)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            val currentSem = timetableRepository.getCurrentSemester()
                ?: run {
                    timetableRepository.ensureGuestInitialized()
                    timetableRepository.getCurrentSemester()!!
                }

            // 1. 同步更新用户确认的学期第一天（第 1 周周一）
            val confirmedStart = runCatching { LocalDate.parse(state.importSemesterStartDate) }
                .getOrNull()?.toString() ?: currentSem.startDate
            val updatedSem = if (confirmedStart != currentSem.startDate) {
                currentSem.copy(startDate = confirmedStart).also { timetableRepository.saveSemester(it) }
            } else currentSem

            // 2. 写入作息时间（优先官网识别时间；否则若用户在预览中选用了作息规则如 95/100 分钟大课制，同步生成并持久化）
            var appliedPeriodSummary = ""
            val maxCoursePeriod = state.candidates.maxOfOrNull { it.endPeriod } ?: 0
            if (state.applyOfficialPeriodsOnImport && state.detectedOfficialPeriods.isNotEmpty()) {
                val classPeriods = buildClassPeriodsFromOfficial(
                    semesterId = updatedSem.id,
                    official = state.detectedOfficialPeriods,
                    minPeriodCount = maxOf(updatedSem.periodsPerDay, maxCoursePeriod, state.detectedOfficialPeriods.maxOf { it.periodNumber })
                )
                if (classPeriods.isNotEmpty()) {
                    timetableRepository.applyClassPeriodsToDays(updatedSem.id, listOf(0), classPeriods)
                    val inferredRule = BellScheduleRuleEngine.inferRuleFromPeriods(classPeriods, classPeriods.size)
                    settingsRepository.saveBellRuleJson(
                        updatedSem.id,
                        0,
                        BellScheduleRuleEngine.encodeRuleJson(inferredRule)
                    )
                    appliedPeriodSummary = "；已同步官网 ${state.detectedOfficialPeriods.size} 节作息时间"
                }
            } else if (state.overrideBellRuleOnImport) {
                val targetRule = state.previewBellRule.copy(
                    totalPeriods = maxOf(updatedSem.periodsPerDay, maxCoursePeriod, state.previewBellRule.totalPeriods).coerceIn(4, 20)
                )
                val generated = BellScheduleRuleEngine.generatePeriods(updatedSem.id, 0, targetRule)
                if (generated.isNotEmpty()) {
                    timetableRepository.applyClassPeriodsToDays(updatedSem.id, listOf(0), generated)
                    settingsRepository.saveBellRuleJson(
                        updatedSem.id,
                        0,
                        BellScheduleRuleEngine.encodeRuleJson(targetRule)
                    )
                    val bigMin = BellScheduleRuleEngine.bigPeriodDurationMinutes(targetRule)
                    appliedPeriodSummary = "；已按小课${targetRule.defaultPeriodDurationMinutes}分+小课间${targetRule.defaultSmallBreakMinutes}分（一大节${bigMin}分）修正作息"
                }
            }

            // 3. 写入导入课程并联动重算闹钟/天气/桌面小组件
            val history = schoolImportRepository.batchImportCourses(
                semesterId = updatedSem.id,
                candidates = state.candidates,
                sourceType = state.previewSourceType ?: ImportSourceType.EXCEL,
                sourceName = state.previewSourceName.ifBlank { "文件导入" },
                schoolCode = state.previewSchoolCode,
                replaceExisting = state.replaceExistingOnImport
            )
            alarmScheduler.rescheduleAllUpcomingAlarms("courses_imported")
            weatherScheduler.schedulePeWeatherChecks()
            widgetRefresher.refreshAllWidgets()

            _uiState.update {
                it.copy(
                    isBusy = false,
                    rawCandidates = emptyList(),
                    candidates = emptyList(),
                    autoDetectedBigPeriodMode = false,
                    expandBigPeriodPairs = false,
                    previewConflicts = emptyList(),
                    previewSourceType = null,
                    previewSourceName = "",
                    hasConfirmedSemesterStartDate = false,
                    showStartDateConfirmDialog = false,
                    pendingConfirmImportAfterDate = false,
                    detectedCurrentWeekHint = null,
                    detectedOfficialPeriods = emptyList(),
                    overrideBellRuleOnImport = false,
                    currentEffectivePeriods = emptyList(),
                    statusMessage = "成功导入 ${history.importedCount} 条排课（开学日 $confirmedStart$appliedPeriodSummary；检测到 ${history.conflictCount} 处时间冲突）"
                )
            }
            onCompleted()
        }
    }

    private fun buildClassPeriodsFromOfficial(
        semesterId: String,
        official: List<ExtractedClassPeriod>,
        minPeriodCount: Int
    ): List<ClassPeriod> {
        val sortedOfficial = official.distinctBy { it.periodNumber }.sortedBy { it.periodNumber }
        if (sortedOfficial.isEmpty()) return emptyList()

        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val durations = sortedOfficial.map {
            Duration.between(LocalTime.parse(it.startTime), LocalTime.parse(it.endTime)).toMinutes().toInt().coerceIn(20, 120)
        }
        val defaultDuration = durations.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 45
        val smallBreaks = sortedOfficial.zipWithNext().mapNotNull { (a, b) ->
            Duration.between(LocalTime.parse(a.endTime), LocalTime.parse(b.startTime)).toMinutes().toInt()
                .takeIf { it in 1..25 }
        }
        val defaultGap = smallBreaks.minOrNull() ?: 10
        val byNum = sortedOfficial.associateBy { it.periodNumber }
        val targetCount = minPeriodCount.coerceIn(sortedOfficial.last().periodNumber, 20)

        val result = mutableListOf<ClassPeriod>()
        var prevEnd = LocalTime.of(8, 0)
        for (p in 1..targetCount) {
            val matched = byNum[p]
            val (startStr, endStr, endObj) = if (matched != null) {
                Triple(matched.startTime, matched.endTime, LocalTime.parse(matched.endTime))
            } else {
                val s = if (p == 1) LocalTime.of(8, 0) else prevEnd.plusMinutes(defaultGap.toLong())
                val e = s.plusMinutes(defaultDuration.toLong())
                Triple(s.format(timeFmt), e.format(timeFmt), e)
            }
            result += ClassPeriod(
                id = "${semesterId}_d0_p${p}",
                semesterId = semesterId,
                dayOfWeek = 0,
                periodNumber = p,
                startTime = startStr,
                endTime = endStr
            )
            prevEnd = endObj
        }
        return result
    }

    private suspend fun setPreviewCandidates(
        candidates: List<ParsedCourseCandidate>,
        sourceType: ImportSourceType,
        sourceName: String,
        summary: String,
        detectedPeriods: List<ExtractedClassPeriod> = emptyList(),
        detectedCurrentWeek: Int? = null
    ) {
        val sem = timetableRepository.getCurrentSemester()
        val existingStart = sem?.startDate.orEmpty()
        val todayMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val suggestedStart = if (detectedCurrentWeek != null && detectedCurrentWeek in 1..25) {
            todayMonday.minusWeeks((detectedCurrentWeek - 1).toLong()).toString()
        } else null
        // 若当前学期尚未设置真实开学日（仍停留在默认的本周一），且网页提示了第 N 教学周，自动预填推算出的第 1 周周一
        val initialStartDate = when {
            existingStart.isBlank() -> suggestedStart ?: todayMonday.toString()
            suggestedStart != null && existingStart == todayMonday.toString() -> suggestedStart
            else -> existingStart
        }

        // 通用大节自动识别与连堂合并：识别全表奇数单节占位（1-1/3-3/5-5/7-7 -> 1-2/3-4/5-6/7-8，并合并 1-4 四节连堂）
        val isOddBigPeriod = TimetablePeriodNormalizer.detectOddSlotBigPeriodPattern(candidates)
        val normalizedCandidates = TimetablePeriodNormalizer.normalize(candidates, expandBigPeriods = isOddBigPeriod)

        val currentPeriods = sem?.let { timetableRepository.getClassPeriods(it.id) }.orEmpty()
        val effectiveBellRule = if (detectedPeriods.isNotEmpty() && sem != null) {
            val officialCp = buildClassPeriodsFromOfficial(sem.id, detectedPeriods, sem.periodsPerDay)
            BellScheduleRuleEngine.inferRuleFromPeriods(officialCp, officialCp.size)
        } else if (sem != null) {
            BellScheduleRuleEngine.decodeRuleJson(settingsRepository.getSavedBellRuleJson(sem.id, 0))
                ?: BellScheduleRuleEngine.inferRuleFromPeriods(currentPeriods.filter { it.dayOfWeek == 0 }, sem.periodsPerDay)
        } else {
            BellScheduleRule()
        }

        _uiState.update {
            it.copy(
                isBusy = false,
                previewSourceType = sourceType,
                previewSourceName = sourceName,
                rawCandidates = candidates,
                candidates = normalizedCandidates,
                autoDetectedBigPeriodMode = isOddBigPeriod,
                expandBigPeriodPairs = isOddBigPeriod,
                currentSemesterStartDate = existingStart,
                importSemesterStartDate = initialStartDate,
                hasConfirmedSemesterStartDate = false,
                showStartDateConfirmDialog = normalizedCandidates.isNotEmpty(),
                pendingConfirmImportAfterDate = false,
                detectedCurrentWeekHint = detectedCurrentWeek,
                detectedOfficialPeriods = detectedPeriods,
                applyOfficialPeriodsOnImport = detectedPeriods.isNotEmpty(),
                previewBellRule = effectiveBellRule,
                overrideBellRuleOnImport = false,
                statusMessage = summary,
                errorMessage = null
            )
        }
        recheckPreviewConflicts(normalizedCandidates)
    }

    private fun recheckPreviewConflicts(candidates: List<ParsedCourseCandidate>) {
        viewModelScope.launch {
            val baseSem = timetableRepository.getCurrentSemester() ?: return@launch
            val state = _uiState.value
            val effectiveStart = runCatching { LocalDate.parse(state.importSemesterStartDate) }.getOrNull()?.toString()
                ?: baseSem.startDate
            val sem = baseSem.copy(startDate = effectiveStart)
            val replaceExisting = state.replaceExistingOnImport
            val existing = if (replaceExisting) emptyList() else timetableRepository.getCoursesWithDetails(sem.id)
            val maxP = maxOf(sem.periodsPerDay, candidates.maxOfOrNull { it.endPeriod } ?: 0)
            val periods = when {
                state.applyOfficialPeriodsOnImport && state.detectedOfficialPeriods.isNotEmpty() -> {
                    buildClassPeriodsFromOfficial(sem.id, state.detectedOfficialPeriods, maxP)
                }
                state.overrideBellRuleOnImport -> {
                    BellScheduleRuleEngine.generatePeriods(
                        sem.id,
                        0,
                        state.previewBellRule.copy(totalPeriods = maxOf(maxP, state.previewBellRule.totalPeriods).coerceIn(4, 20))
                    )
                }
                else -> timetableRepository.getClassPeriods(sem.id)
            }
            val simulated = existing + candidates.map { c ->
                val cid = "preview-${c.tempId}"
                CourseWithDetails(
                    course = Course(
                        id = cid,
                        semesterId = sem.id,
                        name = c.name,
                        teacher = c.teacher,
                        location = c.location,
                        courseType = if (c.courseType == CourseType.PHYSICAL_EDUCATION || PeCourseDetector.isLikelyPeCourse(c.name)) {
                            CourseType.PHYSICAL_EDUCATION
                        } else CourseType.NORMAL
                    ),
                    schedules = listOf(
                        CourseSchedule(
                            courseId = cid,
                            dayOfWeek = c.dayOfWeek,
                            startPeriod = c.startPeriod,
                            endPeriod = c.endPeriod,
                            weekRuleType = c.weekRuleType,
                            startWeek = c.startWeek,
                            endWeek = c.endWeek,
                            customWeeks = c.customWeeks
                        )
                    ),
                    exceptions = emptyList()
                )
            }
            val conflicts = ConflictDetectionEngine.detectConflictsInSemester(sem, simulated, periods)
            _uiState.update { it.copy(previewConflicts = conflicts, currentEffectivePeriods = periods) }
        }
    }

    private fun resolveFileName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment ?: "timetable_file"
    }
}
