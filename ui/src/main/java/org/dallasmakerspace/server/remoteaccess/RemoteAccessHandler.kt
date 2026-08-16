package org.dallasmakerspace.server.remoteaccess

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.thymeleaf.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.models.getSlugFromName
import org.dallasmakerspace.server.routes.AuthenticatedHandler

private val CHICAGO_ZONE = ZoneId.of("America/Chicago")

/** Converts a UTC epoch-millis session start time into the Chicago-local timestamp string that
 * `<dms-relative-date>` expects (timezone-less values are interpreted as America/Chicago). */
fun epochMsToChicagoDisplay(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(CHICAGO_ZONE)
        .toLocalDateTime()
        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

/**
 * Reshapes a single raw machine map (as returned by the backend's /remote-access/categories) into
 * the view model the `remote-access` template renders, resolving the requesting user's own
 * per-machine capacity (maxConnectionsPerUser) - something the backend can't do since it has no
 * per-request user context. Extracted as a pure function so it's unit-testable without Ktor.
 */
fun buildMachineViewModel(
    machine: Map<String, Any?>,
    currentUsername: String?,
): Map<String, Any?> {
  @Suppress("UNCHECKED_CAST")
  val rawSessions = (machine["sessions"] as? List<Map<String, Any?>>) ?: emptyList()

  val sessions =
      rawSessions.map { sess ->
        val startEpochMs = (sess["sessionStartEpochMs"] as? Number)?.toLong() ?: 0L
        mapOf(
            "activeConnectionId" to (sess["activeConnectionId"] as? String ?: ""),
            "username" to (sess["username"] as? String ?: ""),
            "sessionStartDisplay" to epochMsToChicagoDisplay(startEpochMs),
        )
      }

  val maxConnectionsPerUser = (machine["maxConnectionsPerUser"] as? Number)?.toInt()
  val userSessionCount = rawSessions.count { it["username"] as? String == currentUsername }
  val userAtPersonalCap =
      maxConnectionsPerUser != null &&
          maxConnectionsPerUser > 0 &&
          userSessionCount >= maxConnectionsPerUser

  val globalCanConnect = machine["canConnect"] as? Boolean ?: false

  return mapOf(
      "connectionId" to (machine["connectionId"] as? String ?: ""),
      "displayName" to (machine["displayName"] as? String ?: ""),
      "protocol" to (machine["protocol"] as? String ?: ""),
      "status" to (machine["status"] as? String ?: ""),
      "sessions" to sessions,
      "canConnect" to (globalCanConnect && !userAtPersonalCap),
  )
}

/**
 * Fetches and reshapes remote-access categories into the view model the `remote-access` template
 * (and its `categoriesFragment`, used for the AJAX refresh) render. Shared by [RemoteAccessHandler]
 * and [RemoteAccessRefreshHandler] so the full-page render and the partial refresh never drift.
 */
suspend fun buildRemoteAccessCategories(
    memberServiceClient: MemberServiceClient,
    sessionId: String?,
    userInfo: Map<String, Any>,
    currentUsername: String?,
): List<Map<String, Any?>> {
  val rawCategories = memberServiceClient.getRemoteAccessCategories(sessionId)
  val userGroups =
      ((userInfo["groups"] as? List<*>) ?: emptyList<String>()).filterIsInstance<String>()

  return rawCategories.map { category ->
    val requiredAdGroup = category["requiredAdGroup"] as? String ?: ""
    val userHasAccess =
        requiredAdGroup.isBlank() ||
            userGroups.any { it.trimStart('/') == requiredAdGroup.trimStart('/') }

    @Suppress("UNCHECKED_CAST")
    val rawMachines =
        (category["machines"] as? List<Map<String, Any?>>) ?: emptyList<Map<String, Any?>>()

    val machines =
        rawMachines.map { machine ->
          val viewModel = buildMachineViewModel(machine, currentUsername)
          viewModel + mapOf("userCanConnect" to (userHasAccess && viewModel["canConnect"] as Boolean))
        }

    mapOf(
        "slug" to (category["slug"] as? String ?: ""),
        "displayName" to (category["displayName"] as? String ?: ""),
        "description" to (category["description"] as? String ?: ""),
        "requiredAdGroup" to requiredAdGroup,
        "requiredAdGroupSlug" to (if (requiredAdGroup.isBlank()) "" else getSlugFromName(requiredAdGroup)),
        "userHasAccess" to userHasAccess,
        "machines" to machines,
    )
  }
}

class RemoteAccessHandler
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
          memberServiceClient.isFeatureEnabled("remote-access.enabled", it, session.sessionId)
        } ?: false

    if (!featureEnabled) {
      call.respond(ThymeleafContent("remote-access-denied", mapOf("reason" to "disabled")))
      return
    }

    val categories =
        buildRemoteAccessCategories(memberServiceClient, session.sessionId, userInfo, currentUsername)
    val canKillConnections = authz.can(Permission.MANAGE_REMOTE_ACCESS.name)
    call.respond(
        ThymeleafContent(
            "remote-access",
            mapOf("categories" to categories, "canKillConnections" to canKillConnections)))
  }
}
