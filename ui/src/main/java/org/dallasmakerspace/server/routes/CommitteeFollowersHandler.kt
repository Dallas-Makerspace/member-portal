package org.dallasmakerspace.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.plugins.AuthException

/**
 * Everyone following a committee, reached from the committee page's follower stack. Like the
 * profile follows page, the list is visible to any signed-in member; it is hidden (404) wherever
 * the follow button is, i.e. for committees that can't be followed and viewers off the pilot.
 */
class CommitteeFollowersHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) = coroutineScope {
    val committeeSlug =
        call.parameters["committee_slug"]
            ?: throw AuthException("No committee slug found in url path")
    val committee = Committees.findBySlug(committeeSlug)
    val currentUsername = userInfo["preferred_username"] as? String
    if (committee == null || currentUsername == null || committee.groupPrefixes.isEmpty()) {
      call.respond(HttpStatusCode.NotFound)
      return@coroutineScope
    }
    val followEnabled =
        memberService.getSubscriptionStatus(
            TopicKeys.COMMITTEE_UPDATES,
            mapOf("committee" to committee.id.toString()),
            currentUsername,
            session.sessionId) != null
    if (!followEnabled) {
      call.respond(HttpStatusCode.NotFound)
      return@coroutineScope
    }

    val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
    val followers =
        memberService.getCommitteeFollowers(
            committee.id, PAGE_SIZE, (page - 1) * PAGE_SIZE, session.sessionId)
    val pageCount = ((followers.total + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
    if (page > pageCount) {
      call.respondRedirect("/committees/${committee.slug}/followers?page=$pageCount")
      return@coroutineScope
    }

    val model =
        mapOf(
            "committee" to mapOf("name" to committee.name, "slug" to committee.slug),
            "total" to followers.total,
            // Shape expected by fragments/member-grid (items exposing `username`).
            "followers" to followers.usernames.map { mapOf("username" to it) },
            "page" to page,
            "page_count" to pageCount,
        )
    call.respond(ThymeleafContent("committee-followers", model))
  }

  companion object {
    const val PAGE_SIZE = 48
  }
}
