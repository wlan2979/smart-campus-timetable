package com.campus.timetable.core.di

import com.campus.timetable.core.clock.AppClock
import com.campus.timetable.core.clock.SystemAppClock
import com.campus.timetable.core.common.AppDispatchers
import com.campus.timetable.core.common.DefaultDispatcher
import com.campus.timetable.core.common.IoDispatcher
import com.campus.timetable.core.common.MainDispatcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreBindingsModule {
    @Binds
    @Singleton
    abstract fun bindAppClock(impl: SystemAppClock): AppClock
}

@Module
@InstallIn(SingletonComponent::class)
object CoreProvidersModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @Singleton
    fun provideAppDispatchers(): AppDispatchers = AppDispatchers(
        io = Dispatchers.IO,
        default = Dispatchers.Default,
        main = Dispatchers.Main
    )
}
