package org.dallasmakerspace.server.bambustatus

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler

class BambuCameraApiHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val bambuCameraProxyClient: BambuCameraProxyClient,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val printerId = call.parameters["id"]
    if (printerId.isNullOrBlank()) {
      call.respond(HttpStatusCode.BadRequest)
      return
    }
    // Mirrors BambuPrinterStatusHandler's gate - without this, a logged-in member could hit this
    // proxy directly (guessing small numeric printer IDs) and see live camera snapshots even while
    // the feature is off, or still limited to a pilot group, for them.
    val username = userInfo["preferred_username"] as? String
    val featureEnabled =
        username?.let {
          memberServiceClient.isFeatureEnabled("bambu-status.enabled", it, session.sessionId)
        } ?: false
    if (!featureEnabled) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    bambuCameraProxyClient.proxyCameraSnapshot(printerId, call)
  }
}
