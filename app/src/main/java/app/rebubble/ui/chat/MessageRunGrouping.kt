package app.rebubble.ui.chat

import app.rebubble.data.local.entity.MessageEntity

/** Max gap (ms) between consecutive same-sender messages to stay in one visual run. */
const val RUN_GROUP_WINDOW_MS: Long = 60_000L

/**
 * Per-bubble flags for consecutive-run geometry: tight inner radii + a directional tail only on
 * the chronologically last bubble of a same-sender run (within [RUN_GROUP_WINDOW_MS]).
 */
data class BubbleRunFlags(
    val showTail: Boolean,
    val isFirstInRun: Boolean,
    val isLastInRun: Boolean,
)

/**
 * Computes run flags for ordinary bubbles (`itemType == 0`). Group-event rows are ignored.
 *
 * [messages] may be newest-first or oldest-first; ordering is normalized internally. Returns a
 * map keyed by message guid covering only `itemType == 0` rows.
 */
fun computeBubbleRunFlags(messages: List<MessageEntity>): Map<String, BubbleRunFlags> {
    val chronological = messages
        .filter { it.itemType == 0 }
        .sortedBy { it.dateCreated }
    if (chronological.isEmpty()) return emptyMap()

    val result = LinkedHashMap<String, BubbleRunFlags>(chronological.size)
    var runStart = 0
    for (i in 1..chronological.size) {
        val endOfRun = i == chronological.size ||
            !sameSender(chronological[i - 1], chronological[i]) ||
            chronological[i].dateCreated - chronological[i - 1].dateCreated > RUN_GROUP_WINDOW_MS
        if (endOfRun) {
            val runLast = i - 1
            for (j in runStart..runLast) {
                result[chronological[j].guid] = BubbleRunFlags(
                    showTail = j == runLast,
                    isFirstInRun = j == runStart,
                    isLastInRun = j == runLast,
                )
            }
            runStart = i
        }
    }
    return result
}

internal fun sameSender(a: MessageEntity, b: MessageEntity): Boolean {
    if (a.isFromMe != b.isFromMe) return false
    if (a.isFromMe) return true
    return a.senderAddress == b.senderAddress
}
