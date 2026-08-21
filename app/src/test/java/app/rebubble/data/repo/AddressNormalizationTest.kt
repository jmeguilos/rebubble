package app.rebubble.data.repo

import app.rebubble.data.local.entity.ContactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [normalizeAddress] canonicalizes phone-number punctuation and email casing so a contact synced
 * from `GET /contact` can still be found by a handle address reported in a different verbatim
 * form. [findByAddress]/[findNameByAddress] cover the shared verbatim-then-normalized lookup used
 * at every call site (chat title resolution, notifications, group-event sender names).
 */
class AddressNormalizationTest {

    @Test
    fun `strips phone punctuation but keeps a single leading plus`() {
        assertEquals("+15550100001", normalizeAddress("+1 (555) 010-0001"))
    }

    @Test
    fun `strips phone punctuation with no leading plus`() {
        assertEquals("5550100001", normalizeAddress("555.010.0001"))
    }

    @Test
    fun `lowercases emails`() {
        assertEquals("foo@bar.com", normalizeAddress("Foo@Bar.COM"))
    }

    @Test
    fun `findByAddress matches the verbatim address first`() {
        val contact = ContactEntity(address = "+15550100001", displayName = "Maya Chen", avatarPath = null)
        val map = mapOf("+15550100001" to contact)

        assertEquals(contact, map.findByAddress("+15550100001"))
    }

    @Test
    fun `findByAddress falls back to the normalized address`() {
        val contact = ContactEntity(address = "+15550100001", displayName = "Maya Chen", avatarPath = null)
        val map = mapOf("+15550100001" to contact)

        assertEquals(contact, map.findByAddress("+1 (555) 010-0001"))
    }

    @Test
    fun `findByAddress returns null when neither form is present`() {
        val map = mapOf("+15550100001" to ContactEntity(address = "+15550100001", displayName = "Maya Chen", avatarPath = null))

        assertNull(map.findByAddress("+15559998888"))
    }

    @Test
    fun `findNameByAddress falls back to the normalized address`() {
        val map = mapOf("+15550100001" to "Maya Chen")

        assertEquals("Maya Chen", map.findNameByAddress("+1 (555) 010-0001"))
    }
}
