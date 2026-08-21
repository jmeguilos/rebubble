package app.rebubble.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rebubble.data.sync.SyncStatus

private val ChipShape = RoundedCornerShape(50)

/**
 * Slim sync-status pill shown under the search bar.
 * Idle → nothing. Syncing → progress + "Syncing…". Error → dismissible errorContainer chip.
 */
@Composable
fun SyncStatusChip(
    status: SyncStatus,
    modifier: Modifier = Modifier,
    onDismissError: (() -> Unit)? = null,
) {
    when (status) {
        SyncStatus.Idle -> Unit
        SyncStatus.Syncing -> {
            Surface(
                modifier = modifier,
                shape = ChipShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Syncing…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is SyncStatus.Error -> {
            var dismissed by remember(status.at) { mutableStateOf(false) }
            if (!dismissed) {
                Surface(
                    modifier = modifier,
                    shape = ChipShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        // No vertical padding: the dismiss button's 48dp slot (below) already sets
                        // this pill's height, so 2dp top/bottom would only make it 52dp.
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Sync issue — retrying",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        IconButton(
                            onClick = {
                                dismissed = true
                                onDismissError?.invoke()
                            },
                            // IconButton applies the 48dp interactive minimum itself, but the
                            // explicit 32dp size clamped it back down; declaring the minimum first
                            // keeps the 32dp state layer inside a real 48dp target. Measured cost:
                            // the pill goes from 36dp to 48dp tall — the honest price of a dismiss
                            // control a finger can actually hit.
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}
