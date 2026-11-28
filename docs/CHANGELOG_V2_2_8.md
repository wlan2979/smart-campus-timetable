# 变更记录 v2.2.8（versionCode = 12，第十二轮）

- **发布日期**：2026-11-28
- **Android 产物**：`app-debug.apk`（`versionCode = 12`，`versionName = 2.2.8-debug`，`79,892,924` 字节）
- **后端产物**：`smart-campus-timetable-backend-2.2.8.jar`（`60,918,296` 字节）

---

## 本轮解决的核心问题（对照真机与 WakeUp 课表对比截图）

### 一、修复树维 EAMS 连堂截断（`1-1` / `3-3` / `5-5` / `7-7`）与课程缺失（3 节「电工学」丢失）

1. **`EamsSchoolAdapter` 槽位格式与跨度双保险还原**：
   - 修复 `TD{slot}_{tableIdx}`（如 `TD0_0..TD83_0`）与 `TD{day}_{unit}`（如 `TD0_0..TD6_11`）格式歧义判断，避免把第 1 列之后的小节误映射到第 1 节；
   - 修复 `inferUnitsPerDayFromTable()`：优先按相邻星期行首 `TD` 编号差与最大槽位推算每日节数，避免个别单元格缺失 `colspan` 属性导致 `unitsPerDay` 算错一位；
   - `parseRenderedEamsTable()` 同时读取 `colspan`/`rowspan`、受控浏览器 DOM 注入属性 `data-agy-colspan`/`data-agy-rowspan`，以及基于 `TaskActivity.js` `fillTable()` 删除邻格规律的「现存 `TD` 槽位缺口推算跨度」，彻底还原 `1-2`、`3-4`、`5-6`、`7-8` 两节连堂及周四 `1-4` 四节连堂；
   - 修复 `splitEamsTitleAndTeacher()`，避免把 `(A)` / `(三)` 等课程分卷后缀误拆成教师名；清理 `cleanEamsCourseName()` 中的 `&nbsp;` 与尾随序号。
2. **`EamsSchoolAdapter.parseTaskActivityScript()` 与运行时快照解析加固**：
   - 将 `new TaskActivity(...)` 的提取由 `[^;]*?` 正则改为引号与括号平衡扫描器，彻底解决课程名称/备注中包含分号 `;` 或 `&nbsp;` 导致整门课（如 `电工学`）被丢弃的问题；
   - 新增对 `index++`、`++index`、`index += k`、`index = index + k`、`.activities[index + k]` 以及 `endAtSat`（年底是否周六）`leftShift()` 左移规则的完整支持；
   - `parseRuntimeTableJson()` 中当 `marshalContents` 与课程一对一匹配时优先采纳官网已算出的准确周次，防止自然周偏移导致特定课程消失。
3. **`ChineseCourseTextParser.parseGridCell()` 单 `<br>` 多课程格拆分与嵌套括号保留**：
   - 支持在同一单元格内用单换行紧邻排布多门课程（例如前期已结课课程与后期接续的 `电工学 (李老师)` 共用同一节次格子），按行状态机拆分为独立课程，不再整格吞并只保留第一门；
   - `cleanLocation()` 清理 `TaskActivity.js` 偶发拼接的尾部 `/undefined`，同时完整保留 `一教122(主校区)`、`综合楼305(主校区)` 等内部校区括号。

---

### 二、导入课表前/入库前询问「本学期第一天是几月几号」

1. 在 `ImportViewModel` 与 `ImportScreen` 的预览及入库链路新增 `SemesterStartDateDialog` 与开学日期核对卡片：
   - 进入导入预览后立即询问并引导确认本学期第一天（第 1 周周一）是几月几号，点击「确认导入本地课表」若尚未确认也会强制弹出核对；
   - 支持 **Material 3 日历直接选择具体日期** 与 **按「今天是第几周」一键反推第 1 周周一**，实时换算显示「按此开学日，今天为第 X 周」；
   - 若教务网页（例如页脚 `2026-2027第秋季学期 13 教学周`）提取到当前教学周提示，自动给出推荐日期一键设定按钮；
   - 确认导入时同步保存当前学期 `Semester.startDate`，并以此精确检测冲突、重算闹钟、体育课天气与刷新全部桌面小组件。

---

### 三、导入课表时自动以学校官网作息为准修正上下课与休息时间

1. **新增通用官网作息与教学周提取器 `OfficialBellScheduleExtractor`**：
   - 跨全部抓取页面及 `iframe` 扫描表头/行头文本、`title` / `data-agy-title` 属性、`第X节 HH:mm-HH:mm` 与 JSON 节次字段（适配树维 EAMS / 正方 / 强智 / 新旧 URP / 通用网页课表），经单节时长与单调递增校验后提取真实作息（如部分高校 `08:00-08:45`、`08:50-09:35`、30 分钟大课间 `10:05-10:50`、`16:05-16:50`、晚间 `18:40-19:25` 等）；
   - `WebTimetableParser.parseRenderedDocuments()` 统一在胜出结果中汇总全部帧识别出的 `detectedPeriods` 与 `detectedCurrentWeek`。
2. **增强作息反推引擎 `BellScheduleRuleEngine.inferRuleFromPeriods()`**：
   - 自动识别上午结束节次、下午开始/结束节次、晚间开始时间、常规小课间与大课间时长、单节差异时长与非固定间距休息，确保反推规则与导入的官网作息 100% 逐分钟一致。
3. **导入同步修正与主课表展示**：
   - 导入预览默认开启「导入时同步修正本学期上下课与休息时间」（支持查看每节明细并手动开关），确认导入时同步写入 `class_periods`、更新 `periodsPerDay` 并持久化反推的 `BellScheduleRule`；
   - 主课表左侧 `PeriodTimeColumn` 现在同步展示每一节的上课时间与下课时间（如 `08:00` / `08:45`），清晰呈现官网作息与课间休息。
