package org.dallasmakerspace.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.plugins.AuthException

class ProfileKioskWaiverPhotosHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    if (!call.require(Permission.MANAGE_MEMBERS)) return

    val requestedUsername =
        call.parameters["preferred_username"]
            ?: throw AuthException("No username found in url path")

    try {
      val waivers = memberService.getKioskWaiverPhotos(requestedUsername, session.sessionId)
      // Audit trail: face photos are sensitive, so every view records who looked at whose.
      log.info(
          "Kiosk waiver photos viewed: viewer=${userInfo["preferred_username"]} " +
              "subject=$requestedUsername waivers=${waivers.size} " +
              "photos=${waivers.sumOf { it.photos.size }}")
      call.respond(mapOf("waivers" to waivers))
    } catch (e: Exception) {
      log.warn("Failed to fetch kiosk waiver photos for $requestedUsername", e)
      call.respond(
          HttpStatusCode.BadGateway, mapOf("error" to "Failed to fetch kiosk waiver photos"))
    }
  }
}
