package org.dallasmakerspace.server.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import javax.inject.Inject
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient

/**
 * `GET /api/members-to-follow` — the home page's "Members to Follow" suggestions. Infra users
 * (MANAGE_MEMBERS) can pass `?username=` to inspect another member's list; that call also returns a
 * `debug` breakdown and ignores the follow feature flag (reporting it instead).
 */
class MembersToFollowHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = userInfo["preferred_username"] as? String
    if (username == null) {
      call.respond(disabled())
      return
    }
    try {
      val inspected =
          call.request.queryParameters["username"]?.takeIf {
            it.isNotBlank() && authz.can(Permission.MANAGE_MEMBERS.name)
          }
      if (inspected != null) {
        call.respond(inspect(inspected))
        return
      }
      if (!memberServiceClient.isFeatureEnabled(
          ProfileFollowsHandler.FEATURE_FLAG_KEY, username, session.sessionId)) {
        log.debug("Members to follow: follow feature disabled for $username")
        call.respond(disabled())
        return
      }
      val usernames = memberServiceClient.getFollowSuggestions(username, session.sessionId)
      val (cards, _) = resolveCards(username, usernames)
      call.respond(mapOf("enabled" to true, "members" to cards))
    } catch (e: Exception) {
      log.warn("Failed to get members to follow", e)
      call.respond(disabled())
    }
  }

  private suspend fun inspect(target: String): Map<String, Any?> {
    val data = memberServiceClient.getFollowSuggestionsDebug(target, session.sessionId)
    val usernames = (data?.get("suggestions") as? List<*>).orEmpty().filterIsInstance<String>()
    val (cards, dropped) = resolveCards(target, usernames)
    val debug = mutableMapOf<Any?, Any?>()
    (data?.get("debug") as? Map<*, *>)?.forEach { (k, v) -> debug[k] = v }
    debug["flagEnabled"] = data?.get("flagEnabled")
    debug["disabledDropped"] = dropped
    debug["error"] = data == null
    return mapOf("enabled" to true, "members" to cards, "debug" to debug)
  }

  /** Cards for the enabled members among [usernames]; second value lists those dropped. */
  private suspend fun resolveCards(
      forUsername: String,
      usernames: List<String>,
  ): Pair<List<Map<String, String>>, List<String>> {
    val members = memberServiceClient.getMembersByUsernames(usernames, session.sessionId)
    val dropped = usernames.filter { members[it]?.enabled != true }
    log.debug(
        "Members to follow for $forUsername: suggestions=$usernames resolved=${members.keys} " +
            "dropped=$dropped")
    val cards = usernames.mapNotNull { members[it] }.filter { it.enabled }.map { it.toCard() }
    return cards to dropped
  }

  private fun DMSMember.toCard(): Map<String, String> =
      mapOf(
          "username" to username,
          "displayName" to (displayName ?: username),
          "avatarUrl" to normalizeAvatarUrl(displayAvatarUrl()),
          "profileUrl" to "/profile/@$username",
      )

  private fun disabled(): Map<String, Any> =
      mapOf("enabled" to false, "members" to emptyList<Any>())
}
