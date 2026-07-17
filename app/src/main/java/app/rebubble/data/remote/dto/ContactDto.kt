package app.rebubble.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * A device contact (phone number / email) surfaced by `GET /api/v1/contact`.
 *
 * This is an intentionally MINIMAL placeholder DTO for T3's Retrofit interface signature only.
 * T2's DTOs were derived from the actual BlueBubbles server TypeScript source (serializers +
 * types.ts) checked out at task time; that checkout is not available in this task, so this DTO's
 * field list is a reasonable guess at the contact shape rather than a source-cited contract. A
 * later contacts-focused task should reconcile this against the real `ContactSerializer` /
 * contact interface source and expand/correct the field list (e.g. phone/email sub-objects with
 * per-entry ids, nicknames, avatar, birthday) before the app relies on it for real contact data.
 */
@Serializable
data class ContactDto(
    val id: String? = null,
    val displayName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList()
)
