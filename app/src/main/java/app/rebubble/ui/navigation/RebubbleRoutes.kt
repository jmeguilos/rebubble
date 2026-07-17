package app.rebubble.ui.navigation

import app.rebubble.data.repo.ServerConfig

object RebubbleRoutes {
    const val ONBOARDING = "onboarding"
    const val CHATS = "chats"
}

/** Start destination from a one-shot config snapshot collected at cold start. */
fun startDestinationForConfig(config: ServerConfig?): String =
    if (config == null) RebubbleRoutes.ONBOARDING else RebubbleRoutes.CHATS
