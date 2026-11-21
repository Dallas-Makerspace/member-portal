package org.dallasmakerspace.server.routes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.thymeleaf.ThymeleafContent
import javax.inject.Inject
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.DashboardCallResult
import org.dallasmakerspace.server.memberservice.DashboardImageResult
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
            "sourceUrl" to "/committees/${committee.slug}/dashboard-api/dashboard/preview",
            "imageBase" to "/committees/${committee.slug}/dashboard-api/dashboard/images/",
            "avatarBase" to "/avatar/@")
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
    if (path == "dashboard/images" && method == HttpMethod.Post) {
      uploadImage(call, committee, username)
      return
    }
    if (path.startsWith("dashboard/images/") && method == HttpMethod.Get) {
      val file = path.substringAfterLast('/')
      val image =
          if (DashboardImageNames.isValid(file)) {
            dashboard.editorImage(committee.id, file, username, session.sessionId)
          } else {
            null
          }
      call.respondImage(image, "private, max-age=3600")
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

  private suspend fun uploadImage(call: ApplicationCall, committee: Committee, username: String) {
    val length = call.request.header("Content-Length")?.toLongOrNull() ?: 0L
    val type = call.request.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
    if (length > MAX_IMAGE_BYTES || type !in IMAGE_TYPES) {
      val message = if (type !in IMAGE_TYPES) "Use a JPEG or PNG picture" else "That picture is over 5 MB"
      call.respond(HttpStatusCode.BadRequest, mapOf("status" to "ERROR", "message" to message))
      return
    }
    val bytes = call.receive<ByteArray>()
    call.respondResult(
        dashboard.uploadImage(committee.id, username, bytes, checkNotNull(type), session.sessionId))
  }

  private companion object {
    const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
    val IMAGE_TYPES = setOf("image/jpeg", "image/png")
    val ALLOWED =
        Regex(
            "dashboard(/preview(/all)?|/members|/group-members|/announcements(/[0-9]+)?|" +
                "/images(/[0-9a-f-]{36}\\.jpg)?)?|" +
                "cast-targets(/[0-9]+(/revoke)?)?")
  }
}

/** The names the service gives stored pictures (`{uuid}.jpg`); anything else is never forwarded. */
internal object DashboardImageNames {
  private val FILE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$")

  fun isValid(file: String) = FILE.matches(file)
}

private suspend fun ApplicationCall.respondImage(image: DashboardImageResult?, cacheControl: String) {
  if (image?.bytes == null) {
    respond(HttpStatusCode.fromValue(image?.statusCode ?: HttpStatusCode.NotFound.value))
    return
  }
  response.header("Cache-Control", cacheControl)
  respondBytes(image.bytes, ContentType.parse(image.contentType ?: "image/jpeg"))
}

/** `/tv/receiver`: the page a Chromecast loads. Public and empty until it is handed a token. */
class TvReceiverHandler @Inject constructor() : IRouteHandler {
  override suspend fun handle(call: ApplicationCall) {
    call.response.header("Cache-Control", "no-store")
    call.respond(
        ThymeleafContent(
            "tv-receiver",
            mapOf(
                "mode" to "tv",
                "sourceUrl" to "/tv/api/dashboard",
                "imageBase" to "/tv/api/images/",
                "avatarBase" to "/tv/api/avatars/")))
  }
}

/** `/tv/api/images/{file}`: a picture for the device token in the `Authorization` header. */
class TvImageApiHandler
@Inject
constructor(
    private val dashboard: DashboardServiceClient,
) : IRouteHandler {
  override suspend fun handle(call: ApplicationCall) {
    val token = call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
    val file = call.request.path().substringAfterLast('/')
    if (token.isNullOrBlank()) {
      call.respond(HttpStatusCode.Unauthorized)
      return
    }
    if (!DashboardImageNames.isValid(file)) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    // File names never repeat, so a TV can keep a picture; the token is checked on every fetch.
    call.respondImage(dashboard.tvImage(file, token), "private, max-age=86400")
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

/** `/tv/api/avatars/{username}`: an uploaded member photo for the device token in the header. */
class TvAvatarApiHandler
@Inject
constructor(
    private val dashboard: DashboardServiceClient,
) : IRouteHandler {
  override suspend fun handle(call: ApplicationCall) {
    val token = call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
    val username = call.request.path().substringAfterLast('/')
    if (token.isNullOrBlank()) {
      call.respond(HttpStatusCode.Unauthorized)
      return
    }
    if (!USERNAME.matches(username)) {
      call.respond(HttpStatusCode.NotFound)
      return
    }
    call.respondImage(dashboard.tvAvatar(username, token), "private, max-age=3600")
  }

  private companion object {
    val USERNAME = Regex("^[A-Za-z0-9._-]{1,64}$")
  }
}
