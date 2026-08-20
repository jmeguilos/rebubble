package app.rebubble.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Extra-large top-only radius for the conversation list sheet (Messages-style). */
val ListSheetTopShape = RoundedCornerShape(
    topStart = 28.dp,
    topEnd = 28.dp,
)

/** Full pill used by the docked search bar. */
val SearchPillShape = RoundedCornerShape(28.dp)

/** "Start chat" extended FAB corner radius (design/screens/chat-list.html `.fab`, 18px). */
val StartChatFabShape = RoundedCornerShape(18.dp)

val RebubbleShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
