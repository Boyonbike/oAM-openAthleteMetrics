package com.athletedata.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.athletedata.app.data.repository.DataStoreSettingsRepository
import com.athletedata.app.data.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides the DataStore instance and binds [SettingsRepository]
 * to its DataStore-backed implementation.
 *
 * Separated from [DatabaseModule] so DataStore and Room concerns don't mix.
 * Installed in [SingletonComponent] so the same DataStore file is used throughout
 * the app's lifetime.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataStoreModule {

    /**
     * Binds [SettingsRepository] → [DataStoreSettingsRepository].
     * Injected into the Settings screen ViewModel and the root theme composable.
     */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    companion object {

        /**
         * Provides the [DataStore]<[Preferences]> singleton backed by the
         * "settings.preferences_pb" file in the app's data directory.
         *
         * Using [PreferenceDataStoreFactory] rather than the `by dataStore`
         * property delegate because Hilt needs an explicit provider function.
         * Injected into [DataStoreSettingsRepository].
         */
        @Provides
        @Singleton
        fun provideDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("settings")
        }
    }
}
