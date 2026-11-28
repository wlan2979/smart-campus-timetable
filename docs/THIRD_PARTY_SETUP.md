# 第三方服务与环境变量配置说明

本项目严格遵循「**严禁硬编码密钥、严禁使用假接口冒充真实服务**」的工程原则。以下是所有外部依赖的配置说明与接入步骤。

---

## 1. 天气服务配置（体育课天气提醒）

**v2.1.0 数据源**：仅支持 ① Open-Meteo（默认开箱可用）→ ② 和风天气 QWeather（需配置）。**已删除手机系统/厂商天气入口与探测代码**，不再提供「打开手机自带天气」按钮，也不读取任何厂商天气应用数据。

- **城市覆盖**：客户端内置 2154 座城市离线速查表（`ChinaCityPresets.kt`：地级市、地区/自治州首府、县级市与县城区，含苏州、涿州、昆山等），输入城市名可即时本地筛选；未命中时可联网调用 Open-Meteo Geocoding（限定 `countryCode=CN`）检索全国城市。
- Open-Meteo 地理编码对部分城市仅收录拼音名，客户端会自动尝试「中文名 → 拼音」并去掉「市」后缀，避免「苏州搜不到」类问题。

应用通过 `WeatherProvider` 可替换接口提供两种真实天气服务：

### 方案 A：Open-Meteo 实时开放气象（默认开箱可用，免 API Key）
- **实时天气与 7 天逐小时预报**：`https://api.open-meteo.com/v1/forecast`
- **实时空气质量 AQI**：`https://air-quality-api.open-meteo.com/v1/air-quality`
- **中文城市地理搜索**：`https://geocoding-api.open-meteo.com/v1/search`
- **特点**：非商业开源使用无需申请密钥，可直接获取真实温度、降雨/降雪/雷电 WMO 气象码与逐小时 AQI。

### 方案 B：和风天气 QWeather API v7（适合生产高并发商业部署）
1. 前往 [和风天气开发服务](https://dev.qweather.com/) 创建项目并获取专属 API Host（例如 `https://xxxx.re.qweatherapi.com`）与 API Key。
2. **推荐方式（服务端代理）**：在根目录 `.env` 中填入：
   ```env
   QWEATHER_API_HOST=https://your-host.re.qweatherapi.com
   QWEATHER_API_KEY=your_real_api_key
   ```
3. **客户端直连方式（仅私有构建）**：在 `android/local.properties`（已被 `.gitignore` 忽略）中填入：
   ```properties
   QWEATHER_API_HOST=https://your-host.re.qweatherapi.com
   QWEATHER_API_KEY=your_real_api_key
   ```
4. 在 App 内进入「天气设置 → 天气数据源」切换至「和风天气 QWeather」。若未填写密钥，系统会明确报错提示缺少配置，绝不返回假天气。

---

## 2. 短信验证码服务配置（手机号登录）

后端通过 `SmsDispatchService` 实现环境隔离：
- **本地开发调试（`SPRING_PROFILES_ACTIVE=dev` + `SMS_PROVIDER=debug_log`）**：每次请求生成密码学安全的 6 位随机验证码，SHA-256 哈希入库，并将明文验证码打印到后端控制台日志（不在 HTTP 接口中泄露）。
- **生产环境（`SPRING_PROFILES_ACTIVE=prod`）**：
  - 后端启动时**强制拦截 `debug_log`**，杜绝任何测试验证码后门。
  - 需配置阿里云短信服务（企业实名认证 + 审核通过的签名与验证码模板）：
    ```env
    SMS_PROVIDER=aliyun
    ALIYUN_SMS_ACCESS_KEY_ID=你的AccessKeyId
    ALIYUN_SMS_ACCESS_KEY_SECRET=你的AccessKeySecret
    ALIYUN_SMS_SIGN_NAME=已审核通过的短信签名
    ALIYUN_SMS_TEMPLATE_CODE=SMS_XXXXXXXXX
    ```
  - 未完成企业短信资质审核前，调用生产短信接口会返回 `503 SERVICE_UNAVAILABLE` 并明确提示所缺配置。

---

## 3. 国务院法定节假日数据源配置

- 默认通过可配置 URL 模板获取同步自国务院办公厅年度放假通知的公开 JSON 数据源：
  ```env
  HOLIDAY_REMOTE_URL_TEMPLATE=https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/{year}.json
  ```
- **v2.2.1 镜像回退**：默认 GitHub raw 地址在部分国内网络不可达（实测 15 秒超时）。客户端在保持默认模板时依次自动尝试同仓库的 jsDelivr CDN 镜像（`cdn.jsdelivr.net`、`fastly.jsdelivr.net`），全部失败才提示网络错误；若配置了自定义 `HOLIDAY_REMOTE_URL_TEMPLATE`，则只使用自定义地址，不追加镜像。
- 国内生产服务器建议将该年度 JSON 托管至自有 OSS / CDN 或由后端定时任务同步至 PostgreSQL，并在 `.env` / `android/local.properties` 中修改 `HOLIDAY_REMOTE_URL_TEMPLATE` 指向自有 HTTPS 地址。
- 若某年份国务院尚未发布通知（HTTP 404），客户端与服务端均会明确提示「尚未公布」，绝不虚构放假安排。

---

## 4. 全国高校目录与教务适配扩展

### 4.1 高校目录与教务处直达地址（v2.2.0 更新）
- **内置离线目录**：`android/data/src/main/assets/schools_catalog_v2.json`（教育部《全国普通高等学校名单》2741 所：本科 1273 / 高职 1468，31 省；含示例树维高校（如清华大学等试点样例））。
- **远程热更新**（任选其一）：
  - 使用自建后端：客户端默认请求 `GET /api/v1/schools/catalog`（随 JAR 打包同一份 JSON，无需额外配置）。
  - 使用静态托管：在 `.env` / `android/local.properties` 配置
    ```properties
    SCHOOL_CATALOG_REMOTE_URL=https://your-cdn.example.com/schools_catalog_v2.json
    ```
- **教务处直达地址**：`android/data/src/main/assets/school_academic_urls.json`（学校代码 → 教务处/教务系统网址，当前 **1033 所**）。仅收录经公开 HTTP 访问校验、页面标题或正文含「教务处/教务」的真实地址；未收录学校自动改为「学校名 + 教务处 + 课表」检索，绝不把学校官网首页冒充教务入口。
- 更新目录只需替换远端 JSON，在 App「导入 → 学校教务 → 检查远程更新」即可生效，**无需重新发版**。
- 目录中每所学校的 `supportStatus` 必须如实标注：`VERIFIED`（已验证）、`GENERIC_ADAPTER`（支持通用教务导入）、`ASSISTED_LOGIN`（需要辅助登录）、`UNSUPPORTED`（暂未适配）、`LAST_VERIFY_FAILED`（最近验证失败）。

### 4.2 适配扩展步骤
1. 新建学校适配器类实现 `:school-adapters` 中的 `SchoolAdapter` 接口（通用层已内置正方、强智、新 URP、旧 URP）。
2. 完成与目标高校真实教务系统的**受控官方 WebView 登录验证**（遵守安全红线：不绕过验证码、不保存教务密码、不抓取非课表个人隐私）。
3. 实测验证通过后，将该校代码加入 `verifiedSchoolCodes`，并把目录中该校 `verified` 置为 `true`、`supportStatus` 置为 `VERIFIED`、填写 `lastVerifiedAt`。
4. 未完成实测的学校统一标记为「暂未适配」，自动引导用户使用 Excel / PDF / 受控网页导入。
5. 用户可在 App 内提交适配申请（必须勾选授权）；服务端 `POST /api/v1/schools/adapt-requests` 受理并双重拦截任何密码参数。
