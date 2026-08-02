package org.dallasmakerspace.server.memberservice

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.sessions.set
import io.ktor.server.sessions.sessions
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import org.dallasmakerspace.models.CommunicationPreference
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.routes.AuthenticatedHandler

class MemberSettingsHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught", "SwallowedException")
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val requestedUsername =
        call.parameters["preferred_username"]
            ?: throw AuthException("No username found in url path")

    val currentUsername = userInfo["preferred_username"] as String?
    val canManageMembers = authz.can(Permission.MANAGE_MEMBERS.name)
    if (requestedUsername != currentUsername && !canManageMembers) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }

    if (call.request.httpMethod == HttpMethod.Post) {
      val params = call.receiveParameters()
      val communicationPreference = params["communication_preference"]

      memberService.updateMemberSettings(
          requestedUsername, currentUsername, communicationPreference, session.sessionId)

      call.sessions.set(session.copy(isSettingsUpdateSuccess = true))
      call.respondRedirect("/profile/@$requestedUsername/settings", permanent = false)
      return
    }

    val settings = memberService.getMemberSettings(requestedUsername, session.sessionId)
    val history = memberService.getMemberSettingsHistory(requestedUsername, session.sessionId)

    var discourseLinked = false
    var discordLinked = false
    try {
      val member = memberService.getMember(requestedUsername, session.sessionId)
      discourseLinked = member.discourseUsername?.isNotEmpty() == true
      discordLinked = member.discordUserId?.isNotEmpty() == true
    } catch (e: Exception) {
      log.warn("Failed to fetch member for settings page: $requestedUsername", e)
    }

    val jsonMap =
        mutableMapOf<String, Any>(
            "preferred_username" to requestedUsername,
            "is_self" to (requestedUsername == currentUsername).toString(),
            "authz" to authz,
            "communication_preferences" to
                CommunicationPreference.entries.map { pref -> mapOf("value" to pref.name, "label" to pref.label) },
            "communication_preference" to (settings["communication_preference"] ?: ""),
            "discourse_linked" to discourseLinked,
            "discord_linked" to discordLinked,
            "settings_history" to history,
        )

    if (session.isSettingsUpdateSuccess) {
      log.info("Setting toast for successful settings update")
      call.sessions.set(session.copy(isSettingsUpdateSuccess = false))
      jsonMap["toast_message"] = "Communication preference updated."
    }

    call.respond(ThymeleafContent("settings", jsonMap))
  }
}
