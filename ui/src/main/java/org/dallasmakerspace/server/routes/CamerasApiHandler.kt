package org.dallasmakerspace.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.chronoring.ChronoRingClient
import org.dallasmakerspace.server.common.logging.LoggerFactory

class CamerasApiHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val chronoRingClient: ChronoRingClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val path = call.request.path().removePrefix("/cameras-api/")
    val queryString = call.request.queryString()
    val fullPath = if (queryString.isNotEmpty()) "$path?$queryString" else path

    when (call.request.httpMethod) {
      HttpMethod.Get -> {
        if (isBinaryPath(path)) {
          chronoRingClient.proxyBinary(fullPath, call)
        } else {
          // Covers /timelapse (combined), /streams, /streams/{id}/timelapse, and any other JSON
          // endpoints.
          chronoRingClient.proxyJson(fullPath, call)
        }
      }
      HttpMethod.Post -> {
        val body = call.receiveText()
        chronoRingClient.proxyJsonPost(fullPath, body, call)
      }
      else -> call.respond(HttpStatusCode.MethodNotAllowed)
    }
  }

  private fun isBinaryPath(path: String): Boolean =
      path.matches("^frame/[^/]+$".toRegex()) ||
          path.matches("^streams/[^/]+/saved-frame/[^/]+$".toRegex())
}
