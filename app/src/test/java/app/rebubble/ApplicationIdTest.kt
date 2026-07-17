package app.rebubble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Placeholder unit test verifying the project scaffold is wired up correctly:
 * the applicationId configured in app/build.gradle.kts is exposed via BuildConfig.
 */
class ApplicationIdTest {

    @Test
    fun applicationId_isRebubbleAndroid() {
        assertEquals("app.rebubble.android", BuildConfig.APPLICATION_ID)
    }
}
