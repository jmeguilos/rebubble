package app.rebubble.ui.navigation

import android.net.Uri
import app.rebubble.data.repo.ServerConfig

object RebubbleRoutes {
    const val ONBOARDING = "onboarding"
    const val CHATS = "chats"
    const val CHAT = "chat/{guid}"

    fun chat(guid: String): String = "chat/${Uri.encode(guid)}"
}

/** Start destination from a one-shot config snapshot collected at cold start. */
fun startDestinationForConfig(config: ServerConfig?): String =
    if (config == null) RebubbleRoutes.ONBOARDING else RebubbleRoutes.CHATS
