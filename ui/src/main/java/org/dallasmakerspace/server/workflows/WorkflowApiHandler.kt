package org.dallasmakerspace.server.workflows

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.ClosedWriteChannelException
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.dallasmakerspace.models.workflows.WorkflowArtifactUrlResponse
import org.dallasmakerspace.models.workflows.WorkflowDefinitionDto
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.server.auth.Authz
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.HttpException
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.plugins.getCachedFeatureFlag
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler

/**
 * Browser-facing `/api/workflows/...`: session-authenticated JSON and SSE for the workflow UI. Every
 * request is gated by the `workflows.enabled` flag, and workflow permissions are enforced here
 * because the service only authenticates the UI as an API client.
 */
@Suppress("TooManyFunctions")
class WorkflowApiHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
    private val client: WorkflowServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = userInfo["preferred_username"] as? String
    if (username == null) {
      call.respondMessage(HttpStatusCode.Unauthorized, "No username in session")
      return
    }
    if (!getCachedFeatureFlag(memberServiceClient, WORKFLOWS_FEATURE_FLAG, username, session.sessionId)) {
      call.respondMessage(HttpStatusCode.NotFound, "Not found")
      return
    }
    val route = parseWorkflowRoute(call.request.httpMethod.value, call.request.path())
    if (route == null) {
      call.respondMessage(HttpStatusCode.NotFound, "Not found")
      return
    }
    if (call.request.httpMethod.value == "POST" && !call.hasJsonBody()) {
      call.respondMessage(HttpStatusCode.UnsupportedMediaType, "Content-Type must be application/json")
      return
    }
    try {
      dispatch(call, route, username)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.error("Workflow API error on ${call.request.path()}", e)
      if (!call.response.isCommitted) {
        call.respondMessage(HttpStatusCode.BadGateway, "Workflow service unavailable")
      }
    }
  }

  private suspend fun dispatch(call: ApplicationCall, route: WorkflowRoute, username: String) {
    val sid = session.sessionId
    when (route) {
      WorkflowRoute.Definitions -> {
        val defs = client.definitions(sid).filter { canUseWorkflow(authz, it) }
        call.respondRaw(
            HttpStatusCode.OK,
            WorkflowServiceClient.json.encodeToString(
                ListSerializer(WorkflowDefinitionDto.serializer()), defs))
      }
      is WorkflowRoute.Preflight -> {
        val body = call.requireBody(needToken = false) ?: return
        val def = call.requireUsableDefinition(route.workflowId) ?: return
        call.relay(client.preflight(def.id, body, username, sid))
      }
      is WorkflowRoute.Start -> {
        val body = call.requireBody(needToken = true) ?: return
        val def = call.requireUsableDefinition(route.workflowId) ?: return
        call.relay(client.start(def.id, body, username, sid))
      }
      WorkflowRoute.ListRuns -> listRuns(call, username)
      is WorkflowRoute.Run -> {
        val resp = client.rawRun(route.runId, username, sid)
        if (!resp.isSuccess) return call.relay(resp)
        call.requireViewable(resp.body) ?: return
        call.relay(resp)
      }
      is WorkflowRoute.RunEvents -> {
        call.requireViewableRun(route.runId, username) ?: return
        call.relay(client.events(route.runId, call.request.queryParameters["after"]?.toLongOrNull(), username, sid))
      }
      is WorkflowRoute.Operate -> {
        val run = call.requireViewableRun(route.runId, username) ?: return
        val def = client.definition(run.workflowId, sid)
        if (!canOperateWorkflow(authz, def)) {
          return call.respondMessage(HttpStatusCode.Forbidden, "Not allowed to operate this workflow")
        }
        call.relay(client.operate(route.runId, route.action, username, sid))
      }
      is WorkflowRoute.ArtifactDownload -> downloadArtifact(call, route, username)
      WorkflowRoute.AdminInProgress -> {
        if (!call.requireAdmin()) return
        call.relay(client.rawAdminInProgress(username, sid))
      }
      WorkflowRoute.AdminSummary -> {
        if (!call.requireAdmin()) return
        call.respondRaw(HttpStatusCode.OK, adminSummary(client.adminInProgress(username, sid)).toString())
      }
      WorkflowRoute.SubjectStream -> subjectStream(call, username)
      is WorkflowRoute.RunStream -> {
        val run = call.requireViewableRun(route.runId, username) ?: return
        val def = client.definition(run.workflowId, sid)
        stream(call, "/workflows/runs/${route.runId}/stream", emptyMap(), username) {
          canViewWorkflow(it, def)
        }
      }
      WorkflowRoute.AdminStream -> {
        if (!call.requireAdmin()) return
        stream(call, "/workflows/admin/stream", emptyMap(), username) { isWorkflowAdmin(it) }
      }
    }
  }

  /** Checks rights, asks the service for a fresh presigned URL (audited there) and redirects. */
  private suspend fun downloadArtifact(
      call: ApplicationCall,
      route: WorkflowRoute.ArtifactDownload,
      username: String,
  ) {
    val sid = session.sessionId
    val run =
        client.run(route.runId, username, sid)
            ?: return call.respondMessage(HttpStatusCode.NotFound, "Not found")
    val def = client.definition(run.workflowId, sid)
    val artifact =
        run.steps.getOrNull(route.stepIndex)?.artifacts?.firstOrNull { it.name == route.name }
    if (def == null || !canUseWorkflow(authz, def)) {
      return call.respondMessage(HttpStatusCode.Forbidden, "Not allowed to download from this workflow")
    }
    if (artifact == null) return call.respondMessage(HttpStatusCode.NotFound, "Not found")
    if (!canDownloadArtifact(authz, def, artifact)) {
      return call.respondMessage(HttpStatusCode.Forbidden, "Not allowed to download this file")
    }
    val resp = client.artifactUrl(route.runId, route.stepIndex, route.name, username, sid)
    when {
      resp.status == HttpStatusCode.Gone.value ->
          call.respondMessage(HttpStatusCode.Gone, "The file no longer exists")
      resp.status == HttpStatusCode.NotFound.value ->
          call.respondMessage(HttpStatusCode.NotFound, "Not found")
      !resp.isSuccess -> call.respondMessage(HttpStatusCode.BadGateway, "Download unavailable")
      else -> {
        val url = presignedUrl(resp.body)
        if (url == null) {
          call.respondMessage(HttpStatusCode.BadGateway, "Download unavailable")
        } else {
          call.response.header(HttpHeaders.CacheControl, "no-store")
          call.respondRedirect(url, permanent = false)
        }
      }
    }
  }

  private suspend fun listRuns(call: ApplicationCall, username: String) {
    val q = call.request.queryParameters
    val workflow = q["workflow"]?.takeIf { it.isNotBlank() }
    if (workflow == null) {
      if (!call.requireAdmin()) return
    } else {
      val def = client.definition(workflow, session.sessionId)
      if (!canViewWorkflow(authz, def)) {
        return call.respondMessage(HttpStatusCode.Forbidden, "Not allowed to view this workflow")
      }
    }
    val query = linkedMapOf<String, String>()
    workflow?.let { query["workflow"] = it }
    q["subject"]?.takeIf { it.isNotBlank() }?.let {
      if (!isValidSubject(it)) {
        return call.respondMessage(HttpStatusCode.BadRequest, "subject must be <type>:<key>")
      }
      query["subject"] = it
    }
    q["status"]?.takeIf { it.isNotBlank() }?.let { query["status"] = it }
    q["actor"]?.takeIf { it.isNotBlank() }?.let { query["actor"] = it }
    q["active"]?.takeIf { it == "true" }?.let { query["active"] = it }
    q["limit"]?.toIntOrNull()?.let { query["limit"] = it.coerceIn(1, MAX_LIMIT).toString() }
    call.relay(client.listRuns(query, username, session.sessionId))
  }

  private suspend fun subjectStream(call: ApplicationCall, username: String) {
    val q = call.request.queryParameters
    val workflow = q["workflow"]?.takeIf { it.isNotBlank() }
    if (workflow == null) {
      return call.respondMessage(HttpStatusCode.BadRequest, "workflow is required")
    }
    val def = client.definition(workflow, session.sessionId)
    if (!canViewWorkflow(authz, def)) {
      return call.respondMessage(HttpStatusCode.Forbidden, "Not allowed to view this workflow")
    }
    val query = linkedMapOf("workflow" to workflow)
    q["subject"]?.takeIf { it.isNotBlank() }?.let {
      if (!isValidSubject(it)) {
        return call.respondMessage(HttpStatusCode.BadRequest, "subject must be <type>:<key>")
      }
      query["subject"] = it
    }
    stream(call, "/workflows/runs/stream", query, username) { canViewWorkflow(it, def) }
  }

  private suspend fun stream(
      call: ApplicationCall,
      path: String,
      query: Map<String, String>,
      username: String,
      allowed: (Authz) -> Boolean,
  ) {
    val lastEventId =
        sanitizeLastEventId(
            call.request.headers["Last-Event-ID"] ?: call.request.queryParameters["lastEventId"])
    val token = session.accessToken
    val sid = session.sessionId
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.response.header("X-Accel-Buffering", "no")
    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
      val out: ByteWriteChannel = this
      try {
        client.openStream(path, query, lastEventId, username, sid) { status, body ->
          if (status in 200..299) {
            SseRelay.relay(body, out) { stillAllowed(token, allowed) }
          } else {
            SseRelay.writeEvent(out, SseRelay.ERROR_EVENT, """{"status":$status}""")
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: ClosedWriteChannelException) {
        log.debug("Workflow stream closed by browser: $path")
      } catch (e: IOException) {
        log.debug("Workflow stream ended: $path ${e.message}")
      } catch (e: Exception) {
        log.warn("Workflow stream failed: $path", e)
        runCatching { SseRelay.writeEvent(out, SseRelay.ERROR_EVENT, """{"status":502}""") }
      }
    }
  }

  /** Re-validates the session on each heartbeat; a transient lookup failure keeps the stream. */
  private suspend fun stillAllowed(token: String?, allowed: (Authz) -> Boolean): Boolean =
      try {
        val info = userInfoProvider.getUserInfo(token ?: return false)
        allowed(Authz.fromUserInfo(info))
      } catch (e: CancellationException) {
        throw e
      } catch (e: HttpException) {
        e.message?.contains("401") != true
      } catch (e: Exception) {
        true
      }

  private suspend fun ApplicationCall.requireAdmin(): Boolean {
    if (isWorkflowAdmin(authz)) return true
    respondMessage(HttpStatusCode.Forbidden, "Admin only")
    return false
  }

  private suspend fun ApplicationCall.requireUsableDefinition(id: String): WorkflowDefinitionDto? {
    val def = client.definition(id, session.sessionId)
    if (def == null) {
      respondMessage(HttpStatusCode.NotFound, "Unknown workflow")
      return null
    }
    if (!canUseWorkflow(authz, def)) {
      respondMessage(HttpStatusCode.Forbidden, "Not allowed to use this workflow")
      return null
    }
    return def
  }

  private suspend fun ApplicationCall.requireViewable(runJson: String): Unit? {
    val workflowId = runWorkflowId(runJson)
    val def = workflowId?.let { client.definition(it, session.sessionId) }
    if (!canViewWorkflow(authz, def)) {
      respondMessage(HttpStatusCode.Forbidden, "Not allowed to view this run")
      return null
    }
    return Unit
  }

  private suspend fun ApplicationCall.requireViewableRun(runId: Long, username: String) =
      client.run(runId, username, session.sessionId).let { run ->
        if (run == null) {
          respondMessage(HttpStatusCode.NotFound, "Run $runId not found")
          null
        } else if (!canViewWorkflow(authz, client.definition(run.workflowId, session.sessionId))) {
          respondMessage(HttpStatusCode.Forbidden, "Not allowed to view this run")
          null
        } else {
          run
        }
      }

  /** Valid JSON object body with an `input` object (and an idempotency token when starting). */
  private suspend fun ApplicationCall.requireBody(needToken: Boolean): String? {
    val text = receiveText()
    val obj =
        try {
          WorkflowServiceClient.json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
          null
        }
    val token = (obj?.get("idempotencyToken") as? JsonPrimitive)?.contentOrNull
    val valid =
        obj != null &&
            obj["input"] is JsonObject &&
            (!needToken || (token != null && token.isNotBlank() && token.length <= MAX_TOKEN))
    if (!valid) {
      respondMessage(HttpStatusCode.BadRequest, "Invalid request body")
      return null
    }
    return text
  }

  private fun ApplicationCall.hasJsonBody() =
      request.contentType().match(ContentType.Application.Json)

  private suspend fun ApplicationCall.relay(resp: ServiceResponse) {
    respondRaw(HttpStatusCode.fromValue(resp.status), resp.body)
  }

  companion object {
    private const val MAX_LIMIT = 200
    private const val MAX_TOKEN = 100

    /** The URL from the service answer, or null; failures never carry the body into logs. */
    private fun presignedUrl(body: String): String? =
        try {
          WorkflowServiceClient.json
              .decodeFromString(WorkflowArtifactUrlResponse.serializer(), body)
              .url
              .takeIf { it.startsWith("https://") || it.startsWith("http://") }
        } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
          null
        }

    private fun runWorkflowId(runJson: String): String? =
        try {
          WorkflowServiceClient.json
              .parseToJsonElement(runJson)
              .jsonObject["workflowId"]
              ?.jsonPrimitive
              ?.contentOrNull
        } catch (e: IllegalArgumentException) {
          null
        }

    fun adminSummary(dto: org.dallasmakerspace.models.workflows.WorkflowAdminInProgressDto) =
        buildJsonObject {
          put("safeToDeploy", dto.safeToDeploy)
          put("inProgress", dto.runs.size)
          put(
              "needsAttention",
              dto.runs.count {
                it.status == WorkflowRunStatus.PAUSED || it.status == WorkflowRunStatus.ROLLBACK_FAILED
              })
        }
  }
}

internal suspend fun ApplicationCall.respondRaw(status: HttpStatusCode, body: String) {
  response.header(HttpHeaders.CacheControl, "no-store")
  respondText(body, ContentType.Application.Json, status)
}

internal suspend fun ApplicationCall.respondMessage(status: HttpStatusCode, message: String) {
  respondRaw(status, buildJsonObject { put("message", message) }.toString())
}
