package com.athletedata.openAthleteMetrics.di

import android.companion.CompanionDeviceManager
import android.content.Context
import com.athletedata.openAthleteMetrics.ble.companion.CompanionDeviceAssociator
import com.athletedata.openAthleteMetrics.ble.companion.SystemCompanionDeviceAssociator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CompanionModule {

    @Binds
    @Singleton
    abstract fun bindCompanionDeviceAssociator(impl: SystemCompanionDeviceAssociator): CompanionDeviceAssociator

    companion object {
        @Provides
        @Singleton
        fun provideCompanionDeviceManager(
            @ApplicationContext context: Context,
        ): CompanionDeviceManager = context.getSystemService(CompanionDeviceManager::class.java)
    }
}
