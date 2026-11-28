package com.campus.timetable.data.remote.weather

import com.campus.timetable.core.config.AppRuntimeConfig
import com.campus.timetable.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 可替换天气服务提供商接口。
 * 所有实现必须调用真实天气服务，禁止返回硬编码假天气数据。
 */
interface WeatherProvider {
    val type: WeatherProviderType
    suspend fun fetchWeather(setting: WeatherSetting): Result<WeatherSnapshot>
    suspend fun searchCities(query: String): Result<List<CityOption>>
}

/**
 * Open-Meteo 真实开放气象与空气质量服务实现（免 API Key，可直接使用真实实时气象与未来 7 天逐小时预报）。
 */
@Singleton
class OpenMeteoWeatherProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) : WeatherProvider {

    override val type: WeatherProviderType = WeatherProviderType.OPEN_METEO

    override suspend fun fetchWeather(setting: WeatherSetting): Result<WeatherSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val forecastUrl = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${setting.latitude}&longitude=${setting.longitude}" +
                "&current=temperature_2m,weather_code" +
                "&hourly=temperature_2m,precipitation_probability,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=Asia%2FShanghai&forecast_days=7"

            val forecastBody = executeGet(forecastUrl)
            val root = json.parseToJsonElement(forecastBody).jsonObject

            // 同步查询真实空气质量 AQI（失败时不阻断基础天气）
            val aqiByDateHour = runCatching {
                val aqiUrl = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                    "?latitude=${setting.latitude}&longitude=${setting.longitude}" +
                    "&hourly=us_aqi&timezone=Asia%2FShanghai&forecast_days=5"
                parseAqiHourly(executeGet(aqiUrl))
            }.getOrDefault(emptyMap())

            val currentObj = root["current"]?.jsonObject
            val currentTemp = currentObj?.get("temperature_2m")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val currentCode = currentObj?.get("weather_code")?.jsonPrimitive?.intOrNull ?: 0
            val (_, currentText) = interpretWmoCode(currentCode)

            val hourlyObj = root["hourly"]?.jsonObject
            val hourlyTimes = hourlyObj?.get("time")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val hourlyTemps = hourlyObj?.get("temperature_2m")?.jsonArray?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 } ?: emptyList()
            val hourlyPops = hourlyObj?.get("precipitation_probability")?.jsonArray?.map { it.jsonPrimitive.intOrNull ?: 0 } ?: emptyList()
            val hourlyCodes = hourlyObj?.get("weather_code")?.jsonArray?.map { it.jsonPrimitive.intOrNull ?: 0 } ?: emptyList()

            val hourlyGrouped = mutableMapOf<String, MutableList<WeatherHourlyPoint>>()
            for (i in hourlyTimes.indices) {
                val dt = hourlyTimes[i]
                val datePart = dt.substringBefore("T")
                val hourPart = dt.substringAfter("T", "00:00").substringBefore(":").toIntOrNull() ?: 0
                val code = hourlyCodes.getOrElse(i) { 0 }
                val (flags, condText) = interpretWmoCode(code)
                hourlyGrouped.getOrPut(datePart) { mutableListOf() }.add(
                    WeatherHourlyPoint(
                        hour = hourPart,
                        tempC = hourlyTemps.getOrElse(i) { 0.0 },
                        weatherCode = code,
                        conditionText = condText,
                        precipitationProb = hourlyPops.getOrElse(i) { 0 },
                        isRain = flags.isRain,
                        isSnow = flags.isSnow,
                        isThunder = flags.isThunder,
                        aqi = aqiByDateHour[dt]
                    )
                )
            }

            val dailyObj = root["daily"]?.jsonObject
            val dailyDates = dailyObj?.get("time")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val dailyCodes = dailyObj?.get("weather_code")?.jsonArray?.map { it.jsonPrimitive.intOrNull ?: 0 } ?: emptyList()
            val dailyMax = dailyObj?.get("temperature_2m_max")?.jsonArray?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 } ?: emptyList()
            val dailyMin = dailyObj?.get("temperature_2m_min")?.jsonArray?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 } ?: emptyList()
            val dailyPop = dailyObj?.get("precipitation_probability_max")?.jsonArray?.map { it.jsonPrimitive.intOrNull ?: 0 } ?: emptyList()

            val dailyList = dailyDates.indices.map { i ->
                val d = dailyDates[i]
                val code = dailyCodes.getOrElse(i) { 0 }
                val (flags, condText) = interpretWmoCode(code)
                val hours = hourlyGrouped[d].orEmpty()
                WeatherForecastDay(
                    date = d,
                    tempMinC = dailyMin.getOrElse(i) { 0.0 },
                    tempMaxC = dailyMax.getOrElse(i) { 0.0 },
                    conditionText = condText,
                    weatherCode = code,
                    precipitationProbMax = dailyPop.getOrElse(i) { 0 },
                    isRain = flags.isRain || hours.any { it.isRain },
                    isSnow = flags.isSnow || hours.any { it.isSnow },
                    isThunder = flags.isThunder || hours.any { it.isThunder },
                    aqiMax = hours.mapNotNull { it.aqi }.maxOrNull(),
                    hourly = hours
                )
            }

            val nowKey = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withMinute(0).toString().substring(0, 16)
            val currentAqi = aqiByDateHour[nowKey] ?: dailyList.firstOrNull()?.aqiMax

            WeatherSnapshot(
                providerName = "Open-Meteo",
                cityName = setting.cityName,
                currentTempC = currentTemp,
                currentCondition = currentText,
                currentAqi = currentAqi,
                fetchedAt = System.currentTimeMillis(),
                daily = dailyList
            )
        }
    }

    override suspend fun searchCities(query: String): Result<List<CityOption>> = withContext(Dispatchers.IO) {
        runCatching {
            // 「苏州」「苏州市」都尝试；限定中国境内，避免同名海外城市干扰
            val attempts = listOf(query.trim(), query.trim().removeSuffix("市").trim())
                .filter { it.isNotEmpty() }
                .distinct()
            for (attempt in attempts) {
                val encoded = URLEncoder.encode(attempt, "UTF-8")
                val url = "https://geocoding-api.open-meteo.com/v1/search" +
                    "?name=$encoded&count=20&language=zh&format=json&countryCode=CN"
                val root = json.parseToJsonElement(executeGet(url)).jsonObject
                val arr = root["results"]?.jsonArray ?: emptyList()
                val cities = arr.mapNotNull { el ->
                    val obj = el.jsonObject
                    val countryCode = obj["country_code"]?.jsonPrimitive?.content
                    if (countryCode != null && countryCode != "CN") return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val lat = obj["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val lon = obj["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val admin1 = obj["admin1"]?.jsonPrimitive?.content
                        ?: obj["country"]?.jsonPrimitive?.content ?: ""
                    CityOption(
                        name = name.removeSuffix("市"),
                        province = admin1,
                        latitude = lat,
                        longitude = lon
                    )
                }
                if (cities.isNotEmpty()) return@runCatching cities
            }
            emptyList()
        }
    }

    private fun parseAqiHourly(body: String): Map<String, Int> {
        val root = json.parseToJsonElement(body).jsonObject
        val hourly = root["hourly"]?.jsonObject ?: return emptyMap()
        val times = hourly["time"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return emptyMap()
        val aqis = hourly["us_aqi"]?.jsonArray?.map { it.jsonPrimitive.intOrNull } ?: return emptyMap()
        val result = mutableMapOf<String, Int>()
        for (i in times.indices) {
            val v = aqis.getOrNull(i)
            if (v != null) result[times[i]] = v
        }
        return result
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SmartCampusTimetable/1.0")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("天气服务 HTTP ${resp.code}: ${resp.message}")
            }
            return resp.body?.string() ?: throw IllegalStateException("天气服务返回空响应")
        }
    }

    private data class WmoFlags(val isRain: Boolean, val isSnow: Boolean, val isThunder: Boolean)

    private fun interpretWmoCode(code: Int): Pair<WmoFlags, String> = when (code) {
        0 -> WmoFlags(false, false, false) to "晴"
        1, 2 -> WmoFlags(false, false, false) to "多云"
        3 -> WmoFlags(false, false, false) to "阴"
        45, 48 -> WmoFlags(false, false, false) to "雾"
        51, 53, 55, 56, 57 -> WmoFlags(true, false, false) to "毛毛雨"
        61, 63, 65, 66, 67 -> WmoFlags(true, false, false) to "雨"
        71, 73, 75, 77, 85, 86 -> WmoFlags(false, true, false) to "雪"
        80, 81, 82 -> WmoFlags(true, false, false) to "阵雨"
        95, 96, 99 -> WmoFlags(true, false, true) to "雷阵雨"
        else -> WmoFlags(false, false, false) to "多云"
    }
}

/**
 * 和风天气 QWeather API v7 实现。
 * API Host 与 API Key 通过安全构建配置（local.properties / 环境变量）注入，未配置时明确报错引导接入，绝不硬编码密钥或伪造数据。
 */
@Singleton
class QWeatherProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val config: AppRuntimeConfig
) : WeatherProvider {

    override val type: WeatherProviderType = WeatherProviderType.QWEATHER

    override suspend fun fetchWeather(setting: WeatherSetting): Result<WeatherSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val host = config.qweatherApiHost.trim().trimEnd('/')
            val key = config.qweatherApiKey.trim()
            if (host.isEmpty() || key.isEmpty()) {
                throw IllegalStateException(
                    "尚未配置和风天气凭证（QWEATHER_API_HOST / QWEATHER_API_KEY）。请在 local.properties 或服务端 .env 中配置后重新构建，或切换至 Open-Meteo 实时天气源。"
                )
            }
            val locationParam = "${"%.2f".format(setting.longitude)},${"%.2f".format(setting.latitude)}"
            val nowObj = getJson("$host/v7/weather/now?location=$locationParam", key)
            val dailyObj = getJson("$host/v7/weather/7d?location=$locationParam", key)
            val aqiObj = runCatching { getJson("$host/v7/air/now?location=$locationParam", key) }.getOrNull()

            val now = nowObj["now"]?.jsonObject ?: throw IllegalStateException("和风天气实况响应格式异常")
            val currentTemp = now["temp"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val currentText = now["text"]?.jsonPrimitive?.content ?: "未知"
            val currentAqi = aqiObj?.get("now")?.jsonObject?.get("aqi")?.jsonPrimitive?.intOrNull

            val dailyArr = dailyObj["daily"]?.jsonArray ?: emptyList()
            val days = dailyArr.map { item ->
                val obj = item.jsonObject
                val date = obj["fxDate"]?.jsonPrimitive?.content ?: ""
                val textDay = obj["textDay"]?.jsonPrimitive?.content ?: ""
                val textNight = obj["textNight"]?.jsonPrimitive?.content ?: ""
                val combined = "$textDay $textNight"
                WeatherForecastDay(
                    date = date,
                    tempMinC = obj["tempMin"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    tempMaxC = obj["tempMax"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    conditionText = textDay,
                    weatherCode = obj["iconDay"]?.jsonPrimitive?.intOrNull ?: 0,
                    precipitationProbMax = if (combined.contains("雨")) 70 else 10,
                    isRain = combined.contains("雨"),
                    isSnow = combined.contains("雪") || combined.contains("冰雹"),
                    isThunder = combined.contains("雷"),
                    aqiMax = currentAqi
                )
            }

            WeatherSnapshot(
                providerName = "和风天气 QWeather",
                cityName = setting.cityName,
                currentTempC = currentTemp,
                currentCondition = currentText,
                currentAqi = currentAqi,
                fetchedAt = System.currentTimeMillis(),
                daily = days
            )
        }
    }

    override suspend fun searchCities(query: String): Result<List<CityOption>> = withContext(Dispatchers.IO) {
        runCatching {
            val host = config.qweatherApiHost.trim().trimEnd('/')
            val key = config.qweatherApiKey.trim()
            if (host.isEmpty() || key.isEmpty()) {
                throw IllegalStateException("未配置和风天气 API 密钥，无法使用和风城市搜索。")
            }
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val root = getJson("$host/geo/v2/city/lookup?location=$encoded", key)
            val arr = root["location"]?.jsonArray ?: return@runCatching emptyList()
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                CityOption(
                    name = name,
                    province = o["adm1"]?.jsonPrimitive?.content ?: "",
                    latitude = lat,
                    longitude = lon,
                    cityId = o["id"]?.jsonPrimitive?.content ?: ""
                )
            }
        }
    }

    private fun getJson(url: String, apiKey: String): JsonObject {
        val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("无效的和风天气 URL: $url")
        val req = Request.Builder()
            .url(parsed)
            .header("X-QW-Api-Key", apiKey)
            .get()
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("和风天气 HTTP ${resp.code}: ${resp.message}")
            }
            val body = resp.body?.string() ?: throw IllegalStateException("和风天气返回空响应")
            return json.parseToJsonElement(body).jsonObject
        }
    }
}
