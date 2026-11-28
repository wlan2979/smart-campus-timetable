package com.campus.timetable.feature.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.domain.engine.ScheduleResolutionEngine
import com.campus.timetable.domain.model.AlarmMode
import com.campus.timetable.domain.model.AlarmSetting
import com.campus.timetable.domain.model.ResolvedCourseSession
import com.campus.timetable.domain.repository.AlarmRepository
import com.campus.timetable.domain.repository.AlarmSchedulerPort
import com.campus.timetable.domain.repository.HolidayRepository
import com.campus.timetable.domain.repository.TimetableRepository
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ScheduledAlarmPlan(
    val requestCode: Int,
    val triggerAtMillis: Long,
    val courseId: String,
    val courseName: String,
    val location: String,
    val startTimeText: String,
    val advanceMinutes: Int,
    val isClassEnd: Boolean,
    val isHolidayReminder: Boolean = false,
    val mode: AlarmMode,
    val ringtoneUri: String?,
    val vibrate: Boolean
)

/**
 * 桃课核心课程闹钟与连续假期节前单次提醒调度器。
 * 第二轮已彻底移除不可靠的 TTS 语音播报，专注保障普通通知、真闹钟响铃、震动、上课/下课提醒与节假日规则稳定运行。
 */
@Singleton
class CourseAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timetableRepository: TimetableRepository,
    private val holidayRepository: HolidayRepository,
    private val alarmRepository: AlarmRepository,
    private val clock: AppClock
) : AlarmSchedulerPort {

    companion object {
        const val CHANNEL_NOTIFICATION = "taoke_course_notify"
        const val CHANNEL_ALARM_CLOCK = "taoke_course_alarm_clock"
        const val ACTION_FIRE_ALARM = "com.campus.timetable.ACTION_FIRE_COURSE_ALARM"
        const val ACTION_DISMISS_ALARM = "com.campus.timetable.ACTION_DISMISS_COURSE_ALARM"

        const val EXTRA_REQUEST_CODE = "extra_request_code"
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_LOCATION = "extra_location"
        const val EXTRA_TIME_TEXT = "extra_time_text"
        const val EXTRA_IS_CLASS_END = "extra_is_class_end"
        const val EXTRA_IS_HOLIDAY = "extra_is_holiday"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_RINGTONE_URI = "extra_ringtone_uri"
        const val EXTRA_VIBRATE = "extra_vibrate"

        private const val PREF_NAME = "scheduled_alarm_registry"
        private const val KEY_ACTIVE_CODES = "active_request_codes"
        private const val KEY_LAST_RESCHEDULE_REASON = "last_reason"
        private const val KEY_LAST_EXACT_USED = "last_exact_used"
    }

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val registryPrefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun ensureNotificationChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NOTIFICATION,
                "桃课 · 课程与假期普通提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "提前提醒即将开始的课程、教室位置与法定节假日安排"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM_CLOCK,
                "桃课 · 课程真闹钟提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "课程强提醒闹钟通道，支持自定义铃声与震动"
                enableVibration(true)
            }
        )
    }

    override fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    override suspend fun cancelAllAlarms() {
        val oldCodes = registryPrefs.getStringSet(KEY_ACTIVE_CODES, emptySet()).orEmpty()
        for (codeStr in oldCodes) {
            val code = codeStr.toIntOrNull() ?: continue
            val intent = Intent(context, CourseAlarmReceiver::class.java).apply { action = ACTION_FIRE_ALARM }
            val pi = PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                alarmManager.cancel(pi)
                pi.cancel()
            }
        }
        registryPrefs.edit().putStringSet(KEY_ACTIVE_CODES, emptySet()).apply()
    }

    override suspend fun rescheduleAllUpcomingAlarms(reason: String) {
        ensureNotificationChannels()
        cancelAllAlarms()

        val plans = buildUpcomingPlans(daysAhead = 7)
        val exactAllowed = canScheduleExactAlarms()
        val scheduledCodes = mutableSetOf<String>()

        for (plan in plans) {
            val intent = Intent(context, CourseAlarmReceiver::class.java).apply {
                action = ACTION_FIRE_ALARM
                putExtra(EXTRA_REQUEST_CODE, plan.requestCode)
                putExtra(EXTRA_COURSE_NAME, plan.courseName)
                putExtra(EXTRA_LOCATION, plan.location)
                putExtra(EXTRA_TIME_TEXT, plan.startTimeText)
                putExtra(EXTRA_IS_CLASS_END, plan.isClassEnd)
                putExtra(EXTRA_IS_HOLIDAY, plan.isHolidayReminder)
                putExtra(EXTRA_MODE, plan.mode.name)
                putExtra(EXTRA_RINGTONE_URI, plan.ringtoneUri)
                putExtra(EXTRA_VIBRATE, plan.vibrate)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                plan.requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                if (exactAllowed) {
                    if (plan.mode == AlarmMode.ALARM_CLOCK && !plan.isHolidayReminder && !plan.isClassEnd) {
                        // 点击系统锁屏/状态栏闹钟图标时打开主课表页，绝不误打开空白响铃界面
                        val openAppIntent = Intent(Intent.ACTION_VIEW, Uri.parse("taoke://open/timetable")).apply {
                            component = ComponentName(context.packageName, "com.campus.timetable.MainActivity")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("target_route", "timetable")
                        }
                        val showIntent = PendingIntent.getActivity(
                            context,
                            plan.requestCode,
                            openAppIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        alarmManager.setAlarmClock(
                            AlarmManager.AlarmClockInfo(plan.triggerAtMillis, showIntent),
                            pi
                        )
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pi)
                    }
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pi)
                }
                scheduledCodes += plan.requestCode.toString()
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pi)
                scheduledCodes += plan.requestCode.toString()
            }
        }

        registryPrefs.edit()
            .putStringSet(KEY_ACTIVE_CODES, scheduledCodes)
            .putString(KEY_LAST_RESCHEDULE_REASON, reason)
            .putBoolean(KEY_LAST_EXACT_USED, exactAllowed)
            .apply()

        // 注册每 24 小时续约一次未来 7 天闹钟的后台任务，避免用户连续 7 天未打开 App 后闹钟断档
        DailyAlarmRenewalWorker.ensureScheduled(context)
    }

    suspend fun buildUpcomingPlans(daysAhead: Int = 7): List<ScheduledAlarmPlan> {
        val semester = timetableRepository.getCurrentSemester() ?: return emptyList()
        val global = alarmRepository.getGlobalSetting()
        if (!global.enabled) return emptyList()

        val allCourseSettings = alarmRepository.getAllAlarmSettings()
            .filter { it.courseId != null }
            .associateBy { it.courseId!! }

        val courses = timetableRepository.getCoursesWithDetails(semester.id)
        val periods = timetableRepository.getClassPeriods(semester.id)
        val today = clock.today()
        val nowMillis = clock.currentTimeMillis()
        val zone = clock.zoneId()
        val holidays = holidayRepository.getHolidaysByYear(today.year) +
            if (today.plusDays(daysAhead.toLong()).year != today.year) {
                holidayRepository.getHolidaysByYear(today.year + 1)
            } else emptyList()
        val overrides = holidayRepository.getSchoolOverrides(semester.id)

        val plans = mutableListOf<ScheduledAlarmPlan>()

        // 1. 未来 7 天课程上课/下课提醒
        for (offset in 0L until daysAhead.toLong()) {
            val date = today.plusDays(offset)
            val dateIso = date.toString()
            if (global.muteDate == dateIso) continue

            val (_, sessions) = ScheduleResolutionEngine.resolveDaySessions(
                semester = semester,
                date = date,
                courses = courses,
                periods = periods,
                holidays = holidays,
                overrides = overrides,
                includeCancelled = false
            )

            for (session in sessions) {
                if (session.isCancelled || session.holidayPaused) continue
                val effective = mergeCourseSetting(global, allCourseSettings[session.course.id])
                if (!effective.enabled) continue

                val startDt = LocalDateTime.of(date, session.startTime)
                    .minusMinutes(effective.advanceMinutes.toLong())
                val startTrigger = startDt.atZone(zone).toInstant().toEpochMilli()
                if (startTrigger > nowMillis) {
                    plans += ScheduledAlarmPlan(
                        requestCode = stableRequestCode(session, isClassEnd = false),
                        triggerAtMillis = startTrigger,
                        courseId = session.course.id,
                        courseName = session.course.name,
                        location = session.effectiveLocation.ifBlank { "指定教室" },
                        startTimeText = "${session.startTime}-${session.endTime}",
                        advanceMinutes = effective.advanceMinutes,
                        isClassEnd = false,
                        mode = effective.mode,
                        ringtoneUri = effective.ringtoneUri,
                        vibrate = effective.vibrate
                    )
                }

                if (effective.enableClassEndReminder) {
                    val endTrigger = LocalDateTime.of(date, session.endTime)
                        .atZone(zone).toInstant().toEpochMilli()
                    if (endTrigger > nowMillis) {
                        plans += ScheduledAlarmPlan(
                            requestCode = stableRequestCode(session, isClassEnd = true),
                            triggerAtMillis = endTrigger,
                            courseId = session.course.id,
                            courseName = session.course.name,
                            location = session.effectiveLocation.ifBlank { "教室" },
                            startTimeText = session.endTime.toString().take(5),
                            advanceMinutes = 0,
                            isClassEnd = true,
                            mode = AlarmMode.NOTIFICATION,
                            ringtoneUri = effective.ringtoneUri,
                            vibrate = effective.vibrate
                        )
                    }
                }
            }
        }

        // 2. 连续假期节前提醒：每个连续假期区间只创建 1 条提醒（绝不每天重复提醒）
        val windowEnd = today.plusDays(daysAhead.toLong())
        for (holiday in holidays.filter { it.isOffDay && !it.reminderDate.isNullOrBlank() }.distinctBy { it.id }) {
            val remDate = runCatching { LocalDate.parse(holiday.reminderDate) }.getOrNull() ?: continue
            if (remDate.isBefore(today) || remDate.isAfter(windowEnd)) continue
            val triggerMillis = LocalDateTime.of(remDate, LocalTime.of(20, 0))
                .atZone(zone).toInstant().toEpochMilli()
            if (triggerMillis <= nowMillis) continue

            plans += ScheduledAlarmPlan(
                requestCode = ("holiday_reminder:${holiday.id}".hashCode() and 0x7FFFFFFF),
                triggerAtMillis = triggerMillis,
                courseId = holiday.id,
                courseName = holiday.name,
                location = "${holiday.formatDateRangeZh()} · 共${holiday.totalDays}天（期间默认暂停普通课程闹钟）",
                startTimeText = holiday.formatDateRangeZh(),
                advanceMinutes = 0,
                isClassEnd = false,
                isHolidayReminder = true,
                mode = AlarmMode.NOTIFICATION,
                ringtoneUri = null,
                vibrate = true
            )
        }

        return plans.sortedBy { it.triggerAtMillis }
    }

    fun getActiveScheduledCount(): Int =
        registryPrefs.getStringSet(KEY_ACTIVE_CODES, emptySet()).orEmpty().size

    private fun mergeCourseSetting(global: AlarmSetting, override: AlarmSetting?): AlarmSetting {
        if (override == null) return global
        return override.copy(ringtoneUri = override.ringtoneUri ?: global.ringtoneUri)
    }

    private fun stableRequestCode(session: ResolvedCourseSession, isClassEnd: Boolean): Int {
        val raw = "${session.course.id}:${session.scheduleId}:${session.date}:${session.startPeriod}:$isClassEnd"
        return (raw.hashCode() and 0x7FFFFFFF)
    }
}

/**
 * 每日闹钟滚动续约任务：每 24 小时重排一次未来 7 天课程与连续假期提醒，
 * 确保即使连续多日未打开 App，上课闹钟也不会在第 8 天断档。
 */
@HiltWorker
class DailyAlarmRenewalWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val alarmScheduler: AlarmSchedulerPort
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            alarmScheduler.rescheduleAllUpcomingAlarms("daily_periodic_renewal")
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "taoke_daily_alarm_renewal"

        fun ensureScheduled(context: Context) {
            runCatching {
                val req = PeriodicWorkRequestBuilder<DailyAlarmRenewalWorker>(24, TimeUnit.HOURS).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    req
                )
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmBindingsModule {
    @Binds
    @Singleton
    abstract fun bindAlarmSchedulerPort(impl: CourseAlarmScheduler): AlarmSchedulerPort
}
