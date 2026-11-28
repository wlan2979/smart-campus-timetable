package com.campus.timetable.feature.weather

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.domain.engine.PeWeatherRuleEngine
import com.campus.timetable.domain.engine.ScheduleResolutionEngine
import com.campus.timetable.domain.model.*
import com.campus.timetable.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WeatherUiState(
    val setting: WeatherSetting = WeatherSetting(),
    val snapshot: WeatherSnapshot? = null,
    val upcomingPeAdvices: List<PeWeatherAdvice> = emptyList(),
    val citySearchQuery: String = "",
    val citySearchResults: List<CityOption> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val feedback: String? = null
)

@HiltViewModel
class WeatherViewModel @Inject constructor(
    private val weatherRepository: WeatherRepository,
    private val timetableRepository: TimetableRepository,
    private val holidayRepository: HolidayRepository,
    private val locationHelper: OneShotLocationHelper,
    private val weatherScheduler: PeWeatherScheduler,
    private val widgetRefresher: WidgetRefresherPort,
    private val clock: AppClock
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeatherUiState())
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            weatherRepository.observeWeatherSetting().collect { setting ->
                val cached = weatherRepository.getCachedWeather(setting)
                val advices = computeUpcomingPeAdvices(setting, cached)
                _uiState.update {
                    it.copy(
                        setting = setting,
                        snapshot = cached,
                        upcomingPeAdvices = advices
                    )
                }
            }
        }
    }

    fun refreshLiveWeather() {
        viewModelScope.launch {
            val setting = _uiState.value.setting
            _uiState.update { it.copy(isLoading = true, error = null) }
            weatherRepository.fetchLiveWeather(setting)
                .onSuccess { snap ->
                    val advices = computeUpcomingPeAdvices(setting, snap)
                    weatherScheduler.schedulePeWeatherChecks()
                    widgetRefresher.refreshAllWidgets()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            snapshot = snap,
                            upcomingPeAdvices = advices,
                            feedback = "已获取 ${snap.cityName} 最新真实天气（来源：${snap.providerName}）"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "天气获取失败：${e.localizedMessage ?: "请检查网络或切换天气源"}"
                        )
                    }
                }
        }
    }

    fun autoLocateCurrentCity() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            locationHelper.fetchCurrentCityOnce()
                .onSuccess { city ->
                    val next = _uiState.value.setting.copy(
                        locationMode = LocationMode.AUTO,
                        cityName = city.name,
                        cityId = city.cityId,
                        latitude = city.latitude,
                        longitude = city.longitude,
                        lastWeatherJson = null,
                        lastUpdatedAt = null
                    )
                    weatherRepository.saveWeatherSetting(next)
                    _uiState.update { it.copy(setting = next, snapshot = null, upcomingPeAdvices = emptyList()) }
                    refreshLiveWeather()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "定位失败") }
                }
        }
    }

    fun selectCity(city: CityOption) {
        viewModelScope.launch {
            val next = _uiState.value.setting.copy(
                locationMode = LocationMode.MANUAL,
                cityName = city.name,
                cityId = city.cityId,
                latitude = city.latitude,
                longitude = city.longitude,
                lastWeatherJson = null,
                lastUpdatedAt = null
            )
            weatherRepository.saveWeatherSetting(next)
            _uiState.update {
                it.copy(
                    setting = next,
                    snapshot = null,
                    upcomingPeAdvices = emptyList(),
                    citySearchResults = emptyList(),
                    citySearchQuery = ""
                )
            }
            refreshLiveWeather()
        }
    }

    fun updateCitySearchQuery(q: String) {
        // 输入变化时清空上一次联网结果，避免展示与当前关键词不符的城市
        _uiState.update { it.copy(citySearchQuery = q, citySearchResults = emptyList()) }
    }

    fun searchCitiesOnline() {
        val q = _uiState.value.citySearchQuery.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            weatherRepository.searchCities(q, _uiState.value.setting.provider)
                .onSuccess { list ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            citySearchResults = list,
                            feedback = if (list.isEmpty()) "未找到匹配城市「$q」" else null
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.localizedMessage) }
                }
        }
    }

    fun updateSetting(transform: (WeatherSetting) -> WeatherSetting) {
        viewModelScope.launch {
            val next = transform(_uiState.value.setting)
            weatherRepository.saveWeatherSetting(next)
            weatherScheduler.schedulePeWeatherChecks()
            widgetRefresher.refreshAllWidgets()
            _uiState.update { it.copy(feedback = "天气提醒规则已更新并重新排程") }
        }
    }

    fun clearFeedback() { _uiState.update { it.copy(feedback = null) } }

    private suspend fun computeUpcomingPeAdvices(
        setting: WeatherSetting,
        snapshot: WeatherSnapshot?
    ): List<PeWeatherAdvice> {
        if (snapshot == null) return emptyList()
        val sem = timetableRepository.getCurrentSemester() ?: return emptyList()
        val courses = timetableRepository.getCoursesWithDetails(sem.id)
        val periods = timetableRepository.getClassPeriods(sem.id)
        val today = clock.today()
        val holidays = holidayRepository.getHolidaysByYear(today.year)
        val overrides = holidayRepository.getSchoolOverrides(sem.id)

        val result = mutableListOf<PeWeatherAdvice>()
        for (offset in 0L..6L) {
            val d = today.plusDays(offset)
            val (_, sessions) = ScheduleResolutionEngine.resolveDaySessions(sem, d, courses, periods, holidays, overrides)
            val pe = sessions.filter { it.isPhysicalEducation && !it.isCancelled && !it.holidayPaused }
            if (pe.isEmpty()) continue
            val forecast = snapshot.daily.firstOrNull { it.date == d.toString() }
            PeWeatherRuleEngine.evaluatePeSessionsForDate(d, pe, forecast, setting)?.let { result += it }
        }
        return result
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WeatherScreen(
    onBack: () -> Unit,
    viewModel: WeatherViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCityPicker by remember { mutableStateOf(false) }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.any { it }) viewModel.autoLocateCurrentCity()
    }

    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    var highTempInput by remember(state.setting.highTempThresholdC) { mutableStateOf(state.setting.highTempThresholdC.toInt().toString()) }
    var lowTempInput by remember(state.setting.lowTempThresholdC) { mutableStateOf(state.setting.lowTempThresholdC.toInt().toString()) }
    var aqiInput by remember(state.setting.aqiThreshold) { mutableStateOf(state.setting.aqiThreshold.toString()) }
    var prevHourInput by remember(state.setting.prevDayNotifyHour) { mutableStateOf(state.setting.prevDayNotifyHour.toString()) }
    var sameDayAdvInput by remember(state.setting.sameDayAdvanceMinutes) { mutableStateOf(state.setting.sameDayAdvanceMinutes.toString()) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("天气与体育课提醒") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("返回")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.refreshLiveWeather() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("刷新")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.isLoading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            state.error?.let { err ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            // 当前城市与实时天气卡片
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(state.setting.cityName, style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    "定位模式：${state.setting.locationMode.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.snapshot != null) {
                                Text(
                                    "${state.snapshot!!.currentTempC.toInt()}°C ${state.snapshot!!.currentCondition}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (state.snapshot == null) {
                            Text("尚未拉取实时天气数据，点击下方按钮获取真实气象预报。", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                "实况来源：${state.snapshot!!.providerName}" +
                                    (state.snapshot!!.currentAqi?.let { " · AQI $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { viewModel.refreshLiveWeather() }) { Text("刷新真实天气") }
                            OutlinedButton(onClick = { showCityPicker = true }) { Text("切换城市") }
                            OutlinedButton(onClick = {
                                locationPermLauncher.launch(
                                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                                )
                            }) { Text("自动定位") }
                        }
                    }
                }
            }

            // 未来 7 天体育课天气预警评估
            item { Text("未来 7 天体育课天气检查结果", style = MaterialTheme.typography.titleMedium) }
            if (state.upcomingPeAdvices.isEmpty()) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Text(
                            "未来 7 天内暂无体育课排课，或尚未刷新天气数据。将课程类型设为「体育课程」后将自动接入前一天与当天双重天气提醒。",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(state.upcomingPeAdvices) { adv ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (adv.needsAlert) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(adv.summary, fontWeight = FontWeight.Bold)
                            Text(adv.detail, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // 天气源策略切换
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("天气数据来源策略", fontWeight = FontWeight.SemiBold)
                        Text(
                            "所有天气数据均来自真实公开气象接口；不再使用手机系统自带天气（厂商未开放公开数据接口）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        WeatherProviderType.entries.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { viewModel.updateSetting { it.copy(provider = p) } },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = state.setting.provider == p, onClick = { viewModel.updateSetting { it.copy(provider = p) } })
                                Text(p.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // 预警条件与阈值配置
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("开启体育课天气提醒", fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = state.setting.enablePeWeatherReminder,
                                onCheckedChange = { v -> viewModel.updateSetting { it.copy(enablePeWeatherReminder = v) } }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = state.setting.remindRain, onClick = { viewModel.updateSetting { it.copy(remindRain = !it.remindRain) } }, label = { Text("降雨") })
                            FilterChip(selected = state.setting.remindSnow, onClick = { viewModel.updateSetting { it.copy(remindSnow = !it.remindSnow) } }, label = { Text("降雪") })
                            FilterChip(selected = state.setting.remindThunder, onClick = { viewModel.updateSetting { it.copy(remindThunder = !it.remindThunder) } }, label = { Text("雷电") })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = highTempInput, onValueChange = { highTempInput = it }, label = { Text("高温阈值°C") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = lowTempInput, onValueChange = { lowTempInput = it }, label = { Text("低温阈值°C") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = aqiInput, onValueChange = { aqiInput = it.filter(Char::isDigit) }, label = { Text("AQI阈值") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = prevHourInput, onValueChange = { prevHourInput = it.filter(Char::isDigit) }, label = { Text("前一天晚间提醒(时)") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = sameDayAdvInput, onValueChange = { sameDayAdvInput = it.filter(Char::isDigit) }, label = { Text("当天课前提前(分)") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        Button(onClick = {
                            viewModel.updateSetting {
                                it.copy(
                                    highTempThresholdC = highTempInput.toDoubleOrNull() ?: 33.0,
                                    lowTempThresholdC = lowTempInput.toDoubleOrNull() ?: 5.0,
                                    aqiThreshold = (aqiInput.toIntOrNull() ?: 150).coerceIn(50, 500),
                                    prevDayNotifyHour = (prevHourInput.toIntOrNull() ?: 20).coerceIn(0, 23),
                                    sameDayAdvanceMinutes = (sameDayAdvInput.toIntOrNull() ?: 90).coerceIn(15, 360)
                                )
                            }
                        }) { Text("保存预警阈值与提醒时间") }
                    }
                }
            }
        }
    }

    if (showCityPicker) {
        AlertDialog(
            onDismissRequest = { showCityPicker = false },
            title = { Text("手动选择或搜索城市") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.citySearchQuery,
                            onValueChange = { viewModel.updateCitySearchQuery(it) },
                            label = { Text("输入城市名") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        FilledTonalButton(onClick = { viewModel.searchCitiesOnline() }) { Text("搜索") }
                    }
                    val query = state.citySearchQuery.trim()
                    val localMatches = if (query.isBlank()) ChinaMajorCities.presets
                    else ChinaMajorCities.presets.filter { it.name.contains(query) || it.province.contains(query) }
                    val displayList = (state.citySearchResults + localMatches)
                        .distinctBy { it.name to it.latitude }
                    Text(
                        "离线收录 ${ChinaMajorCities.presets.size} 座城市，输入即可即时筛选；也可点击「搜索」联网查询 Open-Meteo 全国城市。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (displayList.isEmpty()) {
                        Text(
                            "未找到「$query」。请尝试更短的城市名（例如输入「杭州」「苏州」），或点击「搜索」联网查询。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        LazyColumn(modifier = Modifier.height(260.dp)) {
                            items(displayList, key = { "${it.name}-${it.latitude}" }) { city ->
                                ListItem(
                                    headlineContent = { Text(city.name) },
                                    supportingContent = { Text(city.province) },
                                    modifier = Modifier.clickable {
                                        viewModel.selectCity(city)
                                        showCityPicker = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCityPicker = false }) { Text("关闭") } }
        )
    }
}
