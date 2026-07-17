package app.rebubble.data.remote.socket

import app.rebubble.data.remote.dto.MessageDto

/**
 * Realtime events from the BlueBubbles socket.io server.
 *
 * Payloads are **raw** serialized objects (not wrapped in the REST
 * [app.rebubble.data.remote.dto.Envelope]). Event names match
 * `packages/server/src/server/events.ts`.
 */
sealed interface SocketEvent {
    /** `new-message` — raw [MessageDto] (typically with `chats` included for socket). */
    data class NewMessage(val dto: MessageDto) : SocketEvent

    /** `updated-message` — raw [MessageDto]. */
    data class UpdatedMessage(val dto: MessageDto) : SocketEvent

    /**
     * `typing-indicator` — server shape `{ display: Boolean, guid: <chatGuid> }`
     * (see PrivateApiTypingEventHandler).
     */
    data class TypingIndicator(val chatGuid: String, val display: Boolean) : SocketEvent

    /**
     * `chat-read-status-changed` — primary server emitters use `{ chatGuid, read }`;
     * the `toggle-chat-read-status` relay uses `{ chatGuid, status }`. Parsed as [read].
     */
    data class ChatReadStatusChanged(val chatGuid: String, val read: Boolean) : SocketEvent

    /**
     * `message-send-error` — keep the payload as raw JSON for M1 (no ingest path yet).
     */
    data class MessageSendError(val raw: String) : SocketEvent

    /** Unrecognized event name, or a known event whose payload could not be decoded. */
    data class Unknown(val event: String) : SocketEvent
}
