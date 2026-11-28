# 变更记录（v2.2.5 第九轮 · 树维 EAMS 通用适配器与受控浏览器一键直达课表页）

- **版本**：Android `2.2.5`（`versionCode = 9`）、Backend `2.2.5`
- **触发背景**：真机实测登录示例树维高校（如清华大学等试点样例）树维 EAMS 教学管理系统（`https://jwxt.example.edu.cn/eams/localLogin.action`）后，系统自动跳转至首页仪表盘（`/eams/homeExt.action`）。因 EAMS 桌面端导航栏在手机 WebView 下文字极小且下拉菜单依赖悬停，用户难以找到「我的课表」入口；且原通用解析器仅支持纵置表及带「周」字的单元格格式，尚未适配树维 EAMS 的 `TaskActivity` 与横置 `colspan` 渲染课表。

---

## 核心修复与新增能力

1. **新增树维 EAMS 通用适配器（`EamsSchoolAdapter` · `eams_standard`）**
   - 在 `:domain` 新增 `EduSystemType.EAMS("树维 EAMS 教务系统")`，并注册为五大通用教务适配器之一（正方 / 强智 / 新 URP / 旧 URP / 树维 EAMS）。
   - **三层递增解析引擎**：
     1. **运行时课表对象直采（`__eamsRuntimeTable`）**：受控浏览器提取 JS 升级为同步导出 `window.table0`（`CourseTable` 实例的 `activities` + EAMS 已按当前学期偏移算好的 `marshalContents`），彻底解决 jQuery AJAX 渲染后移除 `<script>` 节点导致丢数据的问题。
     2. **源码 `TaskActivity` 脚本解析**：按真实 EAMS `TaskActivity.js` 公式（`semesterWeek = idx - from + 2` + 跨年回绕）精确解码 53 位自然周位图，并自动合并连续节次。
     3. **渲染后 `#manualArrangeCourseTable` / `TD{k}` 表格解析**：兼容横置表（`colspan`）与纵置表（`rowspan`），处理 3 连 `<br><br><br>` 空占位及无「周」字括号格式（如 `(1-16,一教201)`、`(单1-15,田径场)`、`(2-16双,实验楼305)`、`(1-8 10-16,一教403)` 与同格多门课）。
   - 通用 `WebTimetableParser.parseHtmlTableToCandidates()` 同步补齐**横置课表**（行头=星期一..星期日、列头=节次）解析支持。

2. **受控教务浏览器新增「一键直达我的课表页」与会话加固**
   - 新增 `EduPortalShortcutResolver`：当用户登录进入树维 EAMS 首页（如 `/eams/homeExt.action`、`/eams/home.action`）或强智首页（`/jsxsd/framework/xsMain.jsp`）时，步骤横幅自动亮出 **「前往我的课表页」/「前往学期理论课表」** 按钮，复用当前已登录 Cookie 合法直达标准课表页（如 `https://jwxt.example.edu.cn/eams/courseTableForStd.action`），绝不绕过登录或接触密码。
   - 精准区分「已登录树维 EAMS 首页」与「未登录教务处首页」，避免已登录用户误见「请先登录」提示。
   - WebView 显式启用 `CookieManager.setAcceptCookie(true)` 与 `setAcceptThirdPartyCookies(webView, true)`，切换页面时自动清理上一页报错提示。

3. **双端高校目录同步升级（`moe-mainland-2026.3`）**
   - 同步更新 Android `assets/schools_catalog_v2.json` 与后端 `resources/schools_catalog_v2.json`（均保持 2741 所完整收录，字节级一致）：将示例树维高校（如清华大学等试点样例）（`4100000001`）与电子科技大学（`4151010614`）更新为 `EAMS` / `eams_standard` / `GENERIC_ADAPTER`，**仍诚实保持 `verified = false`**，直至实机完成真实账号课表导入终验。
   - 覆盖安装后 App 启动自动同步内置目录变更，无需卸载重装。

---

## 验证结果

- **自动化测试**：14 个测试套件 / **58 项**自动化测试用例全部通过（Android 52 项 + 后端 6 项，0 失败，0 跳过；新增 6 项树维 EAMS 运行时对象/脚本位图/横置渲染表/已登录首页诊断/直达路由测试）。
- **构建产物**：
  - `android/app/build/outputs/apk/debug/app-debug.apk`（`79,727,815` 字节，`versionCode = 9`，`versionName = 2.2.5-debug`，通过 `aapt2 dump badging` 校验）
  - `backend/build/libs/smart-campus-timetable-backend-2.2.5.jar`（`60,916,284` 字节）
