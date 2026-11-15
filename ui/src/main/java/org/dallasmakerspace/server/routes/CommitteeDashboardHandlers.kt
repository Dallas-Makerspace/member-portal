package org.dallasmakerspace.server.routes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.DashboardCallResult
import org.dallasmakerspace.server.memberservice.DashboardServiceClient
import org.dallasmakerspace.server.memberservice.MemberService

private val json = jacksonObjectMapper()

private fun ApplicationCall.committee(): Committee? =
    parameters["committee_slug"]?.let { Committees.findBySlug(it) }

private suspend fun ApplicationCall.respondResult(result: DashboardCallResult) {
  val ok = result.statusCode in HttpStatusCode.OK.value until HttpStatusCode.MultipleChoices.value
  respond(
      HttpStatusCode.fromValue(result.statusCode),
      mapOf(
          "status" to if (ok) "SUCCESS" else "ERROR",
          "message" to (result.message ?: ""),
          "data" to result.data))
}

/** `/committees/{slug}/dashboard`: where a chair builds the committee's TV dashboard. */
class CommitteeDashboardHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val dashboard: DashboardServiceClient,
    private val memberService: MemberService,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  @Suppress("ReturnCount", "TooGenericExceptionCaught")
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val committee = call.committee()
    val username = userInfo["preferred_username"] as? String
    if (committee == null || username == null || !canEdit(committee, username)) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val groups =
        try {
          memberService.getAllGroups(session.sessionId).map { it.name }
        } catch (e: Exception) {
          log.warn("Failed to fetch groups for the dashboard editor: {}", e.message)
          emptyList()
        }
    val model =
        mapOf(
            "committee" to mapOf("name" to committee.name, "slug" to committee.slug),
            "groupNames" to groups.filter { committee.ownsGroup(it) }.sorted(),
            "apiBase" to "/committees/${committee.slug}/dashboard-api",
            "previewUrl" to "/committees/${committee.slug}/dashboard/preview")
    call.respond(ThymeleafContent("committee-dashboard", model))
  }

  private suspend fun canEdit(committee: Committee, username: String): Boolean {
    val result =
        dashboard.call(
            HttpMethod.Get, "committees/${committee.id}/dashboard/access", username, null, session.sessionId)
    return (result.data as? Map<*, *>)?.get("canEdit") == true
  }
}

/** The editor's live preview: the TV page, fed the saved dashboard through the signed-in session. */
class CommitteeDashboardPreviewHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val committee = call.committee()
    if (committee == null) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val model =
        mapOf(
            "mode" to "preview",
            "sourceUrl" to "/committees/${committee.slug}/dashboard-api/dashboard/preview")
    call.respond(ThymeleafContent("tv-receiver", model))
  }
}

/**
 * `/committees/{slug}/dashboard-api/...`: forwards the editor's calls to the service as the
 * signed-in member. Only the dashboard, announcement and TV paths pass; the service decides who may
 * edit, and the acting member always comes from the session.
 */
class CommitteeDashboardApiHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val dashboard: DashboardServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val committee = call.committee()
    val username = userInfo["preferred_username"] as? String
    val path = call.request.path().substringAfter("/dashboard-api/", "")
    if (committee == null || username == null || !ALLOWED.matches(path)) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    val method = call.request.httpMethod
    val writes = method != HttpMethod.Get
    if (writes && (call.request.header("Origin") == null || !call.isSameOriginRequest())) {
      call.respond(HttpStatusCode.Forbidden, mapOf("status" to "ERROR", "message" to "Cross-site request rejected"))
      return
    }
    val body: Any? =
        if (method == HttpMethod.Put || method == HttpMethod.Post) {
          val text = call.receiveText()
          if (text.isBlank()) null
          else
              try {
                json.readValue(text, Any::class.java)
              } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("status" to "ERROR", "message" to "Invalid request"))
                return
              }
        } else null
    call.respondResult(
        dashboard.call(method, "committees/${committee.id}/$path", username, body, session.sessionId))
  }

  private companion object {
    val ALLOWED =
        Regex(
            "dashboard(/preview|/announcements(/[0-9]+)?)?|" +
                "cast-targets(/[0-9]+(/revoke)?)?")
  }
}

/** `/tv/receiver`: the page a Chromecast loads. Public and empty until it is handed a token. */
class TvReceiverHandler @Inject constructor() : IRouteHandler {
  override suspend fun handle(call: ApplicationCall) {
    call.response.header("Cache-Control", "no-store")
    call.respond(ThymeleafContent("tv-receiver", mapOf("mode" to "tv", "sourceUrl" to "/tv/api/dashboard")))
  }
}

/** `/tv/api/dashboard`: the dashboard for the device token in the `Authorization` header. */
class TvDashboardApiHandler
@Inject
constructor(
    private val dashboard: DashboardServiceClient,
) : IRouteHandler {
  override suspend fun handle(call: ApplicationCall) {
    call.response.header("Cache-Control", "no-store")
    val token = call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
    if (token.isNullOrBlank()) {
      call.respond(HttpStatusCode.Unauthorized, mapOf("status" to "ERROR", "message" to "Missing token"))
      return
    }
    call.respondResult(dashboard.tvDashboard(token))
  }
}
