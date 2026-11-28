package com.campus.timetable.feature.weather

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.domain.engine.PeWeatherRuleEngine
import com.campus.timetable.domain.engine.ScheduleResolutionEngine
import com.campus.timetable.domain.model.CityOption
import com.campus.timetable.domain.model.PeWeatherAdvice
import com.campus.timetable.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton


/**
 * 按需单次定位器：仅在用户于天气页面主动触发自动定位并授予权限时读取一次当前位置，绝不后台持续追踪。
 */
@Singleton
class OneShotLocationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasLocationPermission(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return coarse || fine
    }

    @SuppressLint("MissingPermission")
    suspend fun fetchCurrentCityOnce(): Result<CityOption> = withContext(Dispatchers.IO) {
        runCatching {
            if (!hasLocationPermission()) {
                throw SecurityException("尚未授予位置权限，请先允许获取大概位置或改用手动选择城市")
            }
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            var loc: Location? = providers.firstNotNullOfOrNull { p ->
                runCatching { if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }.getOrNull()
            }
            // Android 11+ 无系统定位缓存时，主动触发一次单次按需定位（超时 8 秒，绝不后台持续追踪）
            if (loc == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val activeProvider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                    .firstOrNull { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
                if (activeProvider != null) {
                    loc = withTimeoutOrNull(8_000L) {
                        suspendCancellableCoroutine { cont ->
                            val signal = CancellationSignal()
                            cont.invokeOnCancellation { signal.cancel() }
                            runCatching {
                                lm.getCurrentLocation(activeProvider, signal, context.mainExecutor) { fresh ->
                                    if (cont.isActive) cont.resume(fresh)
                                }
                            }.onFailure {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    }
                }
            }
            val resolvedLoc = loc ?: throw IllegalStateException("未获取到设备定位，请确认手机定位开关已开启或改用手动选择城市")

            var cityName = "当前位置"
            var province = ""
            runCatching {
                val geocoder = Geocoder(context, Locale.CHINA)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(resolvedLoc.latitude, resolvedLoc.longitude, 1)
                val first = addresses?.firstOrNull()
                if (first != null) {
                    cityName = (first.locality ?: first.subAdminArea ?: first.adminArea ?: "当前位置")
                        .removeSuffix("市")
                    province = first.adminArea.orEmpty()
                }
            }
            CityOption(
                name = cityName,
                province = province,
                latitude = resolvedLoc.latitude,
                longitude = resolvedLoc.longitude
            )
        }
    }
}

/**
 * 体育课天气提醒后台任务（WorkManager），根据实际体育课日期拉取最新真实天气并发送合并预警通知。
 * 天气拉取失败时静默降级，绝不影响正常课表与上课闹钟。
 */
@HiltWorker
class PeWeatherCheckWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val weatherScheduler: PeWeatherScheduler
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val targetDateIso = inputData.getString(KEY_TARGET_DATE)
        val stageLabel = inputData.getString(KEY_STAGE_LABEL) ?: "体育课天气提醒"
        return try {
            val targetDate = targetDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            weatherScheduler.checkAndNotifyForDate(targetDate, stageLabel)
            Result.success()
        } catch (_: Exception) {
            // 天气接口失败不影响正常课表与闹钟，温和返回成功或延后重试
            Result.success()
        }
    }

    companion object {
        const val KEY_TARGET_DATE = "target_date"
        const val KEY_STAGE_LABEL = "stage_label"
    }
}

@Singleton
class PeWeatherScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timetableRepository: TimetableRepository,
    private val holidayRepository: HolidayRepository,
    private val weatherRepository: WeatherRepository,
    private val clock: AppClock
) : WeatherSchedulerPort {

    companion object {
        const val CHANNEL_PE_WEATHER = "campus_pe_weather_alerts"
        private const val TAG_PE_WEATHER_WORK = "pe_weather_check_work"
    }

    fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PE_WEATHER,
                "体育课天气预警",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "体育课前一天晚间与当天课前推送降雨、雷电、高温、低温及空气质量提醒"
            }
        )
    }

    override suspend fun schedulePeWeatherChecks() {
        val wm = WorkManager.getInstance(context)
        wm.cancelAllWorkByTag(TAG_PE_WEATHER_WORK)

        val setting = weatherRepository.getWeatherSetting()
        if (!setting.enablePeWeatherReminder) return
        val sem = timetableRepository.getCurrentSemester() ?: return
        val courses = timetableRepository.getCoursesWithDetails(sem.id)
        val periods = timetableRepository.getClassPeriods(sem.id)
        val today = clock.today()
        val now = clock.now()
        val holidays = holidayRepository.getHolidaysByYear(today.year) +
            if (today.plusDays(7).year != today.year) {
                holidayRepository.getHolidaysByYear(today.year + 1)
            } else emptyList()
        val overrides = holidayRepository.getSchoolOverrides(sem.id)

        // 检查未来 7 天内有体育课的日期，为每天安排「前一天晚间预报」+「当天课前再查」两次任务
        for (offset in 0L..7L) {
            val date = today.plusDays(offset)
            val (_, sessions) = ScheduleResolutionEngine.resolveDaySessions(
                sem, date, courses, periods, holidays, overrides
            )
            val peSessions = sessions.filter { it.isPhysicalEducation && !it.isCancelled && !it.holidayPaused }
            if (peSessions.isEmpty()) continue

            // 1. 体育课前一天晚间预报
            val prevEvening = LocalDateTime.of(
                date.minusDays(1),
                LocalTime.of(setting.prevDayNotifyHour.coerceIn(0, 23), setting.prevDayNotifyMinute.coerceIn(0, 59))
            )
            if (prevEvening.isAfter(now)) {
                enqueueCheck(
                    wm = wm,
                    uniqueName = "pe_weather_prev_$date",
                    delayMillis = Duration.between(now, prevEvening).toMillis(),
                    targetDate = date,
                    stageLabel = "明日体育课天气预报"
                )
            }

            // 2. 体育课当天最早一节体育课前再次核查（同一天多节体育课自动合并为一次提醒）
            val earliestStart = peSessions.minOf { it.startTime }
            val sameDayCheck = LocalDateTime.of(date, earliestStart)
                .minusMinutes(setting.sameDayAdvanceMinutes.toLong().coerceAtLeast(10))
            if (sameDayCheck.isAfter(now)) {
                enqueueCheck(
                    wm = wm,
                    uniqueName = "pe_weather_same_$date",
                    delayMillis = Duration.between(now, sameDayCheck).toMillis(),
                    targetDate = date,
                    stageLabel = "今日体育课前天气复查"
                )
            }
        }
    }

    override suspend fun runImmediateWeatherCheck(): PeWeatherAdvice? {
        return checkAndNotifyForDate(clock.today(), "今日体育课天气检查", sendNotification = false)
            ?: checkAndNotifyForDate(clock.today().plusDays(1), "明日体育课天气预报", sendNotification = false)
    }

    suspend fun checkAndNotifyForDate(
        targetDate: LocalDate?,
        stageLabel: String,
        sendNotification: Boolean = true
    ): PeWeatherAdvice? {
        val date = targetDate ?: clock.today()
        val setting = weatherRepository.getWeatherSetting()
        if (!setting.enablePeWeatherReminder) return null
        val sem = timetableRepository.getCurrentSemester() ?: return null
        val courses = timetableRepository.getCoursesWithDetails(sem.id)
        val periods = timetableRepository.getClassPeriods(sem.id)
        val holidays = holidayRepository.getHolidaysByYear(date.year)
        val overrides = holidayRepository.getSchoolOverrides(sem.id)

        val (_, sessions) = ScheduleResolutionEngine.resolveDaySessions(sem, date, courses, periods, holidays, overrides)
        val peSessions = sessions.filter { it.isPhysicalEducation && !it.isCancelled && !it.holidayPaused }
        if (peSessions.isEmpty()) return null

        val snapshot = weatherRepository.fetchLiveWeather(setting).getOrNull()
            ?: weatherRepository.getCachedWeather(setting)
            ?: return null

        val forecastDay = snapshot.daily.firstOrNull { it.date == date.toString() }
        val advice = PeWeatherRuleEngine.evaluatePeSessionsForDate(date, peSessions, forecastDay, setting)
        if (advice != null && advice.needsAlert && sendNotification) {
            postWeatherNotification(date, stageLabel, advice)
        }
        return advice
    }

    private fun enqueueCheck(
        wm: WorkManager,
        uniqueName: String,
        delayMillis: Long,
        targetDate: LocalDate,
        stageLabel: String
    ) {
        val req = OneTimeWorkRequestBuilder<PeWeatherCheckWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    PeWeatherCheckWorker.KEY_TARGET_DATE to targetDate.toString(),
                    PeWeatherCheckWorker.KEY_STAGE_LABEL to stageLabel
                )
            )
            .addTag(TAG_PE_WEATHER_WORK)
            .build()
        wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, req)
    }

    private fun postWeatherNotification(date: LocalDate, stageLabel: String, advice: PeWeatherAdvice) {
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!canNotify) return
        ensureChannel()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_PE_WEATHER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$stageLabel · ${advice.summary}")
            .setContentText(advice.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(advice.detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(("pe_weather_$date").hashCode(), notification)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WeatherBindingsModule {
    @Binds
    @Singleton
    abstract fun bindWeatherSchedulerPort(impl: PeWeatherScheduler): WeatherSchedulerPort
}
