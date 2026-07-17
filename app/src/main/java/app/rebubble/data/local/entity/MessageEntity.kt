package app.rebubble.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local-only send lifecycle for outbound messages; never present in the server wire format. */
enum class SendStatus { SENT, SENDING, FAILED }

@Entity(
    tableName = "messages",
    indices = [
        Index("chatGuid", "dateCreated"),
        Index("originalRowId"),
        Index("threadOriginatorGuid"),
    ],
)
data class MessageEntity(
    @PrimaryKey val guid: String, // "temp-<8hex>" until ack swaps it
    val chatGuid: String,
    val originalRowId: Long?,
    val text: String?,
    val subject: String?,
    val isFromMe: Boolean,
    val senderAddress: String?,
    val dateCreated: Long,
    val dateRead: Long?,
    val dateDelivered: Long?,
    val error: Int = 0,
    val itemType: Int = 0,
    val groupActionType: Int = 0,
    val groupTitle: String?,
    val associatedMessageGuid: String?, // stored PRE-stripped of p:N/ and bp: prefixes
    val associatedMessageType: String?,
    val threadOriginatorGuid: String?,
    val expressiveSendStyleId: String?,
    val dateEdited: Long?,
    val dateRetracted: Long?,
    val sendStatus: SendStatus,
)
