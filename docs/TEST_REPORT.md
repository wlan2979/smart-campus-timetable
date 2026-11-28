# 测试与验收报告（v2.2.9 第十三轮 · 通用一大节 95/100 分钟连堂与作息优化 + 高校拼音 A–Z 排序）

- **执行日期**：2026-11-28
- **构建工具链**：Eclipse Temurin JDK 17、Gradle 8.11.1、Android SDK API 35 / Build-Tools 35.0.0
- **总计结果**：**14 个测试套件 / 70 项自动化测试用例全部通过（0 失败，0 跳过）**——Android 64 项 + 后端 6 项；Android Debug APK（2.2.9 / versionCode 13）与 Spring Boot 后端 JAR（2.2.9）均构建通过，并通过 `aapt2` 清单校验（精确体积见第三节）。
- **实机验收**：v2.2.1–v2.2.3 与 v2.2.6 已在雷电模拟器 LDPlayer14（Android 14，720×1600 @320dpi）完成逐页操作验收及树维 EAMS 高校 EAMS 完整渲染核验（`docs/screenshots/`）；本轮 v2.2.9 对照用户真机全表奇数单节大课截图，实现面向全国所有高校通用的「一大节 = 两小节 + 95/100 分钟作息校准」连堂自动合并与全国 2,741 所高校按拼音 A–Z 排序及索引筛选，并通过 70 项全量回归验证（详见 `CHANGELOG_V2_2_9.md`）。

> **v2.2.9 核心升级**：新增通用引擎 `TimetablePeriodNormalizer`（自动识别奇数单节大课制并展开 `1-2`/`3-4`/`5-6`/`7-8`，自动合并同课连续节次如 `1-4` 四节连堂，覆盖网页/Excel/PDF 全导入渠道，不写死任何单一学校）；`ChineseCourseTextParser` 支持「第 N 大节」换算；`BellScheduleRuleEngine` 新增 `bigPeriodDurationMinutes()` 与 95 分钟紧凑作息通用模板并在导入预览/作息编辑器支持一键切换与自定义大课时长；`SchoolDao`、省份列表、选学校页面与后端 `/api/v1/schools` 全部升级为按拼音 A–Z 升序排序与首字母检索。

---

## 一、自动化测试套件实测明细

| 模块 | 测试类 | 用例数 | 失败 | 验证范围 |
|---|---|---:|---:|---|
| `:core` | `ThemePaletteTest` | 1 | 0 | 浅色/深色/跟随系统与 6 套主色色板映射 |
| `:domain` | `TimetableEnginesTest` | 9 | 0 | 周次计算、单双周/自定义周、调停课、多维冲突、按星期作息、体育课识别、节假日与校历覆盖、体育课天气合并预警、中文课表文本解析、游客/云端合并 |
| `:domain` | `BellAndHolidayEnginesTest` | 9 | 0 | 作息规则逐节时间推算、差异时长与自定义休息、无课日与非法规则校验、连续假期合并与幂等、规则 JSON 编解码与反推；**v2.2.9 新增通用 `TimetablePeriodNormalizer` 奇数单节展开与 4 节连堂合并、「第 N 大节」换算、95 分钟一大节计算 2 项测试** |
| `:data` | `CampusDatabaseTest` | 5 | 0 | Room v2 内存库游客初始化、课程/排课/临时变更持久化、`MockWebServer` 节假日 200/404、事务覆盖、导入更新开机日与作息；**v2.2.9 新增选学校严格按拼音 A–Z 排序与省份中文拼音排序测试** |
| `:data` | `SchoolAcademicUrlAssetTest` | 2 | 0 | 教务处直达地址资源 ≥800 所、全部为合法 HTTP(S) 地址；示例树维高校（如清华大学等试点样例）指向 `https://jwc.example.edu.cn` |
| `:data` | `CampusDatabaseMigrationTest` | 1 | 0 | `MigrationTestHelper` 按 v1 Schema 建库并写入数据，执行 `MIGRATION_1_2` 后对照 `2.json` 全量校验 |
| `:data` | `SyncAndThemePersistenceTest` | 4 | 0 | 覆盖 **Android API 26 (8.0) 与 API 33 (13)** 双版本（2 用例 × 2 SDK = 4）：深色模式/主色/节假日提醒天数/作息规则持久化、备份导出与事务恢复、`BackendSyncClient` 401 与刷新续期 |
| `:school-adapters` | `SchoolAdaptersTest` | 22 | 0 | 未验证学校拦截、正方/强智/树维 EAMS、`colspan`/`rowspan`/同源 `iframe`；**v2.2.8 新增完整复现第 13 周横置 `TD{k}_0` 课表（`1-2`/`3-4`/`5-6`/`7-8`/`1-4` 连堂、同格单 `<br>` 多课、3 条 `电工学`、官网 12 节作息与「13 教学周」提示、脚本 `index++` 与参数含分号）全链路测试** |
| `:feature-import` | `FileParsersTest` | 5 | 0 | Apache POI `.xls`、OOXML `.xlsx`（含跨行合并单元格）、PDFBox + OCR 回退、`SchoolEntryClassifier` 入口分类与防 `cas` 误判 |
| `:feature-alarm` | `AlarmSchedulerTest` | 2 | 0 | Robolectric `AlarmManager` 调度、改课/删课取消旧闹钟、法定节假日暂停与校历恢复、真闹钟广播 `fullScreenIntent`、`AlarmRestoreReceiver` 系统事件恢复 |
| `:feature-weather` | `PeWeatherSchedulerTest` | 1 | 0 | 周一晚检查周二体育课雷阵雨触发预警、天气 API 异常静默降级 |
| `:feature-weather` | `CityPresetsTest` | 2 | 0 | 离线城市 2154 座唯一性、中国范围坐标与县级城市检索 |
| `:feature-widgets` | `WidgetSnapshotLoaderTest` | 1 | 0 | 四种桌面小组件（今日概览、下一节课倒计时、本周课表、天气与体育课提醒）真实数据计算 |
| `backend` | `BackendIntegrationTest` | 6 | 0 | Flyway V1–V4 迁移、短信验证码登录/JWT/游客合并/乐观锁/防密码/账号注销、`GET /api/v1/schools` 统一 2741 校检索与 **v2.2.9 新增全拼 A–Z 排序校验**、远程目录 2741 条、`prod` 禁用 `debug_log`/弱密钥、防暴力锁定与申请校验 |

**合计：70 项，通过 70，失败 0，跳过 0。**

---

## 二、验收场景对照表

### 第一轮 20 项核心场景（回归）

| 编号 | 验收场景 | 验证方式 | 结果 | 备注说明 |
|---:|---|---|---|---|
| 1 | 新用户不登录能否正常使用 | `CampusDatabaseTest` + `TimetableViewModel` | ✅ 通过 | 游客模式自动初始化默认学期与 12 节作息，不强制登录 |
| 2 | 手动创建课程是否正常 | `CampusDatabaseTest` | ✅ 通过 | 真实写入 Room `courses` + `course_schedules` |
| 3 | 单双周是否正确 | `TimetableEnginesTest` | ✅ 通过 | 同节次单周与双周课按周准确切换且无误报冲突 |
| 4 | 自定义周次是否正确 | `TimetableEnginesTest` + `FileParsersTest` | ✅ 通过 | 完整支持自定义周 |
| 5 | 临时调课是否正确更新 | `TimetableEnginesTest` + `CampusDatabaseTest` | ✅ 通过 | 独立存于 `course_exceptions`，原周期排课保持不变 |
| 6 | 课程冲突是否正确识别 | `TimetableEnginesTest` | ✅ 通过 | 综合星期、周次、节次、调课与停课结果判定 |
| 7 | Excel 是否能够导入 | `FileParsersTest` | ✅ 通过 | 真实二进制 `.xls` 与 `.xlsx` 双格式 |
| 8 | PDF 是否能够解析 | `FileParsersTest` | ✅ 通过 | PDFBox 文本提取 + 扫描版 OCR 路径 |
| 9 | 无法识别的课程是否允许人工修改 | `ImportViewModel` + `FileParsersTest` | ✅ 通过 | 不完整条目 `needsManualReview` 并可在预览页校正 |
| 10 | 教务系统未适配时是否有明确提示 | `SchoolAdaptersTest` + `BackendIntegrationTest` | ✅ 通过 | 未验证学校一律 `verified=false`，展示「暂未适配」并引导文件/网页导入 |
| 11 | 闹钟是否可以按设置触发 | `AlarmSchedulerTest` | ✅ 通过 | 按提前时间与下课时间注册；本轮已删除 TTS，改为校验课程名/地点/提前分钟数 |
| 12 | 修改课程后旧闹钟是否取消 | `AlarmSchedulerTest` | ✅ 通过 | 每次重调度先取消全部已登记旧 `PendingIntent` |
| 13 | 重启手机后是否恢复提醒 | `AlarmSchedulerTest` + `aapt2` 清单校验 | ✅ 通过 | 已注册 `BOOT_COMPLETED`/`TIME_SET`/`TIMEZONE_CHANGED`/`MY_PACKAGE_REPLACED` |
| 14 | 天气提醒是否根据体育课时间触发 | `PeWeatherSchedulerTest` + `TimetableEnginesTest` | ✅ 通过 | 按体育课日期与时段检查逐小时天气，同天多节合并预警 |
| 15 | 节假日是否暂停普通课程提醒 | `AlarmSchedulerTest` + `TimetableEnginesTest` | ✅ 通过 | 法定节假日 `holidayPaused=true` 并跳过常规闹钟 |
| 16 | 学校特殊教学日是否覆盖节假日 | `AlarmSchedulerTest` + `TimetableEnginesTest` | ✅ 通过 | `SchoolCalendarOverride` 优先级高于法定节假日 |
| 17 | 游客数据是否迁移到正式账号 | `BackendIntegrationTest` + `TimetableEnginesTest` | ✅ 通过 | 登录后合并本地与云端快照，冲突显式记录 |
| 18 | 云端同步是否恢复数据 | `BackendIntegrationTest` + `SyncAndThemePersistenceTest` | ✅ 通过 | 换机 `pull` 恢复及离线 JSON 备份导出/预览/恢复 |
| 19 | 四种桌面小组件是否正常显示 | `WidgetSnapshotLoaderTest` + `aapt2` 清单校验 | ✅ 通过（注册与数据层） | 4 个 Receiver 已打入 APK；真机拖拽/渲染需实机补测 |
| 20 | 深色模式是否正常 | `ThemePaletteTest` + `SyncAndThemePersistenceTest` | ✅ 通过 | API 26 与 API 33 均验证持久化与色板 |

### 第二轮 6 项专项修复验收

| 编号 | 修复项 | 验收方式 | 结果 | 说明 |
|---:|---|---|---|---|
| 21 | 学校覆盖面 | 目录数据 + `BackendIntegrationTest` | ✅ 通过 | 2741 所（本科 1273 / 高职 1468 / 31 省，含示例树维高校（如清华大学等试点样例）），远程下发条数一致，`verified=0` |
| 22 | 作息规则编辑器 | `BellAndHolidayEnginesTest` 5 项 | ✅ 通过 | 标准 12 节全天时间逐节精确断言；差异时长/自定义休息/无课日/非法输入 |
| 23 | 节假日合并 | `BellAndHolidayEnginesTest` + `CampusDatabaseMigrationTest` | ✅ 通过 | 连续 7 天合并为一条、调休单独成条、幂等；v1 单日记录升级后 `totalDays=1` 且课程数据无损 |
| 24 | 天气来源调整 | `PeWeatherSchedulerTest` + `CityPresetsTest` | ✅ 通过 | v2.1.0 已删除系统/厂商天气；Open-Meteo 离线已于 v2.2.0 扩充至 2154 城（含苏州与县级市）、联网限定 CN 并支持去「市」重试 |
| 25 | TTS 处理 | 全仓检索 + `AlarmSchedulerTest` | ✅ 已删除 | 无任何 TTS 功能代码，通知/铃声/震动/上下课提醒测试通过 |
| 26 | 桌面小组件 | `aapt2` 清单 + `WidgetSnapshotLoaderTest` | ✅ 通过（注册与数据层） | 4 个 Widget Receiver + 固定回调全部注册；真机添加/刷新待补测 |

### 第三轮 7 项专项修复验收（v2.1.0）

| 编号 | 修复项 | 验收方式 | 结果 | 说明 |
|---:|---|---|---|---|
| 27 | 应用图标 | `aapt2` badging + mipmap 资源检查 | ✅ 通过（资源层） | 用户提供图片生成 108–432dp 全密度自适应图标（`ic_launcher` / `ic_launcher_round` = 背景图 + 透明前景）；真机桌面/任务卡显示待补测 |
| 28 | 入口合并 | 代码与导航检查 | ✅ 通过 | 设置页仅保留「学期与作息时间管理」一项，作息编辑器从学期管理页顶部进入；课表页重复入口已移除 |
| 29 | 高校目录与适配状态 | 目录数据 + `BackendIntegrationTest` | ✅ 通过 | 已收录全国 2741 所高校（本科 1273 / 高职 1468），严格区分 `GENERIC_ADAPTER`/`ASSISTED_LOGIN`/`UNSUPPORTED` 并诚实标注 `verified=false` |
| 30 | 天气来源与城市 | `CityPresetsTest` 2 项 + `PeWeatherSchedulerTest` | ✅ 通过 | 系统/厂商天气入口与探测代码删除；离线城市扩充至 2154 座；联网检索限定 `countryCode=CN` 并去「市」重试 |
| 31 | 按钮文字说明 | 全量 Compose 界面检查 | ✅ 通过 | 「添加课程」扩展 FAB、学期/作息 chip、编辑/删除/撤销/浏览器打开/清除会话等均带可见文字，仅纯装饰图标保留 `contentDescription=null` |
| 32 | 导入跳转浏览器 | `ImportViewModel` + WebView 代码检查 | ✅ 通过（逻辑层） | 受控 WebView 自动使用学校门户地址并补全 `https://`，返回键回退、顶栏「浏览器打开」调用系统浏览器 |
| 33 | 版本与文档同步 | 构建产物 + 文档核对 | ✅ 通过 | Android 与后端版本号、README/架构/数据库/API/部署/清单/测试报告全部同步 |

### 第四轮 5 项体验升级验收（v2.2.0）

| 编号 | 升级项 | 验收方式 | 结果 | 说明 |
|---:|---|---|---|---|
| 34 | 按钮文字与形状统一 | 全仓检索 + 编译 | ✅ 通过 | `IconButton(` 检索 0 处；返回、上/下一周、上/下一年、清除、关闭等全部改为图标+可见文字的统一 Material 3 按钮 |
| 35 | 导入直达教务处 | `SchoolAcademicUrlAssetTest` 2 项 + 地址资源 | ✅ 通过 | 高校教务处地址经公开 HTTP 访问校验，未收录学校走教务处检索而非官网首页 |
| 36 | 天气城市扩充 | `CityPresetsTest` 2 项 | ✅ 通过 | 离线 2154 座（地级/自治州首府/县级市/县城区），名称唯一、坐标范围合法 |
| 37 | 学校浏览与检索 | 代码与界面检查 | ✅ 通过 | 保留省份筛选与拼音检索，v2.2.9 起全面改为按全拼 A–Z 排序与首字母快捷定位 |
| 38 | 小组件聚合信息 | `WidgetSnapshotLoaderTest` + 代码检查 | ✅ 通过（数据层） | 「今日概览」单组件聚合日期/教学周、缓存天气、下一节倒计时与今日课程；真机渲染待补测 |

### 第五轮 v2.2.1 雷电模拟器实机验收（Android 14，720×1600 @320dpi）

| 编号 | 验收项 | 实机结果 | 截图 |
|---:|---|---|---|
| 39 | 按钮文字完整（窄屏不截断） | ✅ 天气页「刷新真实天气/切换城市/自动定位」换行后完整显示；作息页底栏三按钮、节假日同步按钮均完整 | 07、13、17 |
| 40 | 离线城市 2154 座 + 县级市 | ✅ 「离线收录 2154 座城市」；弥勒（县级市）可选并返回真实实况 16°C 晴 · AQI 69 | 08、10 |
| 41 | 节假日同步（镜像回退） | ✅ 默认 GitHub raw 超时后自动改用 jsDelivr 镜像，成功同步 2026 年 7 个假期区间 + 6 个调休条目 | 19 |
| 42 | 新增课程写入与展示 | ✅ 「高等数学 · 周五 1–2 节 · 教学楼A101」写入 Room 并在周视图正确显示 | 15、16 |
| 43 | 聚合小组件（数据层与预览） | ✅ 今日概览同时显示日期教学周、天气、下一节状态与今日课程 | 14 |
| 44 | 一键固定降级提示 | ⚠️ LDPlayer 桌面不支持一键固定，应用如实提示并提供手动步骤（真机待验证） | 21 |

### 第六轮 v2.2.2 – v2.2.3 浏览器与入口体验验收（Android 14，720×1600 @320dpi）

| 编号 | 验收项 | 实机结果 | 截图 |
|---:|---|---|---|
| 45 | 真实账号课表抓取 | ⚠️ 需用户本人在校园网内使用本人账号登录教务课表页；目录诚实标注 `verified=false` | — |
| 46 | 打不开帮助 | ✅ 弹出三条出路（电脑模式+横屏 / 系统浏览器 / Excel-PDF 或适配申请）与「复制链接」「知道了」 | 29 |
| 47 | 入口如实标注 | ✅ 门户入口如实提示并显示「打开校内门户/教务处」，未适配学校仍显示「暂未适配」 | 30 |

---

## 三、最终成品包校验（`aapt2` 实测）

- **Debug APK 路径**：`android/app/build/outputs/apk/debug/app-debug.apk`（**76.41 MiB / 80,124,574 字节**）
- **应用名称**：`application-label='桃课'`
- **应用标识**：`com.campus.timetable.debug`，`versionCode=13`，`versionName=2.2.9-debug`
- **系统兼容**：`minSdkVersion=26`（Android 8.0），`targetSdkVersion=35`（Android 15）
- **已验证注册权限与组件**：已包含 `USE_FULL_SCREEN_INTENT`、`SCHEDULE_EXACT_ALARM`、`POST_NOTIFICATIONS` 与显式 `network_security_config`；组件含 `MainActivity`、`AlarmRingingActivity`、`CourseAlarmReceiver`、`AlarmRestoreReceiver`、`TodayCoursesWidgetReceiver`、`NextCourseCountdownWidgetReceiver`、`WeeklyTimetableWidgetReceiver`、`WeatherPeWidgetReceiver`、`WidgetPinnedCallbackReceiver`。
- **后端构建包**：`backend/build/libs/smart-campus-timetable-backend-2.2.9.jar`（**58.10 MiB / 60,918,640 字节**）

> **诚实说明（验收环境）**：本轮已在本机雷电模拟器 LDPlayer14（Android 14）安装 APK 完成逐页实机验收，留存的通用功能截图（`docs/screenshots/` 共 17 张）均来自该模拟器真实运行，未使用任何合成或示意界面。仍未能验证的部分：①物理真机与国产 ROM（MIUI/ColorOS/OriginOS/HarmonyOS）的后台保活、真实响铃与重启恢复；②真实教务账号登录与课表抓取（需用户本人在校内网络使用本人账号，应用不保存任何教务密码）；③ML Kit 离线 OCR 首次模型下载（模拟器无 Google Play 服务）；④桌面小组件在支持 `requestPinAppWidget` 的真机桌面上的拖拽与渲染（LDPlayer 桌面不支持一键固定，应用已如实提示并提供分品牌手动添加步骤，小组件数据层与应用内预览已验证）；⑤云同步需部署后端后实网验证。
