package app.rebubble.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

private val WATERMARK_KEY = longPreferencesKey("watermark_row_id")

/**
 * Persists [Reconciler]'s high-water mark: the largest BlueBubbles iMessage `ROWID`
 * (`MessageDto.originalRowId`) successfully ingested so far. Backed by a dedicated "sync_state"
 * Preferences DataStore (see `app.rebubble.di.DataStoreModule`) — deliberately separate from
 * "server_config" ([app.rebubble.data.repo.ServerConfigRepository]'s store), since watermark
 * progress is sync-runtime state, not user-entered server configuration.
 *
 * A `null` watermark means "not yet initialized": onboarding (T16) is expected to call
 * [initializeIfAbsent] once, seeding it to the server's *current* max `ROWID` so the first
 * reconcile starts from "now" rather than replaying the user's entire iMessage history. Outside
 * that onboarding window, a `null` watermark tells [Reconciler] there is nothing to reconcile
 * from yet, so its message pass is skipped entirely (see [Reconciler.reconcile]'s KDoc).
 */
class SyncWatermarkStore(private val dataStore: DataStore<Preferences>) {

    /** The persisted watermark, or `null` if never set. */
    suspend fun get(): Long? = dataStore.data.first()[WATERMARK_KEY]

    /** Unconditionally overwrites the persisted watermark with [rowId]. */
    suspend fun set(rowId: Long) {
        dataStore.edit { prefs -> prefs[WATERMARK_KEY] = rowId }
    }

    /**
     * Sets the watermark to [rowId] only if it is currently absent. Returns `true` if this call
     * performed that initialization, `false` if a watermark was already present (in which case it
     * is left untouched). The read-modify-write happens inside a single [DataStore.updateData]
     * transaction (via [edit]), so concurrent callers can't both observe "absent" and both write.
     */
    suspend fun initializeIfAbsent(rowId: Long): Boolean {
        var initialized = false
        dataStore.edit { prefs ->
            if (prefs[WATERMARK_KEY] == null) {
                prefs[WATERMARK_KEY] = rowId
                initialized = true
            }
        }
        return initialized
    }
}
