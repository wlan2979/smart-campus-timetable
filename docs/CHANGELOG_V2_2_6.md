# 变更记录（v2.2.6 第十轮 · 彻底修复 WebView 剥离 `<head>` 导致教务网站残缺 + 全量高校网址可达性净化）

- **版本**：Android `2.2.6`（`versionCode = 10`）、Backend `2.2.6`
- **高校目录版本**：`moe-mainland-2026.4`（2,741 所大陆高校完整收录，双端字节级一致）

---

## 一、根治「连完整网站都显示不出来」的 Android WebView 底层缺陷

### 根因定位（通过 Chromium WebView DevTools + HTTP 抓包实证）
- Android `WebView` 默认会为发出的每一个 HTTP 请求自动附带请求头 `X-Requested-With: <应用包名>`（如 `com.campus.timetable.debug`）。
- 树维 EAMS（Beangle / Struts2）及大量国内高校教务后端框架只要检测到请求头中存在非空的 `X-Requested-With`，就会将普通页面浏览误判为 AJAX 局部请求，**直接剥离整个 `<!DOCTYPE html><head>...</head>`**，导致页面丢失全部 CSS（`beangle-ui.css`、`bootstrap.css`、`education.css`、`home.css`）与核心 JS（`jQuery`、`beangle.js`、`semesterCalendar.js`、`TaskActivity.js`），表现为登录页纯白裸 HTML、首页无导航菜单且 AJAX 区域全空白、课表页无法初始化项目/学年学期与课表表格。

### 彻底修复
1. 引入官方 **`androidx.webkit:webkit:1.12.1`**，在 `ControlledEduWebViewScreen`（`ImportScreen.kt`）初始化时通过 `WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())` 从 Chromium 底层网络栈彻底移除默认 `X-Requested-With` 包名头（页面内真实 `XMLHttpRequest`/`jQuery.ajax` 的 `X-Requested-With: XMLHttpRequest` 完全不受影响）。
2. 为极少数未升级系统 WebView（< M110）的老设备提供空值头回退（`loadCleanUrl`）。
3. 清理默认 User-Agent 中的 `; wv` WebView 指纹，避免部分高校教务/统一身份认证 WAF 拒绝内置浏览器访问。

---

## 二、彻底治理「相当一部分学校网站压根打不开」

### 全量公网无代理直连巡检结果
- **79 所置顶教务入口（`portalUrl`）**：原 30 所可达，**49 所公网不可达**（16 所 DNS 废除/已更名、21 所仅限校内网或错误 HTTPS、3 所根路径 404、3 所 403、3 所证书链异常、2 所连接重置、1 所 412）。
- **1,033 所教务处网址（`school_academic_urls.json`）**：原 911 所可达，122 所异常（含 51 所证书异常、35 所 HTTPS 错误、8 所 DNS 失效及含硬编码 Session 串的死链）。

### 净化与页内救场改造
1. **双端高校目录升级（`moe-mainland-2026.4`）**：
   - 将 **30 所**失败置顶高校更新为公网实测 HTTP 200 OK 可达的官方入口（含华东师范大学树维 EAMS `/eams/home.action`、武汉理工大学正方 `http://jw.whut.edu.cn`、中南大学 `http://csujwc.its.csu.edu.cn` 及北理工/中农/北师/南开/吉大/南大/川大/中山/西工大等官方教务处/本科生院）。
   - 彻底清理 **19 所**确认公网不可达/废域名的失效 `portalUrl`，如实回退为「搜索教务处」与文件导入，绝不保留死链。
2. **教务处直达网址净化**：修复 **29 所**错写 `https://` 为实测 200 OK 的 `http://`，剔除 **93 条**死链/坏链，净保留 **949 所** 100% 公网实测 HTTP 200 OK 的高校教务处网址。
3. **受控浏览器页内一键救场**：新增 `onReceivedHttpError` 主框架 4xx/5xx 拦截提示；当页面加载异常、HTTP 错误或 SSL 证书异常时，红色提示条内直接提供 **「重试」「改用 HTTP 尝试」「打开教务处网站」「搜索最新入口」** 一键切换按钮（仍严格禁止绕过 SSL 证书校验）。
