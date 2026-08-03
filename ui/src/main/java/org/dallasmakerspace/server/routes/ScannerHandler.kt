package org.dallasmakerspace.server.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.thymeleaf.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory

class ScannerHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val userGroups = (userInfo["groups"] as? List<*>) ?: emptyList<String>()
    val isPrivilegedViewer =
        userGroups.contains("/Infrastructure") || userGroups.contains("/Logistics Committee Chair")
    call.respond(ThymeleafContent("scanner", mapOf("isPrivilegedViewer" to isPrivilegedViewer)))
  }
}
