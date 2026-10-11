package org.dallasmakerspace.members.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Groups
import org.dallasmakerspace.core.api.Status
import org.dallasmakerspace.core.appLogger
import org.dallasmakerspace.members.GroupSettingsRegistry
import org.dallasmakerspace.members.GroupSettingsService
import org.dallasmakerspace.members.MemberService

private val log = appLogger("GroupSelfMembershipRoutes")

/**
 * Self-service join/leave for the `X-Actor-Username` member only, gated by the group's
 * `allow_self_add_remove` setting. Only direct AD membership counts. [groupLockMessage] returns a
 * reason when a workflow run has locked the group (join/leave then gets a 409), else null.
 */
fun Route.groupSelfMembershipRoutes(
    groupSettingsService: GroupSettingsService,
    memberService: MemberService,
    groupLockMessage: suspend (groupName: String) -> String? = { null },
) {
  authenticate(ApiKeyAuthProvider.X_API_KEY) {
    authorize(Permission.MANAGE_GROUPS) {
      get("/groups/{groupslug}/self") { handleStatus(call, groupSettingsService, memberService) }
      put("/groups/{groupslug}/self") {
        handleChange(call, groupSettingsService, memberService, groupLockMessage, join = true)
      }
      delete("/groups/{groupslug}/self") {
        handleChange(call, groupSettingsService, memberService, groupLockMessage, join = false)
      }
    }
  }
}

@Suppress("ReturnCount", "TooGenericExceptionCaught")
private suspend fun handleStatus(
    call: ApplicationCall,
    groupSettingsService: GroupSettingsService,
    memberService: MemberService,
) {
  val groupslug = call.parameters["groupslug"] ?: return call.respondMissingGroup()
  val actor = call.actorUsername() ?: return call.respondMissingActor()
  val groupName = Groups.getNameFromSlug(groupslug)

  if (!groupSettingsService.isEnabled(groupName, GroupSettingsRegistry.ALLOW_SELF_ADD_REMOVE)) {
    return call.respondStatus(allowed = false, isMember = false)
  }
  val isMember =
      try {
        memberService.isDirectMemberOfGroup(actor, groupslug)
      } catch (e: Exception) {
        log.warn("Could not check '$actor' membership in $groupName; hiding self-service", e)
        return call.respondStatus(allowed = false, isMember = false)
      }
  call.respondStatus(allowed = true, isMember = isMember)
}

@Suppress("ReturnCount")
private suspend fun handleChange(
    call: ApplicationCall,
    groupSettingsService: GroupSettingsService,
    memberService: MemberService,
    groupLockMessage: suspend (String) -> String?,
    join: Boolean,
) {
  val groupslug = call.parameters["groupslug"] ?: return call.respondMissingGroup()
  val actor = call.actorUsername() ?: return call.respondMissingActor()
  val groupName = Groups.getNameFromSlug(groupslug)

  if (!groupSettingsService.isEnabled(groupName, GroupSettingsRegistry.ALLOW_SELF_ADD_REMOVE)) {
    return call.respond(
        HttpStatusCode.Forbidden,
        ApiResponse(Status.ERROR, "$groupName does not allow members to join or leave", null))
  }
  groupLockMessage(groupName)?.let {
    return call.respond(HttpStatusCode.Conflict, ApiResponse(Status.ERROR, it, null))
  }
  val isMember = memberService.isDirectMemberOfGroup(actor, groupslug)
  if (join && isMember) {
    return call.respond(
        HttpStatusCode.Conflict,
        ApiResponse(Status.ERROR, "$actor is already a member of $groupName", null))
  }
  if (!join && !isMember) {
    return call.respond(
        HttpStatusCode.Conflict,
        ApiResponse(Status.ERROR, "$actor is not a direct member of $groupName", null))
  }

  if (join) {
    memberService.addMembersToGroup(listOf(actor), groupslug, actor)
    log.info("$actor joined $groupName via self-service")
    call.respond(ApiResponse(Status.SUCCESS, "$actor joined $groupName", null))
  } else {
    memberService.removeMembersToGroup(listOf(actor), groupslug, actor)
    log.info("$actor left $groupName via self-service")
    call.respond(ApiResponse(Status.SUCCESS, "$actor left $groupName", null))
  }
}

private fun ApplicationCall.actorUsername(): String? =
    request.headers["X-Actor-Username"]?.takeIf { it.isNotBlank() }

private suspend fun ApplicationCall.respondStatus(allowed: Boolean, isMember: Boolean) =
    respond(
        ApiResponse(
            Status.SUCCESS,
            "Self-service membership status",
            mapOf("allowed" to allowed, "isMember" to isMember)))

private suspend fun ApplicationCall.respondMissingGroup() =
    respond(HttpStatusCode.BadRequest, ApiResponse(Status.ERROR, "Group is required", null))

private suspend fun ApplicationCall.respondMissingActor() =
    respond(
        HttpStatusCode.BadRequest,
        ApiResponse(Status.ERROR, "X-Actor-Username header is required", null))
