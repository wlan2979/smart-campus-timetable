package com.campus.timetable.core.config

/**
 * 运行时外部服务配置，由 :app 模块从 BuildConfig（即 local.properties / 环境变量）注入，
 * 绝不将第三方密钥硬编码在源码中。
 */
data class AppRuntimeConfig(
    val apiBaseUrl: String,
    val qweatherApiHost: String,
    val qweatherApiKey: String,
    val holidayRemoteUrlTemplate: String,
    val schoolCatalogRemoteUrl: String = "",
    val appVersion: String
)
