package org.dallasmakerspace.server.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.SettingsSections

/**
 * Internal JSON endpoint backing the sitewide "set your communication preference" banner in the
 * header. Returns a flag only for the authenticated user — there is no admin inspection mode.
 */
class SettingsBannerHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught", "SwallowedException")
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = userInfo["preferred_username"] as? String
    if (username.isNullOrBlank()) {
      call.respond(mapOf("showBanner" to false))
      return
    }

    val showBanner =
        try {
          val settings = memberService.getMemberSettings(username, session.sessionId)
          settings["communication_preference"].isNullOrBlank()
        } catch (e: Exception) {
          log.warn("Failed to fetch settings for banner: $username", e)
          false
        }

    call.respond(
        mapOf(
            "showBanner" to showBanner,
            "settingsUrl" to SettingsSections.url(username, SettingsSections.CONTACT_PREFERENCE)))
  }
}
