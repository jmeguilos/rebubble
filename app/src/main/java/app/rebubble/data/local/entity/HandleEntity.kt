package app.rebubble.data.local.entity

import androidx.room.Entity

@Entity(tableName = "handles", primaryKeys = ["address", "service"])
data class HandleEntity(val address: String, val service: String)
