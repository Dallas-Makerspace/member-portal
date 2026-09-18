package org.dallasmakerspace.server.bambustatus

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.thymeleaf.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler

class BambuPrinterStatusHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val currentUsername = userInfo["preferred_username"] as? String
    val featureEnabled =
        currentUsername?.let {
          memberServiceClient.isFeatureEnabled("bambu-status.enabled", it, session.sessionId)
        } ?: false

    if (!featureEnabled) {
      call.respond(ThymeleafContent("bambu-printer-status-denied", mapOf("reason" to "disabled")))
      return
    }

    val printers =
        memberServiceClient.getBambuPrinters(session.sessionId).map(::buildPrinterViewModel)
    call.respond(ThymeleafContent("bambu-printer-status", mapOf("printers" to printers)))
  }
}
