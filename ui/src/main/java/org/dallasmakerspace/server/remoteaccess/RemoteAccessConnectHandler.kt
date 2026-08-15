package org.dallasmakerspace.server.remoteaccess

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler

class RemoteAccessConnectHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val connectionId =
        call.parameters["connectionId"]
            ?: run {
              call.respond(HttpStatusCode.BadRequest)
              return
            }

    val currentUsername = userInfo["preferred_username"] as? String
    val featureEnabled =
        currentUsername?.let {
          memberServiceClient.isFeatureEnabled("remote-access.enabled", it, session.sessionId)
        } ?: false

    if (!featureEnabled) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }

    val rawCategories = memberServiceClient.getRemoteAccessCategories(session.sessionId)
    val userGroups =
        ((userInfo["groups"] as? List<*>) ?: emptyList<String>()).filterIsInstance<String>()

    var matchingMachine: Map<String, Any?>? = null
    val matchingCategory =
        rawCategories.firstOrNull { category ->
          @Suppress("UNCHECKED_CAST")
          val machines = (category["machines"] as? List<Map<String, Any?>>) ?: emptyList()
          val machine = machines.firstOrNull { it["connectionId"] as? String == connectionId }
          if (machine != null) matchingMachine = machine
          machine != null
        }

    if (matchingCategory == null || matchingMachine == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }

    val requiredAdGroup = matchingCategory["requiredAdGroup"] as? String ?: ""
    val userHasAccess =
        requiredAdGroup.isBlank() ||
            userGroups.any { it.trimStart('/') == requiredAdGroup.trimStart('/') }

    if (!userHasAccess) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }

    val viewModel = buildMachineViewModel(matchingMachine!!, currentUsername)
    if (viewModel["canConnect"] != true) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }

    val guacClientId = GuacamoleClientId.encode(connectionId)
    call.respondRedirect("/guacamole/#/client/$guacClientId")
  }
}
