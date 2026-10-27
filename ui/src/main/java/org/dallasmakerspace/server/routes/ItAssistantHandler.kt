package org.dallasmakerspace.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import org.dallasmakerspace.server.agent.AgentBotAccess
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory

/** The IT assistant chat page, only for members the service lets use it. */
class ItAssistantHandler(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val botAccess: AgentBotAccess,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = userInfo["preferred_username"] as? String
    if (!botAccess.canUse(BOT_KEY, username, session.sessionId)) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    call.respond(
        ThymeleafContent(
            "agent-chat",
            userInfo +
                mapOf(
                    "profile_url" to "./profile/@$username",
                    "botKey" to BOT_KEY,
                    "botTitle" to "IT Assistant")))
  }

  companion object {
    const val BOT_KEY = "it-assistant"
  }
}
