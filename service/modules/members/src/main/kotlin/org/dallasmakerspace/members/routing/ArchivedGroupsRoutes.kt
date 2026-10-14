package org.dallasmakerspace.members.routing

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status
import org.dallasmakerspace.members.IGroupArchiveRepository

/**
 * `GET /groups/archived?prefix=<p>&prefix=<q>`: archived groups, newest first. Each prefix filters
 * group names case-insensitively; no prefix returns all. The static segment wins over
 * `/groups/{groupslug}`.
 */
fun Route.archivedGroupsRoutes(archiveRepository: IGroupArchiveRepository) {
  authenticate(ApiKeyAuthProvider.X_API_KEY) {
    authorize(Permission.MANAGE_GROUPS) {
      get("/groups/archived") {
        val prefixes = call.request.queryParameters.getAll("prefix").orEmpty()
        val groups = archiveRepository.listArchived(prefixes)
        call.respond(ApiResponse(Status.SUCCESS, "Archived groups: ${groups.size}", groups))
      }
    }
  }
}
