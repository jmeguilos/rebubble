package app.rebubble.data.remote

import app.rebubble.data.remote.dto.ContactDto
import app.rebubble.data.remote.dto.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes fixtures/contacts.json (see its "_source" field): `ContactInterface.mapContacts()`
 * emits `phoneNumbers`/`emails` as arrays of `{address, id}` objects (not bare strings), and the
 * top-level `id` can be a JSON number (DB contacts, TypeORM auto-increment primary key) or a
 * string (API contacts).
 */
class ContactsDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes phoneNumbers and emails as address objects, and both id forms`() {
        val raw = loadFixture("contacts.json")

        val envelope: Envelope<List<ContactDto>> = json.decodeFromString(raw)
        val contacts = requireNotNull(envelope.data)
        assertEquals(2, contacts.size)

        val dbContact = contacts[0]
        assertEquals("+15551234567", dbContact.phoneNumbers[0].address)
        assertEquals("ada@example.com", dbContact.emails[0].address)
        assertEquals(7, dbContact.id?.jsonPrimitive?.int)
        assertEquals(101, dbContact.phoneNumbers[0].id?.jsonPrimitive?.int)

        val apiContact = contacts[1]
        assertEquals("+15557654321", apiContact.phoneNumbers[0].address)
        assertEquals("grace@example.com", apiContact.emails[0].address)
        assertEquals("contact-guid-99", apiContact.id?.jsonPrimitive?.content)
        assertTrue(apiContact.id?.jsonPrimitive?.isString == true)
        assertEquals("api-addr-1", apiContact.phoneNumbers[0].id?.jsonPrimitive?.content)
    }
}
