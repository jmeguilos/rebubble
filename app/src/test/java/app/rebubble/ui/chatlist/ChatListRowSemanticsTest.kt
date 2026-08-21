package app.rebubble.ui.chatlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.rebubble.data.repo.ChatListItem
import app.rebubble.ui.theme.RebubbleTheme
import coil3.ImageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Semantics and font-scale guards for the conversation row.
 *
 * The row is one control, so TalkBack must see one node carrying one sentence — not the four
 * disconnected nodes (title, preview, relative time, bare unread number) it used to read.
 *
 * The second half of this class is the font-scale contract: at 200% a two-line row needs ~88dp, so
 * every text-bearing container has to express a *minimum* height rather than a fixed one.
 * Robolectric 4.16.1 has no `fontScale` resource qualifier — font scale is not an Android resource
 * qualifier at all — so the 200% cases use `@Config(fontScale = ...)`, which drives
 * `Configuration.fontScale` and therefore Compose's `LocalDensity.fontScale`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ChatListRowSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    /** Matches the row whether the title is its own node (unmerged) or merged into the row. */
    private fun rowMatcher(title: String): SemanticsMatcher =
        hasClickAction() and (hasText(title) or hasAnyDescendant(hasText(title)))

    @Composable
    private fun ChatList(items: List<ChatListItem>) {
        val context = LocalContext.current
        RebubbleTheme(darkTheme = false, dynamicColor = false) {
            ChatListScreen(
                uiState = ChatListUiState.Loaded(items = items),
                onChatClick = {},
                imageLoader = ImageLoader.Builder(context).build(),
                nowMs = FixedNow,
            )
        }
    }

    @Test
    fun `row is one merged node whose description reads as a sentence`() {
        rule.setContent { ChatList(listOf(unreadItem())) }

        val row = rule.onNode(rowMatcher(Title))
        row.assertContentDescriptionContains(Title, substring = true)
        row.assertContentDescriptionContains(Preview, substring = true)
        // "2" alone is what TalkBack used to read; the count must be words.
        row.assertContentDescriptionContains("unread message", substring = true)

        // "Single node" check: after merging, the title is no longer a node of its own, so exactly
        // one node in the merged tree carries that text — the clickable row.
        assertEquals(1, rule.onAllNodesWithText(Title).fetchSemanticsNodes().size)
        rule.onNodeWithText(Title).assertHasClickAction()
    }

    /**
     * Maestro guard. Three flows in `maestro/` select conversation rows by their user-facing text
     * (`Fixture Friends`, `Maya Chen`, `Maestro fixture hello`), so the merge must not make those
     * strings unfindable: they stay in the row's own text (merged tree) and as child text nodes in
     * the unmerged tree.
     */
    @Test
    fun `row title and preview remain findable by text`() {
        rule.setContent { ChatList(listOf(unreadItem())) }

        rule.onNodeWithText(Title).assertExists()
        rule.onNodeWithText(Preview).assertExists()
        rule.onNodeWithText(Title, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(Preview, useUnmergedTree = true).assertExists()
    }

    /**
     * The row height has to be a *minimum*, not a fixed 76dp.
     *
     * The scale is 250%, not 200%, and that is a measurement rather than a preference: Android 14+
     * scales text non-linearly, so at `fontScale = 2.0` this row's two lines come to exactly 76dp —
     * the old fixed height, with zero margin left. At 250% the same two lines need 88dp, which a
     * fixed 76dp container can only deliver by clipping the preview.
     */
    @Test
    @Config(fontScale = 2.5f)
    fun `row grows to fit two lines at large font scale`() {
        rule.setContent { ChatList(listOf(unreadItem())) }

        rule.onNode(rowMatcher(Title)).assertHeightIsAtLeast(88.dp)
        rule.onNodeWithText(Preview, useUnmergedTree = true).assertExists()
        // …and the preview is inside the row, not clipped out of the bottom of it.
        val row = rule.onNode(rowMatcher(Title)).getUnclippedBoundsInRoot()
        val preview = rule.onNodeWithText(Preview, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "preview bottom ${preview.bottom} should sit inside the row bottom ${row.bottom}",
            preview.bottom.value <= row.bottom.value + 0.5f,
        )
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `unread badge still fits its label at 200 percent font scale`() {
        rule.setContent { ChatList(listOf(unreadItem())) }

        // 12sp of label at 200% needs ~24dp; the badge used to be pinned to 20dp and clipped it.
        rule.onNodeWithText(UnreadCount.toString(), useUnmergedTree = true)
            .assertHeightIsAtLeast(24.dp)
    }

    private companion object {
        const val FixedNow = 1_700_000_000_000L
        const val Title = "Maya Chen"
        const val Preview = "Are you coming tonight?"
        const val UnreadCount = 2

        fun unreadItem(): ChatListItem = ChatListItem(
            guid = "guid-1",
            title = Title,
            isGroup = false,
            lastMessageDate = FixedNow - 300_000,
            lastMessagePreview = Preview,
            style = 45,
            unreadCount = UnreadCount,
        )
    }
}
