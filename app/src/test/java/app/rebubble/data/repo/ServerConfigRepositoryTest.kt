package app.rebubble.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.inject.Provider

/**
 * Covers [ServerConfigRepository]: URL sanitization on [ServerConfigRepository.save], round-trip
 * persistence of [ServerConfigRepository.config] (real Preferences DataStore in a temp dir + an
 * in-memory [SecretStore] fake standing in for `EncryptedSecretStore`), [ServerInfo] capability
 * flag mapping + cache round-trip via [ServerConfigRepository.refreshServerInfo], and the
 * synchronous [app.rebubble.data.remote.api.ServerCredentialsProvider] snapshot seam (including
 * its documented cold-start-returns-null behavior).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ServerConfigRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: ServerConfigRepository
    private val servers = mutableListOf<MockWebServer>()

    @Before
    fun setUp() {
        repository = buildRepository()
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun newDataStore(file: File = File(tempFolder.newFolder(), "server_config.preferences_pb")): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { file })

    private fun failingApiProvider(): Provider<BlueBubblesApi> =
        Provider { error("BlueBubblesApi not expected to be used in this test") }

    private fun buildRepository(
        dataStore: DataStore<Preferences> = newDataStore(),
        secretStore: SecretStore = InMemorySecretStore(),
        apiProvider: Provider<BlueBubblesApi> = failingApiProvider(),
    ): ServerConfigRepository = ServerConfigRepository(dataStore, secretStore, apiProvider)

    private fun startServer(): MockWebServer {
        val server = MockWebServer()
        server.start()
        servers += server
        return server
    }

    private fun apiFor(server: MockWebServer): BlueBubblesApi {
        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw",
        )
        return testBlueBubblesApi(credentials)
    }

    // -- config round-trip -----------------------------------------------------------------

    @Test
    fun `config is null before any server has been configured`() = runBlocking {
        assertNull(repository.config.first())
    }

    @Test
    fun `save persists a sanitized url and password, readable back via config`() = runBlocking {
        repository.save("host:1234", "hunter2")

        assertEquals(ServerConfig("https://host:1234", "hunter2"), repository.config.first())
    }

    @Test
    fun `config round-trips across a repository restart, backed by the same storage`() = runBlocking {
        val dataStore = newDataStore()
        val secretStore = InMemorySecretStore()

        val repo1 = buildRepository(dataStore = dataStore, secretStore = secretStore)
        repo1.save("host:1234", "hunter2")
        assertEquals(ServerConfig("https://host:1234", "hunter2"), repo1.config.first())

        // Simulate a process restart: a fresh repository instance wrapping the same underlying
        // DataStore file + secret store.
        val repo2 = buildRepository(dataStore = dataStore, secretStore = secretStore)
        assertEquals(ServerConfig("https://host:1234", "hunter2"), repo2.config.first())
    }

    // -- URL sanitization -------------------------------------------------------------------

    @Test
    fun `save defaults a bare host colon port to https`() = runBlocking {
        repository.save("host:1234", "pw")

        assertEquals("https://host:1234", repository.config.first()?.url)
    }

    @Test
    fun `save keeps an explicit http scheme and strips a single trailing slash`() = runBlocking {
        repository.save("http://h/", "pw")

        assertEquals("http://h", repository.config.first()?.url)
    }

    @Test
    fun `save strips multiple trailing slashes`() = runBlocking {
        repository.save("https://h///", "pw")

        assertEquals("https://h", repository.config.first()?.url)
    }

    @Test
    fun `save trims surrounding whitespace`() = runBlocking {
        repository.save("  http://host  ", "pw")

        assertEquals("http://host", repository.config.first()?.url)
    }

    @Test
    fun `save strips a trailing api slash v1 suffix pasted from the docs`() = runBlocking {
        repository.save("https://myserver.com/api/v1", "pw")

        assertEquals("https://myserver.com", repository.config.first()?.url)
    }

    @Test
    fun `save strips only the trailing api slash v1 suffix, keeping a reverse-proxy prefix`() = runBlocking {
        repository.save("https://h/bluebubbles/api/v1", "pw")

        assertEquals("https://h/bluebubbles", repository.config.first()?.url)
    }

    @Test
    fun `save leaves a reverse-proxy path prefix unchanged when there is no api slash v1 suffix`() = runBlocking {
        repository.save("https://h/bluebubbles", "pw")

        assertEquals("https://h/bluebubbles", repository.config.first()?.url)
    }

    @Test
    fun `save rejects a blank url with a typed error`() {
        assertThrows(InvalidServerConfigException::class.java) {
            runBlocking { repository.save("   ", "pw") }
        }
    }

    @Test
    fun `save rejects an unparseable url with a typed error`() {
        assertThrows(InvalidServerConfigException::class.java) {
            runBlocking { repository.save("not a url", "pw") }
        }
    }

    @Test
    fun `save rejects an unsupported scheme with a typed error`() {
        assertThrows(InvalidServerConfigException::class.java) {
            runBlocking { repository.save("ftp://host", "pw") }
        }
    }

    @Test
    fun `save rejects a blank password with a typed error`() {
        assertThrows(InvalidServerConfigException::class.java) {
            runBlocking { repository.save("host:1234", "   ") }
        }
    }

    @Test
    fun `an invalid save leaves any previously-saved config untouched`() = runBlocking {
        repository.save("host:1234", "hunter2")

        assertThrows(InvalidServerConfigException::class.java) {
            runBlocking { repository.save("garbage url", "pw2") }
        }

        assertEquals(ServerConfig("https://host:1234", "hunter2"), repository.config.first())
    }

    // -- refreshServerInfo / capability flag mapping -----------------------------------------

    @Test
    fun `refreshServerInfo maps all ServerInfoDto fields when capability flags are true`() = runBlocking {
        val server = startServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"OK","data":{"computer_id":"c1","os_version":"14.5","server_version":"1.9.7","private_api":true,"helper_connected":true}}"""
            )
        )
        val repo = buildRepository(apiProvider = Provider { apiFor(server) })

        val info = repo.refreshServerInfo()

        assertEquals("1.9.7", info.serverVersion)
        assertEquals("14.5", info.osVersion)
        assertTrue(info.privateApi)
        assertTrue(info.helperConnected)
    }

    @Test
    fun `refreshServerInfo defaults capability flags to false when the server omits them`() = runBlocking {
        val server = startServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"OK","data":{"computer_id":"c1","os_version":"14.5","server_version":"1.9.7"}}"""
            )
        )
        val repo = buildRepository(apiProvider = Provider { apiFor(server) })

        val info = repo.refreshServerInfo()

        assertFalse(info.privateApi)
        assertFalse(info.helperConnected)
    }

    @Test
    fun `serverInfo is null until refreshServerInfo has completed at least once`() = runBlocking {
        assertNull(repository.serverInfo.first())
    }

    @Test
    fun `serverInfo cache round-trips across a repository restart`() = runBlocking {
        val server = startServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"OK","data":{"computer_id":"c1","os_version":"14.5","server_version":"1.9.7","private_api":true,"helper_connected":true}}"""
            )
        )
        val dataStore = newDataStore()
        val repo1 = buildRepository(dataStore = dataStore, apiProvider = Provider { apiFor(server) })
        repo1.refreshServerInfo()

        val repo2 = buildRepository(dataStore = dataStore)
        val cached = repo2.serverInfo.first()

        assertEquals(ServerInfo("1.9.7", true, true, "14.5"), cached)
    }

    // -- ServerCredentialsProvider seam -------------------------------------------------------

    @Test
    fun `url and password are null before onboarding (cold start)`() {
        assertNull(repository.url())
        assertNull(repository.password())
    }

    @Test
    fun `url and password eventually reflect a saved config via the in-memory snapshot`() = runBlocking {
        repository.save("host:1234", "hunter2")

        withTimeout(5_000) {
            while (repository.url() == null) {
                delay(10)
            }
        }

        assertEquals("https://host:1234", repository.url())
        assertEquals("hunter2", repository.password())
    }

    @Test
    fun `url and password are synchronously primed on a fresh repository, no polling required`() = runBlocking {
        val dataStore = newDataStore()
        val secretStore = InMemorySecretStore()

        val repo1 = buildRepository(dataStore = dataStore, secretStore = secretStore)
        repo1.save("host:1234", "hunter2")

        // Simulate a process restart: a brand-new repository instance wrapping the same
        // underlying DataStore file + secret store, given no time for its own async snapshot
        // collector to have emitted yet. Calling url()/password() immediately must still see the
        // already-persisted config rather than a cold-start null.
        val repo2 = buildRepository(dataStore = dataStore, secretStore = secretStore)

        assertEquals("https://host:1234", repo2.url())
        assertEquals("hunter2", repo2.password())
    }

    @Test
    fun `init collector recovers from a transient SecretStore exception, snapshot eventually reflects config`() = runBlocking {
        val dataStore = newDataStore()
        val secretStore = ThrowOnceThenDelegateSecretStore(InMemorySecretStore())
        val repo = buildRepository(dataStore = dataStore, secretStore = secretStore)

        repo.save("host:1234", "hunter2")

        withTimeout(5_000) {
            while (repo.url() == null) {
                delay(10)
            }
        }

        assertEquals("https://host:1234", repo.url())
        assertEquals("hunter2", repo.password())
    }

    /**
     * [SecretStore] fake whose [getString] throws once (simulating a transient
     * DataStore/EncryptedSharedPreferences failure) before delegating normally on every
     * subsequent call — used to exercise the init collector's `retryWhen` resilience.
     */
    private class ThrowOnceThenDelegateSecretStore(private val delegate: SecretStore) : SecretStore {
        private var thrown = false

        override fun putString(key: String, value: String) = delegate.putString(key, value)

        override fun getString(key: String): String? {
            if (!thrown) {
                thrown = true
                throw IllegalStateException("simulated transient SecretStore failure")
            }
            return delegate.getString(key)
        }

        override fun remove(key: String) = delegate.remove(key)
    }
}
