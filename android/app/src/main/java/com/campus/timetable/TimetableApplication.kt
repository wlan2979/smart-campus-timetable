package com.campus.timetable

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.campus.timetable.feature.alarm.DailyAlarmRenewalWorker
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TimetableApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // 初始化 PDFBox Android 资源，确保本地 PDF 文本提取与渲染可用
        PDFBoxResourceLoader.init(applicationContext)
        // 确保每日闹钟续约任务已登记，避免连续多日未打开 App 时闹钟 7 天窗口断档
        DailyAlarmRenewalWorker.ensureScheduled(applicationContext)
    }
}
