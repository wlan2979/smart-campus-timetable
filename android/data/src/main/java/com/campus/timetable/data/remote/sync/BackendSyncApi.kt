package com.campus.timetable.data.remote.sync

import com.campus.timetable.domain.model.BackupBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SmsSendRequestDto(val phone: String)

@Serializable
data class SmsSendResponseDto(
    val success: Boolean,
    val message: String,
    val expiresInSeconds: Int = 300
)

@Serializable
data class SmsVerifyRequestDto(val phone: String, val code: String)

@Serializable
data class AuthTokenResponseDto(
    val userId: String,
    val phone: String,
    val nickname: String,
    val accessToken: String,
    val refreshToken: String,
    val syncVersion: Long = 0
)

@Serializable
data class SyncPushRequestDto(
    val deviceId: String,
    val baseVersion: Long,
    val bundle: BackupBundle
)

@Serializable
data class SyncPushResponseDto(
    val status: String,
    val syncVersion: Long,
    val serverUpdatedAt: Long,
    val serverBundle: BackupBundle? = null,
    val message: String = ""
)

@Serializable
data class SyncPullResponseDto(
    val hasSnapshot: Boolean,
    val syncVersion: Long,
    val updatedAt: Long,
    val bundle: BackupBundle? = null
)

@Serializable
data class RefreshTokenRequestDto(val refreshToken: String)

@Serializable
data class RefreshTokenResponseDto(
    val accessToken: String,
    val refreshToken: String
)

@Serializable
data class ApiErrorDto(
    val code: String = "ERROR",
    val message: String = "请求失败"
)

class HttpStatusException(val statusCode: Int, override val message: String) : IllegalStateException(message)

/**
 * 面向自建 Spring Boot 后端的真实 HTTP 客户端，支持动态服务端地址切换（HTTPS 生产或本地调试服务）。
 */
@Singleton
class BackendSyncClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    suspend fun sendSmsCode(baseUrl: String, phone: String): Result<SmsSendResponseDto> =
        postJson(
            url = "${normalizeBaseUrl(baseUrl)}/api/v1/auth/sms/send",
            bodyJson = json.encodeToString(SmsSendRequestDto(phone)),
            token = null
        )

    suspend fun verifySmsCode(baseUrl: String, phone: String, code: String): Result<AuthTokenResponseDto> =
        postJson(
            url = "${normalizeBaseUrl(baseUrl)}/api/v1/auth/sms/verify",
            bodyJson = json.encodeToString(SmsVerifyRequestDto(phone, code)),
            token = null
        )

    suspend fun refreshToken(baseUrl: String, refreshToken: String): Result<RefreshTokenResponseDto> =
        postJson(
            url = "${normalizeBaseUrl(baseUrl)}/api/v1/auth/refresh",
            bodyJson = json.encodeToString(RefreshTokenRequestDto(refreshToken)),
            token = null
        )

    suspend fun pushSync(baseUrl: String, token: String, req: SyncPushRequestDto): Result<SyncPushResponseDto> =
        postJson(
            url = "${normalizeBaseUrl(baseUrl)}/api/v1/sync/push",
            bodyJson = json.encodeToString(req),
            token = token
        )

    suspend fun mergeGuest(baseUrl: String, token: String, req: SyncPushRequestDto): Result<SyncPushResponseDto> =
        postJson(
            url = "${normalizeBaseUrl(baseUrl)}/api/v1/sync/merge-guest",
            bodyJson = json.encodeToString(req),
            token = token
        )

    suspend fun pullSync(baseUrl: String, token: String): Result<SyncPullResponseDto> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${normalizeBaseUrl(baseUrl)}/api/v1/sync/pull")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = runCatching { json.decodeFromString<ApiErrorDto>(text).message }.getOrNull()
                    throw HttpStatusException(resp.code, errMsg ?: "拉取云端数据失败（HTTP ${resp.code}）")
                }
                json.decodeFromString<SyncPullResponseDto>(text)
            }
        }
    }

    suspend fun deleteAccount(baseUrl: String, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${normalizeBaseUrl(baseUrl)}/api/v1/account")
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    val errMsg = runCatching { json.decodeFromString<ApiErrorDto>(text).message }.getOrNull()
                    throw HttpStatusException(resp.code, errMsg ?: "注销云端账号失败（HTTP ${resp.code}）")
                }
            }
        }
    }

    private suspend inline fun <reified T> postJson(
        url: String,
        bodyJson: String,
        token: String?
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMediaType))
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
            okHttpClient.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = runCatching { json.decodeFromString<ApiErrorDto>(text).message }.getOrNull()
                    throw HttpStatusException(resp.code, errMsg ?: "服务端返回 HTTP ${resp.code}")
                }
                json.decodeFromString<T>(text)
            }
        }
    }
}
