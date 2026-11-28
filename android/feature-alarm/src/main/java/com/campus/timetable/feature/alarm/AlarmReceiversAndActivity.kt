package com.campus.timetable.feature.alarm

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlarmOn
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.campus.timetable.core.theme.CampusTimetableTheme
import com.campus.timetable.domain.model.AlarmMode
import com.campus.timetable.domain.repository.AlarmSchedulerPort
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 课程与节假日提醒广播接收器（已彻底清除 TTS 语音播报，专注普通通知、全屏响铃与震动可靠性）。
 */
class CourseAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestCode = intent.getIntExtra(CourseAlarmScheduler.EXTRA_REQUEST_CODE, 1001)
        if (intent.action == CourseAlarmScheduler.ACTION_DISMISS_ALARM) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(requestCode)
            return
        }

        val courseName = intent.getStringExtra(CourseAlarmScheduler.EXTRA_COURSE_NAME) ?: "即将上课"
        val location = intent.getStringExtra(CourseAlarmScheduler.EXTRA_LOCATION).orEmpty()
        val timeText = intent.getStringExtra(CourseAlarmScheduler.EXTRA_TIME_TEXT).orEmpty()
        val isClassEnd = intent.getBooleanExtra(CourseAlarmScheduler.EXTRA_IS_CLASS_END, false)
        val isHoliday = intent.getBooleanExtra(CourseAlarmScheduler.EXTRA_IS_HOLIDAY, false)
        val mode = runCatching {
            AlarmMode.valueOf(intent.getStringExtra(CourseAlarmScheduler.EXTRA_MODE) ?: AlarmMode.NOTIFICATION.name)
        }.getOrDefault(AlarmMode.NOTIFICATION)
        val ringtoneUri = intent.getStringExtra(CourseAlarmScheduler.EXTRA_RINGTONE_URI)
        val vibrate = intent.getBooleanExtra(CourseAlarmScheduler.EXTRA_VIBRATE, true)

        val title = when {
            isHoliday -> "假期提醒 · $courseName"
            isClassEnd -> "下课提醒 · $courseName"
            else -> "上课提醒 · $courseName"
        }
        val content = when {
            isHoliday -> location
            isClassEnd -> "本节课已到达下课时间（$timeText）"
            else -> "时间：$timeText · 教室：${location.ifBlank { "未填写" }}"
        }

        val dismissIntent = PendingIntent.getBroadcast(
            context,
            requestCode + 500_000,
            Intent(context, CourseAlarmReceiver::class.java).apply {
                action = CourseAlarmScheduler.ACTION_DISMISS_ALARM
                putExtra(CourseAlarmScheduler.EXTRA_REQUEST_CODE, requestCode)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ringIntent = Intent(context, AlarmRingingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CourseAlarmScheduler.EXTRA_REQUEST_CODE, requestCode)
            putExtra(CourseAlarmScheduler.EXTRA_COURSE_NAME, courseName)
            putExtra(CourseAlarmScheduler.EXTRA_LOCATION, location)
            putExtra(CourseAlarmScheduler.EXTRA_TIME_TEXT, timeText)
            putExtra(CourseAlarmScheduler.EXTRA_RINGTONE_URI, ringtoneUri)
            putExtra(CourseAlarmScheduler.EXTRA_VIBRATE, vibrate)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            requestCode,
            ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        if (canNotify) {
            val isFullAlarm = mode == AlarmMode.ALARM_CLOCK && !isClassEnd && !isHoliday
            val channelId = if (isFullAlarm) {
                CourseAlarmScheduler.CHANNEL_ALARM_CLOCK
            } else {
                CourseAlarmScheduler.CHANNEL_NOTIFICATION
            }
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(if (isFullAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .addAction(0, "关闭提醒", dismissIntent)

            if (isFullAlarm) {
                // Android 10–15 后台/锁屏弹出真正闹钟响铃页的标准合规路径
                builder.setFullScreenIntent(contentPi, true)
                builder.setOngoing(true)
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(requestCode, builder.build())
        }

        if (mode == AlarmMode.ALARM_CLOCK && !isClassEnd && !isHoliday) {
            runCatching { context.startActivity(ringIntent) }
        }
    }
}

/**
 * 真正闹钟模式响铃界面：播放用户选择的系统铃声与震动，提供一键停止关闭。
 */
class AlarmRingingActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val requestCode = intent.getIntExtra(CourseAlarmScheduler.EXTRA_REQUEST_CODE, 1001)
        val courseName = intent.getStringExtra(CourseAlarmScheduler.EXTRA_COURSE_NAME) ?: "课程提醒"
        val location = intent.getStringExtra(CourseAlarmScheduler.EXTRA_LOCATION).orEmpty()
        val timeText = intent.getStringExtra(CourseAlarmScheduler.EXTRA_TIME_TEXT).orEmpty()
        val ringtoneUriStr = intent.getStringExtra(CourseAlarmScheduler.EXTRA_RINGTONE_URI)
        val shouldVibrate = intent.getBooleanExtra(CourseAlarmScheduler.EXTRA_VIBRATE, true)

        startRingtoneAndVibration(ringtoneUriStr, shouldVibrate)

        setContent {
            CampusTimetableTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(28.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(24.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(
                                Icons.Outlined.AlarmOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(72.dp)
                            )
                            Text("桃课 · 上课闹钟提醒", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(courseName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            if (timeText.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("上课时间：$timeText", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            if (location.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("教室位置：$location", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }

                        Button(
                            onClick = {
                                stopAllAndDismiss(requestCode)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("停止并关闭闹钟", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }

    private fun startRingtoneAndVibration(uriStr: String?, vibrate: Boolean) {
        runCatching {
            val uri = uriStr?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
        }

        if (vibrate) {
            runCatching {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600), 0))
            }
        }
    }

    private fun stopAllAndDismiss(requestCode: Int) {
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(requestCode)
    }

    override fun onDestroy() {
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        super.onDestroy()
    }
}

/**
 * 后台恢复接收器：处理手机重启、系统时间变更、时区切换、App 覆盖更新后的全部课程与假期闹钟重建。
 */
@AndroidEntryPoint
class AlarmRestoreReceiver : BroadcastReceiver() {

    @Inject
    lateinit var alarmScheduler: AlarmSchedulerPort

    companion object {
        val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )

        suspend fun restoreIfSupported(action: String?, scheduler: AlarmSchedulerPort): Boolean {
            if (action == null || action !in RESTORE_ACTIONS) return false
            scheduler.rescheduleAllUpcomingAlarms("system_restore:$action")
            return true
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action in RESTORE_ACTIONS) {
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    restoreIfSupported(action, alarmScheduler)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
