package org.dallasmakerspace.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.plugins.AuthException

/**
 * Full following/followers listing for a member, reached from the profile page's Follows card
 * (section headings and the facepile's "+N" overflow link). Rendered with the same member grid
 * as group member lists.
 *
 * Unlike the notifications page, there's no self-or-admin guard: follower lists are public (an
 * earlier, intentional product decision), so this is reachable for any username the same way the
 * profile page's facepile already is.
 */
class ProfileFollowsHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) = coroutineScope {
    val requestedUsername =
        call.parameters["preferred_username"]
            ?: throw AuthException("No username found in url path")
    val currentUsername = userInfo["preferred_username"] as? String

    // Part of the follow pilot; hide it (not just the link) when the viewer is off-pilot, same
    // as the facepile it's linked from.
    val flagEnabled =
        currentUsername?.let {
          memberServiceClient.isFeatureEnabled(FOLLOW_FEATURE_FLAG_KEY, it, session.sessionId)
        } ?: false
    if (!flagEnabled) {
      call.respond(HttpStatusCode.NotFound)
      return@coroutineScope
    }

    val followingDeferred = async {
      memberServiceClient.getFollowing(requestedUsername, session.sessionId)
    }
    val followersDeferred = async {
      memberServiceClient.getFollowers(requestedUsername, session.sessionId)
    }

    val model =
        mapOf(
            "preferred_username" to requestedUsername,
            "is_self" to (requestedUsername == currentUsername),
            // Shape expected by fragments/member-grid (items exposing `username`).
            "following" to followingDeferred.await().map { mapOf("username" to it) },
            "followers" to followersDeferred.await().map { mapOf("username" to it) },
        )
    call.respond(ThymeleafContent("follows", model))
  }

  private companion object {
    const val FOLLOW_FEATURE_FLAG_KEY = "notifications.follow-feature-enabled"
  }
}
