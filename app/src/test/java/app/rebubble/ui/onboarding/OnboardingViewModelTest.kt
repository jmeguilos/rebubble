package app.rebubble.ui.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.testBlueBubblesApi
import app.rebubble.data.repo.InMemorySecretStore
import app.rebubble.data.repo.ServerConfig
import app.rebubble.data.repo.ServerConfigRepository
import app.rebubble.data.sync.SyncOutcome
import app.rebubble.data.sync.SyncStatus
import app.rebubble.data.sync.SyncStatusTracker
import app.rebubble.data.sync.SyncWatermarkStore
import app.rebubble.notifications.FcmSetupResult
import app.rebubble.ui.navigation.RebubbleRoutes
import app.rebubble.ui.navigation.startDestinationForConfig
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
import org.junit.Assert.assertFalse
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
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class OnboardingViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: BlueBubblesApi
    private lateinit var serverConfig: ServerConfigRepository
    private lateinit var watermarkStore: SyncWatermarkStore
    private lateinit var tracker: SyncStatusTracker

    private val reconcileCalls = AtomicInteger(0)
    private val callOrder = mutableListOf<String>()
    private var fcmResult: FcmSetupResult = FcmSetupResult.Success("token")
    private var reconcileResult: SyncOutcome = SyncOutcome(emptyList(), null)

    @Before
    fun setUp() {
        // Real Unconfined so viewModelScope + DataStore/OkHttp resumes interoperate under runBlocking.
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
        reconcileCalls.set(0)
        callOrder.clear()
        fcmResult = FcmSetupResult.Success("token")
        reconcileResult = SyncOutcome(emptyList(), null)

        serverConfig = ServerConfigRepository(
            dataStore = newDataStore("server_config"),
            secretStore = InMemorySecretStore(),
            apiProvider = Provider { api },
        )
        api = testBlueBubblesApi(serverConfig)
        watermarkStore = SyncWatermarkStore(newDataStore("sync_state"))
        tracker = SyncStatusTracker()
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

    private fun viewModel(): OnboardingViewModel = OnboardingViewModel(
        serverConfigRepository = serverConfig,
        api = api,
        watermarkStore = watermarkStore,
        syncStatusTracker = tracker,
        reconcile = OnboardingReconcileAction {
            reconcileCalls.incrementAndGet()
            val watermark = watermarkStore.get()
            callOrder += "reconcile(watermark=$watermark)"
            reconcileResult
        },
        setupFcm = OnboardingFcmSetupAction {
            callOrder += "fcm"
            fcmResult
        },
    )

    private fun enqueueServerInfoOk() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":{"computer_id":"c","os_version":"14.0","server_version":"1.9.0","private_api":true,"helper_connected":true}}""",
            ),
        )
    }

    private fun enqueueMaxRowId(rowId: Long) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":[{"originalROWID":$rowId,"guid":"m-max","text":"hi","isFromMe":true,"dateCreated":1}],"metadata":{"offset":0,"limit":1,"total":1,"count":1}}""",
            ),
        )
    }

    private fun serverUrl(): String = server.url("/").toString().trimEnd('/')

    private fun isTerminal(state: OnboardingUiState): Boolean = when (state) {
        is OnboardingUiState.Done,
        is OnboardingUiState.PasswordError,
        is OnboardingUiState.Unreachable,
        is OnboardingUiState.QrError,
        is OnboardingUiState.SyncError,
        -> true
        else -> false
    }

    private suspend fun OnboardingViewModel.awaitTerminal(): OnboardingUiState =
        withTimeout(15_000) {
            uiState.first { isTerminal(it) }
        }

    @Test
    fun `manual connect happy path advances through syncing to done with navigation event`() = runBlocking {
        enqueueServerInfoOk()
        enqueueMaxRowId(42)

        val vm = viewModel()
        vm.connectManual(url = serverUrl(), password = "  secret  ")
        val done = vm.awaitTerminal() as OnboardingUiState.Done

        val saved = serverConfig.config.first()
        assertEquals(ServerConfig(serverUrl(), "secret"), saved)
        assertEquals(1, reconcileCalls.get())
        assertTrue(callOrder.contains("fcm"))
        assertFalse(done.notificationsLimited)
        assertTrue(
            "expected NavigateToChats in ${vm.events.replayCache}",
            OnboardingEvent.NavigateToChats in vm.events.replayCache,
        )
    }

    @Test
    fun `401 yields PasswordError with exact copy and retry re-runs validation`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401,"message":"Unauthorized"}"""))
        enqueueServerInfoOk()
        enqueueMaxRowId(1)

        val vm = viewModel()
        vm.connectManual(url = serverUrl(), password = "wrong")
        val err = vm.awaitTerminal() as OnboardingUiState.PasswordError
        assertEquals(OnboardingCopy.PASSWORD_REJECTED, err.message)
        assertEquals(0, reconcileCalls.get())

        vm.retryConnect()
        assertTrue(vm.awaitTerminal() is OnboardingUiState.Done)
        assertEquals(1, reconcileCalls.get())
    }

    @Test
    fun `IOException yields Unreachable state with exact copy`() = runBlocking {
        val vm = viewModel()
        vm.connectManual(url = "http://127.0.0.1:1", password = "pw")
        val err = vm.awaitTerminal() as OnboardingUiState.Unreachable
        assertEquals(OnboardingCopy.UNREACHABLE, err.message)
        assertEquals(0, reconcileCalls.get())
    }

    @Test
    fun `valid QR payload follows the same path as manual`() = runBlocking {
        enqueueServerInfoOk()
        enqueueMaxRowId(7)

        val vm = viewModel()
        vm.onQrPayload("""["qr-pw","${serverUrl()}"]""")
        assertTrue(vm.awaitTerminal() is OnboardingUiState.Done)

        assertEquals(ServerConfig(serverUrl(), "qr-pw"), serverConfig.config.first())
    }

    @Test
    fun `malformed QR payload sets QrError and does not save`() = runBlocking {
        val vm = viewModel()
        vm.onQrPayload("not-json")
        assertEquals(OnboardingUiState.QrError(OnboardingCopy.QR_MALFORMED), vm.awaitTerminal())
        assertEquals(null, serverConfig.config.first())

        vm.onQrPayload("""["only-one"]""")
        assertEquals(OnboardingUiState.QrError(OnboardingCopy.QR_MALFORMED), vm.awaitTerminal())
        assertEquals(null, serverConfig.config.first())
        assertEquals(0, reconcileCalls.get())
    }

    @Test
    fun `FCM setup failure still reaches done with banner flag`() = runBlocking {
        enqueueServerInfoOk()
        enqueueMaxRowId(3)
        fcmResult = FcmSetupResult.Failure(
            FcmSetupResult.Failure.Step.FETCH_CLIENT,
            IOException("no fcm"),
        )

        val vm = viewModel()
        vm.connectManual(url = serverUrl(), password = "pw")
        val done = vm.awaitTerminal() as OnboardingUiState.Done
        assertTrue(done.notificationsLimited)
        assertTrue(OnboardingEvent.NavigateToChats in vm.events.replayCache)
    }

    @Test
    fun `watermark initialized with max ROWID before reconcile runs`() = runBlocking {
        enqueueServerInfoOk()
        enqueueMaxRowId(999)

        val vm = viewModel()
        vm.connectManual(url = serverUrl(), password = "pw")
        assertTrue(vm.awaitTerminal() is OnboardingUiState.Done)

        assertEquals(listOf("reconcile(watermark=999)", "fcm"), callOrder)
        assertEquals(999L, watermarkStore.get())
        val recorded = (0 until server.requestCount).map { server.takeRequest().path.orEmpty() }
        val messageQueryIdx = recorded.indexOfFirst { it.contains("/message/query") }
        assertTrue("expected message/query in $recorded", messageQueryIdx >= 0)
        assertTrue(tracker.status.value == SyncStatus.Idle)
    }

    @Test
    fun `start destination is onboarding when config null and chats when present`() {
        assertEquals(RebubbleRoutes.ONBOARDING, startDestinationForConfig(null))
        assertEquals(
            RebubbleRoutes.CHATS,
            startDestinationForConfig(ServerConfig("https://h", "pw")),
        )
    }

    @Test
    fun `queryMaxRowId IOException yields SyncError without stuck Syncing or FCM`() = runBlocking {
        enqueueServerInfoOk()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val vm = viewModel()
        vm.connectManual(url = serverUrl(), password = "pw")
        val err = vm.awaitTerminal() as OnboardingUiState.SyncError

        assertEquals(OnboardingCopy.SYNC_FAILED, err.message)
        assertFalse(vm.uiState.value is OnboardingUiState.Syncing)
        assertEquals(0, reconcileCalls.get())
        assertFalse(callOrder.contains("fcm"))
        assertFalse(OnboardingEvent.NavigateToChats in vm.events.replayCache)
    }

    @Test
    fun `reconcile SyncOutcome error yields SyncError and skips FCM`() = runBlocking {
        enqueueServerInfoOk()
        enqueueMaxRowId(5)
        reconcileResult = SyncOutcome(emptyList(), IOException("network down"))

        val vm = viewModel()
        vm.connectManual(url = serverUrl(), password = "pw")
        val err = vm.awaitTerminal() as OnboardingUiState.SyncError

        assertEquals(OnboardingCopy.SYNC_FAILED, err.message)
        assertEquals(1, reconcileCalls.get())
        assertFalse(callOrder.contains("fcm"))
        assertFalse(OnboardingEvent.NavigateToChats in vm.events.replayCache)
    }

    @Test
    fun `retry from SyncError re-runs first sync and can reach Done`() = runBlocking {
        enqueueServerInfoOk()
        enqueueMaxRowId(11)
        reconcileResult = SyncOutcome(emptyList(), IOException("first fail"))

        val vm = viewModel()
        vm.connectManual(url = serverUrl(), password = "pw")
        assertTrue(vm.awaitTerminal() is OnboardingUiState.SyncError)
        assertEquals(1, reconcileCalls.get())
        assertFalse(callOrder.contains("fcm"))

        enqueueMaxRowId(11)
        reconcileResult = SyncOutcome(emptyList(), null)
        callOrder.clear()

        vm.retryConnect()
        val done = vm.awaitTerminal() as OnboardingUiState.Done

        assertEquals(2, reconcileCalls.get())
        assertEquals(listOf("reconcile(watermark=11)", "fcm"), callOrder)
        assertFalse(done.notificationsLimited)
        assertTrue(OnboardingEvent.NavigateToChats in vm.events.replayCache)
    }
}
