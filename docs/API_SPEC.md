# 后端 RESTful API 接口文档（v2.2.0）

- **基础路径**：`/api/v1`
- **认证方式**：`Authorization: Bearer <accessToken>`（JWT HS256）
- **传输要求**：生产环境必须通过 HTTPS（TLS 1.2+）传输；严禁向服务端提交任何教务系统账号或密码。

---

## 1. 认证与账号接口

### 1.1 发送手机短信验证码
- **Endpoint**：`POST /api/v1/auth/sms/send`
- **鉴权**：公开（带 60 秒频控）
- **请求体**：
```json
{ "phone": "13800138000" }
```
- **成功响应 (200)**：
```json
{
  "success": true,
  "message": "验证码发送结果说明",
  "expiresInSeconds": 300
}
```
- **说明**：服务端对 6 位随机验证码做 SHA-256 哈希后存库，5 分钟过期且一次性作废。生产环境强制禁用 `debug_log` 通道。

### 1.2 手机号验证码登录 / 注册
- **Endpoint**：`POST /api/v1/auth/sms/verify`
- **鉴权**：公开
- **请求体**：
```json
{ "phone": "13800138000", "code": "123456" }
```
- **成功响应 (200)**：
```json
{
  "userId": "u-xxxx",
  "phone": "13800138000",
  "nickname": "同学8000",
  "accessToken": "<JWT>",
  "refreshToken": "<JWT>",
  "syncVersion": 0
}
```

### 1.3 刷新令牌
- **Endpoint**：`POST /api/v1/auth/refresh`
- **请求体**：`{ "refreshToken": "<JWT>" }`

### 1.4 注销账号并彻底删除云端个人数据
- **Endpoint**：`DELETE /api/v1/account`
- **鉴权**：需登录（Bearer Token）
- **行为**：在单个数据库事务内彻底删除 `user_sync_snapshots`、`sync_audit_logs`、`sms_verification_codes` 与 `users` 记录。
- **响应 (200)**：`{ "deleted": true, "message": "账号及所有云端个人课表数据已彻底删除" }`

---

## 2. 云端同步与游客数据迁移接口

### 2.1 拉取云端课表快照
- **Endpoint**：`GET /api/v1/sync/pull`
- **鉴权**：需登录
- **响应 (200)**：
```json
{
  "hasSnapshot": true,
  "syncVersion": 3,
  "updatedAt": 1764160000000,
  "bundle": {
    "schemaVersion": 2,
    "appVersion": "2.0.0",
    "exportedAt": 1764160000000,
    "semesters": [],
    "classPeriods": [],
    "bellTemplates": [],
    "courses": [],
    "schedules": [],
    "exceptions": [],
    "schoolOverrides": [],
    "alarmSettings": [],
    "weatherSetting": {}
  }
}
```

### 2.2 推送同步（含乐观锁冲突检测）
- **Endpoint**：`POST /api/v1/sync/push`
- **鉴权**：需登录
- **请求体**：
```json
{
  "deviceId": "android-device-uuid",
  "baseVersion": 3,
  "bundle": { "schemaVersion": 2, "...": "..." }
}
```
- **响应 (200)**：
  - 正常：`status = "SUCCESS"`，`syncVersion = 4`
  - 冲突（`baseVersion < serverVersion`）：`status = "CONFLICT"`，附带最新 `serverBundle` 供客户端执行 `BackupMergeEngine` 合并后再重传。
- **隐私校验**：若请求体包含 `eduPassword` 或 `academicPassword`，服务端直接返回 `400 BAD_REQUEST` 拒绝落库。

### 2.3 游客数据迁移合并至正式账号
- **Endpoint**：`POST /api/v1/sync/merge-guest`
- **鉴权**：需登录
- **行为**：客户端在登录前已使用本地游客数据与云端数据执行 `BackupMergeEngine.merge`，通过本接口提交合并结果并递增版本号、写入 `MERGE` 审计日志。

---

## 3. 法定节假日、学校目录与天气代理接口

### 3.1 获取年度国务院法定节假日安排
- **Endpoint**：`GET /api/v1/holidays/{year}?refresh=false`
- **鉴权**：公开
- **响应 (200)**：
```json
{
  "year": 2026,
  "published": true,
  "source": "国务院办公厅通知 (...)",
  "version": "2026-cnt31",
  "updatedAt": 1764160000000,
  "notice": "已同步 2026年国务院办公厅节假日安排",
  "holidays": [
    {
      "id": "holiday_2026-10-01",
      "holidayYear": 2026,
      "holidayDate": "2026-10-01",
      "name": "国庆节",
      "holidayType": "LEGAL_HOLIDAY",
      "offDay": true
    }
  ]
}
```
- 若该年度尚未公布，返回 `"published": false, "holidays": [], "notice": "...尚未公布..."`，绝不虚构数据。
- 服务端按国务院原始数据返回逐日记录；**客户端使用 `HolidayMergeEngine` 自动把连续同名额假期合并为一条区间事件（`endDate` / `totalDays`），一个连续假期只生成一条节前提醒**。

### 3.2 查询高校适配器目录
- **Endpoint**：`GET /api/v1/schools?q=关键词`
- **鉴权**：公开
- **说明**：仅经真实校园环境验证通过的学校 `verified` 为 `true`；当前种子数据全部为 `false`。

### 3.3 下发全国高校目录（v2 新增）
- **Endpoint**：`GET /api/v1/schools/catalog`
- **鉴权**：公开
- **响应 (200)**：`application/json; charset=UTF-8`，内容为随 JAR 打包的 `schools_catalog_v2.json`：
```json
{
  "catalogVersion": "moe-mainland-2026.4",
  "source": "教育部《全国普通高等学校名单》+ 桃课适配配置层",
  "updatedAt": 1764160000000,
  "totalCount": 2741,
  "schools": [
    {
      "id": "moe_4111010001",
      "code": "4111010001",
      "name": "北京大学",
      "shortName": "北大",
      "aliases": "PKU,Beida",
      "pinyin": "beijingdaxue",
      "pinyinInitials": "bjdx",
      "province": "北京市",
      "city": "北京市",
      "schoolLevel": "UNDERGRADUATE",
      "systemType": "CUSTOM",
      "portalUrl": "https://portal.pku.edu.cn",
      "supportStatus": "ASSISTED_LOGIN",
      "verified": false,
      "lastVerifiedAt": null,
      "statusNote": "..."
    }
  ]
}
```
- **客户端行为**：校验响应非空后整体替换本地 `schools` 表并返回统计；失败时保留本地 2741 所离线目录。

### 3.4 提交学校适配申请（v2 新增）
- **Endpoint**：`POST /api/v1/schools/adapt-requests`
- **鉴权**：公开（但必须 `userAuthorized = true`）
- **请求体**：
```json
{
  "schoolName": "某职业技术学院",
  "eduSystemType": "ZHENGFANG",
  "portalUrl": "https://jw.example.edu.cn",
  "pageSummary": "正方教务 2024 版课表页结构说明",
  "userAuthorized": true
}
```
- **成功响应 (202 Accepted)**：
```json
{ "id": "adapt_xxx", "status": "SUBMITTED", "message": "适配申请已受理..." }
```
- **拒绝 (400)**：未授权、学校名称为空，或 `portalUrl`/`pageSummary` 中出现 `password=`/`passwd=` 等密码参数。服务端重新生成 id 与状态，绝不信任客户端提交的 id/status。

### 3.5 和风天气服务端代理（可选）
- **Endpoint**：`GET /api/v1/weather/proxy?lat=39.90&lon=116.40`
- **鉴权**：需登录
- **说明**：由服务端持有 `QWEATHER_API_KEY` 请求上游和风天气，避免密钥暴露在 APK 客户端中；未配置时返回 `503 SERVICE_UNAVAILABLE` 及明确配置指引。
