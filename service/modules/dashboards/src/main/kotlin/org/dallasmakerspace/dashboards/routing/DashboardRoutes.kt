package org.dallasmakerspace.dashboards.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.http.CacheControl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status
import org.dallasmakerspace.dashboards.AnnouncementDto
import org.dallasmakerspace.dashboards.CastTargetDto
import org.dallasmakerspace.dashboards.DashboardDocument
import org.dallasmakerspace.dashboards.DashboardError
import org.dallasmakerspace.dashboards.DashboardEvents
import org.dallasmakerspace.dashboards.DashboardException
import org.dallasmakerspace.dashboards.DashboardImageService
import org.dallasmakerspace.dashboards.DashboardService
import org.dallasmakerspace.dashboards.DiscoveredBatch
import org.dallasmakerspace.dashboards.DraftPreviewRequest
import org.dallasmakerspace.dashboards.GroupPick
import org.dallasmakerspace.dashboards.TargetHost
import org.dallasmakerspace.dashboards.TargetIdentity
import org.dallasmakerspace.dashboards.TargetStatusReport
import org.dallasmakerspace.dashboards.UsernamePick

private const val TOKEN_HEADER = "X-Dashboard-Token"

/**
 * Committee TV dashboards. The editor routes act for `X-Username` (set by the UI for the
 * logged-in member) and are checked by [DashboardService]; the TV routes take the device token the
 * cast agent hands the receiver; the cast-agent routes are for the agent's own API client.
 */
fun Route.dashboardRoutes(service: DashboardService, events: DashboardEvents) {
  authenticate(ApiKeyAuthProvider.X_API_KEY) {
    authorize(Permission.MANAGE_MEMBERS) {
      editorRoutes(service)
      tvRoutes(service)
    }
    authorize(Permission.CAST_AGENT) { castAgentRoutes(service, events) }
  }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun Route.editorRoutes(service: DashboardService) {
  get("/displays") {
    call.guarded { ApiResponse(Status.SUCCESS, "Displays", service.displays()) }
  }
  get("/committees/{id}/dashboard/access") {
    val (actor, id) = call.actorAndCommittee() ?: return@get
    call.respond(ApiResponse(Status.SUCCESS, "Access", mapOf("canEdit" to service.canEdit(actor, id))))
  }
  get("/committees/{id}/dashboard") {
    val (actor, id) = call.actorAndCommittee() ?: return@get
    call.guarded { ApiResponse(Status.SUCCESS, "Dashboard", service.document(actor, id)) }
  }
  post("/committees/{id}/dashboard/images") {
    val (actor, id) = call.actorAndCommittee() ?: return@post
    val length = call.request.contentLength() ?: 0
    if (length > DashboardImageService.MAX_UPLOAD_BYTES) {
      return@post call.respond(
          HttpStatusCode.PayloadTooLarge,
          ApiResponse<String?>(Status.ERROR, "That picture is over 5 MB", null))
    }
    val bytes = call.receive<ByteArray>()
    call.guarded {
      ApiResponse(Status.SUCCESS, "Picture saved", mapOf("key" to service.uploadImage(actor, id, bytes)))
    }
  }
  get("/committees/{id}/dashboard/images/{file}") {
    val (actor, id) = call.actorAndCommittee() ?: return@get
    call.guardedImage { service.editorImage(actor, id, call.parameters["file"].orEmpty()) }
  }
  post("/committees/{id}/dashboard/members") {
    val (actor, id) = call.actorAndCommittee() ?: return@post
    val pick = call.receive<UsernamePick>()
    call.guarded {
      ApiResponse(Status.SUCCESS, "Members", service.checkMembers(actor, id, pick.usernames))
    }
  }
  post("/committees/{id}/dashboard/group-members") {
    val (actor, id) = call.actorAndCommittee() ?: return@post
    val pick = call.receive<GroupPick>()
    call.guarded { ApiResponse(Status.SUCCESS, "Members", service.groupMembers(actor, id, pick.group)) }
  }
  post("/committees/{id}/dashboard/preview") {
    val (actor, id) = call.actorAndCommittee() ?: return@post
    val draft = call.receive<DraftPreviewRequest>()
    call.guarded { ApiResponse(Status.SUCCESS, "Preview", service.previewDraft(actor, id, draft)) }
  }
  get("/committees/{id}/dashboard/preview/{pageId}") {
    val (actor, id) = call.actorAndCommittee() ?: return@get
    val pageId = call.parameters["pageId"]?.toIntOrNull()
    if (pageId == null) {
      return@get call.respond(HttpStatusCode.BadRequest, ApiResponse<String?>(Status.ERROR, "Invalid page", null))
    }
    call.guarded { ApiResponse(Status.SUCCESS, "Preview", service.previewSavedPage(actor, id, pageId)) }
  }
  get("/committees/{id}/dashboard/preview") {
    val (actor, id) = call.actorAndCommittee() ?: return@get
    call.guarded { ApiResponse(Status.SUCCESS, "Preview", service.preview(actor, id)) }
  }
  put("/committees/{id}/dashboard") {
    val (actor, id) = call.actorAndCommittee() ?: return@put
    val document = call.receive<DashboardDocument>()
    call.guarded {
      service.saveDocument(actor, id, document)
      ApiResponse(Status.SUCCESS, "Dashboard saved", service.document(actor, id))
    }
  }

  get("/committees/{id}/dashboard/announcements") {
    val (actor, id) = call.actorAndCommittee() ?: return@get
    call.guarded { ApiResponse(Status.SUCCESS, "Announcements", service.announcements(actor, id)) }
  }
  post("/committees/{id}/dashboard/announcements") {
    val (actor, id) = call.actorAndCommittee() ?: return@post
    val dto = call.receive<AnnouncementDto>()
    call.guarded {
      ApiResponse(Status.SUCCESS, "Announcement added", mapOf("id" to service.addAnnouncement(actor, id, dto)))
    }
  }
  put("/committees/{id}/dashboard/announcements/{itemId}") {
    val (actor, id) = call.actorAndCommittee() ?: return@put
    val itemId = call.itemId() ?: return@put
    val dto = call.receive<AnnouncementDto>()
    call.guarded {
      service.updateAnnouncement(actor, id, itemId, dto)
      ok("Announcement saved")
    }
  }
  delete("/committees/{id}/dashboard/announcements/{itemId}") {
    val (actor, id) = call.actorAndCommittee() ?: return@delete
    val itemId = call.itemId() ?: return@delete
    call.guarded {
      service.deleteAnnouncement(actor, id, itemId)
      ok("Announcement deleted")
    }
  }

  get("/committees/{id}/cast-targets") {
    val (actor, id) = call.actorAndCommittee() ?: return@get
    call.guarded { ApiResponse(Status.SUCCESS, "TVs", service.targets(actor, id)) }
  }
  post("/committees/{id}/cast-targets") {
    val (actor, id) = call.actorAndCommittee() ?: return@post
    val dto = call.receive<CastTargetDto>()
    call.guarded {
      ApiResponse(Status.SUCCESS, "TV added", mapOf("id" to service.addTarget(actor, id, dto)))
    }
  }
  put("/committees/{id}/cast-targets/{itemId}") {
    val (actor, id) = call.actorAndCommittee() ?: return@put
    val itemId = call.itemId() ?: return@put
    val dto = call.receive<CastTargetDto>()
    call.guarded {
      service.updateTarget(actor, id, itemId, dto)
      ok("TV saved")
    }
  }
  delete("/committees/{id}/cast-targets/{itemId}") {
    val (actor, id) = call.actorAndCommittee() ?: return@delete
    val itemId = call.itemId() ?: return@delete
    call.guarded {
      service.deleteTarget(actor, id, itemId)
      ok("TV removed")
    }
  }
  post("/committees/{id}/cast-targets/{itemId}/revoke") {
    val (actor, id) = call.actorAndCommittee() ?: return@post
    val itemId = call.itemId() ?: return@post
    call.guarded {
      service.revokeTarget(actor, id, itemId)
      ok("TV access revoked")
    }
  }
}

private fun Route.tvRoutes(service: DashboardService) {
  get("/tv/images/{file}") {
    call.response.cacheControl(CacheControl.NoStore(null))
    call.guardedImage { service.tvImage(call.request.headers[TOKEN_HEADER], call.parameters["file"].orEmpty()) }
  }
  get("/tv/avatars/{username}") {
    call.response.cacheControl(CacheControl.NoStore(null))
    call.guardedImage { service.tvAvatar(call.request.headers[TOKEN_HEADER], call.parameters["username"].orEmpty()) }
  }
  get("/tv/dashboard") {
    call.response.cacheControl(CacheControl.NoStore(null))
    val payload = service.dashboardFor(call.request.headers[TOKEN_HEADER])
    if (payload == null) {
      call.respond(HttpStatusCode.Unauthorized, ApiResponse<String?>(Status.ERROR, "Invalid token", null))
    } else {
      call.respond(ApiResponse(Status.SUCCESS, "Dashboard", payload))
    }
  }
}

private fun Route.castAgentRoutes(service: DashboardService, events: DashboardEvents) {
  get("/cast-agent/targets") {
    call.respond(ApiResponse(Status.SUCCESS, "Targets", service.agentTargets()))
  }
  post("/cast-agent/targets/{itemId}/token") {
    val itemId = call.itemId() ?: return@post
    call.guarded { ApiResponse(Status.SUCCESS, "Token", service.issueToken(itemId)) }
  }
  post("/cast-agent/targets/{itemId}/identity") {
    val itemId = call.itemId() ?: return@post
    val identity = call.receive<TargetIdentity>()
    call.guarded {
      service.recordIdentity(itemId, identity)
      ok("Recorded")
    }
  }
  post("/cast-agent/targets/{itemId}/host") {
    val itemId = call.itemId() ?: return@post
    val moved = call.receive<TargetHost>()
    call.guarded {
      service.moveTarget(itemId, moved.host.trim())
      ok("Moved")
    }
  }
  post("/cast-agent/discovered") {
    val batch = call.receive<DiscoveredBatch>()
    call.guarded {
      service.recordDiscovered(batch)
      ok("Recorded")
    }
  }
  post("/cast-agent/targets/{itemId}/status") {
    val itemId = call.itemId() ?: return@post
    val report = call.receive<TargetStatusReport>()
    call.guarded {
      service.reportStatus(itemId, report)
      ok("Recorded")
    }
  }
  get("/cast-agent/events") {
    call.response.cacheControl(CacheControl.NoCache(null))
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
      write(": connected\n\n")
      flush()
      events.events.collect { event ->
        write("data: ${Json.encodeToString(event)}\n\n")
        flush()
      }
    }
  }
}

private suspend fun ApplicationCall.actorAndCommittee(): Pair<String, Int>? {
  val actor = request.headers["X-Username"]
  val committeeId = parameters["id"]?.toIntOrNull()
  if (actor.isNullOrBlank() || committeeId == null) {
    respond(
        HttpStatusCode.BadRequest,
        ApiResponse<String?>(Status.ERROR, "X-Username header and committee id are required", null))
    return null
  }
  return actor to committeeId
}

/** Responds with a stored picture, 404 when it isn't there, or the error a [DashboardException] maps to. */
private suspend fun ApplicationCall.guardedImage(load: suspend () -> Pair<ByteArray, String>?) {
  try {
    val image = load()
    if (image == null) respond(HttpStatusCode.NotFound, ApiResponse<String?>(Status.ERROR, "No such picture", null))
    else respondBytes(image.first, ContentType.parse(image.second))
  } catch (e: DashboardException) {
    respond(statusFor(e), ApiResponse<String?>(Status.ERROR, e.message ?: "Request failed", null))
  }
}

private suspend fun ApplicationCall.itemId(): Int? {
  val id = parameters["itemId"]?.toIntOrNull()
  if (id == null) {
    respond(HttpStatusCode.BadRequest, ApiResponse<String?>(Status.ERROR, "Invalid id", null))
  }
  return id
}

/** Runs [block] and responds with its result, mapping a [DashboardException] to its status. */
private suspend inline fun <reified T> ApplicationCall.guarded(block: () -> ApiResponse<T>) {
  try {
    respond(block())
  } catch (e: DashboardException) {
    respond(statusFor(e), ApiResponse<String?>(Status.ERROR, e.message ?: "Request failed", null))
  }
}

private fun statusFor(e: DashboardException): HttpStatusCode =
    when (e.error) {
      DashboardError.NOT_FOUND -> HttpStatusCode.NotFound
      DashboardError.FORBIDDEN -> HttpStatusCode.Forbidden
      DashboardError.INVALID -> HttpStatusCode.BadRequest
      DashboardError.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
    }

private fun ok(message: String) = ApiResponse<String?>(Status.SUCCESS, message, null)
