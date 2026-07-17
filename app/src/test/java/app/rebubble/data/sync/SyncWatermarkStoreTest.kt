package app.rebubble.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [SyncWatermarkStore] is a thin, single-key wrapper around a Preferences DataStore. These tests
 * cover its three operations directly (round-trip persistence and the absent-only semantics of
 * [SyncWatermarkStore.initializeIfAbsent]); [ReconcilerTest] covers how [Reconciler] actually
 * drives it.
 */
class SyncWatermarkStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore(file: File = File(tempFolder.newFolder(), "sync_state.preferences_pb")): SyncWatermarkStore {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(produceFile = { file })
        return SyncWatermarkStore(dataStore)
    }

    @Test
    fun `get returns null before anything has been set`() = runBlocking {
        assertNull(newStore().get())
    }

    @Test
    fun `set then get round-trips the value`() = runBlocking {
        val store = newStore()

        store.set(42L)

        assertEquals(42L, store.get())
    }

    @Test
    fun `set overwrites a previously-set value`() = runBlocking {
        val store = newStore()

        store.set(10L)
        store.set(20L)

        assertEquals(20L, store.get())
    }

    @Test
    fun `initializeIfAbsent sets the value and reports true when absent`() = runBlocking {
        val store = newStore()

        val initialized = store.initializeIfAbsent(99L)

        assertTrue(initialized)
        assertEquals(99L, store.get())
    }

    @Test
    fun `initializeIfAbsent is a no-op and reports false when already present`() = runBlocking {
        val store = newStore()
        store.set(5L)

        val initialized = store.initializeIfAbsent(99L)

        assertFalse(initialized)
        assertEquals(5L, store.get())
    }

    @Test
    fun `a value persists across a fresh store instance backed by the same underlying DataStore`() = runBlocking {
        // A DataStore enforces a single active instance per backing file within a process, so this
        // wraps two SyncWatermarkStores around the *same* DataStore (mirroring how
        // ServerConfigRepositoryTest simulates a process restart), rather than opening the same
        // file from two independent DataStore instances.
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "sync_state.preferences_pb") }
        )

        SyncWatermarkStore(dataStore).set(7L)

        assertEquals(7L, SyncWatermarkStore(dataStore).get())
    }
}
