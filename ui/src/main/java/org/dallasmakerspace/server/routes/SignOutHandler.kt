package org.dallasmakerspace.server.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import javax.inject.Inject
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.plugins.UserSession

/** Ends the portal session only; the Keycloak SSO session is left alone. */
class SignOutHandler @Inject constructor(private val appConfig: AppConfig) : IRouteHandler {
  override suspend fun handle(call: ApplicationCall) {
    call.sessions.clear<UserSession>()
    call.respondRedirect(appConfig.requireStringProperty("app.sign-out-redirect-url"))
  }
}
