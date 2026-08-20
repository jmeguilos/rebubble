package app.rebubble.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first real migration in this codebase: v1 → v2 adds `chats.unreadCount`.
 *
 * Runs under Robolectric (not instrumentation) against the committed schema JSON in `app/schemas/`,
 * which is wired into the unit-test assets by `app/build.gradle.kts`. Two things are asserted, and
 * they are the two ways this kind of migration goes wrong:
 *  1. [MigrationTestHelper.runMigrationsAndValidate] fails if the post-migration table shape does
 *     not match the *generated* v2 schema — so an `ALTER TABLE` that disagreed with the entity
 *     (nullability, default, affinity) would be caught here rather than as a runtime
 *     `IllegalStateException` on the user's device;
 *  2. rows written at v1 survive with their columns intact and land on `unreadCount = 0` — a
 *     migrated install must not open with the whole conversation list badged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class RebubbleMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RebubbleDatabase::class.java,
    )

    @Test
    fun `migration 1 to 2 adds unreadCount defaulting to zero and preserves existing chats`() {
        helper.createDatabase(TEST_DB, 1).use { v1 ->
            v1.execSQL(
                """
                INSERT INTO chats
                    (guid, style, chatIdentifier, displayName, isArchived, lastMessageDate,
                     lastMessagePreview)
                VALUES ('chat-1', 43, 'chat.group', 'Ski trip 2026', 0, 1700000000000, 'lift tickets')
                """.trimIndent(),
            )
            v1.execSQL(
                """
                INSERT INTO chats
                    (guid, style, chatIdentifier, displayName, isArchived, lastMessageDate,
                     lastMessagePreview)
                VALUES ('chat-2', 45, '+15551234567', NULL, 0, NULL, NULL)
                """.trimIndent(),
            )
        }

        // Throws if the migrated schema doesn't match the exported v2 schema.
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RebubbleDatabase::class.java,
            TEST_DB,
        )
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        try {
            val chats = runBlocking { db.chatDao().observeChats().first() }
            assertEquals(listOf("chat-1", "chat-2"), chats.map { it.guid })

            val group = chats.first { it.guid == "chat-1" }
            assertEquals(0, group.unreadCount)
            assertEquals(43, group.style)
            assertEquals("Ski trip 2026", group.displayName)
            assertEquals(1_700_000_000_000L, group.lastMessageDate)
            assertEquals("lift tickets", group.lastMessagePreview)

            val direct = chats.first { it.guid == "chat-2" }
            assertEquals(0, direct.unreadCount)
            assertNotNull(direct.chatIdentifier)

            // The new column is writable through the DAO after the migration.
            runBlocking {
                db.chatDao().incrementUnread("chat-1")
                assertEquals(1, db.chatDao().getByGuid("chat-1")?.unreadCount)
                db.chatDao().clearUnread("chat-1")
                assertEquals(0, db.chatDao().getByGuid("chat-1")?.unreadCount)
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
