package org.dallasmakerspace.server.remoteaccess

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler

class RemoteAccessDisconnectHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    if (!call.require(Permission.MANAGE_REMOTE_ACCESS)) return

    // NOTE: despite the "connectionId" path param name (kept for route-wiring stability), this
    // now holds a specific Guacamole *active connection* (session) id, sent directly by the
    // template's kill button - a machine can have multiple concurrent sessions, so there's no
    // longer a single "the" active connection to derive by re-looking-up the machine.
    val activeConnectionId =
        call.parameters["connectionId"]
            ?: run {
              call.respond(HttpStatusCode.BadRequest)
              return
            }

    memberServiceClient.killActiveConnection(activeConnectionId, session.sessionId)
    call.respond(HttpStatusCode.NoContent)
  }
}
