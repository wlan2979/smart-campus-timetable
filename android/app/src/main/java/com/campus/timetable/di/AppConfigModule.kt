package com.campus.timetable.di

import com.campus.timetable.BuildConfig
import com.campus.timetable.core.config.AppRuntimeConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {

    @Provides
    @Singleton
    fun provideRuntimeConfig(): AppRuntimeConfig = AppRuntimeConfig(
        apiBaseUrl = BuildConfig.API_BASE_URL,
        qweatherApiHost = BuildConfig.QWEATHER_API_HOST,
        qweatherApiKey = BuildConfig.QWEATHER_API_KEY,
        holidayRemoteUrlTemplate = BuildConfig.HOLIDAY_REMOTE_URL_TEMPLATE,
        schoolCatalogRemoteUrl = BuildConfig.SCHOOL_CATALOG_REMOTE_URL,
        appVersion = BuildConfig.VERSION_NAME
    )
}
