package org.dallasmakerspace.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import org.dallasmakerspace.server.agent.AgentBotAccess
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory

/** A bot's chat page, only for members the service lets use that bot. */
class BotPageHandler(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val botAccess: AgentBotAccess,
    private val botKey: String,
    private val botTitle: String,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = userInfo["preferred_username"] as? String
    if (!botAccess.canUse(botKey, username, session.sessionId)) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    call.respond(
        ThymeleafContent(
            "agent-chat",
            userInfo +
                mapOf(
                    "profile_url" to "./profile/@$username",
                    "botKey" to botKey,
                    "botTitle" to botTitle)))
  }

  companion object {
    const val IT_BOT_KEY = "it-assistant"
    const val COURSE_BOT_KEY = "course-assistant"
  }
}
