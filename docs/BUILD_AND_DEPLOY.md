# 编译、运行、安装与后端部署指南（v2.2.0）

> 当前交付：Android `versionCode 4` / `versionName 2.2.0`（Debug 包 `2.2.0-debug`，应用名「桃课」），后端 `2.2.0`。

---

## 一、Android 客户端编译与运行

### 环境要求
- **JDK**：Eclipse Temurin / OpenJDK 17（`JAVA_HOME` 指向 JDK 17）
- **Android SDK**：Compile/Target SDK 35，Build-Tools 35.0.0，Min SDK 26（Android 8.0）
- **Gradle**：内置 Gradle Wrapper 8.11.1（`./gradlew` / `gradlew.bat`）

### 可选构建配置（`android/local.properties`）
```properties
sdk.dir=D\:\\antigravity\\.toolchain\\android-sdk
# 可选：指向自建后端云同步服务地址（模拟器访问本机后端为 http://10.0.2.2:8080/，真机请填 HTTPS 域名或局域网 IP）
TIMETABLE_API_BASE_URL=http://10.0.2.2:8080/
# 可选：如切换至和风天气 QWeather，填写您的专属 Host 与 Key（留空则默认使用免密钥真实 Open-Meteo 气象）
QWEATHER_API_HOST=
QWEATHER_API_KEY=
# 可选：高校目录远程更新地址（留空则使用自建后端 /api/v1/schools/catalog）
SCHOOL_CATALOG_REMOTE_URL=
```

### 构建可安装的 Debug APK
```powershell
cd D:\antigravity\smart-campus-timetable\android
.\gradlew.bat :app:assembleDebug
```
生成的 APK 路径：
`android/app/build/outputs/apk/debug/app-debug.apk`

### 运行全量 Android 自动化测试
```powershell
cd D:\antigravity\smart-campus-timetable\android
.\gradlew.bat testDebugUnitTest :domain:test
```

---

## 二、APK 安装到真机或模拟器说明

### 方式 1：通过 ADB 命令行安装（推荐）
1. 在 Android 手机（Android 8.0+）开启「开发者选项 → USB 调试」并用数据线连接电脑。
2. 执行安装：
   ```powershell
   adb devices
   adb install -r D:\antigravity\smart-campus-timetable\android\app\build\outputs\apk\debug\app-debug.apk
   ```

### 方式 2：直接传输安装
1. 将 `app-debug.apk` 复制到手机存储目录。
2. 在手机文件管理器点击 APK，允许安装未知来源应用后完成安装。

### 首次运行建议授权项（按需申请，绝不强制）
- **游客模式**：安装后无需登录即可直接使用课表创建、导入、闹钟、天气、节假日与 4 种桌面小组件。
- **Android 13+ 通知权限**与 **Android 12+ 精确闹钟权限**：在「提醒」页按引导开启；未授予精确闹钟权限时应用将自动降级为近似定时提醒并提示。
- **位置权限**：仅在「天气」页主动点击「自动定位」时申请，若拒绝仍可手动选择或搜索全国城市。

---

## 三、Spring Boot 后端服务部署（Docker Compose / 本地）

### 方式 1：Docker Compose 一键部署（PostgreSQL 16 + 后端服务）
1. 复制环境变量模板并修改真实密码与密钥：
   ```powershell
   cd D:\antigravity\smart-campus-timetable
   copy .env.example .env
   ```
2. 编辑 `.env`：
   - 必填：`POSTGRES_PASSWORD`、`JWT_SECRET`（至少 32 字节高熵随机字符串）
   - 生产模式（`SPRING_PROFILES_ACTIVE=prod`）必须配置真实短信服务商（`SMS_PROVIDER=aliyun` 及阿里云 SMS 参数）；开发调试可设为 `SPRING_PROFILES_ACTIVE=dev` 与 `SMS_PROVIDER=debug_log`。
3. 启动服务：
   ```powershell
   cd D:\antigravity\smart-campus-timetable\backend
   docker compose --env-file ..\.env up -d --build
   # 若需同时启用 Redis：
   docker compose --env-file ..\.env --profile with-redis up -d --build
   ```
4. 验证健康检查：
   ```powershell
   curl http://localhost:8080/actuator/health
   ```
   Flyway 会在首次启动时自动执行 `V1__init_schema.sql`、`V2__seed_school_catalog.sql` 与 `V3__school_adaptation_requests.sql` 初始化 PostgreSQL 表结构。
5. 验证高校目录下发接口：
   ```powershell
   curl http://localhost:8080/api/v1/schools/catalog
   ```

### 方式 2：本地运行与后端测试
```powershell
cd D:\antigravity\smart-campus-timetable\backend
.\gradlew.bat test
.\gradlew.bat bootJar
# 产物：build\libs\smart-campus-timetable-backend-2.2.0.jar
```

### 数据库版本说明
- Android Room：`version = 2`，首次升级自动执行 `MIGRATION_1_2`（节假日合并字段、作息模板表、学校适配申请表、学校目录扩展字段），无需清空数据；该迁移已由 `CampusDatabaseMigrationTest` 按 v1 Schema 建库实测校验。
- 后端 Flyway：`V1` → `V2` → `V3`，升级时自动增量执行。
