package app.rebubble.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

/**
 * Shared helper for DAO tests: an in-memory (non-persisted) [RebubbleDatabase] instance backed by
 * Robolectric's Android [android.content.Context]. Each test gets a fresh database.
 */
object InMemoryDatabaseFactory {
    fun create(): RebubbleDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RebubbleDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
}
