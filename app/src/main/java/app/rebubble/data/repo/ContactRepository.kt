package app.rebubble.data.repo

import android.util.Log
import app.rebubble.data.local.dao.ContactDao
import app.rebubble.data.local.entity.ContactEntity
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.apiCall
import app.rebubble.data.remote.dto.ContactDto
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_TAG = "ContactRepository"

/**
 * Syncs device contacts from `GET /contact` into [ContactDao], so chat titles, notification person
 * names, and group-event sender names can resolve a handle address to a real display name instead
 * of showing the raw address.
 *
 * [syncContacts] never throws — same convention as [app.rebubble.data.sync.Reconciler.reconcile]:
 * any failure (auth, HTTP, network, decode) is caught and logged, leaving whatever contacts were
 * already persisted from a prior successful sync untouched (this never clears the table before
 * upserting).
 *
 * Avatars are explicitly out of scope here: [ContactDto.avatar] is a (potentially large) base64
 * blob, so [ContactEntity.avatarPath] is always written as `null` by this repository.
 */
@Singleton
class ContactRepository @Inject constructor(
    private val api: BlueBubblesApi,
    private val contactDao: ContactDao,
) {

    suspend fun syncContacts() {
        try {
            val contacts = apiCall { api.contacts() }
            val rows = contacts.flatMap { it.toContactEntities() }
            if (rows.isNotEmpty()) {
                contactDao.upsert(rows)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(LOG_TAG, "syncContacts failed; keeping previously synced contacts", failure)
        }
    }
}

/**
 * One [ContactEntity] per verbatim address on [this] contact, plus a second row for that
 * address's [normalizeAddress] form when it differs from the verbatim one — so a lookup by either
 * form finds this contact. See [ContactRepository.syncContacts]'s KDoc for why `avatarPath` is
 * always `null`.
 */
private fun ContactDto.toContactEntities(): List<ContactEntity> {
    val name = resolvedDisplayName()
    val addresses = (phoneNumbers.map { it.address } + emails.map { it.address })
        .filter { it.isNotBlank() }
    return addresses.flatMap { address ->
        val normalized = normalizeAddress(address)
        buildList {
            add(ContactEntity(address = address, displayName = name, avatarPath = null))
            if (normalized != address) {
                add(ContactEntity(address = normalized, displayName = name, avatarPath = null))
            }
        }
    }
}

/** displayName > "firstName lastName".trim() > nickname — first non-blank wins; else `null`. */
private fun ContactDto.resolvedDisplayName(): String? = listOfNotNull(
    displayName?.takeIf { it.isNotBlank() },
    "${firstName.orEmpty()} ${lastName.orEmpty()}".trim().takeIf { it.isNotBlank() },
    nickname?.takeIf { it.isNotBlank() },
).firstOrNull()
