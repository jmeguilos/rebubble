package app.rebubble.data.local

import androidx.room.TypeConverter
import app.rebubble.data.local.entity.DownloadState
import app.rebubble.data.local.entity.SendStatus

/** Local-only enums are stored as their [Enum.name] TEXT representation. */
class Converters {

    @TypeConverter
    fun sendStatusToString(value: SendStatus): String = value.name

    @TypeConverter
    fun stringToSendStatus(value: String): SendStatus = SendStatus.valueOf(value)

    @TypeConverter
    fun downloadStateToString(value: DownloadState): String = value.name

    @TypeConverter
    fun stringToDownloadState(value: String): DownloadState = DownloadState.valueOf(value)
}
