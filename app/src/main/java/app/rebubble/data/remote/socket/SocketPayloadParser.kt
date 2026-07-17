package app.rebubble.data.remote.socket

import android.util.Log
import app.rebubble.data.remote.dto.MessageDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a socket.io `(eventName, rawJsonPayload)` pair into a [SocketEvent].
 *
 * Extracted from [IoSocketClient] so unit tests can cover payload shapes without a live
 * engine.io connection. Malformed JSON for a *known* event becomes [SocketEvent.Unknown]
 * (logged) rather than throwing — the socket thread must never crash on a bad frame.
 */
@Singleton
class SocketPayloadParser @Inject constructor(
    private val json: Json,
) {
    fun parse(event: String, payload: String?): SocketEvent {
        return try {
            when (event) {
                EVENT_NEW_MESSAGE -> {
                    val raw = payload ?: return SocketEvent.Unknown(event)
                    SocketEvent.NewMessage(json.decodeFromString(MessageDto.serializer(), raw))
                }
                EVENT_UPDATED_MESSAGE -> {
                    val raw = payload ?: return SocketEvent.Unknown(event)
                    SocketEvent.UpdatedMessage(json.decodeFromString(MessageDto.serializer(), raw))
                }
                EVENT_TYPING_INDICATOR -> {
                    val raw = payload ?: return SocketEvent.Unknown(event)
                    val body = json.decodeFromString(TypingIndicatorPayload.serializer(), raw)
                    SocketEvent.TypingIndicator(chatGuid = body.guid, display = body.display)
                }
                EVENT_CHAT_READ_STATUS_CHANGED -> {
                    val raw = payload ?: return SocketEvent.Unknown(event)
                    val body = json.decodeFromString(ChatReadStatusPayload.serializer(), raw)
                    val read = body.read ?: body.status
                        ?: return SocketEvent.Unknown(event).also {
                            Log.w(LOG_TAG, "chat-read-status-changed missing read/status: $raw")
                        }
                    SocketEvent.ChatReadStatusChanged(chatGuid = body.chatGuid, read = read)
                }
                EVENT_MESSAGE_SEND_ERROR -> {
                    SocketEvent.MessageSendError(raw = payload ?: "")
                }
                else -> SocketEvent.Unknown(event)
            }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "failed to parse socket event=$event payload=$payload", t)
            SocketEvent.Unknown(event)
        }
    }

    companion object {
        const val EVENT_NEW_MESSAGE = "new-message"
        const val EVENT_UPDATED_MESSAGE = "updated-message"
        const val EVENT_TYPING_INDICATOR = "typing-indicator"
        const val EVENT_CHAT_READ_STATUS_CHANGED = "chat-read-status-changed"
        const val EVENT_MESSAGE_SEND_ERROR = "message-send-error"

        private const val LOG_TAG = "SocketPayloadParser"
    }
}

/** Server shape from PrivateApiTypingEventHandler: `{ display, guid }`. */
@Serializable
internal data class TypingIndicatorPayload(
    val display: Boolean,
    val guid: String,
)

/**
 * Primary emitters (`Server` iMessage listener, `ChatInterface.markRead/Unread`) send
 * `{ chatGuid, read }`. The `toggle-chat-read-status` socket route relays
 * `{ chatGuid, status }`. Accept either.
 */
@Serializable
internal data class ChatReadStatusPayload(
    val chatGuid: String,
    val read: Boolean? = null,
    val status: Boolean? = null,
)
