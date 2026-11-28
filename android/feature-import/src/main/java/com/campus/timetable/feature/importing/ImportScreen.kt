package com.campus.timetable.feature.importing

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campus.timetable.domain.engine.BellScheduleRuleEngine
import com.campus.timetable.domain.engine.HolidayCalendarResolver
import com.campus.timetable.domain.engine.ScheduleResolutionEngine
import com.campus.timetable.domain.model.*
import com.campus.timetable.schooladapters.EduPortalShortcutResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onImportedSuccess: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingCandidate by remember { mutableStateOf<ParsedCourseCandidate?>(null) }

    // 受控浏览器打开时隐藏底部导航，给教务网页（尤其横屏）留出完整空间
    LaunchedEffect(state.showControlledWebView) {
        ImportChromeState.browserOpen.value = state.showControlledWebView
    }
    DisposableEffect(Unit) {
        onDispose { ImportChromeState.browserOpen.value = false }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importFileUri(uri)
    }

    if (state.showControlledWebView) {
        ControlledEduWebViewScreen(
            initialUrl = state.webViewInitialUrl,
            schoolName = state.selectedSchool?.name.orEmpty(),
            fallbackAcademicUrl = state.selectedSchoolAcademicUrl,
            isExtracting = state.isBusy,
            errorMessage = state.errorMessage,
            onClose = { viewModel.closeControlledWebView() },
            onClearError = { viewModel.clearControlledWebViewError() },
            onExtractDocuments = { url, docs -> viewModel.parseDocumentsFromControlledWebView(url, docs) }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("课表导入") })
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 如果已经进入导入预览与人工校正态，直接展示完整预览工作台
            if (state.candidates.isNotEmpty() || state.previewSourceType != null) {
                ImportPreviewWorkbench(
                    state = state,
                    onToggleReplaceExisting = { viewModel.setReplaceExistingOnImport(it) },
                    onToggleExpandBigPeriods = { viewModel.setExpandBigPeriodPairs(it) },
                    onToggleApplyOfficialPeriods = { viewModel.setApplyOfficialPeriodsOnImport(it) },
                    onApplyBellRulePreset = { viewModel.applyQuickBellPreset(it) },
                    onOpenStartDateDialog = { viewModel.openStartDateDialog(proceedImportAfter = false) },
                    onEditCandidate = { editingCandidate = it },
                    onRemoveCandidate = { viewModel.removeCandidate(it) },
                    onAddManual = { viewModel.addManualBlankCandidate() },
                    onCancel = { viewModel.clearPreview() },
                    onConfirm = { viewModel.confirmImport(onImportedSuccess) }
                )
            } else {
                TabRow(selectedTabIndex = state.selectedTab.ordinal) {
                    ImportTab.entries.forEach { tab ->
                        Tab(
                            selected = state.selectedTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            text = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }

                if (state.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                state.errorMessage?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    ) {
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                    }
                }

                state.statusMessage?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(msg, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(12.dp))
                    }
                }

                when (state.selectedTab) {
                    ImportTab.SCHOOL -> SchoolAdapterTab(
                        state = state,
                        onQueryChange = { viewModel.updateSchoolQuery(it) },
                        onSelectProvince = { viewModel.selectProvince(it) },
                        onSelectPinyinLetter = { viewModel.selectPinyinLetter(it) },
                        onSelectSchool = { viewModel.selectSchool(it) },
                        onLaunchSchoolWebView = { viewModel.launchSelectedSchoolWebView() },
                        onOpenAcademicUrl = { viewModel.launchSchoolWebView(it) },
                        onRefreshRemoteCatalog = { viewModel.refreshRemoteCatalog() },
                        onSubmitAdaptation = { name, sys, url, summary, auth ->
                            viewModel.submitAdaptationRequest(name, sys, url, summary, auth)
                        },
                        onGoFileImport = { viewModel.selectTab(ImportTab.FILE) },
                        onGoWebImport = { viewModel.selectTab(ImportTab.WEB) }
                    )
                    ImportTab.FILE -> FileImportTab(
                        onPickExcel = {
                            fileLauncher.launch(
                                arrayOf(
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/vnd.ms-excel",
                                    "*/*"
                                )
                            )
                        },
                        onPickPdf = {
                            fileLauncher.launch(arrayOf("application/pdf", "*/*"))
                        }
                    )
                    ImportTab.WEB -> WebLinkImportTab(
                        url = state.webUrlInput,
                        onUrlChange = { viewModel.updateWebUrl(it) },
                        onDirectParse = { viewModel.fetchWebUrl() },
                        onOpenWebView = { viewModel.openControlledWebView() }
                    )
                    ImportTab.HISTORY -> ImportHistoryTab(histories = state.histories)
                }
            }
        }
    }

    editingCandidate?.let { candidate ->
        CandidateEditDialog(
            candidate = candidate,
            onDismiss = { editingCandidate = null },
            onSave = { updated ->
                viewModel.updateCandidate(updated)
                editingCandidate = null
            }
        )
    }

    if (state.showStartDateConfirmDialog) {
        SemesterStartDateDialog(
            currentStartDateIso = state.importSemesterStartDate,
            detectedCurrentWeekHint = state.detectedCurrentWeekHint,
            pendingProceedImport = state.pendingConfirmImportAfterDate,
            onSelectIsoDate = { viewModel.updateImportSemesterStartDate(it) },
            onApplyCurrentWeek = { viewModel.applyStartDateFromCurrentWeek(it) },
            onDismiss = { viewModel.dismissStartDateDialog() },
            onConfirm = { viewModel.confirmStartDateSelection(onImportedSuccess) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchoolAdapterTab(
    state: ImportUiState,
    onQueryChange: (String) -> Unit,
    onSelectProvince: (String?) -> Unit,
    onSelectPinyinLetter: (String?) -> Unit,
    onSelectSchool: (School) -> Unit,
    onLaunchSchoolWebView: () -> Unit,
    onOpenAcademicUrl: (String) -> Unit,
    onRefreshRemoteCatalog: () -> Unit,
    onSubmitAdaptation: (String, EduSystemType, String, String, Boolean) -> Unit,
    onGoFileImport: () -> Unit,
    onGoWebImport: () -> Unit
) {
    var showAdaptDialog by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 真实学校目录与适配覆盖统计卡片
        state.catalogStats?.let { stats ->
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("全国高校离线目录（共 ${stats.totalCatalogCount} 所）", fontWeight = FontWeight.Bold)
                            TextButton(onClick = onRefreshRemoteCatalog) { Text("检查远程更新") }
                        }
                        Text(
                            "覆盖 ${state.availableProvinces.size} 个省份 · 已配教务入口 ${stats.configuredPortalCount} 所（按拼音 A–Z 排序）",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            "支持通用教务导入 ${stats.genericAdapterCount} 所 · 需辅助登录 ${stats.assistedLoginCount} 所 · 已完成实网终验 ${stats.verifiedCount} 所",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "数据来源：${stats.catalogSource}（本地离线秒搜，绝不将未实测高校标为已验证支持）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 关键词 / 全称 / 简称 / 拼音搜索框
        item {
            OutlinedTextField(
                value = state.schoolQuery,
                onValueChange = onQueryChange,
                label = { Text("搜全称、简称、别名或拼音（如：浙大 / bjdx / 深职）") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.schoolQuery.isNotEmpty()) {
                        TextButton(onClick = { onQueryChange("") }) { Text("清除") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // 提交适配申请
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "共筛选 ${state.schools.size} 所高校（已按拼音 A–Z 排序）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { showAdaptDialog = true }) { Text("申请适配本校") }
            }
        }

        // 省份横向筛选
        if (state.availableProvinces.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = state.selectedProvince == null,
                            onClick = { onSelectProvince(null) },
                            label = { Text("全部省份") }
                        )
                    }
                    items(state.availableProvinces) { prov ->
                        FilterChip(
                            selected = state.selectedProvince == prov,
                            onClick = { onSelectProvince(prov) },
                            label = { Text(prov.removeSuffix("省").removeSuffix("市").removeSuffix("自治区")) }
                        )
                    }
                }
            }
        }

        // 拼音 A–Z 首字母快速定位条
        item {
            val letters = remember { ('A'..'Z').map { it.toString() } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    FilterChip(
                        selected = state.selectedPinyinLetter == null,
                        onClick = { onSelectPinyinLetter(null) },
                        label = { Text("全部") }
                    )
                }
                items(letters) { ch ->
                    FilterChip(
                        selected = state.selectedPinyinLetter == ch,
                        onClick = { onSelectPinyinLetter(if (state.selectedPinyinLetter == ch) null else ch) },
                        label = { Text(ch) }
                    )
                }
            }
        }

        // 选中高校的适配状态详情与操作引导卡片
        state.selectedSchoolNotice?.let { notice ->
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "${state.selectedSchool?.name.orEmpty()} · ${state.selectedSchool?.supportStatus?.label.orEmpty()}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(notice, style = MaterialTheme.typography.bodyMedium)
                        when (state.selectedSchoolEntryKind) {
                            SchoolEntryKind.SEARCH -> {
                                Text(
                                    text = "暂未收录该校教务系统直达地址，将打开搜索「${state.selectedSchool?.name.orEmpty()} 教务处 课表」",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SchoolEntryKind.LOGIN_SYSTEM -> {
                                if (state.selectedSchoolPortalUrl.isNotBlank()) {
                                    Text(
                                        text = "教务系统入口：${state.selectedSchoolPortalUrl}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            SchoolEntryKind.PORTAL_HOMEPAGE -> {
                                if (state.selectedSchoolPortalUrl.isNotBlank()) {
                                    Text(
                                        text = "校内统一门户：${state.selectedSchoolPortalUrl}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "进入校内门户登录后，请打开「教务系统 / 学生课表」页面再点提取。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            SchoolEntryKind.ACADEMIC_HOMEPAGE -> {
                                if (state.selectedSchoolPortalUrl.isNotBlank()) {
                                    Text(
                                        text = "教务处网站：${state.selectedSchoolPortalUrl}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "该地址为教务处网站，进入后请点「教务系统 / 学生登录」再打开课表页提取。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (state.selectedSchoolAcademicUrl.isNotBlank() &&
                            state.selectedSchoolAcademicUrl != state.selectedSchoolPortalUrl
                        ) {
                            Text(
                                text = "教务处网站：${state.selectedSchoolAcademicUrl}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (state.selectedSchoolCanLaunchWebView) {
                                Button(onClick = onLaunchSchoolWebView) {
                                    Text(
                                        when (state.selectedSchoolEntryKind) {
                                            SchoolEntryKind.SEARCH -> "搜索教务处"
                                            SchoolEntryKind.LOGIN_SYSTEM -> "登录教务系统"
                                            SchoolEntryKind.PORTAL_HOMEPAGE -> "打开校内门户"
                                            SchoolEntryKind.ACADEMIC_HOMEPAGE -> "打开教务处"
                                        }
                                    )
                                }
                            }
                            if (state.selectedSchoolAcademicUrl.isNotBlank() &&
                                state.selectedSchoolAcademicUrl != state.selectedSchoolPortalUrl
                            ) {
                                OutlinedButton(onClick = { onOpenAcademicUrl(state.selectedSchoolAcademicUrl) }) {
                                    Text("打开教务处网站")
                                }
                            }
                            FilledTonalButton(onClick = onGoFileImport) { Text("文件导入") }
                            if (!state.selectedSchoolCanLaunchWebView) {
                                OutlinedButton(onClick = { showAdaptDialog = true }) { Text("提交适配申请") }
                            }
                        }
                    }
                }
            }
        }

        val groupedByPinyin = state.schools.groupBy { school ->
            school.pinyin.trim().firstOrNull()?.uppercaseChar()?.takeIf { ch -> ch in 'A'..'Z' }?.toString()
                ?: school.pinyinInitials.trim().firstOrNull()?.uppercaseChar()?.takeIf { ch -> ch in 'A'..'Z' }?.toString()
                ?: "#"
        }
        groupedByPinyin.forEach { (letter, schoolsInLetter) ->
            item(key = "pinyin_$letter") {
                Text(
                    text = "$letter（${schoolsInLetter.size} 所）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            }
            items(schoolsInLetter, key = { it.id }) { school ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectSchool(school) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = school.name + if (school.shortName.isNotBlank()) "（${school.shortName}）" else "",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${school.province.removeSuffix("省").removeSuffix("市").removeSuffix("自治区")} · ${school.city} · ${school.systemType.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (school.campuses.isNotBlank()) {
                                Text("校区：${school.campuses}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        AssistChip(
                            onClick = { onSelectSchool(school) },
                            label = { Text(school.supportStatus.label) }
                        )
                    }
                }
            }
        }
    }

    if (showAdaptDialog) {
        var reqSchoolName by remember { mutableStateOf(state.selectedSchool?.name ?: state.schoolQuery) }
        var reqSystem by remember { mutableStateOf(state.selectedSchool?.systemType ?: EduSystemType.UNKNOWN) }
        var reqUrl by remember { mutableStateOf(state.selectedSchool?.portalUrl.orEmpty()) }
        var reqSummary by remember { mutableStateOf("") }
        var authorized by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAdaptDialog = false },
            title = { Text("提交学校教务适配申请") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = reqSchoolName, onValueChange = { reqSchoolName = it }, label = { Text("学校全称 *") }, singleLine = true)
                    OutlinedTextField(value = reqUrl, onValueChange = { reqUrl = it }, label = { Text("公开教务登录网址（选填）") }, singleLine = true)
                    Text("已知教务系统类型：", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(EduSystemType.entries) { sys ->
                            FilterChip(selected = reqSystem == sys, onClick = { reqSystem = sys }, label = { Text(sys.label) })
                        }
                    }
                    OutlinedTextField(
                        value = reqSummary,
                        onValueChange = { reqSummary = it },
                        label = { Text("页面结构/版本补充说明（勿填任何密码）") },
                        maxLines = 3
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = authorized, onCheckedChange = { authorized = it })
                        Text("我明确授权提交上述非敏感公开教务信息用于适配，确认未包含任何个人账号密码。", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = authorized && reqSchoolName.isNotBlank(),
                    onClick = {
                        onSubmitAdaptation(reqSchoolName, reqSystem, reqUrl, reqSummary, authorized)
                        showAdaptDialog = false
                    }
                ) { Text("授权提交") }
            },
            dismissButton = { TextButton(onClick = { showAdaptDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun FileImportTab(
    onPickExcel: () -> Unit,
    onPickPdf: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Excel 课表导入（.xlsx / .xls）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "自动识别列表型课表及周一至周日网格型课表（含合并单元格），自动提取课程名称、教师、教室、星期、节次、单双周与自定义周次。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onPickExcel) {
                    Icon(Icons.Outlined.TableChart, null)
                    Spacer(Modifier.width(8.dp))
                    Text("选择 Excel 文件")
                }
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PDF 与扫描版课表 OCR 导入（.pdf）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "优先通过本地 PDFBox 提取真实文本；若检测到为扫描图片版 PDF，将自动调用本地 Google ML Kit 中文 OCR 识别，并进入人工校正预览确认。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onPickPdf) {
                    Icon(Icons.Outlined.PictureAsPdf, null)
                    Spacer(Modifier.width(8.dp))
                    Text("选择 PDF 文件")
                }
            }
        }
    }
}

@Composable
private fun WebLinkImportTab(
    url: String,
    onUrlChange: (String) -> Unit,
    onDirectParse: () -> Unit,
    onOpenWebView: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text("教务系统课表网页链接") },
            placeholder = { Text("https://jwxt.example.edu.cn/...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onDirectParse, modifier = Modifier.weight(1f)) {
                Text("直接读取解析")
            }
            FilledTonalButton(onClick = onOpenWebView, modifier = Modifier.weight(1f)) {
                Text("受控浏览器登录")
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text(
                "说明：\n1. 支持自动识别 UTF-8 / GBK / GB2312 编码及正方/强智/通用 HTML 表格。\n2. 若教务页面需要登录、验证码或使用 JavaScript 动态渲染，请点击「受控浏览器登录」，在官方页面手动完成登录并打开课表页后点击提取。\n3. 不会记录任何密码，且可随时一键清除浏览器 Cookie；不保证任意非标准网页均可直接自动解析，解析后均提供人工校正。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun ImportHistoryTab(histories: List<ImportHistory>) {
    if (histories.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无导入历史记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(histories, key = { it.id }) { item ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${item.sourceType.label} · ${item.status.label}", fontWeight = FontWeight.SemiBold)
                    Text("来源：${item.sourceName}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "解析 ${item.totalParsed} 条 / 入库 ${item.importedCount} 条 / 冲突 ${item.conflictCount} 处 · ${fmt.format(Date(item.createdAt))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportPreviewWorkbench(
    state: ImportUiState,
    onToggleReplaceExisting: (Boolean) -> Unit,
    onToggleExpandBigPeriods: (Boolean) -> Unit,
    onToggleApplyOfficialPeriods: (Boolean) -> Unit,
    onApplyBellRulePreset: (BellScheduleRule) -> Unit,
    onOpenStartDateDialog: () -> Unit,
    onEditCandidate: (ParsedCourseCandidate) -> Unit,
    onRemoveCandidate: (String) -> Unit,
    onAddManual: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    var showOfficialPeriodsDetail by remember { mutableStateOf(false) }
    var showCustomBellDialog by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val parsedStart = remember(state.importSemesterStartDate) {
        runCatching { LocalDate.parse(state.importSemesterStartDate) }.getOrNull()
    }
    val computedTodayWeek = remember(parsedStart, today) {
        parsedStart?.let { ScheduleResolutionEngine.calculateWeekNumber(it, today) }
    }
    val bigPeriodMin = remember(state.previewBellRule) {
        BellScheduleRuleEngine.bigPeriodDurationMinutes(state.previewBellRule)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("导入预览与人工校正（共 ${state.candidates.size} 条）", fontWeight = FontWeight.Bold)
                state.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                // 学期第一天（开学日期）核对卡
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenStartDateDialog() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "学期第一天：${parsedStart?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日（周${HolidayCalendarResolver.dayOfWeekShortZh(it.dayOfWeek.value)}）" } ?: "未设置"}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = buildString {
                                    if (computedTodayWeek != null) {
                                        append(if (computedTodayWeek >= 1) "按此日期今天为第 $computedTodayWeek 周" else "尚未到本学期第一周")
                                    }
                                    append(if (state.hasConfirmedSemesterStartDate) " · 已核对" else " · 请核对开学日期")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.hasConfirmedSemesterStartDate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        TextButton(onClick = onOpenStartDateDialog) {
                            Text("修改日期")
                        }
                    }
                }

                // 通用「一大节 = 两小节」展开与连续大节合并卡
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "一大节占 2 小节并合并连堂",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (state.expandBigPeriodPairs) {
                                    if (state.autoDetectedBigPeriodMode) "已识别教务按大节记格，自动展开 1-2/3-4 并合并 4 节连堂" else "已开启：奇数单节按 2 小节展开并合并相邻连堂"
                                } else {
                                    "按网页原始节次保持不变（如遇大课仅显示 1 节可开启）"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.expandBigPeriodPairs,
                            onCheckedChange = onToggleExpandBigPeriods
                        )
                    }
                }

                // 学校官网作息 / 一大节总时长（如 45×2 + 5 = 95 分钟）校准卡
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.detectedOfficialPeriods.isNotEmpty()) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "已识别官网 ${state.detectedOfficialPeriods.size} 节作息（一大节 ${bigPeriodMin} 分钟）",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val first = state.detectedOfficialPeriods.first()
                                    val second = state.detectedOfficialPeriods.firstOrNull { it.periodNumber == 2 }
                                    val fifth = state.detectedOfficialPeriods.firstOrNull { it.periodNumber == 5 }
                                    Text(
                                        text = buildString {
                                            append("1-2节 ${first.startTime}-${second?.endTime ?: first.endTime}")
                                            if (fifth != null) append(" · 下午第5节 ${fifth.startTime}")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = state.applyOfficialPeriodsOnImport,
                                    onCheckedChange = onToggleApplyOfficialPeriods
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (state.applyOfficialPeriodsOnImport) "导入时将以学校官网课表为准同步修正作息时间" else "保持当前本地作息时间不变",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { showOfficialPeriodsDetail = !showOfficialPeriodsDetail },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text(if (showOfficialPeriodsDetail) "收起明细" else "查看明细", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (showOfficialPeriodsDetail) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    state.detectedOfficialPeriods.forEach { p ->
                                        AssistChip(
                                            onClick = {},
                                            label = { Text("第${p.periodNumber}节 ${p.startTime}-${p.endTime}", style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val firstRange = ScheduleResolutionEngine.resolvePeriodTimeRange(state.currentEffectivePeriods, 0, 1, 2)
                            Text(
                                text = "当前作息：小课${state.previewBellRule.defaultPeriodDurationMinutes}分 + 小课间${state.previewBellRule.defaultSmallBreakMinutes}分 → 一大节 $bigPeriodMin 分钟（1-2节 ${firstRange.first.toString().take(5)}-${firstRange.second.toString().take(5)}）",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            val presets = remember { BellScheduleRuleEngine.defaultTemplates() }
                            val compact95 = presets.firstOrNull { it.id == "tpl_compact_95" }?.rule
                            val standard100 = presets.firstOrNull { it.id == "tpl_normal" }?.rule
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (compact95 != null) {
                                    FilterChip(
                                        selected = state.overrideBellRuleOnImport &&
                                            state.previewBellRule.defaultPeriodDurationMinutes == 45 &&
                                            state.previewBellRule.defaultSmallBreakMinutes == 5,
                                        onClick = { onApplyBellRulePreset(compact95) },
                                        label = { Text("一大节95分(45+5)") }
                                    )
                                }
                                if (standard100 != null) {
                                    FilterChip(
                                        selected = state.overrideBellRuleOnImport &&
                                            state.previewBellRule.defaultPeriodDurationMinutes == 45 &&
                                            state.previewBellRule.defaultSmallBreakMinutes == 10,
                                        onClick = { onApplyBellRulePreset(standard100) },
                                        label = { Text("一大节100分(45+10)") }
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showCustomBellDialog = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) { Text("自定义作息") }
                            }
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = state.replaceExistingOnImport,
                        onClick = { onToggleReplaceExisting(true) },
                        label = { Text("覆盖当前学期旧课表（推荐）") }
                    )
                    FilterChip(
                        selected = !state.replaceExistingOnImport,
                        onClick = { onToggleReplaceExisting(false) },
                        label = { Text("追加到当前学期") }
                    )
                }
                if (state.previewConflicts.isNotEmpty()) {
                    Text(
                        "冲突预警：导入后预计产生 ${state.previewConflicts.size} 处时间冲突，请检查并校正标黄课程。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.candidates, key = { it.tempId }) { c ->
                val timeRange = ScheduleResolutionEngine.resolvePeriodTimeRange(
                    state.currentEffectivePeriods,
                    c.dayOfWeek,
                    c.startPeriod,
                    c.endPeriod
                )
                val durationMin = Duration.between(timeRange.first, timeRange.second).toMinutes().toInt()
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().clickable { onEditCandidate(c) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = c.name + if (c.courseType == CourseType.PHYSICAL_EDUCATION) " [体育]" else "",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "周${HolidayCalendarResolver.dayOfWeekShortZh(c.dayOfWeek)} 第${c.startPeriod}-${c.endPeriod}节（${timeRange.first.toString().take(5)}-${timeRange.second.toString().take(5)} · ${durationMin}分） · " +
                                    when (c.weekRuleType) {
                                        WeekRuleType.CUSTOM -> "自定义第${c.customWeeks.joinToString(",")}周"
                                        else -> "第${c.startWeek}-${c.endWeek}周(${c.weekRuleType.label})"
                                    },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "地点：${c.location.ifBlank { "未填写" }} · 教师：${c.teacher.ifBlank { "未填写" }}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (c.needsManualReview && !c.reviewWarning.isNullOrBlank()) {
                                Text(
                                    "需人工核对：${c.reviewWarning}（点击修改）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        TextButton(
                            onClick = { onEditCandidate(c) },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("修改", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = { onRemoveCandidate(c.tempId) },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("删除", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = onAddManual, modifier = Modifier.fillMaxWidth()) {
                    Text("+ 补录漏识别的课程")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = onConfirm,
                enabled = !state.isBusy && state.candidates.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("确认导入本地课表") }
        }
    }

    if (showCustomBellDialog) {
        QuickBellRuleDialog(
            initialRule = state.previewBellRule,
            onDismiss = { showCustomBellDialog = false },
            onSave = { rule ->
                onApplyBellRulePreset(rule)
                showCustomBellDialog = false
            }
        )
    }
}

@Composable
private fun QuickBellRuleDialog(
    initialRule: BellScheduleRule,
    onDismiss: () -> Unit,
    onSave: (BellScheduleRule) -> Unit
) {
    var firstStart by remember { mutableStateOf(initialRule.firstClassStartTime) }
    var periodDur by remember { mutableStateOf(initialRule.defaultPeriodDurationMinutes.toString()) }
    var smallBreak by remember { mutableStateOf(initialRule.defaultSmallBreakMinutes.toString()) }
    var bigBreak by remember { mutableStateOf(initialRule.bigBreakMinutes.toString()) }
    var afternoonStart by remember { mutableStateOf(initialRule.afternoonStartTime) }
    var eveningStart by remember { mutableStateOf(initialRule.eveningStartTime) }

    val draftRule = BellScheduleRule(
        firstClassStartTime = firstStart.trim().ifBlank { "08:00" },
        defaultPeriodDurationMinutes = (periodDur.toIntOrNull() ?: 45).coerceIn(10, 180),
        defaultSmallBreakMinutes = (smallBreak.toIntOrNull() ?: 5).coerceIn(0, 60),
        bigBreakMinutes = (bigBreak.toIntOrNull() ?: 30).coerceIn(0, 90),
        bigBreakEveryNPeriods = 2,
        morningEndPeriod = 4,
        useFixedAfternoonStart = true,
        afternoonStartTime = afternoonStart.trim().ifBlank { "14:00" },
        afternoonEndPeriod = 8,
        useFixedEveningStart = true,
        eveningStartTime = eveningStart.trim().ifBlank { "18:40" },
        totalPeriods = 12
    )
    val previewFirst = BellScheduleRuleEngine.generatePeriods("preview", 0, draftRule).take(2)
    val bigMin = BellScheduleRuleEngine.bigPeriodDurationMinutes(draftRule)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义作息与一大节时长") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (previewFirst.size == 2) {
                        "第1-2节预览：${previewFirst[0].startTime}-${previewFirst[1].endTime}（一大节共 $bigMin 分钟）"
                    } else "一大节共 $bigMin 分钟",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = firstStart, onValueChange = { firstStart = it }, label = { Text("首节开始") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = periodDur, onValueChange = { periodDur = it.filter(Char::isDigit) }, label = { Text("小课(分)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = smallBreak, onValueChange = { smallBreak = it.filter(Char::isDigit) }, label = { Text("小课间(分)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = bigBreak, onValueChange = { bigBreak = it.filter(Char::isDigit) }, label = { Text("大课间(分)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = afternoonStart, onValueChange = { afternoonStart = it }, label = { Text("下午开始") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = eveningStart, onValueChange = { eveningStart = it }, label = { Text("晚间开始") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = BellScheduleRuleEngine.validateRule(draftRule).isValid,
                onClick = { onSave(draftRule) }
            ) { Text("应用作息") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CandidateEditDialog(
    candidate: ParsedCourseCandidate,
    onDismiss: () -> Unit,
    onSave: (ParsedCourseCandidate) -> Unit
) {
    var name by remember { mutableStateOf(candidate.name) }
    var teacher by remember { mutableStateOf(candidate.teacher) }
    var location by remember { mutableStateOf(candidate.location) }
    var dow by remember { mutableStateOf(candidate.dayOfWeek.toString()) }
    var startP by remember { mutableStateOf(candidate.startPeriod.toString()) }
    var endP by remember { mutableStateOf(candidate.endPeriod.toString()) }
    var rule by remember { mutableStateOf(candidate.weekRuleType) }
    var startW by remember { mutableStateOf(candidate.startWeek.toString()) }
    var endW by remember { mutableStateOf(candidate.endWeek.toString()) }
    var customW by remember { mutableStateOf(candidate.customWeeks.joinToString(",")) }
    var courseType by remember { mutableStateOf(candidate.courseType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("人工校正课程信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("课程名称") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = teacher, onValueChange = { teacher = it }, label = { Text("教师") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("教室") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = dow, onValueChange = { dow = it.filter(Char::isDigit) }, label = { Text("星期(1-7)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = startP, onValueChange = { startP = it.filter(Char::isDigit) }, label = { Text("起节") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = endP, onValueChange = { endP = it.filter(Char::isDigit) }, label = { Text("止节") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WeekRuleType.entries.forEach { r ->
                        FilterChip(selected = rule == r, onClick = { rule = r }, label = { Text(r.label) })
                    }
                }
                if (rule == WeekRuleType.CUSTOM) {
                    OutlinedTextField(value = customW, onValueChange = { customW = it }, label = { Text("自定义周(逗号分隔，如 1,3,5,7)") }, singleLine = true)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = startW, onValueChange = { startW = it.filter(Char::isDigit) }, label = { Text("起始周") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = endW, onValueChange = { endW = it.filter(Char::isDigit) }, label = { Text("结束周") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CourseType.entries.forEach { ct ->
                        FilterChip(selected = courseType == ct, onClick = { courseType = ct }, label = { Text(ct.label) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedCustom = customW.split(",", "，", " ").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..30 }.distinct().sorted()
                onSave(
                    candidate.copy(
                        name = name.trim().ifBlank { candidate.name },
                        teacher = teacher.trim(),
                        location = location.trim(),
                        dayOfWeek = (dow.toIntOrNull() ?: 1).coerceIn(1, 7),
                        startPeriod = (startP.toIntOrNull() ?: 1).coerceIn(1, 20),
                        endPeriod = (endP.toIntOrNull() ?: 2).coerceIn(1, 20),
                        weekRuleType = rule,
                        startWeek = (startW.toIntOrNull() ?: 1).coerceIn(1, 30),
                        endWeek = (endW.toIntOrNull() ?: 16).coerceIn(1, 30),
                        customWeeks = parsedCustom,
                        courseType = courseType
                    )
                )
            }) { Text("保存校正") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SemesterStartDateDialog(
    currentStartDateIso: String,
    detectedCurrentWeekHint: Int?,
    pendingProceedImport: Boolean,
    onSelectIsoDate: (String) -> Unit,
    onApplyCurrentWeek: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var showCalendarPicker by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val selectedDate = remember(currentStartDateIso) {
        runCatching { LocalDate.parse(currentStartDateIso) }.getOrElse {
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }
    }
    val previewWeek = remember(selectedDate, today) {
        ScheduleResolutionEngine.calculateWeekNumber(selectedDate, today).coerceIn(1, 25)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本学期第一天是几月几号？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "正确的开学日期决定本周（第几周）显示哪些课程，以及单双周和结课周次是否准确。建议选择第一周周一。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "${selectedDate.year}年${selectedDate.monthValue}月${selectedDate.dayOfMonth}日（周${HolidayCalendarResolver.dayOfWeekShortZh(selectedDate.dayOfWeek.value)}）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        val realWeek = ScheduleResolutionEngine.calculateWeekNumber(selectedDate, today)
                        Text(
                            text = if (realWeek >= 1) "按此开学日，今天为第 $realWeek 周" else "今天早于开学日（开学前 ${1 - realWeek} 周）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (detectedCurrentWeekHint != null) {
                    val hintMonday = remember(detectedCurrentWeekHint, today) {
                        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                            .minusWeeks((detectedCurrentWeekHint - 1).toLong())
                    }
                    OutlinedButton(
                        onClick = { onApplyCurrentWeek(detectedCurrentWeekHint) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("教务页提示当前为第 $detectedCurrentWeekHint 周 → 设为 ${hintMonday.monthValue}月${hintMonday.dayOfMonth}日")
                    }
                }
                OutlinedButton(
                    onClick = { showCalendarPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("在日历中直接选择几月几号")
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "或按「今天是第几周」一键推算第 1 周周一：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { onApplyCurrentWeek((previewWeek - 1).coerceAtLeast(1)) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text("上一周") }
                        Text("今天：第 $previewWeek 周", fontWeight = FontWeight.SemiBold)
                        FilledTonalButton(
                            onClick = { onApplyCurrentWeek((previewWeek + 1).coerceAtMost(25)) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text("下一周") }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(if (pendingProceedImport) "确认并导入" else "确认学期第一天")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后")
            }
        }
    )

    if (showCalendarPicker) {
        val initialMillis = remember(selectedDate) {
            selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showCalendarPicker = false },
            confirmButton = {
                Button(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onSelectIsoDate(picked.toString())
                    }
                    showCalendarPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarPicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Serializable
private data class ExtractedFramePayload(
    val url: String = "",
    val html: String = "",
    val eamsJson: String? = null
)

private val frameJson = Json { ignoreUnknownKeys = true; isLenient = true }

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ControlledEduWebViewScreen(
    initialUrl: String,
    schoolName: String = "",
    fallbackAcademicUrl: String = "",
    isExtracting: Boolean = false,
    errorMessage: String? = null,
    onClose: () -> Unit,
    onClearError: () -> Unit = {},
    onExtractDocuments: (url: String, documents: List<Pair<String, String>>) -> Unit
) {
    var currentUrl by rememberSaveable { mutableStateOf(initialUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var desktopMode by rememberSaveable { mutableStateOf(false) }
    var landscapeMode by rememberSaveable { mutableStateOf(false) }
    var hasToggledLandscape by rememberSaveable { mutableStateOf(false) }
    var showUrlBar by rememberSaveable { mutableStateOf(true) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var pageLoadProgress by remember { mutableIntStateOf(0) }
    var pageLoadError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activity = remember(context) { context.findActivity() }
    val originalOrientation by rememberSaveable {
        mutableIntStateOf(activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }
    val supportsStripXrw = remember {
        WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)
    }
    // 去掉默认 UA 中的 "; wv" 标记，避免高校教务系统或统一身份认证拦截内置浏览器
    val cleanMobileUserAgent = remember(context) {
        runCatching {
            WebSettings.getDefaultUserAgent(context)
                .replace("; wv)", ")")
                .replace("; wv;", ";")
        }.getOrDefault(MOBILE_CHROME_USER_AGENT)
    }
    val timetableShortcut = remember(currentUrl) {
        EduPortalShortcutResolver.resolveTimetableShortcut(currentUrl)
    }

    // 退出页面时恢复进入前的屏幕方向，并释放 WebView 避免内存泄漏
    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }
    LaunchedEffect(landscapeMode, hasToggledLandscape) {
        if (!hasToggledLandscape) return@LaunchedEffect
        activity?.requestedOrientation = if (landscapeMode) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            originalOrientation
        }
    }
    LaunchedEffect(isLandscape) {
        if (isLandscape) showUrlBar = false
    }

    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    /**
     * 干净加载目标网址：
     * - 支持 REQUESTED_WITH_HEADER_ALLOW_LIST 时，Chromium 底层已彻底停止附带 X-Requested-With 包名头；
     * - 极少数旧版 WebView 回退传空值覆盖包名头，防止树维 EAMS / Beangle / Struts2 误判为 AJAX 而剥离 <head> CSS 与 JS。
     */
    fun loadCleanUrl(wv: WebView, rawUrl: String) {
        val target = normalizeUrl(rawUrl)
        if (supportsStripXrw) {
            wv.loadUrl(target)
        } else {
            wv.loadUrl(target, mapOf("X-Requested-With" to ""))
        }
    }

    fun applyDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        webViewRef?.let { wv ->
            wv.settings.userAgentString = if (enabled) DESKTOP_USER_AGENT else cleanMobileUserAgent
            wv.settings.useWideViewPort = true
            wv.settings.loadWithOverviewMode = true
            wv.reload()
        }
    }

    fun copyCurrentUrl() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("教务页面地址", currentUrl))
        Toast.makeText(context, "已复制当前教务网址", Toast.LENGTH_SHORT).show()
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    val bannerError = errorMessage ?: pageLoadError

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "教务网页",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("关闭")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalizeUrl(currentUrl))))
                        }
                    }) { Text("浏览器打开") }
                    Button(
                        enabled = !isExtracting,
                        onClick = {
                            val wv = webViewRef ?: return@Button
                            val activeUrl = wv.url?.takeIf { it.isNotBlank() } ?: currentUrl
                            wv.evaluateJavascript(EXTRACT_ALL_FRAMES_JS) { raw ->
                                val decodedJson = runCatching { frameJson.decodeFromString<String>(raw) }.getOrNull()
                                val extractedDocs = if (!decodedJson.isNullOrBlank()) {
                                    runCatching {
                                        val payloads = frameJson.decodeFromString<List<ExtractedFramePayload>>(decodedJson)
                                        buildList {
                                            // 若检测到树维 EAMS 运行时课表对象（window.table0），优先置于最前供精准提取
                                            payloads.forEach { p ->
                                                val eams = p.eamsJson?.takeIf { it.isNotBlank() }
                                                if (eams != null) {
                                                    add("eams-runtime://${p.url.ifBlank { activeUrl }}" to eams)
                                                }
                                            }
                                            payloads.filter { it.html.isNotBlank() }.forEach { p ->
                                                add(p.url.ifBlank { activeUrl } to p.html)
                                            }
                                        }
                                    }.getOrNull()
                                } else null
                                val fallback = if (extractedDocs.isNullOrEmpty()) {
                                    listOf(activeUrl to (decodedJson ?: raw.orEmpty()))
                                } else extractedDocs
                                onExtractDocuments(activeUrl, fallback)
                            }
                        }
                    ) { Text("提取课表") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!isLandscape || showUrlBar) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = currentUrl,
                        onValueChange = { currentUrl = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("教务页面地址") }
                    )
                    FilledTonalButton(
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        onClick = {
                            pageLoadError = null
                            onClearError()
                            webViewRef?.let { loadCleanUrl(it, currentUrl) }
                        }
                    ) { Text("前往") }
                    OutlinedButton(
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        onClick = {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                            webViewRef?.clearCache(true)
                            webViewRef?.reload()
                            Toast.makeText(context, "已清除教务登录会话与网页缓存", Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("清会话") }
                }
            }
            // 常驻步骤横幅：识别失败或网页加载异常时变为红色并显示具体原因；
            // 当识别到已登录树维 EAMS / 强智首页时，直接提供「前往我的课表页」一键直达按钮
            Surface(
                color = if (bannerError != null) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = when {
                            errorMessage != null -> "尚未找到有效课表"
                            pageLoadError != null -> "网页加载异常"
                            timetableShortcut != null -> "已识别${timetableShortcut.systemHint}，可一键直达课表页"
                            else -> "请找到个人 / 学期课表"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (bannerError != null) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                    if (!isLandscape || bannerError != null || timetableShortcut != null) {
                        Text(
                            text = bannerError
                                ?: timetableShortcut?.description
                                ?: "① 在页内登录  ② 打开「学生课表 / 本学期课表」  ③ 点右上角「提取课表」。支持内嵌框架提取，绝不保存密码。",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (bannerError != null) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    }
                    if (timetableShortcut != null && pageLoadError == null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    pageLoadError = null
                                    onClearError()
                                    currentUrl = timetableShortcut.targetUrl
                                    webViewRef?.let { loadCleanUrl(it, timetableShortcut.targetUrl) }
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(timetableShortcut.label)
                            }
                            Text(
                                text = "直达后待课表显示，再点右上角「提取课表」",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (bannerError != null) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (pageLoadError != null) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilledTonalButton(
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                onClick = {
                                    pageLoadError = null
                                    webViewRef?.reload()
                                }
                            ) { Text("重试") }
                            if (currentUrl.trim().startsWith("https://", ignoreCase = true)) {
                                OutlinedButton(
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    onClick = {
                                        val httpUrl = "http://" + currentUrl.trim().removePrefix("https://").removePrefix("HTTPS://")
                                        pageLoadError = null
                                        onClearError()
                                        currentUrl = httpUrl
                                        webViewRef?.let { loadCleanUrl(it, httpUrl) }
                                    }
                                ) { Text("改用 HTTP 尝试") }
                            }
                            if (fallbackAcademicUrl.isNotBlank() &&
                                fallbackAcademicUrl.trimEnd('/') != currentUrl.trim().trimEnd('/')
                            ) {
                                OutlinedButton(
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    onClick = {
                                        pageLoadError = null
                                        onClearError()
                                        currentUrl = fallbackAcademicUrl
                                        webViewRef?.let { loadCleanUrl(it, fallbackAcademicUrl) }
                                    }
                                ) { Text("打开教务处网站") }
                            }
                            if (schoolName.isNotBlank()) {
                                OutlinedButton(
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    onClick = {
                                        val q = java.net.URLEncoder.encode("$schoolName 教务系统 课表", "UTF-8")
                                        val searchUrl = "https://www.bing.com/search?q=$q"
                                        pageLoadError = null
                                        onClearError()
                                        currentUrl = searchUrl
                                        webViewRef?.let { loadCleanUrl(it, searchUrl) }
                                    }
                                ) { Text("搜索最新入口") }
                            }
                        }
                    }
                }
            }
            if (isExtracting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (pageLoadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { pageLoadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        // 关键修复：关闭 Android WebView 默认附带的 X-Requested-With=<包名> 请求头！
                        // 树维 EAMS / Beangle / Struts2 等高校教务系统只要看到该头存在，就会把整页浏览当成 AJAX 请求
                        // 而剥离整个 <head>（丢失全部 CSS、jQuery、beangle.js、导航菜单与课表加载脚本）。
                        if (supportsStripXrw) {
                            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        @Suppress("DEPRECATION")
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.userAgentString = if (desktopMode) DESKTOP_USER_AGENT else cleanMobileUserAgent
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                pageLoadProgress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                pageLoadError = null
                                if (url != null && url != currentUrl) {
                                    onClearError()
                                }
                                url?.let { currentUrl = it }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                if (request?.isForMainFrame == true) {
                                    val target = request.url?.toString()
                                    if (!target.isNullOrBlank()) {
                                        currentUrl = target
                                        if (!supportsStripXrw && (target.startsWith("http://") || target.startsWith("https://")) && view != null) {
                                            loadCleanUrl(view, target)
                                            return true
                                        }
                                    }
                                }
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageLoadProgress = 100
                                url?.let { currentUrl = it }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    val desc = error?.description?.toString().orEmpty()
                                    pageLoadError = "页面无法加载${if (desc.isNotBlank()) "（$desc）" else ""}。该校教务可能仅限校园网/VPN 访问或地址已变更，可点下方按钮尝试切换入口，或改用文件导入。"
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    val code = errorResponse?.statusCode ?: 0
                                    if (code >= 400) {
                                        pageLoadError = "该校网页返回 HTTP $code，可能仅限校园网/VPN 访问、需要从教务处首页进入，或网址已更新。可点下方按钮改开教务处或搜索最新入口。"
                                    }
                                }
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
                                // 严格禁止绕过 SSL 证书校验：取消加载并给出合法替代入口
                                handler?.cancel()
                                pageLoadError = "该校网站 HTTPS 证书配置异常，安全策略已阻止加载。可点下方「改用 HTTP 尝试」或「打开教务处网站」，也可使用系统浏览器导出 Excel / PDF 后通过「文件导入」。"
                            }
                        }
                        loadCleanUrl(this, currentUrl)
                        webViewRef = this
                    }
                },
                onRelease = { wv ->
                    if (webViewRef === wv) webViewRef = null
                    wv.stopLoading()
                    wv.destroy()
                }
            )
            // 底部辅助控制：电脑模式 / 横屏模式 / 地址栏 / 打不开
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = desktopMode,
                    onClick = { applyDesktopMode(!desktopMode) },
                    label = { Text("电脑模式") }
                )
                FilterChip(
                    selected = landscapeMode,
                    onClick = {
                        hasToggledLandscape = true
                        landscapeMode = !landscapeMode
                    },
                    label = { Text("横屏模式") }
                )
                if (isLandscape) {
                    TextButton(onClick = { showUrlBar = !showUrlBar }) {
                        Text(if (showUrlBar) "收起地址" else "地址")
                    }
                }
                OutlinedButton(onClick = { showHelpDialog = true }) { Text("打不开") }
            }
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("页面打不开或没有课表？") },
            text = {
                Text(
                    "① 有些教务系统只支持电脑版，可开启「电脑模式」并转横屏，支持双指缩放查看；\n" +
                        "② 点右上角「浏览器打开」可在系统浏览器查看页面或导出课表文件（注意：系统浏览器与应用内浏览器登录状态不互通）；\n" +
                        "③ 若仍无法自动提取，建议在电脑或系统浏览器导出 Excel / PDF 课表后，关闭本页使用「文件导入」，或在导入页提交适配申请。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text("知道了") }
            },
            dismissButton = {
                TextButton(onClick = {
                    copyCurrentUrl()
                    showHelpDialog = false
                }) { Text("复制链接") }
            }
        )
    }
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

private const val MOBILE_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

/**
 * 递归提取顶层页面及所有可访问的同源 iframe / frame 完整 HTML，同时顺带导出树维 EAMS 运行时课表对象（window.table0），
 * 解决 jQuery AJAX 渲染课表后删除 <script> 标签导致仅靠 HTML 丢失结构化字段的问题。
 * 遇到跨域子框架时安全跳过，不中断主流程。
 */
private const val EXTRACT_ALL_FRAMES_JS = """
(function() {
  var results = [];
  function detectFromOffset(doc) {
    try {
      var scripts = doc ? doc.querySelectorAll('script') : [];
      for (var s = 0; s < scripts.length; s++) {
        var txt = scripts[s].textContent || scripts[s].innerHTML || '';
        var m = /\.marshalTable(?:ForAdminclass)?\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)/.exec(txt);
        if (m) {
          return { fromOffset: parseInt(m[1], 10), startWeek: parseInt(m[2], 10), endWeek: parseInt(m[3], 10) };
        }
      }
    } catch (e) {}
    return null;
  }
  function snapshotEamsTable(win, doc) {
    try {
      var candidates = [win.table0, win.table1, win.courseTable];
      try {
        var keys = Object.keys(win);
        for (var ki = 0; ki < keys.length; ki++) {
          var k = keys[ki];
          if (k === 'table0' || k === 'table1' || k === 'courseTable') continue;
          if (/table|course/i.test(k)) {
            try { candidates.push(win[k]); } catch (e) {}
          }
        }
      } catch (e) {}
      var marshalMeta = detectFromOffset(doc);
      for (var ci = 0; ci < candidates.length; ci++) {
        var tbl = candidates[ci];
        if (!tbl || !Array.isArray(tbl.activities) || typeof tbl.unitCounts !== 'number' || tbl.unitCounts < 28) continue;
        var hasAny = false;
        var actsOut = [];
        for (var i = 0; i < tbl.activities.length; i++) {
          var slot = tbl.activities[i];
          var arr = [];
          if (Array.isArray(slot)) {
            for (var j = 0; j < slot.length; j++) {
              var a = slot[j];
              if (!a || !a.courseName) continue;
              hasAny = true;
              arr.push({
                teacherName: String(a.teacherName || ''),
                courseName: String(a.courseName || ''),
                roomName: String(a.roomName || ''),
                vaildWeeks: String(a.vaildWeeks || ''),
                remark: String(a.remark || ''),
                assistantName: String(a.assistantName || ''),
                experiItemName: String(a.experiItemName || ''),
                schGroupNo: String(a.schGroupNo || '')
              });
            }
          }
          actsOut.push(arr);
        }
        var marshals = [];
        if (Array.isArray(tbl.marshalContents)) {
          for (var m = 0; m < tbl.marshalContents.length; m++) {
            var mc = tbl.marshalContents[m];
            if (mc != null && String(mc).length > 0) hasAny = true;
            marshals.push(mc == null ? '' : String(mc));
          }
        }
        if (hasAny) {
          var upd = (typeof win.unitCount === 'number' && win.unitCount >= 6 && win.unitCount <= 20)
            ? win.unitCount
            : (tbl.unitCounts % 7 === 0 ? (tbl.unitCounts / 7) : 12);
          return JSON.stringify({
            __eamsRuntimeTable: true,
            url: String(win.location && win.location.href || ''),
            unitCounts: tbl.unitCounts,
            unitsPerDay: upd,
            endAtSat: (typeof tbl.endAtSat === 'boolean' ? tbl.endAtSat : null),
            fromOffset: marshalMeta ? marshalMeta.fromOffset : null,
            startWeek: marshalMeta ? marshalMeta.startWeek : null,
            endWeek: marshalMeta ? marshalMeta.endWeek : null,
            marshalContents: marshals,
            activities: actsOut
          });
        }
      }
    } catch (e) {}
    return null;
  }
  function annotateDomMetadata(doc) {
    try {
      var cells = doc.querySelectorAll('td, th');
      for (var i = 0; i < cells.length; i++) {
        var cell = cells[i];
        if (cell.colSpan && cell.colSpan > 1) cell.setAttribute('data-agy-colspan', String(cell.colSpan));
        if (cell.rowSpan && cell.rowSpan > 1) cell.setAttribute('data-agy-rowspan', String(cell.rowSpan));
        if (cell.title && String(cell.title).trim().length > 0) cell.setAttribute('data-agy-title', String(cell.title).trim());
      }
    } catch (e) {}
  }
  function collect(win, depth) {
    if (!win || depth > 4) return;
    try {
      var doc = win.document;
      if (doc && doc.documentElement) {
        annotateDomMetadata(doc);
        results.push({
          url: String(win.location && win.location.href || ''),
          html: doc.documentElement.outerHTML || '',
          eamsJson: snapshotEamsTable(win, doc)
        });
      }
      var frames = doc ? doc.querySelectorAll('iframe, frame') : [];
      for (var i = 0; i < frames.length; i++) {
        try {
          if (frames[i].contentWindow) collect(frames[i].contentWindow, depth + 1);
        } catch (e) {}
      }
    } catch (e) {}
  }
  collect(window, 0);
  return JSON.stringify(results);
})();
"""

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
