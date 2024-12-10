package com.machiav3lli.backup.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.machiav3lli.backup.BACKUP_FILTER_DEFAULT
import com.machiav3lli.backup.EnabledFilter
import com.machiav3lli.backup.InstalledFilter
import com.machiav3lli.backup.LatestFilter
import com.machiav3lli.backup.LaunchableFilter
import com.machiav3lli.backup.MAIN_FILTER_DEFAULT
import com.machiav3lli.backup.Sort
import com.machiav3lli.backup.UpdatedFilter
import com.machiav3lli.backup.batchModesSequence
import com.machiav3lli.backup.possibleMainFilters
import org.koin.core.component.KoinComponent
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.getKoin

class NeoPrefs private constructor(val context: Context) : KoinComponent {
    private val dataStore: DataStore<Preferences> by getKoin().inject()

    val sortHome = PrefInt(
        dataStore = dataStore,
        key = PrefKey.SORT_HOME,
        defaultValue = Sort.LABEL.ordinal,
        entries = Sort.entries.map { it.ordinal },
    )

    val sortAscHome = PrefBoolean(
        dataStore = dataStore,
        key = PrefKey.SORT_ASC_HOME,
        defaultValue = true,
    )

    val mainFilterHome = PrefInt(
        dataStore = dataStore,
        key = PrefKey.MAIN_FILTER_HOME,
        defaultValue = MAIN_FILTER_DEFAULT,
        entries = possibleMainFilters, // not really, but shouldn't have an effect
    )

    val backupFilterHome = PrefInt(
        dataStore = dataStore,
        key = PrefKey.BACKUP_FILTER_HOME,
        defaultValue = BACKUP_FILTER_DEFAULT,
        entries = batchModesSequence, // not really, but shouldn't have an effect
    )

    val installedFilterHome = PrefInt(
        dataStore = dataStore,
        key = PrefKey.INSTALLED_FILTER_HOME,
        defaultValue = InstalledFilter.ALL.ordinal,
        entries = InstalledFilter.entries.map { it.ordinal },
    )

    val launchableFilterHome = PrefInt(
        dataStore = dataStore,
        key = PrefKey.LAUNCHABLE_FILTER_HOME,
        defaultValue = LaunchableFilter.ALL.ordinal,
        entries = LaunchableFilter.entries.map { it.ordinal },
    )

    val updatedFilterHome = PrefInt(
        dataStore = dataStore,
        key = PrefKey.UPDATED_FILTER_HOME,
        defaultValue = UpdatedFilter.ALL.ordinal,
        entries = UpdatedFilter.entries.map { it.ordinal },
    )

    val latestFilterHome = PrefInt(
        dataStore = dataStore,
        key = PrefKey.LATEST_FILTER_HOME,
        defaultValue = LatestFilter.ALL.ordinal,
        entries = LatestFilter.entries.map { it.ordinal },
    )

    val enabledFilterHome = PrefInt(
        dataStore = dataStore,
        key = PrefKey.ENABLED_FILTER_HOME,
        defaultValue = EnabledFilter.ALL.ordinal,
        entries = EnabledFilter.entries.map { it.ordinal },
    )

    companion object {
        val prefsModule = module {
            single { NeoPrefs(get()) }
            single { provideDataStore(get()) }
        }

        private fun provideDataStore(context: Context): DataStore<Preferences> {
            return PreferenceDataStoreFactory.create(
                produceFile = {
                    context.preferencesDataStoreFile("neo_backup")
                },
            )
        }

        fun getInstance(): NeoPrefs = getKoin().get()
    }
}