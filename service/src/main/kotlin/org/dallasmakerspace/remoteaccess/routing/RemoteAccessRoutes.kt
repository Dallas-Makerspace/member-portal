package org.dallasmakerspace.remoteaccess.routing

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status
import org.dallasmakerspace.remoteaccess.RemoteAccessService

fun Route.remoteAccessRoutes(remoteAccessService: RemoteAccessService) {
  get("/remote-access/categories") {
    val categories = remoteAccessService.getCategories()
    call.respond(
        ApiResponse(Status.SUCCESS, "Remote access categories: ${categories.size}", categories))
  }

  delete("/remote-access/active-connections/{activeConnectionId}") {
    val identifier =
        call.parameters["activeConnectionId"]
            ?: run {
              call.respond(HttpStatusCode.BadRequest)
              return@delete
            }
    remoteAccessService.killActiveConnection(identifier)
    call.respond(ApiResponse(Status.SUCCESS, "Killed active connection", null))
  }
}
