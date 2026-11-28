package com.campus.timetable.feature.sync

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.campus.timetable.domain.model.BackupBundle
import com.campus.timetable.domain.model.SyncRecord
import com.campus.timetable.domain.repository.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 已登录用户的后台定时自动云备份任务（WorkManager）。游客模式下静默跳过，绝不强制登录。
 */
@HiltWorker
class CloudAutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val summary = syncRepository.getAuthSessionSummary()
        if (!summary.isLoggedIn) return Result.success()
        val res = syncRepository.syncWithCloud(mergeGuestData = false)
        return if (res.isSuccess) {
            Result.success()
        } else if (runAttemptCount < 3) {
            Result.retry()
        } else {
            Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "campus_cloud_auto_backup"

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<CloudAutoBackupWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}

data class SyncUiState(
    val session: AuthSessionSummary = AuthSessionSummary(false, null, "游客同学", "", null, 0),
    val phoneInput: String = "",
    val smsCodeInput: String = "",
    val serverUrlInput: String = "",
    val smsCooldownSeconds: Int = 0,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val pendingImportJson: String? = null,
    val pendingImportPreview: BackupBundle? = null,
    val records: List<SyncRecord> = emptyList()
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncRepository: SyncRepository,
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: AlarmSchedulerPort,
    private val weatherScheduler: WeatherSchedulerPort,
    private val widgetRefresher: WidgetRefresherPort
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        refreshSession()
        viewModelScope.launch {
            syncRepository.observeSyncRecords().collect { recs ->
                _uiState.update { it.copy(records = recs) }
            }
        }
    }

    fun refreshSession() {
        viewModelScope.launch {
            val summary = syncRepository.getAuthSessionSummary()
            _uiState.update {
                it.copy(
                    session = summary,
                    serverUrlInput = it.serverUrlInput.ifBlank { summary.serverBaseUrl }
                )
            }
        }
    }

    fun updatePhone(v: String) { _uiState.update { it.copy(phoneInput = v.filter(Char::isDigit).take(11), errorMessage = null) } }
    fun updateCode(v: String) { _uiState.update { it.copy(smsCodeInput = v.filter(Char::isDigit).take(6), errorMessage = null) } }
    fun updateServerUrl(v: String) { _uiState.update { it.copy(serverUrlInput = v, errorMessage = null) } }

    fun saveServerUrl() {
        viewModelScope.launch {
            settingsRepository.setCustomServerUrl(_uiState.value.serverUrlInput)
            refreshSession()
            _uiState.update { it.copy(statusMessage = "已保存云同步服务器地址") }
        }
    }

    fun sendSmsCode() {
        val state = _uiState.value
        if (state.smsCooldownSeconds > 0 || state.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            settingsRepository.setCustomServerUrl(state.serverUrlInput)
            syncRepository.sendSmsCode(state.phoneInput, state.serverUrlInput)
                .onSuccess { msg ->
                    _uiState.update { it.copy(isBusy = false, statusMessage = msg, smsCooldownSeconds = 60) }
                    launchCooldown()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isBusy = false, errorMessage = e.localizedMessage ?: "发送验证码失败") }
                }
        }
    }

    fun loginWithSmsCode() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            settingsRepository.setCustomServerUrl(state.serverUrlInput)
            syncRepository.loginWithPhoneCode(state.phoneInput, state.smsCodeInput, state.serverUrlInput)
                .onSuccess { user ->
                    CloudAutoBackupWorker.schedulePeriodic(context)
                    alarmScheduler.rescheduleAllUpcomingAlarms("login_merged")
                    weatherScheduler.schedulePeWeatherChecks()
                    widgetRefresher.refreshAllWidgets()
                    refreshSession()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            smsCodeInput = "",
                            statusMessage = "登录成功（${user.phone}），已安全合并本地游客课表与云端数据并开启自动备份"
                        )
                    }
                }
                .onFailure { e ->
                    refreshSession()
                    _uiState.update { it.copy(isBusy = false, errorMessage = e.localizedMessage ?: "登录失败") }
                }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            syncRepository.syncWithCloud(mergeGuestData = false, serverBaseUrl = _uiState.value.serverUrlInput)
                .onSuccess { rec ->
                    alarmScheduler.rescheduleAllUpcomingAlarms("cloud_synced")
                    weatherScheduler.schedulePeWeatherChecks()
                    widgetRefresher.refreshAllWidgets()
                    refreshSession()
                    val conflictNote = rec.conflictSummary?.let { "\n冲突解决记录：$it" }.orEmpty()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = "云端同步完成（版本 v${rec.remoteVersion}，共同步 ${rec.syncedEntitiesCount} 项）$conflictNote"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isBusy = false, errorMessage = e.localizedMessage ?: "云同步失败") }
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            CloudAutoBackupWorker.cancel(context)
            syncRepository.logoutToGuest()
            refreshSession()
            _uiState.update { it.copy(statusMessage = "已退出账号并切换回本地游客模式（本地课表保留）") }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            syncRepository.deleteCloudAccountAndData(_uiState.value.serverUrlInput)
                .onSuccess {
                    CloudAutoBackupWorker.cancel(context)
                    refreshSession()
                    _uiState.update { it.copy(isBusy = false, statusMessage = "账号及全部云端个人数据已彻底删除") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isBusy = false, errorMessage = e.localizedMessage ?: "注销失败") }
                }
        }
    }

    fun exportBackupToUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            runCatching {
                val jsonText = syncRepository.exportLocalBackupJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(jsonText.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入目标备份文件")
                }
            }.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusMessage = "课表备份 JSON 文件导出成功（不含任何教务密码）") }
            }.onFailure { e ->
                _uiState.update { it.copy(isBusy = false, errorMessage = "导出失败：${e.localizedMessage}") }
            }
        }
    }

    fun readBackupPreviewFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取所选备份文件")
                }
                val bundle = syncRepository.parseBackupPreview(text).getOrThrow()
                text to bundle
            }.onSuccess { (raw, bundle) ->
                _uiState.update {
                    it.copy(isBusy = false, pendingImportJson = raw, pendingImportPreview = bundle)
                }
            }.onFailure { e ->
                _uiState.update { it.copy(isBusy = false, errorMessage = "备份文件校验失败：${e.localizedMessage}") }
            }
        }
    }

    fun confirmImportBackup(mergeWithExisting: Boolean) {
        val raw = _uiState.value.pendingImportJson ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, pendingImportJson = null, pendingImportPreview = null) }
            syncRepository.importLocalBackupJson(raw, mergeWithExisting)
                .onSuccess { res ->
                    alarmScheduler.rescheduleAllUpcomingAlarms("backup_imported")
                    weatherScheduler.schedulePeWeatherChecks()
                    widgetRefresher.refreshAllWidgets()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = "备份恢复成功：新增 ${res.addedCount} 项，更新 ${res.updatedCount} 项，冲突处理 ${res.conflicts.size} 项"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isBusy = false, errorMessage = "恢复失败：${e.localizedMessage}") }
                }
        }
    }

    fun cancelBackupPreview() {
        _uiState.update { it.copy(pendingImportJson = null, pendingImportPreview = null) }
    }

    private fun launchCooldown() {
        viewModelScope.launch {
            while (_uiState.value.smsCooldownSeconds > 0) {
                delay(1000L)
                _uiState.update { it.copy(smsCooldownSeconds = (it.smsCooldownSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }
}
