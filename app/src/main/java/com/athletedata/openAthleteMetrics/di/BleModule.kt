package com.athletedata.openAthleteMetrics.di

import com.athletedata.openAthleteMetrics.ble.wasm.JsonDriverEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    @Provides
    @Singleton
    fun provideJsonDriverEngine(): JsonDriverEngine = JsonDriverEngine()
}
