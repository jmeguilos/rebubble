package app.rebubble.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAvatarLabelTest {

    @Test
    fun `phone number title maps to person glyph`() {
        assertEquals(AvatarLabel.Person, avatarLabelForTitle("+15551234567"))
        assertEquals(AvatarLabel.Person, avatarLabelForTitle("(555) 123-4567"))
        assertTrue(isPhoneNumberTitle("+1 555 123 4567"))
    }

    @Test
    fun `named title maps to initials`() {
        assertEquals(AvatarLabel.Initials("AL"), avatarLabelForTitle("Alice"))
        assertEquals(AvatarLabel.Initials("JM"), avatarLabelForTitle("John, Maya"))
        assertFalse(isPhoneNumberTitle("Alice"))
    }

    @Test
    fun `empty title falls back to question mark`() {
        assertEquals(AvatarLabel.Initials("?"), avatarLabelForTitle("   "))
    }
}
