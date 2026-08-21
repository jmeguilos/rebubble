package app.rebubble.ui.a11y

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEqualTo
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.rebubble.data.repo.ChatListItem
import app.rebubble.data.sync.SyncStatus
import app.rebubble.ui.chat.Composer
import app.rebubble.ui.chatlist.ChatFilter
import app.rebubble.ui.chatlist.ChatListScreen
import app.rebubble.ui.chatlist.ChatListUiState
import app.rebubble.ui.common.SyncStatusChip
import app.rebubble.ui.theme.RebubbleTheme
import coil3.ImageLoader
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * WCAG 2.5.8 / M3 "target size" guard for every control this app paints smaller than 48dp.
 *
 * The design cards fix the *painted* size (32dp header avatar, 32dp chips, 44dp composer controls);
 * M3 fixes the *touch* size at 48dp. Those are different properties, and
 * `Modifier.minimumInteractiveComponentSize()` reconciles them: it grows the layout slot to 48dp and
 * centres the child at its painted size. Each control is therefore checked three ways:
 *
 *  1. `assertTouchHeight/WidthIsEqualTo(48.dp)` — the touch bounds Compose reports to the platform.
 *     Worth pinning, but on its own it is nearly a tautology: Compose inflates *any* pointer-input
 *     node towards `ViewConfiguration.minimumTouchTargetSize` when hit-testing, so this already read
 *     48dp for the 32dp gear before it was fixed. It catches clipping and removal, not undersizing.
 *  2. [assertSlotIsAtLeast] — the layout node's own measured size, i.e. what the layout genuinely
 *     reserves. This is the assertion that fails without the fix (32dp / 44dp), and passing it is
 *     what stops neighbouring inflated targets from competing for the same pixels.
 *  3. `assertHeightIsEqualTo(painted)` — the other half of the principle: the visuals must NOT grow.
 *     The design cards still own the painted size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TouchTargetSizeTest {

    @get:Rule
    val rule = createComposeRule()

    private val minTarget = 48.dp

    /**
     * Asserts on the *layout slot*, which the usual bounds helpers cannot see.
     *
     * A semantics node's bounds come from the coordinator at the point in the modifier chain where
     * its semantics modifier sits, and every M3 control (and `Surface(onClick=)`) attaches its
     * click/role semantics *inside* its own `size` modifier. So the clickable node keeps reporting
     * the painted 32dp/44dp while the reserved slot sits one layer further out. `LayoutInfo`
     * describes the whole layout node, so it reports the slot.
     */
    private fun SemanticsNodeInteraction.assertSlotIsAtLeast(
        expected: Dp,
        bothAxes: Boolean = true,
    ) {
        val info = fetchSemanticsNode("Failed to measure the layout slot.").layoutInfo
        val height = with(info.density) { info.height.toDp() }
        val width = with(info.density) { info.width.toDp() }
        assertTrue(
            "layout slot height is $height, expected at least $expected",
            height.value + Tolerance >= expected.value,
        )
        if (bothAxes) {
            assertTrue(
                "layout slot width is $width, expected at least $expected",
                width.value + Tolerance >= expected.value,
            )
        }
    }

    private fun SemanticsNodeInteraction.assertSquareTarget(painted: Dp) {
        assertTouchHeightIsEqualTo(minTarget)
        assertTouchWidthIsEqualTo(minTarget)
        assertSlotIsAtLeast(minTarget)
        assertHeightIsEqualTo(painted)
    }

    /** Chips are legitimately wider than 48dp, so only the vertical axis is pinned. */
    private fun SemanticsNodeInteraction.assertRowTarget(painted: Dp) {
        assertTouchHeightIsEqualTo(minTarget)
        assertSlotIsAtLeast(minTarget, bothAxes = false)
        assertHeightIsEqualTo(painted)
    }

    /** The labelled node and the clickable node are not always the same node; accept either. */
    private fun clickableLabelled(label: String): SemanticsMatcher =
        hasClickAction() and
            (hasContentDescription(label) or hasAnyDescendant(hasContentDescription(label)))

    private fun clickableWithText(text: String): SemanticsMatcher =
        hasClickAction() and (hasText(text) or hasAnyDescendant(hasText(text)))

    @Composable
    private fun ChatList(uiState: ChatListUiState) {
        val context = LocalContext.current
        RebubbleTheme(darkTheme = false, dynamicColor = false) {
            ChatListScreen(
                uiState = uiState,
                onChatClick = {},
                imageLoader = ImageLoader.Builder(context).build(),
                nowMs = FixedNow,
            )
        }
    }

    @Composable
    private fun ComposerHarness(initialText: String = "") {
        // Composer registers a photo-picker launcher; the v2 compose rule hosts content without an
        // Activity, so the registry owner has to be supplied explicitly.
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoOpRegistryOwner) {
            RebubbleTheme(darkTheme = false, dynamicColor = false) {
                Composer(
                    isSms = false,
                    onSendText = {},
                    onSendAttachment = {},
                    initialText = initialText,
                )
            }
        }
    }

    @Test
    fun `chat list settings gear reaches the minimum target`() {
        rule.setContent { ChatList(loadedState()) }

        rule.onNode(clickableLabelled("Settings")).assertSquareTarget(painted = 32.dp)
    }

    @Test
    fun `sync error dismiss button reaches the minimum target`() {
        rule.setContent {
            RebubbleTheme(darkTheme = false, dynamicColor = false) {
                SyncStatusChip(status = SyncStatus.Error(message = "timeout", at = 1L))
            }
        }

        rule.onNode(clickableLabelled("Dismiss")).assertSquareTarget(painted = 32.dp)
    }

    @Test
    fun `every chat filter chip reaches the minimum target`() {
        rule.setContent { ChatList(loadedState()) }

        ChatFilter.entries.forEach { filter ->
            val label = when (filter) {
                ChatFilter.All -> "All"
                ChatFilter.Unread -> "Unread"
                ChatFilter.Groups -> "Groups"
            }
            rule.onNode(clickableWithText(label)).assertRowTarget(painted = 32.dp)
        }
    }

    @Test
    fun `composer attach button reaches the minimum target`() {
        rule.setContent { ComposerHarness() }

        rule.onNode(clickableLabelled("Attach photo")).assertSquareTarget(painted = 44.dp)
    }

    @Test
    fun `composer send button reaches the minimum target`() {
        rule.setContent { ComposerHarness(initialText = "On my way") }

        rule.onNode(clickableLabelled("Send")).assertSquareTarget(painted = 44.dp)
    }

    /**
     * The composer's text field is 56dp, not the card's 44dp: a filled M3 `TextField` enforces
     * `TextFieldDefaults.MinHeight` internally, so the old `heightIn(min = 44.dp)` never bound.
     * 56dp is kept deliberately — shrinking the field to 44dp would create a *new* sub-48dp target.
     */
    @Test
    fun `composer text field is at least the minimum target tall`() {
        rule.setContent { ComposerHarness() }

        val field = rule.onNode(hasText("Message")).fetchSemanticsNode()
        val height = with(field.layoutInfo.density) { field.layoutInfo.height.toDp() }
        height.assertIsEqualTo(56.dp, "composer field height")
    }

    private companion object {
        const val FixedNow = 1_700_000_000_000L

        /** Same 0.5dp slack the built-in size assertions allow. */
        const val Tolerance = 0.5f

        fun loadedState(): ChatListUiState.Loaded = ChatListUiState.Loaded(
            items = listOf(
                ChatListItem(
                    guid = "guid-1",
                    title = "Maya Chen",
                    isGroup = false,
                    lastMessageDate = FixedNow - 120_000,
                    lastMessagePreview = "Are you coming tonight?",
                    style = 45,
                    unreadCount = 7,
                ),
            ),
        )

        val NoOpRegistryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry =
                object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<I, O>,
                        input: I,
                        options: androidx.core.app.ActivityOptionsCompat?,
                    ) = Unit
                }
        }
    }
}
