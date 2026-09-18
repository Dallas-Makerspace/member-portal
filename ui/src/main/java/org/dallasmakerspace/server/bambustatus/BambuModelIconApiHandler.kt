package org.dallasmakerspace.server.bambustatus

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler

class BambuModelIconApiHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val bambuCameraProxyClient: BambuCameraProxyClient,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val model = call.parameters["model"]
    if (model.isNullOrBlank()) {
      call.respond(HttpStatusCode.BadRequest)
      return
    }
    // Mirrors BambuPrinterStatusHandler's gate - keeps this proxy behind the same pilot flag as the
    // rest of the feature rather than being reachable by any logged-in member regardless of rollout.
    val username = userInfo["preferred_username"] as? String
    val featureEnabled =
        username?.let {
          memberServiceClient.isFeatureEnabled("bambu-status.enabled", it, session.sessionId)
        } ?: false
    if (!featureEnabled) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    bambuCameraProxyClient.proxyModelIcon(model, call)
  }
}
