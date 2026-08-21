package app.rebubble.ui.chat

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.rebubble.data.local.entity.AttachmentEntity
import app.rebubble.data.local.entity.DownloadState
import app.rebubble.data.local.entity.MessageEntity
import app.rebubble.data.local.entity.SendStatus
import app.rebubble.ui.theme.RebubbleTheme
import coil3.ImageLoader
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A message bubble is a control: tapping it reveals the timestamp. It therefore has to *announce*
 * itself as one, and it has to announce its send status — which used to be carried by a 65% alpha
 * and nothing else, so "sending" and "sent" were indistinguishable to TalkBack and to anyone who
 * cannot compare two opacities.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class MessageBubbleSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun stateDescription(value: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Composable
    private fun Bubble(
        item: ChatUiItem.Bubble,
        selected: Boolean = false,
        onTap: () -> Unit = {},
    ) {
        val context = LocalContext.current
        RebubbleTheme(darkTheme = false, dynamicColor = false) {
            MessageBubble(
                item = item,
                isSms = false,
                selected = selected,
                onLongPress = {},
                onTap = onTap,
                onRetry = {},
                onDownloadAttachment = {},
                imageLoader = ImageLoader.Builder(context).build(),
                animateSendPop = false,
            )
        }
    }

    @Test
    fun `bubble is an announceable control that still exposes its body text`() {
        rule.setContent { Bubble(bubble("m1", SendStatus.SENT)) }

        val node = rule.onNodeWithText(Body)
        node.assertHasClickAction()
        // Over-merging can swallow the body; the merged node must expose both text and status.
        node.assert(stateDescription("Sent"))
    }

    @Test
    fun `sending bubble announces Sending`() {
        rule.setContent { Bubble(bubble("temp-1", SendStatus.SENDING)) }

        rule.onNodeWithText(Body).assert(stateDescription("Sending"))
    }

    @Test
    fun `failed bubble announces Not sent`() {
        rule.setContent { Bubble(bubble("m2", SendStatus.FAILED)) }

        rule.onNodeWithText(Body).assert(stateDescription("Not sent"))
    }

    @Test
    fun `delivered receipt reaches the state description`() {
        rule.setContent {
            Bubble(bubble("m3", SendStatus.SENT, dateDelivered = FixedDate))
        }

        rule.onNodeWithText(Body).assert(stateDescription("Delivered"))
    }

    @Test
    fun `read wins over delivered in the state description`() {
        rule.setContent {
            Bubble(bubble("m4", SendStatus.SENT, dateDelivered = FixedDate, dateRead = FixedDate))
        }

        rule.onNodeWithText(Body).assert(stateDescription("Read"))
    }

    @Test
    fun `revealed timestamp reaches the state description`() {
        rule.setContent { Bubble(bubble("m5", SendStatus.SENT), selected = true) }

        rule.onNodeWithText(Body)
            .assert(stateDescription("Sent, ${formatBubbleTime(FixedDate)}"))
    }

    /**
     * Merging the bubble must not strand the attachment's own control: a merging node keeps its
     * nearest *merging* descendants as children, and `clickable` merges, so the download button
     * survives as a reachable node of its own.
     */
    @Test
    fun `attachment download button survives the bubble merge`() {
        rule.setContent {
            Bubble(bubble("m8", SendStatus.SENT).copy(attachments = listOf(pendingAttachment())))
        }

        rule.onNodeWithContentDescription("Download").assertHasClickAction()
    }

    @Test
    fun `tapping the bubble invokes onTap`() {
        var taps = 0
        rule.setContent { Bubble(bubble("m6", SendStatus.SENT), onTap = { taps++ }) }

        rule.onNodeWithText(Body).performClick()

        rule.runOnIdle { assertEquals(1, taps) }
    }

    /**
     * `ChatScreen`'s `LazyColumn` carries its own tap-to-deselect `pointerInput`. A clickable child
     * consumes the event first, so the bubble's own tap still wins, and a tap on empty thread space
     * still clears the selection. This is the regression that swapping `pointerInput` for
     * `combinedClickable` could plausibly have introduced.
     */
    @Test
    fun `thread background tap still clears the bubble selection`() {
        rule.setContent { ChatScreenHarness() }

        val time = formatBubbleTime(FixedDate)
        rule.onNodeWithText(Body).performClick()
        assertEquals(
            "tapping the bubble should reveal its timestamp",
            1,
            rule.onAllNodes(hasText(time)).fetchSemanticsNodes().size,
        )

        // Empty space above the (reverse-laid-out) thread belongs to the LazyColumn alone.
        rule.onNode(hasScrollAction()).performTouchInput { click(topCenter + Offset(0f, 8f)) }

        assertEquals(
            "tapping empty thread space should clear the selection",
            0,
            rule.onAllNodes(hasText(time)).fetchSemanticsNodes().size,
        )
    }

    @Composable
    private fun ChatScreenHarness() {
        val context = LocalContext.current
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoOpRegistryOwner) {
            RebubbleTheme(darkTheme = false, dynamicColor = false) {
                ChatScreen(
                    uiState = ChatUiState(
                        title = "Maya Chen",
                        loading = false,
                        items = listOf(bubble("m7", SendStatus.SENT)),
                    ),
                    onBack = {},
                    onSendText = {},
                    onSendAttachment = {},
                    onRetry = {},
                    onDownloadAttachment = {},
                    onLoadOlder = {},
                    imageLoader = ImageLoader.Builder(context).build(),
                )
            }
        }
    }

    private companion object {
        const val Body = "Still sending this one"
        const val FixedDate = 1_700_000_000_000L

        fun bubble(
            guid: String,
            status: SendStatus,
            dateDelivered: Long? = null,
            dateRead: Long? = null,
            fromMe: Boolean = true,
        ) = ChatUiItem.Bubble(
            message = MessageEntity(
                guid = guid,
                chatGuid = "c",
                originalRowId = null,
                text = Body,
                subject = null,
                isFromMe = fromMe,
                senderAddress = null,
                dateCreated = FixedDate,
                dateRead = dateRead,
                dateDelivered = dateDelivered,
                groupTitle = null,
                associatedMessageGuid = null,
                associatedMessageType = null,
                threadOriginatorGuid = null,
                expressiveSendStyleId = null,
                dateEdited = null,
                dateRetracted = null,
                sendStatus = status,
            ),
            attachments = emptyList(),
            isFirstInRun = true,
            isLastInRun = true,
        )

        fun pendingAttachment() = AttachmentEntity(
            guid = "a1",
            messageGuid = "m8",
            uti = null,
            mimeType = "image/jpeg",
            transferName = "photo.jpg",
            totalBytes = 1000,
            width = 1200,
            height = 800,
            hasLivePhoto = false,
            localPath = null,
            downloadState = DownloadState.NOT_DOWNLOADED,
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
