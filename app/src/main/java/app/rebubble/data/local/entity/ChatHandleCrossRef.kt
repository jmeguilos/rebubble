package app.rebubble.data.local.entity

import androidx.room.Entity

@Entity(tableName = "chat_handles", primaryKeys = ["chatGuid", "address"])
data class ChatHandleCrossRef(val chatGuid: String, val address: String)
