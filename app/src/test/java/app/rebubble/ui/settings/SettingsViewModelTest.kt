package app.rebubble.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.rebubble.data.logging.RingBufferLogger
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.testBlueBubblesApi
import app.rebubble.data.repo.InMemorySecretStore
import app.rebubble.data.repo.ServerConfigRepository
import app.rebubble.data.sync.SyncScheduling
import app.rebubble.data.sync.SyncStatusTracker
import app.rebubble.notifications.FcmSetupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class SettingsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: BlueBubblesApi
    private lateinit var serverConfig: ServerConfigRepository
    private lateinit var tracker: SyncStatusTracker
    private lateinit var logger: RingBufferLogger
    private var fcmResult: FcmSetupResult = FcmSetupResult.Success("token")

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        server = MockWebServer()
        server.start()
        fcmResult = FcmSetupResult.Success("token")

        serverConfig = ServerConfigRepository(
            dataStore = newDataStore("server_config"),
            secretStore = InMemorySecretStore(),
            apiProvider = Provider { api },
        )
        api = testBlueBubblesApi(serverConfig)
        tracker = SyncStatusTracker(RingBufferLogger())
        logger = RingBufferLogger()

        runBlocking {
            serverConfig.save(server.url("/").toString().trimEnd('/'), "pw")
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        runCatching { server.shutdown() }
    }

    private fun newDataStore(name: String): DataStore<Preferences> {
        val file = File(tempFolder.newFolder(), "$name.preferences_pb")
        return PreferenceDataStoreFactory.create(produceFile = { file })
    }

    private fun viewModel(): SettingsViewModel = SettingsViewModel(
        serverConfigRepository = serverConfig,
        syncStatusTracker = tracker,
        logger = logger,
        appContext = ApplicationProvider.getApplicationContext(),
        setupFcm = SettingsFcmSetupAction { fcmResult },
        appVersionProvider = SettingsAppVersionProvider { "0.1.0" },
    )

    private fun enqueueServerInfoOk(version: String = "1.9.0") {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":{"computer_id":"c","os_version":"14.0","server_version":"$version","private_api":true,"helper_connected":true}}""",
            ),
        )
    }

    private fun awaitSnackbar(vm: SettingsViewModel, predicate: (String) -> Boolean): String =
        runBlocking {
            withTimeout(5_000) {
                vm.uiState.first { state ->
                    state.snackbarMessage?.let(predicate) == true
                }.snackbarMessage!!
            }
        }

    @Test
    fun `test connection success sets snackbar with version`() {
        enqueueServerInfoOk("2.1.0")
        val vm = viewModel()
        vm.testConnection()
        val msg = awaitSnackbar(vm) { it.contains("Connected") && it.contains("2.1.0") }
        assertTrue(msg.contains("Connected — server v2.1.0"))
    }

    @Test
    fun `test connection failure sets failure snackbar`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val vm = viewModel()
        vm.testConnection()
        val msg = awaitSnackbar(vm) { it.contains("Couldn't") }
        assertEquals(SettingsCopy.CONNECTION_FAILED, msg)
    }

    @Test
    fun `refresh on open populates server section from cache`() = runBlocking {
        enqueueServerInfoOk("1.8.5")
        serverConfig.refreshServerInfo()

        val vm = viewModel()
        vm.onScreenOpen()

        withTimeout(5_000) {
            vm.uiState.first { state ->
                state.serverUrl != null &&
                    state.serverVersion == "1.8.5" &&
                    state.osVersion == "14.0" &&
                    state.privateApi == true
            }
        }
        val state = vm.uiState.value
        assertNotNull(state.serverUrl)
        assertEquals("1.8.5", state.serverVersion)
        assertEquals("14.0", state.osVersion)
        assertEquals(true, state.privateApi)
    }

    @Test
    fun `fcm re-setup success snackbar`() {
        fcmResult = FcmSetupResult.Success("tok")
        val vm = viewModel()
        vm.setupNotificationsAgain()
        assertEquals(SettingsCopy.NOTIFICATIONS_READY, awaitSnackbar(vm) { it == SettingsCopy.NOTIFICATIONS_READY })
    }

    @Test
    fun `fcm re-setup failure snackbar`() {
        fcmResult = FcmSetupResult.Failure(FcmSetupResult.Failure.Step.FETCH_TOKEN, IOException("x"))
        val vm = viewModel()
        vm.setupNotificationsAgain()
        assertEquals(
            SettingsCopy.NOTIFICATIONS_FAILED,
            awaitSnackbar(vm) { it == SettingsCopy.NOTIFICATIONS_FAILED },
        )
    }

    @Test
    fun `sync now enqueues expedited work`() {
        val vm = viewModel()
        vm.syncNow()
        val infos = WorkManager.getInstance(ApplicationProvider.getApplicationContext())
            .getWorkInfosForUniqueWork(SyncScheduling.UNIQUE_EXPEDITED)
            .get()
        assertTrue(infos.isNotEmpty())
        // No NetworkType.CONNECTED — work may finish immediately under SynchronousExecutor
        // (Hilt SyncWorker isn't creatable here); enqueue itself is the contract under test.
        assertEquals(
            NetworkType.NOT_REQUIRED,
            infos[0].constraints.requiredNetworkType,
        )
    }
}
