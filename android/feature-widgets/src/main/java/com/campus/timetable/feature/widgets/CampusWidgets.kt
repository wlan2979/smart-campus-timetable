package com.campus.timetable.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.domain.engine.HolidayCalendarResolver
import com.campus.timetable.domain.engine.PeWeatherRuleEngine
import com.campus.timetable.domain.engine.ScheduleResolutionEngine
import com.campus.timetable.domain.model.*
import com.campus.timetable.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 显式日夜双色小组件配色方案：完整兼容 Android 8.0–15 浅色/深色主题及各国产 ROM 桌面。
 */
private object TaokeWidgetPalette {
    val surface = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1C1F26))
    val primary = ColorProvider(day = Color(0xFF1A73E8), night = Color(0xFF8AB4F8))
    val onSurface = ColorProvider(day = Color(0xFF1F1F1F), night = Color(0xFFE3E5EC))
    val onSurfaceVariant = ColorProvider(day = Color(0xFF5F6368), night = Color(0xFFAEB4C2))
    val error = ColorProvider(day = Color(0xFFC5221F), night = Color(0xFFF28B82))
    val itemBg = ColorProvider(day = Color(0xFFF3F6FC), night = Color(0xFF262B36))
}

data class TodayWidgetData(
    val dateLabel: String,
    val weekLabel: String,
    val badgeText: String?,
    val sessions: List<ResolvedCourseSession>,
    /** 聚合信息：实时天气（城市 + 温度 + 天气现象） */
    val weatherText: String = "",
    /** 聚合信息：下一节课/正在上课的倒计时（含课程名与教室） */
    val nextCourseText: String = "",
    /** 聚合信息：下一节课时间与地点 */
    val nextCourseMeta: String = ""
)

data class NextCourseWidgetData(
    val hasNextCourse: Boolean,
    val courseName: String = "",
    val countdownText: String = "",
    val timeText: String = "",
    val locationText: String = "",
    val emptyMessage: String = "今日剩余时间暂无课程"
)

data class WeeklyWidgetDaySummary(
    val date: LocalDate,
    val dayShortZh: String,
    val isToday: Boolean,
    val sessions: List<ResolvedCourseSession>
)

data class WeeklyWidgetData(
    val weekLabel: String,
    val semesterName: String,
    val days: List<WeeklyWidgetDaySummary>
)

data class WeatherPeWidgetData(
    val cityName: String,
    val temperatureText: String,
    val conditionText: String,
    val todayPeCourses: List<String>,
    val adviceText: String,
    val hasAlert: Boolean
)

/**
 * 统一的小组件真实数据读取器（全部来自 Room 本地数据库与真实缓存天气，零静态假数据）。
 */
@Singleton
class WidgetSnapshotLoader @Inject constructor(
    private val timetableRepository: TimetableRepository,
    private val holidayRepository: HolidayRepository,
    private val weatherRepository: WeatherRepository,
    private val clock: AppClock
) {
    suspend fun loadToday(): TodayWidgetData = withContext(Dispatchers.IO) {
        timetableRepository.ensureGuestInitialized()
        val today = clock.today()
        val dowZh = HolidayCalendarResolver.dayOfWeekShortZh(today.dayOfWeek.value)
        val sem = timetableRepository.getCurrentSemester()
            ?: return@withContext TodayWidgetData("${today.monthValue}月${today.dayOfMonth}日 周$dowZh", "未配置学期", null, emptyList())
        val courses = timetableRepository.getCoursesWithDetails(sem.id)
        val periods = timetableRepository.getClassPeriods(sem.id)
        val holidays = holidayRepository.getHolidaysByYear(today.year) +
            if (today.plusDays(7).year != today.year) {
                holidayRepository.getHolidaysByYear(today.year + 1)
            } else emptyList()
        val overrides = holidayRepository.getSchoolOverrides(sem.id)
        val (status, sessions) = ScheduleResolutionEngine.resolveDaySessions(sem, today, courses, periods, holidays, overrides)
        val visibleSessions = sessions.filter { !it.isCancelled }
        val setting = weatherRepository.getWeatherSetting()
        val weather = weatherRepository.getCachedWeather(setting)
        val weatherText = weather?.let {
            "${setting.cityName} ${it.currentTempC.toInt()}°C ${it.currentCondition}"
        } ?: "${setting.cityName} 天气待同步"
        val base = TodayWidgetData(
            dateLabel = "${today.monthValue}月${today.dayOfMonth}日 周$dowZh",
            weekLabel = "第${status.weekNumber.coerceIn(1, sem.totalWeeks)}周",
            badgeText = status.badgeText,
            sessions = visibleSessions,
            weatherText = weatherText
        )
        val next = nextCourseOf(base)
        base.copy(
            nextCourseText = if (next.hasNextCourse) {
                "${next.courseName} · ${next.countdownText}"
            } else {
                next.emptyMessage
            },
            nextCourseMeta = if (next.hasNextCourse) "${next.timeText} · ${next.locationText}" else ""
        )
    }

    suspend fun loadNextCourse(): NextCourseWidgetData = withContext(Dispatchers.IO) {
        nextCourseOf(loadToday())
    }

    private fun nextCourseOf(todayData: TodayWidgetData): NextCourseWidgetData {
        val nowTime = clock.currentTime()
        val active = todayData.sessions.filter { !it.holidayPaused }
        val inProgress = active.firstOrNull { !nowTime.isBefore(it.startTime) && nowTime.isBefore(it.endTime) }
        if (inProgress != null) {
            val leftMins = Duration.between(nowTime, inProgress.endTime).toMinutes().coerceAtLeast(1)
            return NextCourseWidgetData(
                hasNextCourse = true,
                courseName = inProgress.course.name,
                countdownText = "正在上课 · 距下课约 ${formatDuration(leftMins)}",
                timeText = "${inProgress.startTime}-${inProgress.endTime}",
                locationText = inProgress.effectiveLocation.ifBlank { "未填写教室" }
            )
        }
        val upcoming = active.firstOrNull { it.startTime.isAfter(nowTime) }
            ?: return NextCourseWidgetData(
                hasNextCourse = false,
                emptyMessage = if (todayData.sessions.isEmpty()) "今天没有排课，好好休息" else "今日课程已全部结束"
            )
        val minsUntil = Duration.between(nowTime, upcoming.startTime).toMinutes().coerceAtLeast(1)
        return NextCourseWidgetData(
            hasNextCourse = true,
            courseName = upcoming.course.name,
            countdownText = "距离上课还有 ${formatDuration(minsUntil)}",
            timeText = "${upcoming.startTime}-${upcoming.endTime}",
            locationText = upcoming.effectiveLocation.ifBlank { "未填写教室" }
        )
    }

    suspend fun loadWeekly(): WeeklyWidgetData = withContext(Dispatchers.IO) {
        timetableRepository.ensureGuestInitialized()
        val today = clock.today()
        val sem = timetableRepository.getCurrentSemester()
            ?: return@withContext WeeklyWidgetData("第1周", "未配置学期", emptyList())
        val currentWeek = ScheduleResolutionEngine.calculateWeekNumber(sem.parsedStartDate(), today)
            .coerceIn(1, sem.totalWeeks)
        val weekDates = ScheduleResolutionEngine.datesOfWeek(sem.parsedStartDate(), currentWeek)
        val courses = timetableRepository.getCoursesWithDetails(sem.id)
        val periods = timetableRepository.getClassPeriods(sem.id)
        val weekYears = weekDates.map { it.year }.distinct()
        val holidays = weekYears.flatMap { holidayRepository.getHolidaysByYear(it) }
        val overrides = holidayRepository.getSchoolOverrides(sem.id)

        val days = weekDates.map { d ->
            val (_, sessions) = ScheduleResolutionEngine.resolveDaySessions(sem, d, courses, periods, holidays, overrides)
            WeeklyWidgetDaySummary(
                date = d,
                dayShortZh = "周${HolidayCalendarResolver.dayOfWeekShortZh(d.dayOfWeek.value)}",
                isToday = d == today,
                sessions = sessions.filter { !it.isCancelled }
            )
        }
        WeeklyWidgetData(
            weekLabel = "第${currentWeek}周",
            semesterName = sem.name,
            days = days
        )
    }

    suspend fun loadWeatherPe(): WeatherPeWidgetData = withContext(Dispatchers.IO) {
        val today = clock.today()
        val setting = weatherRepository.getWeatherSetting()
        val snapshot = weatherRepository.getCachedWeather(setting)
        val todayData = loadToday()
        val peSessions = todayData.sessions.filter { it.isPhysicalEducation && !it.holidayPaused }
        val forecast = snapshot?.daily?.firstOrNull { it.date == today.toString() }
        val advice = PeWeatherRuleEngine.evaluatePeSessionsForDate(today, peSessions, forecast, setting)

        val tempText = snapshot?.let { "${it.currentTempC.toInt()}°C" } ?: "--°C"
        val condText = snapshot?.currentCondition ?: "点击打开同步实时天气"
        val peNames = peSessions.map { "${it.course.name}(${it.startTime})" }
        val adviceMsg = when {
            advice != null -> advice.summary
            peSessions.isNotEmpty() && snapshot == null -> "今日有体育课，点击刷新真实天气预报"
            else -> "今日无体育课排课"
        }
        WeatherPeWidgetData(
            cityName = setting.cityName,
            temperatureText = tempText,
            conditionText = condText,
            todayPeCourses = peNames,
            adviceText = adviceMsg,
            hasAlert = advice?.needsAlert == true
        )
    }

    private fun formatDuration(totalMinutes: Long): String {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0 && m > 0 -> "${h}小时${m}分钟"
            h > 0 -> "${h}小时"
            else -> "${m}分钟"
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetDataEntryPoint {
    fun snapshotLoader(): WidgetSnapshotLoader
}

/**
 * 显式 ComponentName + 唯一 DeepLink URI，彻底避免不同小组件 PendingIntent 参数覆盖与跳转失效。
 */
fun buildWidgetOpenRouteIntent(context: Context, route: String): Intent {
    return Intent(Intent.ACTION_VIEW, Uri.parse("taoke://open/$route")).apply {
        component = ComponentName(context.packageName, "com.campus.timetable.MainActivity")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("target_route", route)
    }
}

private fun loaderFrom(context: Context): WidgetSnapshotLoader =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetDataEntryPoint::class.java).snapshotLoader()

// ==================== 1. 今日课程小组件 ====================
class TodayCoursesWidget : GlanceAppWidget(errorUiLayout = R.layout.widget_error_fallback) {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = runCatching { loaderFrom(context).loadToday() }.getOrElse {
            TodayWidgetData("桃课 · 今日课程", "", null, emptyList())
        }
        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(16.dp)
                        .background(TaokeWidgetPalette.surface)
                        .clickable(actionStartActivity(buildWidgetOpenRouteIntent(context, "timetable")))
                        .padding(12.dp)
                ) {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${data.dateLabel} · ${data.weekLabel}",
                            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TaokeWidgetPalette.primary),
                            maxLines = 1
                        )
                        data.badgeText?.let {
                            Spacer(GlanceModifier.width(6.dp))
                            Text(text = "[$it]", style = TextStyle(fontSize = 11.sp, color = TaokeWidgetPalette.error))
                        }
                    }
                    if (data.weatherText.isNotBlank()) {
                        Spacer(GlanceModifier.height(2.dp))
                        Text(
                            text = "天气：${data.weatherText}",
                            style = TextStyle(fontSize = 11.sp, color = TaokeWidgetPalette.onSurfaceVariant),
                            maxLines = 1
                        )
                    }
                    if (data.nextCourseText.isNotBlank()) {
                        Spacer(GlanceModifier.height(4.dp))
                        Text(
                            text = data.nextCourseText,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TaokeWidgetPalette.primary),
                            maxLines = 1
                        )
                        if (data.nextCourseMeta.isNotBlank()) {
                            Text(
                                text = data.nextCourseMeta,
                                style = TextStyle(fontSize = 10.sp, color = TaokeWidgetPalette.onSurfaceVariant),
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    if (data.sessions.isEmpty()) {
                        Text(
                            text = "今日暂无课程安排，点击打开桃课查看周课表",
                            style = TextStyle(fontSize = 12.sp, color = TaokeWidgetPalette.onSurfaceVariant)
                        )
                    } else {
                        data.sessions.take(3).forEach { s ->
                            Row(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .cornerRadius(8.dp)
                                    .background(TaokeWidgetPalette.itemBg)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${s.startTime.toString().take(5)} ",
                                    style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, color = TaokeWidgetPalette.primary)
                                )
                                Text(
                                    text = "${s.course.name} · ${s.effectiveLocation.ifBlank { "待定" }}",
                                    style = TextStyle(fontSize = 12.sp, color = TaokeWidgetPalette.onSurface),
                                    maxLines = 1
                                )
                            }
                            Spacer(GlanceModifier.height(3.dp))
                        }
                        if (data.sessions.size > 3) {
                            Text(
                                text = "还有 ${data.sessions.size - 3} 节课，点击查看全部",
                                style = TextStyle(fontSize = 10.sp, color = TaokeWidgetPalette.onSurfaceVariant)
                            )
                        }
                    }
                }
            }
        }
    }
}

class TodayCoursesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayCoursesWidget()
}

// ==================== 2. 下一节课倒计时小组件 ====================
class NextCourseCountdownWidget : GlanceAppWidget(errorUiLayout = R.layout.widget_error_fallback) {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = runCatching { loaderFrom(context).loadNextCourse() }.getOrElse {
            NextCourseWidgetData(hasNextCourse = false)
        }
        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(16.dp)
                        .background(TaokeWidgetPalette.surface)
                        .clickable(actionStartActivity(buildWidgetOpenRouteIntent(context, "timetable")))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "桃课 · 下一节课",
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TaokeWidgetPalette.primary)
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    if (!data.hasNextCourse) {
                        Text(
                            text = data.emptyMessage,
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TaokeWidgetPalette.onSurfaceVariant)
                        )
                    } else {
                        Text(
                            text = data.courseName,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TaokeWidgetPalette.onSurface),
                            maxLines = 1
                        )
                        Spacer(GlanceModifier.height(2.dp))
                        Text(
                            text = data.countdownText,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TaokeWidgetPalette.primary)
                        )
                        Text(
                            text = "${data.timeText} · ${data.locationText}",
                            style = TextStyle(fontSize = 11.sp, color = TaokeWidgetPalette.onSurfaceVariant),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

class NextCourseCountdownWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextCourseCountdownWidget()
}

// ==================== 3. 本周课表小组件 ====================
class WeeklyTimetableWidget : GlanceAppWidget(errorUiLayout = R.layout.widget_error_fallback) {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(220.dp, 140.dp), DpSize(300.dp, 240.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = runCatching { loaderFrom(context).loadWeekly() }.getOrElse {
            WeeklyWidgetData("本周课表", "桃课", emptyList())
        }
        provideContent {
            val size = LocalSize.current
            val compactMode = size.height < 200.dp
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(16.dp)
                        .background(TaokeWidgetPalette.surface)
                        .clickable(actionStartActivity(buildWidgetOpenRouteIntent(context, "timetable")))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "桃课 ${data.weekLabel} · ${data.semesterName}",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TaokeWidgetPalette.primary),
                        maxLines = 1
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    val daysToShow = if (compactMode) data.days.take(5) else data.days
                    daysToShow.forEach { day ->
                        val summary = if (day.sessions.isEmpty()) {
                            "无课"
                        } else {
                            day.sessions.take(2).joinToString(" / ") { "${it.course.name}(${it.startPeriod}-${it.endPeriod})" } +
                                if (day.sessions.size > 2) " +${day.sessions.size - 2}" else ""
                        }
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (day.isToday) "${day.dayShortZh}* " else "${day.dayShortZh}  ",
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Medium,
                                    color = if (day.isToday) TaokeWidgetPalette.primary else TaokeWidgetPalette.onSurfaceVariant
                                )
                            )
                            Text(
                                text = summary,
                                style = TextStyle(fontSize = 11.sp, color = TaokeWidgetPalette.onSurface),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

class WeeklyTimetableWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeeklyTimetableWidget()
}

// ==================== 4. 天气与体育课提醒小组件 ====================
class WeatherPeWidget : GlanceAppWidget(errorUiLayout = R.layout.widget_error_fallback) {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = runCatching { loaderFrom(context).loadWeatherPe() }.getOrElse {
            WeatherPeWidgetData("未定位", "--°C", "点击刷新天气", emptyList(), "暂无提醒", false)
        }
        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(16.dp)
                        .background(TaokeWidgetPalette.surface)
                        .clickable(actionStartActivity(buildWidgetOpenRouteIntent(context, "weather")))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "${data.cityName} · ${data.temperatureText} ${data.conditionText}",
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TaokeWidgetPalette.primary),
                        maxLines = 1
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        text = if (data.todayPeCourses.isEmpty()) "今日体育课：无" else "今日体育课：${data.todayPeCourses.joinToString("、")}",
                        style = TextStyle(fontSize = 11.sp, color = TaokeWidgetPalette.onSurface),
                        maxLines = 2
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    Text(
                        text = data.adviceText,
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (data.hasAlert) TaokeWidgetPalette.error else TaokeWidgetPalette.onSurfaceVariant
                        ),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

class WeatherPeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherPeWidget()
}

/**
 * 系统桌面实际固定小组件后的回调接收器：只有系统真正确认添加成功时才提示用户，绝不伪造成功。
 */
class WidgetPinnedCallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("widget_label") ?: "桃课桌面小组件"
        Toast.makeText(context, "已成功将「$label」添加到桌面", Toast.LENGTH_SHORT).show()
    }
}

// ==================== 统一刷新器（供所有业务模块调用） ====================
@Singleton
class GlanceWidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context
) : WidgetRefresherPort {
    override suspend fun refreshAllWidgets() {
        runCatching { TodayCoursesWidget().updateAll(context) }
        runCatching { NextCourseCountdownWidget().updateAll(context) }
        runCatching { WeeklyTimetableWidget().updateAll(context) }
        runCatching { WeatherPeWidget().updateAll(context) }
    }

    fun getPinnedCount(receiverClass: Class<*>): Int {
        return runCatching {
            val mgr = AppWidgetManager.getInstance(context)
            mgr.getAppWidgetIds(ComponentName(context, receiverClass)).size
        }.getOrDefault(0)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetsBindingsModule {
    @Binds
    @Singleton
    abstract fun bindWidgetRefresherPort(impl: GlanceWidgetRefresher): WidgetRefresherPort
}
