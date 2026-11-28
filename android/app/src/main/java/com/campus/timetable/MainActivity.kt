package com.campus.timetable

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.campus.timetable.core.theme.AppThemeMode
import com.campus.timetable.core.theme.CampusTimetableTheme
import com.campus.timetable.core.theme.ThemeSeedColor
import com.campus.timetable.domain.repository.SettingsRepository
import com.campus.timetable.feature.alarm.AlarmSettingsScreen
import com.campus.timetable.feature.holiday.HolidayScreen
import com.campus.timetable.feature.importing.ImportChromeState
import com.campus.timetable.feature.importing.ImportScreen
import com.campus.timetable.feature.settings.SettingsScreen
import com.campus.timetable.feature.sync.SyncScreen
import com.campus.timetable.feature.timetable.BellScheduleScreen
import com.campus.timetable.feature.timetable.CourseDetailScreen
import com.campus.timetable.feature.timetable.CourseEditScreen
import com.campus.timetable.feature.timetable.SemesterManagementScreen
import com.campus.timetable.feature.timetable.TimetableScreen
import com.campus.timetable.feature.weather.WeatherScreen
import com.campus.timetable.feature.widgets.WidgetsCenterScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

object AppRoutes {
    const val TIMETABLE = "timetable"
    const val COURSE_DETAIL = "course_detail/{courseId}"
    const val COURSE_EDIT = "course_edit?courseId={courseId}"
    const val IMPORT = "import"
    const val SEMESTER = "semester"
    const val BELL_SCHEDULE = "bell_schedule"
    const val ALARM = "alarm"
    const val WEATHER = "weather"
    const val HOLIDAY = "holiday"
    const val SYNC = "sync"
    const val WIDGETS = "widgets"
    const val SETTINGS = "settings"

    fun courseDetail(courseId: String) = "course_detail/$courseId"
    fun courseEdit(courseId: String? = null) = if (courseId == null) "course_edit" else "course_edit?courseId=$courseId"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private var pendingWidgetRoute by mutableStateOf<String?>(null)

    private fun extractRoute(intent: Intent?): String? {
        if (intent == null) return null
        val extra = intent.getStringExtra("target_route")
        if (!extra.isNullOrBlank()) return extra
        val uri = intent.data
        if (uri != null && uri.scheme == "taoke" && uri.host == "open") {
            return uri.lastPathSegment
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingWidgetRoute = extractRoute(intent)

        setContent {
            val themeModeStr by settingsRepository.observeThemeMode().collectAsStateWithLifecycle(initialValue = "SYSTEM")
            val seedColorKey by settingsRepository.observeSeedColor().collectAsStateWithLifecycle(initialValue = "ocean_blue")

            val themeMode = runCatching { AppThemeMode.valueOf(themeModeStr) }.getOrDefault(AppThemeMode.SYSTEM)
            val seedColor = ThemeSeedColor.fromKey(seedColorKey)

            CampusTimetableTheme(
                themeMode = themeMode,
                seedColor = seedColor
            ) {
                CampusAppNavHost(
                    externalRoute = pendingWidgetRoute,
                    onConsumeExternalRoute = { pendingWidgetRoute = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractRoute(intent)?.let { pendingWidgetRoute = it }
    }
}

@Composable
private fun CampusAppNavHost(
    externalRoute: String?,
    onConsumeExternalRoute: () -> Unit
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: AppRoutes.TIMETABLE

    LaunchedEffect(externalRoute) {
        if (!externalRoute.isNullOrBlank()) {
            navController.navigate(externalRoute) {
                launchSingleTop = true
            }
            onConsumeExternalRoute()
        }
    }

    val topLevelTabs = listOf(
        Triple(AppRoutes.TIMETABLE, "课表", Icons.Outlined.CalendarMonth),
        Triple(AppRoutes.IMPORT, "导入", Icons.Outlined.Download),
        Triple(AppRoutes.ALARM, "提醒", Icons.Outlined.Alarm),
        Triple(AppRoutes.SETTINGS, "设置", Icons.Outlined.Settings)
    )
    val browserOpen by ImportChromeState.browserOpen.collectAsState()
    val showBottomBar = topLevelTabs.any { it.first == currentRoute } && !browserOpen

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevelTabs.forEach { (route, label, icon) ->
                        NavigationBarItem(
                            selected = currentRoute == route,
                            onClick = {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppRoutes.TIMETABLE,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(AppRoutes.TIMETABLE) {
                TimetableScreen(
                    onAddCourse = { navController.navigate(AppRoutes.courseEdit()) },
                    onCourseClick = { id -> navController.navigate(AppRoutes.courseDetail(id)) },
                    onOpenImport = { navController.navigate(AppRoutes.IMPORT) },
                    onOpenSemester = { navController.navigate(AppRoutes.SEMESTER) },
                    onOpenHoliday = { navController.navigate(AppRoutes.HOLIDAY) },
                    onOpenWeather = { navController.navigate(AppRoutes.WEATHER) },
                    onOpenWidgets = { navController.navigate(AppRoutes.WIDGETS) }
                )
            }
            composable(
                route = AppRoutes.COURSE_DETAIL,
                arguments = listOf(navArgument("courseId") { type = NavType.StringType })
            ) { entry ->
                val id = entry.arguments?.getString("courseId").orEmpty()
                CourseDetailScreen(
                    courseId = id,
                    onBack = { navController.popBackStack() },
                    onEditCourse = { cid -> navController.navigate(AppRoutes.courseEdit(cid)) }
                )
            }
            composable(
                route = AppRoutes.COURSE_EDIT,
                arguments = listOf(navArgument("courseId") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) { entry ->
                val id = entry.arguments?.getString("courseId")
                CourseEditScreen(
                    courseId = id,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(AppRoutes.SEMESTER) {
                SemesterManagementScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBellSchedule = { navController.navigate(AppRoutes.BELL_SCHEDULE) }
                )
            }
            composable(AppRoutes.BELL_SCHEDULE) {
                BellScheduleScreen(onBack = { navController.popBackStack() })
            }
            composable(AppRoutes.IMPORT) {
                ImportScreen(
                    onImportedSuccess = {
                        navController.navigate(AppRoutes.TIMETABLE) {
                            popUpTo(AppRoutes.TIMETABLE) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(AppRoutes.ALARM) {
                AlarmSettingsScreen()
            }
            composable(AppRoutes.WEATHER) {
                WeatherScreen(onBack = { navController.popBackStack() })
            }
            composable(AppRoutes.HOLIDAY) {
                HolidayScreen(onBack = { navController.popBackStack() })
            }
            composable(AppRoutes.SYNC) {
                SyncScreen(onBack = { navController.popBackStack() })
            }
            composable(AppRoutes.WIDGETS) {
                WidgetsCenterScreen(onBack = { navController.popBackStack() })
            }
            composable(AppRoutes.SETTINGS) {
                SettingsScreen(
                    onOpenSemester = { navController.navigate(AppRoutes.SEMESTER) },
                    onOpenWeather = { navController.navigate(AppRoutes.WEATHER) },
                    onOpenHoliday = { navController.navigate(AppRoutes.HOLIDAY) },
                    onOpenSync = { navController.navigate(AppRoutes.SYNC) },
                    onOpenWidgets = { navController.navigate(AppRoutes.WIDGETS) }
                )
            }
        }
    }
}
