package app.rebubble.data.repo

import app.rebubble.data.local.InMemoryDatabaseFactory
import app.rebubble.data.local.RebubbleDatabase
import app.rebubble.data.local.entity.ContactEntity
import app.rebubble.data.remote.api.FakeServerCredentialsProvider
import app.rebubble.data.remote.api.testBlueBubblesApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ContactRepository.syncContacts] pulls `GET /contact` into [app.rebubble.data.local.dao.ContactDao]:
 * verbatim + normalized address rows per contact, displayName precedence
 * (displayName > "first last" > nickname), and never-throws-on-failure (partial progress from a
 * prior successful sync is preserved).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ContactRepositoryTest {

    private lateinit var db: RebubbleDatabase
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        db = InMemoryDatabaseFactory.create()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        db.close()
    }

    private fun repository(): ContactRepository {
        val credentials = FakeServerCredentialsProvider(
            urlValue = server.url("/").toString(),
            passwordValue = "pw",
        )
        return ContactRepository(api = testBlueBubblesApi(credentials), contactDao = db.contactDao())
    }

    private fun envelope(dataArrayJson: String) =
        """{"status":200,"message":"OK","data":$dataArrayJson}"""

    // --- verbatim + normalized rows -------------------------------------------------------------

    @Test
    fun `syncContacts upserts verbatim and normalized rows, skipping a second row when they match`() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    envelope(
                        """[{
                            "id": 7,
                            "displayName": "Maya Chen",
                            "phoneNumbers": [{"address": "+1 (555) 010-0001", "id": 101}],
                            "emails": [{"address": "Maya@Example.COM", "id": 202}]
                        }]"""
                    )
                )
            )

            repository().syncContacts()

            val all = db.contactDao().getAll().associateBy { it.address }
            assertEquals("Maya Chen", all["+1 (555) 010-0001"]?.displayName)
            assertEquals("Maya Chen", all["+15550100001"]?.displayName)
            assertEquals("Maya Chen", all["Maya@Example.COM"]?.displayName)
            assertEquals("Maya Chen", all["maya@example.com"]?.displayName)
            // 2 addresses, each with a distinct normalized form -> 4 rows, no extra duplicate.
            assertEquals(4, all.size)
            assertNull(all["+1 (555) 010-0001"]?.avatarPath)
        }

    @Test
    fun `syncContacts does not duplicate a row when the verbatim address is already normalized`() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    envelope(
                        """[{
                            "id": 7,
                            "displayName": "Ada Lovelace",
                            "phoneNumbers": [{"address": "+15551234567", "id": 101}],
                            "emails": []
                        }]"""
                    )
                )
            )

            repository().syncContacts()

            val all = db.contactDao().getAll()
            assertEquals(1, all.size)
            assertEquals("+15551234567", all.single().address)
        }

    // --- displayName precedence -----------------------------------------------------------------

    @Test
    fun `displayName precedence is displayName over first+last over nickname`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                envelope(
                    """[
                        {"id":1,"displayName":"Explicit Name","firstName":"First","lastName":"Last","nickname":"Nick",
                         "phoneNumbers":[{"address":"+15551110001","id":1}],"emails":[]},
                        {"id":2,"firstName":"Grace","lastName":"Hopper","nickname":"Amazing Grace",
                         "phoneNumbers":[{"address":"+15551110002","id":2}],"emails":[]},
                        {"id":3,"nickname":"Just Nick",
                         "phoneNumbers":[{"address":"+15551110003","id":3}],"emails":[]}
                    ]"""
                )
            )
        )

        repository().syncContacts()

        val all = db.contactDao().getAll().associateBy { it.address }
        assertEquals("Explicit Name", all["+15551110001"]?.displayName)
        assertEquals("Grace Hopper", all["+15551110002"]?.displayName)
        assertEquals("Just Nick", all["+15551110003"]?.displayName)
    }

    // --- never throws; preserves prior progress ---------------------------------------------------

    @Test
    fun `a server error does not throw and leaves previously synced contacts untouched`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                envelope(
                    """[{"id":1,"displayName":"Maya Chen","phoneNumbers":[{"address":"+15550100001","id":1}],"emails":[]}]"""
                )
            )
        )
        val repo = repository()
        repo.syncContacts()
        val before = db.contactDao().getAll().associateBy { it.address }
        assertEquals("Maya Chen", before["+15550100001"]?.displayName)

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"status":500,"message":"Server Error"}"""))

        repo.syncContacts() // must not throw

        val after = db.contactDao().getAll().associateBy { it.address }
        assertEquals(before, after)
    }

    @Test
    fun `a malformed response body does not throw`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        repository().syncContacts() // must not throw

        assertTrue(db.contactDao().getAll().isEmpty())
    }

    @Test
    fun `two contacts sharing an address resolve last-write-wins by response order`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                envelope(
                    """[
                        {"displayName":"Home Line","phoneNumbers":[{"address":"+15550100009","id":1}],"emails":[]},
                        {"displayName":"Dana Reyes","phoneNumbers":[{"address":"+15550100009","id":2}],"emails":[]}
                    ]"""
                )
            )
        )

        repository().syncContacts()

        // Documented tradeoff: Room REPLACE on the address PK makes the LAST entry in the
        // server's response order win. This test pins that behavior so a future change that
        // flips the winner is a conscious decision, not an accident.
        assertEquals("Dana Reyes", db.contactDao().getAll().single { it.address == "+15550100009" }.displayName)
    }
}
