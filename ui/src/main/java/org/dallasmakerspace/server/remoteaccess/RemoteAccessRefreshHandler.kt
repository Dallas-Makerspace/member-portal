package org.dallasmakerspace.server.remoteaccess

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.thymeleaf.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler

/**
 * Returns just the rendered `categoriesFragment` of the `remote-access` template, so the page can
 * refresh machine/session state on window focus via `fetch()` instead of a full page reload.
 * Reuses [buildRemoteAccessCategories] so this never drifts from the full-page render in
 * [RemoteAccessHandler].
 */
class RemoteAccessRefreshHandler
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
      call.respond(HttpStatusCode.Forbidden)
      return
    }

    val categories =
        buildRemoteAccessCategories(memberServiceClient, session.sessionId, userInfo, currentUsername)
    val canKillConnections = authz.can(Permission.MANAGE_REMOTE_ACCESS.name)
    call.respond(
        ThymeleafContent(
            "remote-access",
            mapOf("categories" to categories, "canKillConnections" to canKillConnections),
            fragments = setOf("categoriesFragment"),
        ))
  }
}
