package app.rebubble.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure comparison for server-switch: when the default FirebaseApp already exists, decide whether
 * delete+reinit is required before fetching a token for the incoming `/fcm/client` params.
 */
class FirebaseOptionsNeedsReinitTest {

    private val base = FirebaseOptionsParams(
        apiKey = "AIzaSyBASE",
        applicationId = "1:111:android:aaa",
        projectId = "project-a",
        gcmSenderId = "111",
    )

    @Test
    fun `identical projectId and applicationId does not need reinit`() {
        val incoming = base.copy(apiKey = "AIzaSyOTHER", gcmSenderId = "999")
        assertFalse(needsReinit(base, incoming))
    }

    @Test
    fun `projectId mismatch needs reinit`() {
        val incoming = base.copy(projectId = "project-b")
        assertTrue(needsReinit(base, incoming))
    }

    @Test
    fun `applicationId-only mismatch needs reinit`() {
        val incoming = base.copy(applicationId = "1:111:android:bbb")
        assertTrue(needsReinit(base, incoming))
    }
}
