# 变更记录（v2.2.7 第十一轮 · 修复「已结课课程仍显示冲突 / 括号假课拆分 / 单节截断」与覆盖导入）

- **版本**：Android `2.2.7`（`versionCode = 11`）、Backend `2.2.7`
- **触发背景**：用户在树维 EAMS 高校树维 EAMS 导入真实课表后，在第 13 周周视图反馈三类现象：已结课课程仍在第 13 周显示并导致 38 处时间冲突；大量课程标题显示成 `!(`、教室显示成 `@(`；2 节连堂课全变成第 1、3、5、7 单节。

---

## 根因定位与全链路修复

1. **修复「已结课课程未过滤 & 括号拆成假课」**
   - **根因**：树维 EAMS 单元格含 3 连 `<br><br><br>` 空占位及无「周」字括号排课行（如 `(1-8,一教201)`、`(单1-15,田径场)`、`(13,力学综合实验室)`）。旧版 `ChineseCourseTextParser.parseGridCell()` 将其按空行拆成以 `(` 开头的新假课，且因匹配不到「周」字而全部兜底为 `1-16周`，导致 `1-8` / `1-12` 周已结课课程也在第 13 周出现。
   - **修复**：
     - [ChineseCourseTextParser.kt](file:///D:/antigravity/smart-campus-timetable/android/domain/src/main/kotlin/com/campus/timetable/domain/engine/ChineseCourseTextParser.kt) 新增公开 `parseEamsScheduleParenLine()`，支持多段逗号周次（如 `(1-4,6-12,一教201)`）并剥离外层多余括号；强化 `isMetaOnlyLine()`，任何排课括号行与单独实验项行一律归属上一门课，绝不拆成假课；强化 `parseGridCell()` 从括号行直接解析起止周与教室。
     - [SchoolAdapterRegistryAndWebParser.kt](file:///D:/antigravity/smart-campus-timetable/android/school-adapters/src/main/java/com/campus/timetable/schooladapters/SchoolAdapterRegistryAndWebParser.kt) 为 `WebParseOutcome.Success` 新增 `qualityTier`（3=运行时对象/脚本，2=专用适配器，1=通用 HTML 表格，0=自由文本），修改 `parseRenderedDocuments()` 优先采纳高可信度且非括号假课的解析结果，避免低质量兜底结果凭数量反超精准解析。

2. **修复「连堂 2 节课程全变成 1 节」**
   - **根因**：`ChineseCourseTextParser.parsePeriods()` 仅取首个节次匹配，遇到「第1节 第2节」「第1节\n第2节」「第1小节 第2小节」「第一、二节」时均截断为 `1 to 1`、`3 to 3`；同时部分 EAMS 页面单元格 ID 为下划线格式 `id="TD0_0"`，此前未命中 `parseRenderedEamsTable()`。
   - **修复**：
     - `parsePeriods()` 升级为连续相邻节次聚合（`1..2`、`3..4`、`5..6`、`7..8`），并支持「小节」及中文数字节次。
     - [EamsSchoolAdapter.kt](file:///D:/antigravity/smart-campus-timetable/android/school-adapters/src/main/java/com/campus/timetable/schooladapters/EamsSchoolAdapter.kt) 同时支持 `TD{index}` 与 `TD{day}_{unit}`、`#manualArrangeCourseTable*` 前缀表名，并在 `parseRuntimeTableJson()` 中新增 `inferCalendarFromOffset()` 自动反推秋季学期 `from` 自然周偏移，精准解码同格多门课的 53 位自然周位图。

3. **新增「覆盖当前学期旧课表 / 追加导入」模式（参考 WakeUp）**
   - 修改 [Repositories.kt](file:///D:/antigravity/smart-campus-timetable/android/domain/src/main/kotlin/com/campus/timetable/domain/repository/Repositories.kt)、[RoomDaos.kt](file:///D:/antigravity/smart-campus-timetable/android/data/src/main/java/com/campus/timetable/data/local/dao/RoomDaos.kt)、[RepositoryImpls.kt](file:///D:/antigravity/smart-campus-timetable/android/data/src/main/java/com/campus/timetable/data/repository/RepositoryImpls.kt)、[ImportViewModel.kt](file:///D:/antigravity/smart-campus-timetable/android/feature-import/src/main/java/com/campus/timetable/feature/importing/ImportViewModel.kt) 与 [ImportScreen.kt](file:///D:/antigravity/smart-campus-timetable/android/feature-import/src/main/java/com/campus/timetable/feature/importing/ImportScreen.kt)：
   - 导入预览页默认选中 **「覆盖当前学期旧课表（推荐）」**（也可切换为「追加到当前学期」），确认导入时在一个 Room 数据库事务内原子软删当前学期旧课表并写入本次新课表，彻底杜绝多次重导残留旧课造成的假冲突。
