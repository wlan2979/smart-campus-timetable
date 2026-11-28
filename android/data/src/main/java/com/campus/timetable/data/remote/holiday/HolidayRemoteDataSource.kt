package com.campus.timetable.data.remote.holiday

import com.campus.timetable.core.config.AppRuntimeConfig
import com.campus.timetable.domain.engine.HolidayMergeEngine
import com.campus.timetable.domain.model.HolidayYearStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class HolidayCnPayload(
    val year: Int,
    val papers: List<String> = emptyList(),
    val days: List<HolidayCnDay> = emptyList()
)

@Serializable
private data class HolidayCnDay(
    val name: String,
    val date: String,
    val isOffDay: Boolean
)

/**
 * 国务院办公厅年度节假日安排远程拉取器。
 * 拉取后自动经 HolidayMergeEngine 将连续多天假期合并为单条完整假期区间（如春节 7 天合并为 1 条事件），
 * 绝不按天拆成多条独立节日；若目标年份未公布（404）明确提示而不虚构。
 *
 * 数据源为 NateScarlet/holiday-cn 的公开 JSON（中国政府网通知同步）。该仓库托管在 GitHub，
 * 国内网络访问 raw.githubusercontent.com 常被阻断，因此在默认模板失败时依次尝试 jsDelivr CDN
 * 镜像（同一仓库同一文件），全部失败才如实报告网络错误。
 */
@Singleton
class HolidayRemoteDataSource @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val config: AppRuntimeConfig
) {
    private sealed interface Attempt {
        data class Done(val status: HolidayYearStatus) : Attempt
        data class Retry(val status: HolidayYearStatus) : Attempt
    }

    suspend fun fetchYearHolidays(
        year: Int,
        reminderAdvanceDays: Int = 1,
        customUrlTemplate: String? = null
    ): HolidayYearStatus = withContext(Dispatchers.IO) {
        val template = (customUrlTemplate?.takeIf { it.isNotBlank() } ?: config.holidayRemoteUrlTemplate)
        val urls = candidateUrls(template, year)
        var lastFailure: HolidayYearStatus? = null
        for (url in urls) {
            when (val attempt = fetchFrom(url, year, reminderAdvanceDays)) {
                is Attempt.Done -> return@withContext attempt.status
                is Attempt.Retry -> lastFailure = attempt.status
            }
        }
        lastFailure ?: HolidayYearStatus(
            year = year,
            isPublished = false,
            source = urls.firstOrNull().orEmpty(),
            version = "network_error",
            updatedAt = null,
            holidays = emptyList(),
            notice = "节假日数据源未配置，无法拉取${year}年安排。"
        )
    }

    /** 默认 GitHub raw 数据源在国内可能不可达，追加同仓库的 jsDelivr CDN 镜像作为后备。 */
    private fun candidateUrls(template: String, year: Int): List<String> {
        val primary = template.replace("{year}", year.toString())
        if (!template.contains("raw.githubusercontent.com/NateScarlet/holiday-cn")) {
            return listOf(primary)
        }
        return listOf(
            primary,
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json",
            "https://fastly.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json"
        ).distinct()
    }

    private fun fetchFrom(url: String, year: Int, reminderAdvanceDays: Int): Attempt {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "TaokeTimetable/2.0")
            .get()
            .build()
        return try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    return Attempt.Done(
                        HolidayYearStatus(
                            year = year,
                            isPublished = false,
                            source = url,
                            version = "unpublished",
                            updatedAt = null,
                            holidays = emptyList(),
                            notice = "${year}年国务院办公厅法定节假日安排尚未公布，系统不会虚构放假数据；您仍可通过学校校历手动配置。"
                        )
                    )
                }
                if (!resp.isSuccessful) {
                    return Attempt.Retry(
                        HolidayYearStatus(
                            year = year,
                            isPublished = false,
                            source = url,
                            version = "error_${resp.code}",
                            updatedAt = null,
                            holidays = emptyList(),
                            notice = "拉取${year}年节假日数据失败（HTTP ${resp.code}），请稍后重试或检查网络连接。"
                        )
                    )
                }
                val body = resp.body?.string().orEmpty()
                val payload = json.decodeFromString<HolidayCnPayload>(body)
                val now = System.currentTimeMillis()
                val sourceLabel = payload.papers.firstOrNull()?.let { "国务院办公厅通知 ($it)" }
                    ?: "国务院节假日公开数据源 ($url)"
                val versionTag = "${payload.year}-merged-raw${payload.days.size}"

                val mergedHolidays = HolidayMergeEngine.mergeDailyRecords(
                    year = payload.year,
                    rawDays = payload.days.map {
                        HolidayMergeEngine.RawHolidayDay(it.date, it.name, it.isOffDay)
                    },
                    source = sourceLabel,
                    version = versionTag,
                    reminderAdvanceDays = reminderAdvanceDays,
                    updatedAt = now
                )
                val offCount = mergedHolidays.count { it.isOffDay }
                val workCount = mergedHolidays.count { !it.isOffDay }

                Attempt.Done(
                    HolidayYearStatus(
                        year = year,
                        isPublished = mergedHolidays.isNotEmpty(),
                        source = sourceLabel,
                        version = versionTag,
                        updatedAt = now,
                        holidays = mergedHolidays,
                        notice = if (mergedHolidays.isNotEmpty()) {
                            "已同步 ${year}年国务院安排：合并为 $offCount 个完整法定假期区间、$workCount 个调休工作日条目"
                        } else {
                            "${year}年节假日数据为空，请确认国务院是否已发布正式通知。"
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Attempt.Retry(
                HolidayYearStatus(
                    year = year,
                    isPublished = false,
                    source = url,
                    version = "network_error",
                    updatedAt = null,
                    holidays = emptyList(),
                    notice = "无法连接节假日数据源：${e.localizedMessage ?: "网络异常"}。已保留本地缓存与学校校历。"
                )
            )
        }
    }
}
