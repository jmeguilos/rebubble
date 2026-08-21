package app.rebubble.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [ThemeSettingsRepository.appColorMode] round-trips through the same kind of Preferences
 * DataStore [ServerConfigRepository] uses ("server_config"): unset defaults to
 * [AppColorMode.REBUBBLE], [ThemeSettingsRepository.setAppColorMode] persists across a fresh
 * repository instance pointed at the same file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ThemeSettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tempFolder.newFolder(), "server_config.preferences_pb")
    }

    private fun newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { file })

    @Test
    fun `defaults to Rebubble when unset`() = runBlocking {
        val repository = ThemeSettingsRepository(newDataStore())

        assertEquals(AppColorMode.REBUBBLE, repository.appColorMode.first())
    }

    @Test
    fun `setAppColorMode persists and a fresh repository instance on the same DataStore sees it`() =
        runBlocking {
            // Same underlying DataStore<Preferences> instance handed to two repositories, simulating
            // a process restart -- DataStore forbids two *DataStore* instances backed by the same
            // file at once, so the "restart" here is a fresh ThemeSettingsRepository, not a fresh
            // DataStore (mirrors ServerConfigRepositoryTest's "repository restart" test).
            val dataStore = newDataStore()
            val repository = ThemeSettingsRepository(dataStore)

            repository.setAppColorMode(AppColorMode.DYNAMIC)
            assertEquals(AppColorMode.DYNAMIC, repository.appColorMode.first())

            val reopened = ThemeSettingsRepository(dataStore)
            assertEquals(AppColorMode.DYNAMIC, reopened.appColorMode.first())
        }

    @Test
    fun `setAppColorMode back to Rebubble persists too`() = runBlocking {
        val repository = ThemeSettingsRepository(newDataStore())

        repository.setAppColorMode(AppColorMode.DYNAMIC)
        repository.setAppColorMode(AppColorMode.REBUBBLE)

        assertEquals(AppColorMode.REBUBBLE, repository.appColorMode.first())
    }
}
