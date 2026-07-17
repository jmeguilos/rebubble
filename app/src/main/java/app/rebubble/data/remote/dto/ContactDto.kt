package app.rebubble.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single phone-number or email entry within a contact.
 *
 * Wire shape: `{address, id}` — NOT a bare string. `id` is
 * `e?.id ?? e?.identifier ?? null`, so it can be a DB-numeric `ContactAddress.id`
 * (TypeORM auto-increment primary key), a string identifier (API-sourced contact), or absent —
 * decoded as a tolerant [JsonElement] rather than a fixed type.
 *
 * Source: contactInterface.ts `extractPhoneNumbers()` lines 32-46 and `extractEmails()` lines
 * 16-30 -- packages/server/src/server/api/interfaces/contactInterface.ts
 */
@Serializable
data class ContactAddressDto(
    @SerialName("address") val address: String,
    val id: JsonElement? = null
)

/**
 * A device contact surfaced by `GET /api/v1/contact`.
 *
 * Wire shape reconciled against `ContactInterface.mapContacts()`: `phoneNumbers`/`emails` are
 * arrays of [ContactAddressDto] objects (not bare strings), and top-level `id` is
 * `contact?.identifier ?? contact?.id` -- a JSON number for DB-backed contacts (TypeORM
 * auto-increment primary key) or a string for API-sourced contacts, so it is decoded as a
 * tolerant [JsonElement] rather than a fixed type.
 *
 * Source: packages/server/src/server/api/interfaces/contactInterface.ts (`mapContacts()`, lines
 * 82-93).
 */
@Serializable
data class ContactDto(
    val id: JsonElement? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val displayName: String? = null,
    val nickname: String? = null,
    val birthday: String? = null,
    val avatar: String? = null,
    val sourceType: String? = null,
    val phoneNumbers: List<ContactAddressDto> = emptyList(),
    val emails: List<ContactAddressDto> = emptyList()
)
