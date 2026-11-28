# v2.2.4 修改记录（代码评审全量缺陷修复与安全加固）

- **应用名称**：桃课
- **版本**：Android `versionCode 8` / `versionName 2.2.4`（Debug 包 `2.2.4-debug`，包名 `com.campus.timetable.debug`）；后端 `2.2.4`
- **完成日期**：2026-11-27
- **起因**：对 `v2.2.3` 全仓（13 个 Android 模块 + Spring Boot 后端 + 29 张实机截图）执行深度工程评审，逐项修复发现的 P0 / P1 / P2 全部缺陷，并顺带修复了回归测试暴露出的 Jsoup `<br>` 拆课隐蔽漏洞。
- **许可**：CC BY-NC 4.0，**禁止任何形式的商业使用**（见根目录 `LICENSE`）

---

## 一、P0 严重缺陷修复

| 编号 | 模块 | 原缺陷 | 修复方案 |
|---|---|---|---|
| P0-1 | `school-adapters` / `feature-import` | `WebTimetableParser.expandGrid()` 的 `fillPending()` 在存在 `rowspan` 时会跳过前方空闲列，导致第二行课程集体右移错位；且只要行首含节次文本，HTML 与 Excel 网格都会无视 `rowspan`/`mergeSpan` 截断跨行节次；另发现 Jsoup `cell.html()` 自动插入的源码换行会把 `<br>` 变成 `\n\n`，导致单门 4 行课程被拆成「课程名 / 教师 / 教室」三条假课 | 重写 `expandGrid()` 改为 `consumePendingAt()` 仅推进连续被占用列并过滤嵌套表格；预计算各行节次范围（兼容「上午/第1节」双列表头与纯数字节次），按跨行覆盖的最后一行计算 `cellEnd`；解析 HTML 单元格前先折叠源码换行并识别 `<br><br>`/`<hr>` 分隔；`ExcelTimetableParser` 同步修复跨行合并计算并禁用 XML 外部实体（XXE 防护） |
| P0-2 | `feature-import` / `school-adapters` | 受控教务浏览器仅读取 `document.documentElement.outerHTML`，导致把课表嵌在 `<iframe>` / `<frameset>` 内的正方、强智、旧 URP、树维 EAMS 永远抓空 | 受控浏览器改为执行递归脚本收集主文档及所有可访问同源 `iframe`/`frame` 的完整 HTML，经 `WebTimetableParser.parseRenderedDocuments()` 自动选取候选课表最完整的一帧 |
| P0-3 | `domain` / `data` / `feature-sync` | `BackupMergeEngine` 漏合并 `bellTemplates` 导致同步/合并恢复丢自定义作息模板；`applyBundleToLocalDb` 无事务保护且未清旧 `alarm_settings`/`bell_schedule_templates`；客户端未实现 `/api/v1/auth/refresh` 导致 JWT 2 小时过期后云同步永久失效；登录合并同步失败被静默吞掉；`CloudAutoBackupWorker` 失败不重试 | 补齐 `bellTemplates` 合并；`applyBundleToLocalDb` 包入 `db.withTransaction {}` 原子写入；`BackendSyncClient` 抛出带状态码的 `HttpStatusException` 并新增 `refreshToken()`，`SyncRepositoryImpl` 遇到 401/403 自动续期并重试一次；登录游客合并失败如实抛出并刷新界面登录态；`CloudAutoBackupWorker` 失败时返回 `Result.retry()`（最多 3 次） |

---

## 二、P1 高优先级缺陷修复（闹钟 / 浏览器 / 后端安全）

| 编号 | 模块 | 修复内容 |
|---|---|---|
| P1-1 | `feature-alarm` / `app` | 清单声明 `USE_FULL_SCREEN_INTENT`；真闹钟模式通知配置 `setFullScreenIntent(contentPi, true)` 与 `setOngoing(true)`；`AlarmSettingsScreen` 增加 Android 14+ `canUseFullScreenIntent()` 诊断与一键跳转授权；修正 `setAlarmClock` 的 `showIntent` 指向 `MainActivity`（不再误弹空白响铃页）；新增 `DailyAlarmRenewalWorker` 每 24 小时滚动续约未来 7 天闹钟，防止连续 7 天未打开 App 后断档；移除 `CourseAlarmReceiver` 上无用的 `@AndroidEntryPoint` |
| P1-2 | `feature-import` | 修正受控浏览器 7 项体验缺陷：① 修正「打不开」帮助文案（不再误导「用系统浏览器登录后回来」，明确说明登录态不互通并引导导出 Excel/PDF）；② 启用 `useWideViewPort`、`loadWithOverviewMode`、`setSupportZoom`、`builtInZoomControls` 与 `MIXED_CONTENT_COMPATIBILITY_MODE`；③ `shouldOverrideUrlLoading` 仅对 `isForMainFrame == true` 更新地址栏；④ 「清会话」同时清理 `CookieManager` + `WebStorage` + 缓存 + 刷新并弹 Toast；「复制链接」弹 Toast 并关闭弹窗；⑤ `AndroidView` 配置 `onRelease` 销毁 WebView 防泄漏；⑥ 增加页面加载进度条及主框架网络/SSL 明确错误提示（绝不绕过 SSL）；⑦ 新增 `SchoolEntryClassifier` 精细区分「教务系统入口 / 校内统一门户 / 教务处网站 / 搜索」，避免 `"cas"` 误命中 `jw.ucass.edu.cn` 与清华大学门户提示自相矛盾，并将第二 Tab 文案改为「文件导入」彻底消除 720p 截断 |
| P1-3 | `backend` / `app` | ① `JwtService` 在 `prod` profile 下启动即拒绝默认/占位弱 `JWT_SECRET`；② 新增 Flyway `V4__sms_attempts_and_security.sql` 增加 `failed_attempts` 字段，`AuthService.verifySmsCode` 使用 `@Transactional(noRollbackFor = ...)` 持久记录输错次数，单码连续输错 5 次自动作废防暴力破解，并增加基于 IP 滑动窗口的发码/校验限流（HTTP 429）；③ `SchoolCatalogService` 限制 `pageSummary` 长度 ≤2000 字符、校验 `http(s)://` 协议并增加单 IP 每小时 10 次防刷限流；④ `GET /api/v1/schools` 与 `/api/v1/schools/catalog` 统一使用 2,741 所权威目录；⑤ 后端 `HolidayAndWeatherService` 补齐 jsDelivr CDN 镜像自动回退；⑥ Android 清单改用显式 `network_security_config.xml`（仅信任系统根证书，外部天气与节假日域名强制 HTTPS），并修正 `proguard-rules.pro` 保留规则 |

---

## 三、P2 边界体验与代码清理

- **作息编辑器回显**（`BellScheduleScreen` / `SettingsRepository`）：按「学期 + 星期」在 DataStore 持久化已应用的 `BellScheduleRule` JSON，无历史记录时由 `BellScheduleRuleEngine.inferRuleFromPeriods()` 自动反推，解决重进页面表单参数与已保存节次不一致的问题。
- **节假日提前提醒持久化**（`HolidayScreen` / `SettingsRepository`）：提前提醒天数写入 DataStore 持久保存，退出重进不再重置回「前1天」。
- **学校按省查看不截断**（`RoomDaos` / `ImportScreen`）：选定省份或输入关键字时查询上限由 120 放宽至 500（覆盖全国任一省份全部高校），未筛选浏览前 120 条时在列表顶部给出清晰提示。
- **多时段课程临时调课**（`CourseDetailScreen`）：一门课包含多个上课时段时，临时调课/停课/换教室/换教师弹窗支持自由选择具体目标时段。
- **跨年节假日与定位天气**：`WidgetSnapshotLoader`（今日与本周）和 `PeWeatherScheduler` 补齐跨年查次年节假日；`OneShotLocationHelper` 在 Android 11+ 无定位缓存时主动单次调用 `LocationManager.getCurrentLocation()`（8 秒超时）；`WeatherViewModel` 切换城市或重新定位时立即清空旧城市天气缓存避免失败时混显；`OpenMeteoWeatherProvider` 显式使用 `Asia/Shanghai` 时区索引当前小时 AQI。
- **清理警告与默认版本**：`TimetableScreen` 替换已弃用的 `Icons.Outlined.EventNote`；`ImportScreen` 移除弃用 `saveFormData`；`SettingsUiState` 默认版本对齐为 `2.2.4`。

---

## 四、构建与测试结果

- **自动化测试**：**14 个测试套件 / 52 项测试用例全部通过（0 失败，0 跳过）**——Android 13 套件 / 46 项 + 后端 1 套件 / 6 项（较 v2.2.3 新增 9 项回归用例，覆盖跨行展开、同源 `iframe` 提取、Excel 合并单元格、入口分类器、作息规则反推、备份作息模板合并、DataStore 持久化、JWT 401/续期、真闹钟 `fullScreenIntent`、后端 `prod` 拒绝弱密钥与短信 5 次错码锁定）。
- **Android Debug APK**：`android/app/build/outputs/apk/debug/app-debug.apk`，**76.00 MiB / 79,694,935 字节**；`aapt2` 校验 `versionCode=8`、`versionName=2.2.4-debug`、`application-label='桃课'`，已注册 `USE_FULL_SCREEN_INTENT`。
- **后端 JAR**：`backend/build/libs/smart-campus-timetable-backend-2.2.4.jar`，**58.09 MiB / 60,916,183 字节**。

---

## 五、诚实说明

- 本轮修改均已完成自动化单元测试、集成测试及 `aapt2` 产物校验；受限于当前未连接实体 Android 真机且未启动模拟器，本轮未补充新的模拟器截图（仓库现有 29 张实机截图对应 v2.2.1–v2.2.3）。
- 真实高校教务账号登录抓取、物理真机 Android 14/15 锁屏全屏响铃与厂商后台保活策略、生产环境阿里云 SMS 发信及正式 Release 签名打包仍需在目标真机与部署环境中完成最终验收。
