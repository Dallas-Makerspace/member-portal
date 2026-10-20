package org.dallasmakerspace.server.discourse

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberSettingsHandler
import org.dallasmakerspace.server.memberservice.SettingsSections
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.routes.AuthenticatedHandler
import org.dallasmakerspace.server.routes.isSameOriginRequest

/** Unlinks the member's Talk account (POST from the settings page). */
class UnlinkDiscourseHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    if (!call.isSameOriginRequest()) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }
    val username =
        userInfo["preferred_username"] as? String
            ?: throw AuthException("No username found in user info")
    memberService.unlinkDiscourseAccount(username, session.sessionId)
    call.respondRedirect(
        "/profile/@$username/settings?updated=${MemberSettingsHandler.TALK_UNLINKED}" +
            "#${SettingsSections.LINKED_ACCOUNTS}",
        permanent = false)
  }
}
