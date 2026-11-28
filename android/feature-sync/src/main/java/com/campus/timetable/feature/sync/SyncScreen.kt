package com.campus.timetable.feature.sync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteAccountConfirm by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportBackupToUri(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.readBackupPreviewFromUri(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账号、云同步与本地备份") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("返回")
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
            if (state.isBusy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            state.errorMessage?.let { err ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            state.statusMessage?.let { msg ->
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                        Text(msg, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            // 当前身份与云同步操作卡片
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.session.isLoggedIn) {
                            Text("已登录正式账号：${state.session.phone}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "云端版本：v${state.session.syncVersion}" +
                                    (state.session.lastSyncedAt?.let { " · 上次同步：${fmt.format(Date(it))}" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.syncNow() }, enabled = !state.isBusy) { Text("立即云端同步") }
                                OutlinedButton(onClick = { viewModel.logout() }) { Text("退出登录") }
                            }
                            TextButton(
                                onClick = { showDeleteAccountConfirm = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text("注销账号并彻底删除云端个人数据") }
                        } else {
                            Text("当前为游客模式（本地优先，无需登录即可使用全部核心功能）", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "登录后可将课表、学期、闹钟规则、校历覆盖与天气设置加密同步至云端，支持换机恢复，且会自动安全合并当前游客数据（绝不上传教务密码）。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = state.phoneInput,
                                onValueChange = { viewModel.updatePhone(it) },
                                label = { Text("中国大陆手机号") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = state.smsCodeInput,
                                    onValueChange = { viewModel.updateCode(it) },
                                    label = { Text("6 位短信验证码") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                FilledTonalButton(
                                    enabled = state.smsCooldownSeconds == 0 && !state.isBusy,
                                    onClick = { viewModel.sendSmsCode() }
                                ) {
                                    Text(if (state.smsCooldownSeconds > 0) "${state.smsCooldownSeconds}s" else "获取验证码")
                                }
                            }
                            Button(
                                onClick = { viewModel.loginWithSmsCode() },
                                enabled = !state.isBusy && state.phoneInput.length == 11 && state.smsCodeInput.length == 6,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("手机号验证码登录并合并游客数据") }
                        }
                    }
                }
            }

            // 手动本地备份与恢复（游客模式与正式账号均可用）
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("手动本地课表备份文件（含版本校验与导入预览）", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "支持离线导出完整 JSON 备份包，换机或重装前可随时保存并在导入前预览核对。备份文件严格不包含任何教务系统账号密码。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date())
                                    exportLauncher.launch("campus_timetable_backup_$ts.json")
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("导出备份文件") }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                                modifier = Modifier.weight(1f)
                            ) { Text("导入备份恢复") }
                        }
                    }
                }
            }

            // 同步服务器地址设置（支持自建 Docker Compose HTTPS 服务端）
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("云同步后端服务地址", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = state.serverUrlInput,
                            onValueChange = { viewModel.updateServerUrl(it) },
                            label = { Text("API Base URL（生产请使用 https://）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedButton(onClick = { viewModel.saveServerUrl() }) { Text("保存服务地址") }
                    }
                }
            }

            // 同步与备份历史记录
            if (state.records.isNotEmpty()) {
                item { Text("最近同步与备份记录", style = MaterialTheme.typography.titleMedium) }
                items(state.records, key = { it.id }) { rec ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${rec.direction.label} · ${rec.status.label}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "版本 v${rec.localVersion} → v${rec.remoteVersion} · 涉及 ${rec.syncedEntitiesCount} 项 · ${fmt.format(Date(rec.syncedAt))}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            rec.conflictSummary?.let {
                                Text("冲突处理：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    // 备份文件导入前校验与预览弹窗
    state.pendingImportPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelBackupPreview() },
            title = { Text("备份文件校验与恢复预览") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("数据结构版本：v${preview.schemaVersion}（应用 v${preview.appVersion}）")
                    Text("导出时间：${fmt.format(Date(preview.exportedAt))}")
                    Text("包含学期：${preview.semesters.size} 个")
                    Text("包含课程：${preview.courses.size} 门（排课规则 ${preview.schedules.size} 条，调停课 ${preview.exceptions.size} 条）")
                    Text("包含学校校历覆盖：${preview.schoolOverrides.size} 条")
                    Spacer(Modifier.height(4.dp))
                    Text("推荐选择「安全合并」，自动保留本地与备份中较新的记录；或选择「覆盖恢复」完全替换本地数据。", style = MaterialTheme.typography.labelMedium)
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmImportBackup(mergeWithExisting = true) }) { Text("安全合并恢复") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { viewModel.confirmImportBackup(mergeWithExisting = false) }) { Text("覆盖恢复") }
                    TextButton(onClick = { viewModel.cancelBackupPreview() }) { Text("取消") }
                }
            }
        )
    }

    if (showDeleteAccountConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountConfirm = false },
            title = { Text("确认永久注销账号？") },
            text = { Text("注销将立即永久删除您在云端保存的所有课表备份、同步审计与账号记录，且不可撤销。本机课表会保留在游客模式。") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showDeleteAccountConfirm = false
                        viewModel.deleteAccount()
                    }
                ) { Text("确认注销并删除云端数据") }
            },
            dismissButton = { TextButton(onClick = { showDeleteAccountConfirm = false }) { Text("取消") } }
        )
    }
}
