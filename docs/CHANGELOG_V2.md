# v2.0.0 第二轮专项修复记录（CHANGELOG）

- **应用名称**：桃课（原「智能校园课表」）
- **版本**：versionCode `2` / versionName `2.0.0`（Debug 包为 `2.0.0-debug`，包名 `com.campus.timetable.debug`）
- **完成日期**：2026-11-26
- **范围**：基于第一版已安装 APK 的反馈，逐项修复用户提出的六个问题，并完成回归测试与新 APK 打包。

> 本记录只描述**已经落地并通过编译与自动化测试**的内容；因缺少真机/真实校园账号而无法验证的部分，在第五节如实列出，未做任何伪造。

> **v2.1.0 更新（2026-11-26）**：本文是 v2.0.0 的历史记录。v2.1.0 已将目录扩充至 **2741 所**（本科 1273 / 高职 1468、辅助登录 42），并**删除**本文第四节所述的系统/厂商天气入口，天气仅保留 Open-Meteo（275 城离线表）与和风天气。当前状态以 [`CHANGELOG_V2_1.md`](CHANGELOG_V2_1.md) 与 [`FEATURE_CHECKLIST.md`](FEATURE_CHECKLIST.md) 为准。

---

## 一、六项问题逐项修复结果

### 1. 学校数量太少 → 升级为教育部全国高校目录（2740 所）

- **根因**：第一版学校目录仅内置少量样本，搜索覆盖面远低于同类产品。
- **修复**：
  - 新增离线种子库 `android/data/src/main/assets/schools_catalog_v2.json`，数据来源为**教育部《全国普通高等学校名单》**（省级教育主管部门公开名单汇总），共 **2740 所**大陆高校：**本科 1272 所 + 高职（专科）1468 所**，覆盖 **31 个省级行政区**。
  - 每所学校包含：全称、简称、别名、拼音、拼音首字母、省份、城市、办学类型、本科/高职分类、校区、教务系统类型与版本、登录入口、适配器 ID、支持状态、最后验证时间。
  - 搜索支持全称 / 简称 / 别名 / 拼音 / 拼音首字母 / 省份筛选，全部在本地 Room 中完成，不依赖网络即可即时检索。
  - 教务适配扩展为四类通用适配器：正方（`zhengfang_standard`）、强智（`qiangzhi_standard`）、新 URP（`new_urp_standard`）、旧 URP（`old_urp_standard`）。
  - **诚实标注**：目录中 `verified = 0` 所，`GENERIC_ADAPTER（支持通用教务导入）37 所`、`ASSISTED_LOGIN（需要辅助登录）41 所`、`UNSUPPORTED（暂未适配）2662 所`、已配置官方入口 78 所。未完成实网验证的学校一律不显示为「已支持」，搜索页明确展示统计与状态。
  - 支持远程热更新：后端新增 `GET /api/v1/schools/catalog` 下发同一份目录（也可通过 `SCHOOL_CATALOG_REMOTE_URL` 指向 CDN/GitHub Raw），客户端 `refreshSchoolCatalogFromRemote()` 校验后整体替换本地目录，**扩充学校无需重新发版**。
  - 新增用户授权后的「学校适配申请」：客户端本地留存并尝试提交，后端 `POST /api/v1/schools/adapt-requests` 受理（Flyway `V3` 建表），服务端与客户端双重拦截任何携带 `password=`/`passwd=` 的提交。
- **验证**：`SchoolAdaptersTest`（未验证学校拦截、正方/强智解析、GBK/登录页/SPA 探测）通过；`BackendIntegrationTest#remoteSchoolCatalogServesAllSchoolsAndAdaptationRequestsEnforcePrivacy` 校验目录 2740 条、无任何 `verified=true`、未授权与含密码申请均 400。

### 2. 节次时间只能逐节改 → 新增「作息时间规则编辑器」

- **根因**：第一版只有逐节编辑的开始/结束时间，没有统一规则推算。
- **修复**：
  - 新增领域引擎 `BellScheduleRuleEngine`（`domain/.../engine/BellAndHolidayEngines.kt`）：按「首节时间 + 每节时长 + 默认小课间 + 每 N 节大课间 + 午休/固定下午开始 + 晚间休息/固定晚间开始」自动推算整天节次。
  - 支持方式 A 统一规则、方式 B 单节单独微调、方式 C 按星期独立作息（1–7 独立覆盖，`totalPeriods=0` 表示该天无课）、方式 D 模板保存（内置普通/夏季/冬季/周末四套，可另存自定义模板并一键套用/删除）。
  - 支持单节差异化时长（`customPeriodDurations`）与任意两节之间自定义休息（`customBreaksAfterPeriod`）。
  - 编辑页提供**实时预览**（含相邻节次的「小课间 / 大课间 / 午休 / 晚休」标签）、校验提示、保存、恢复默认、应用当前星期、批量应用到多星期/整学期。
  - 应用作息后联动：更新课程显示时间、重算上课/下课闹钟、刷新体育课天气任务与桌面小组件，且**不覆盖**任何 `course_exceptions` 临时调课。
  - 数据层新增 Room 表 `bell_schedule_templates` 并随备份包（schemaVersion 2）一起导出/恢复。
- **验证**：新增 `BellAndHolidayEnginesTest` 5 项用例，覆盖标准作息（08:00 首节、45 分钟、10/20 分钟课间、14:00 下午、19:00 晚间）逐节时间断言、单节差异时长+自定义休息、无课日与非法规则校验。

### 3. 节假日逐日拆条 → 连续假期自动合并为一条

- **根因**：第一版把国务院原始逐日数据直接落库并逐条展示，导致春节等假期出现 7 条记录与 7 次提醒。
- **修复**：
  - `Holiday` 模型升级为区间事件：新增 `endDate`、`totalDays`、`reminderDate`，连续且同名的放假日自动聚合为一条（如「春节假期 2/5—2/11 · 共 7 天」），调休上班日单独成条并标注「调休工作日」。
  - 新增 `HolidayMergeEngine`：既负责把远程逐日数据聚合，也可对历史单日记录做幂等合并；每个连续假期只生成**一条**节前提醒。
  - Room `holidays` 表升级（version 1 → 2，`MIGRATION_1_2` 自动把旧单日记录迁移为新表并把 `date` 同时写入 `endDate`、`totalDays=1`，不丢数据）。
  - 节假日页改为展示「合并后的完整假期区间列表 + 调休工作日列表」，日历仍按天着色；支持设置统一节前提醒天数；节假日期间默认暂停普通课程闹钟，学校校历覆盖优先级最高。
- **验证**：`BellAndHolidayEnginesTest` 覆盖 7 天连续假期合并、调休工作日区分、非连续日期不合并、重复合并幂等；`AlarmSchedulerTest` 覆盖节假日暂停与学校覆盖恢复。

### 4. 天气来源 → 优先手机系统/厂商天气，无公开接口时回落实时开放气象

- **根因**：第一版直接使用第三方气象 API，未考虑用户希望优先使用手机自带天气。
- **修复**：
  - 新增 `DeviceWeatherCapabilityDetector`：按设备品牌识别小米、华为、荣耀、OPPO/一加/真我、vivo/iQOO、三星、Google、魅族等系统天气应用（`AndroidManifest` 声明受控 `<queries>`），提供一键打开系统天气应用的入口，并在设置页展示设备品牌、系统天气应用与当前真实数据来源。
  - **如实说明限制**：Android 没有跨品牌公开天气数据接口，厂商未开放数据时无法读取其内部天气数据；本项目严格不使用非公开 API、不读取其他应用私有数据库、不使用无障碍抓取、不逆向天气应用。
  - 未检测到公开数据接口时自动回落免密钥的真实 **Open-Meteo**（可切换到和风天气 QWeather，需配置 Key），体育课 6 项阈值提醒、前一天晚间与当天课前两次检查、同天多节合并提醒等逻辑全部保留。
  - 天气失败隔离：获取失败只提示，不影响课表、闹钟与小组件。
- **验证**：`PeWeatherSchedulerTest` 通过；`WeatherRepository#detectDeviceWeatherCapability` 在单元测试桩中覆盖。

### 5. TTS 语音播报不可用 → 彻底删除

- **决策**：无法在缺少真机中文 TTS 引擎验证的条件下保证「能初始化、能播报、失败可提示」，按任务书要求**彻底删除**。
- **删除内容**：`TextToSpeech` 初始化与播报代码、TTS 设置开关、`AlarmSetting.enableTts`、闹钟计划中的 `ttsText`、相关界面文案与测试断言。
- **保留内容**：普通通知、真正闹钟响铃页、系统铃声、震动、上课开始提醒、下课提醒、一键静音今日、单课开关、开机/改时/时区/更新恢复全部正常。
- **验证**：全仓检索 `TextToSpeech / enableTts / ttsText / 语音播报` 已无任何功能代码（仅剩 3 处说明“已移除”的注释）；`AlarmSchedulerTest` 改为断言课程名/地点/提前分钟数与 `isClassEnd`。

### 6. 桌面小组件无法使用 → 补齐注册、入口与引导

- **根因**：第一版小组件类已写好，但缺少系统注册与添加入口/引导，用户无法从桌面添加。
- **修复**：
  - `feature-widgets/AndroidManifest.xml` 完整注册 4 个 `GlanceAppWidgetReceiver`（今日课程、下一节课倒计时、本周课表、天气与体育课提醒）+ 固定回调接收器，每个都绑定 `appwidget-provider` 元数据。
  - 新增 `res/xml/widget_*_info.xml`（`minWidth/minHeight`、`resizeMode`、`targetCellWidth/Height`、`updatePeriodMillis=30min`、`initialLayout`）与加载/错误占位布局、浅色/深色卡片背景。
  - 新增「桌面小组件中心」页面：展示 4 种组件预览与说明；`requestPinAppWidget()` 一键请求系统固定，回调 `WidgetPinnedCallbackReceiver` 反馈成功/取消；系统不支持时展示按品牌（小米、华为、荣耀、OPPO、vivo、三星、Pixel/原生）的图文添加步骤。
  - 设置页与课表页均提供小组件入口；课程/作息/学期变更后通过 `WidgetRefresherPort` 触发 `updateAll()`，遵循系统刷新频率限制。
  - 明确说明：Android 原生桌面小组件**不需要额外运行时权限**，不申请任何无关权限。
- **验证**：`aapt2 dump xmltree` 确认最终 APK 清单中 4 个 Widget Receiver 与固定回调均已注册；`WidgetSnapshotLoaderTest` 验证四种组件读取真实 Room 数据与空状态。

---

## 二、其他改动

| 类别 | 内容 |
|---|---|
| 应用更名 | `strings.xml` 中 `app_name` 改为「桃课」，APK `application-label` 实测为「桃课」 |
| 版本号 | `versionCode 2` / `versionName 2.0.0` |
| 数据库 | Room `version 2`，新增 `bell_schedule_templates`、`school_adaptation_requests` 两张表；`holidays` 新增 `endDate/totalDays/reminderDate`；导出 schema `2.json` |
| 备份格式 | `BackupBundle.schemaVersion` 提升至 2（含作息模板），导入端兼容 v1–v3 |
| 后端 | 新增 `GET /api/v1/schools/catalog`、`POST /api/v1/schools/adapt-requests`；Flyway `V3__school_adaptation_requests.sql`；打包 `schools_catalog_v2.json` |
| 配置 | `.env.example` 增加 `SCHOOL_CATALOG_REMOTE_URL`；Android `build.gradle.kts` 注入 `SCHOOL_CATALOG_REMOTE_URL` |
| 导航 | 新增 `bell_schedule`、`widgets` 路由；课表页/设置页入口 |
| 文档 | 本文件 + 更新 README、TEST_REPORT、FEATURE_CHECKLIST、DATABASE_DESIGN、API_SPEC、ARCHITECTURE、BUILD_AND_DEPLOY |

### 主要新增/修改文件（Android）

```text
domain/src/main/kotlin/.../model/TimetableModels.kt          # 作息规则/模板、假期区间、设备天气能力、学校目录统计
domain/src/main/kotlin/.../engine/BellAndHolidayEngines.kt   # 作息推算引擎 + 连续假期合并引擎（新增）
domain/src/main/kotlin/.../engine/TimetableEngines.kt        # 按星期作息、假期区间解析等
domain/src/main/kotlin/.../repository/Repositories.kt        # 作息模板、目录统计、天气能力等接口
data/src/main/java/.../local/CampusDatabase.kt               # v2 表结构与 MIGRATION_1_2
data/src/main/java/.../local/entity/RoomEntities.kt          # 作息模板/假期/学校申请实体
data/src/main/java/.../local/dao/RoomDaos.kt                 # 新 DAO 方法
data/src/main/java/.../repository/RepositoryImpls.kt         # 目录装载/远程刷新、作息模板、天气能力、备份 v2
data/src/main/assets/schools_catalog_v2.json                 # 2740 所高校目录（1.69 MB）
data/src/test/java/.../local/CampusDatabaseMigrationTest.kt  # v1→v2 真实迁移测试（新增）
data/build.gradle.kts                                        # 将 schemas 导出目录挂载为测试 assets
data/src/main/java/.../remote/weather/DeviceWeatherCapabilityDetector.kt  # 厂商天气能力检测（新增）
school-adapters/.../UrpSchoolAdapters.kt                     # 新/旧 URP 通用适配器（新增）
school-adapters/.../SchoolAdapterRegistryAndWebParser.kt     # 四适配器注册与通用/辅助/未适配分级
feature-timetable/.../BellScheduleScreen.kt                  # 作息规则编辑器（新增）
feature-timetable/.../TimetableScreen.kt                     # 课表入口与联动
feature-holiday/.../HolidayScreen.kt                         # 合并假期列表与提醒设置
feature-weather/.../WeatherScreen.kt                         # 设备天气来源展示与系统天气入口
feature-alarm/...（4 个文件）                                 # 移除 TTS，保留通知/闹钟/恢复
feature-widgets/...                                          # 注册、Provider 配置、引导中心、刷新
feature-settings/.../SettingsScreen.kt                       # 作息/小组件/天气入口
app/src/main/java/.../MainActivity.kt                        # 新路由
```

### 后端新增

```text
src/main/resources/db/migration/V3__school_adaptation_requests.sql
src/main/java/.../entity/SchoolAdaptationRequestRecord.java
src/main/java/.../repo/SchoolAdaptationRequestRepository.java
src/main/java/.../service/SchoolCatalogService.java
src/main/java/.../controller/ApiController.java              # 新增两个端点
src/main/resources/schools_catalog_v2.json
```

---

## 三、构建与测试结果

| 项目 | 结果 |
|---|---|
| `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL |
| Android 单元测试 | ✅ **31 项全部通过**（0 失败/0 跳过） |
| 后端 `./gradlew test` | ✅ **4 项集成测试全部通过**（H2 PostgreSQL 模式 + Flyway V1–V3） |
| 后端 `./gradlew bootJar` | ✅ 构建通过 |
| APK 清单校验 | ✅ `com.campus.timetable.debug`，versionCode 2，versionName 2.0.0-debug，minSdk 26，targetSdk 35，label「桃课」 |
| APK 大小 | 约 **75.07 MiB**（78,712,610 字节） |

> 本轮新增 `BellAndHolidayEnginesTest`（5 项）、`CampusDatabaseMigrationTest`（1 项，用 Schema `1.json` 建 v1 库、执行 `MIGRATION_1_2` 后按 `2.json` 校验，证明已装 v1 APK 升级不丢课表数据）与后端高校目录/适配申请集成测试；并修复了第二轮改动后遗留的 4 个测试桩接口不匹配与 2 处断言过时问题。

---

## 四、与任务书的对照

| 任务书要求 | 状态 |
|---|---|
| 生成新的可安装 APK | ✅ `android/app/build/outputs/apk/debug/app-debug.apk` |
| 更新版本号 | ✅ 2 / 2.0.0 |
| 完整修改记录 | ✅ 本文件 |
| 六项问题修复结果 | ✅ 第一节 |
| 学校目录数量与真实适配数量 | ✅ 2740 所；已验证 0 所、通用适配 37 所、辅助登录 41 所、暂未适配 2662 所 |
| 作息规则功能截图 | ⚠️ 无法提供——本机无 Android 真机/模拟器（见第五节） |
| 节假日合并显示截图 | ⚠️ 同上 |
| 天气来源说明 | ✅ 第一节第 4 项与 `THIRD_PARTY_SETUP.md` |
| TTS 保留或删除决定及原因 | ✅ 删除，原因见第一节第 5 项 |
| 桌面小组件实际运行截图 | ⚠️ 运行时截图需真机；APK 清单注册已用 `aapt2` 实测验证 |
| 测试报告 | ✅ `TEST_REPORT.md` |
| 尚未完成的功能清单 | ✅ `FEATURE_CHECKLIST.md` 与第五节 |

---

## 五、尚未完成 / 需要外部条件的事项（如实说明）

1. **真机截图与实机体验**：当前 Windows 开发机未连接 Android 真机，也未安装 Android Emulator 系统镜像，无法生成「作息规则页 / 节假日合并页 / 桌面小组件实际运行」截图。已完成的是编译、单元测试与 APK 清单校验；截图需在真机安装 APK 后补拍。
2. **高校实网导入验证**：2740 所学校已全部进入目录，但**没有一所**完成真实在校账号的登录与课表提取验证，因此全部保持 `verified=false`，分别标注「支持通用教务导入 / 需要辅助登录 / 暂未适配」。要达到 WakeUp 级别的“已适配学校数量”，需要逐校实测与适配器配置，这依赖真实校园账号与各校教务系统访问权限。
3. **厂商天气数据接口**：小米/华为/荣耀/OPPO/vivo/三星/Google/魅族均未向第三方应用开放公开天气数据接口，只能打开系统天气应用；真正的体育课提醒仍使用 Open-Meteo / QWeather。
4. **生产短信与正式签名**：阿里云短信需企业实名与模板审核；本轮仍交付 Debug APK，未伪造正式签名包。
5. **国产 ROM 后台保活**：闹钟在 MIUI/HyperOS、EMUI/HarmonyOS、ColorOS、OriginOS 等系统上的自启动与省电策略需真机实测，已在设置页提供提示，但不能承诺绝不漏报。
