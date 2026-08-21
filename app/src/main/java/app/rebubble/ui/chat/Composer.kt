package app.rebubble.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.rebubble.ui.theme.RebubbleMotion
import app.rebubble.ui.theme.RebubbleTheme

/** Card composer control diameter. */
private val ComposerControlSize = 44.dp

/** Card `.field` border radius. */
private val ComposerFieldShape = RoundedCornerShape(24.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
    isSms: Boolean,
    onSendText: (String) -> Unit,
    onSendAttachment: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = "",
) {
    var text by remember { mutableStateOf(initialText) }
    val keyboard = LocalSoftwareKeyboardController.current
    val motion = RebubbleMotion
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            onSendAttachment(uri)
        }
    }

    val placeholder = if (isSms) "Text message" else "Message"
    val canSend = text.isNotBlank()

    fun send() {
        if (!canSend) return
        onSendText(text)
        text = ""
        keyboard?.hide()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 20.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            // Painted size stays the card's 44dp; the layout slot grows to M3's 48dp minimum and
            // centres the circle in it. `Surface(onClick=)` does not enforce the minimum itself.
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(ComposerControlSize),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Attach photo",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The field is 56dp tall, not the card's 44dp, and that is deliberate.
        //
        // `heightIn(min = 44.dp)` used to sit here and was a no-op: a filled M3 `TextField` enforces
        // `TextFieldDefaults.MinHeight` (56dp) internally, so the minimum never bound. The honest
        // options were to hand-roll the field (`BasicTextField` + `DecorationBox` with content
        // padding that really reaches 44dp) or to accept 56dp. 56dp wins: shrinking the field to
        // 44dp would manufacture a *new* sub-48dp touch target in the very change that is removing
        // them, and the field is the largest, most-used target in the composer. The row is
        // bottom-aligned, so the 48dp control slots sit flush with the field's bottom edge and the
        // 12dp difference reads as the field being taller, not as misalignment.
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = placeholder,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            shape = ComposerFieldShape,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        // Fixed slot so the field does not resize when the send button appears.
        SendButtonSlot(
            visible = canSend,
            onSend = { send() },
            enterFade = motion.fastEffectsSpec(),
            enterScale = motion.fastSpatialSpec(),
            exitFade = motion.fastEffectsSpec(),
            exitScale = motion.fastSpatialSpec(),
        )
    }
}

@Composable
private fun SendButtonSlot(
    visible: Boolean,
    onSend: () -> Unit,
    enterFade: androidx.compose.animation.core.FiniteAnimationSpec<Float>,
    enterScale: androidx.compose.animation.core.FiniteAnimationSpec<Float>,
    exitFade: androidx.compose.animation.core.FiniteAnimationSpec<Float>,
    exitScale: androidx.compose.animation.core.FiniteAnimationSpec<Float>,
) {
    // The slot reserves M3's 48dp minimum even while the button is absent, so the field does not
    // resize when the first character is typed.
    Box(
        modifier = Modifier.minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = enterFade) + scaleIn(animationSpec = enterScale),
            exit = fadeOut(animationSpec = exitFade) + scaleOut(animationSpec = exitScale),
        ) {
            FilledIconButton(
                onClick = onSend,
                // IconButton applies the interactive minimum itself, but an outer fixed `size`
                // would clamp it back to 44dp, so the minimum is declared ahead of it: 48dp slot,
                // 44dp painted circle.
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(ComposerControlSize),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    // Not Color.White: dark-theme `primary` is a light tone, and white on it is
                    // 1.71:1 — below the 3:1 minimum for icons. `onPrimary` is 10.01:1 there.
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Composer empty")
@Composable
private fun ComposerEmptyPreview() {
    RebubbleTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Composer(isSms = false, onSendText = {}, onSendAttachment = {})
        }
    }
}

@Preview(showBackground = true, name = "Composer with text")
@Composable
private fun ComposerWithTextPreview() {
    RebubbleTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Composer(
                isSms = false,
                onSendText = {},
                onSendAttachment = {},
                initialText = "On my way",
            )
        }
    }
}

@Preview(showBackground = true, name = "Composer SMS")
@Composable
private fun ComposerSmsPreview() {
    RebubbleTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Composer(isSms = true, onSendText = {}, onSendAttachment = {})
        }
    }
}
