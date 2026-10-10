package org.dallasmakerspace.server.routes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import javax.inject.Inject
import kotlinx.serialization.Serializable
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.memberservice.MemberServiceException

/**
 * PUT joins / DELETE leaves `/groups/{group_slug}/self` for the logged-in member only. The
 * service enforces the group's allow_self_add_remove setting.
 */
class GroupSelfMembershipHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  private val log = loggerFactory.create(javaClass)

  @Serializable data class ApiResponse(val success: Boolean, val message: String)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val groupSlug =
        call.parameters["group_slug"]
            ?: run {
              call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Missing group slug"))
              return
            }
    val actorUsername =
        userInfo["preferred_username"] as? String
            ?: run {
              call.respond(HttpStatusCode.Forbidden, ApiResponse(false, "Forbidden"))
              return
            }

    try {
      when (call.request.httpMethod) {
        HttpMethod.Put -> {
          memberServiceClient.joinGroup(groupSlug, actorUsername, session.sessionId)
          call.respond(HttpStatusCode.OK, ApiResponse(true, "You joined this group."))
        }
        HttpMethod.Delete -> {
          memberServiceClient.leaveGroup(groupSlug, actorUsername, session.sessionId)
          call.respond(HttpStatusCode.OK, ApiResponse(true, "You left this group."))
        }
        else ->
            call.respond(
                HttpStatusCode.MethodNotAllowed, ApiResponse(false, "Method not allowed"))
      }
    } catch (e: MemberServiceException) {
      log.warn("Self-service membership change failed for $actorUsername on $groupSlug", e)
      call.respond(
          HttpStatusCode.InternalServerError,
          ApiResponse(false, "Couldn't update your membership. Refresh the page and try again."))
    }
  }
}
