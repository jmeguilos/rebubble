package app.rebubble.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val address: String,
    val displayName: String?,
    val avatarPath: String?,
)
