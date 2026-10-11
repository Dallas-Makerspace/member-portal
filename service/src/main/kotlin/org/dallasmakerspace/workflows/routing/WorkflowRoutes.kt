package org.dallasmakerspace.workflows.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Job
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.models.workflows.WorkflowAdminInProgressDto
import org.dallasmakerspace.models.workflows.WorkflowDefinitionDto
import org.dallasmakerspace.models.workflows.WorkflowEventDto
import org.dallasmakerspace.models.workflows.WorkflowOperatorAction
import org.dallasmakerspace.models.workflows.WorkflowPreflightRequest
import org.dallasmakerspace.models.workflows.WorkflowPreflightResponse
import org.dallasmakerspace.models.workflows.WorkflowRunDto
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowStartRequest
import org.dallasmakerspace.models.workflows.WorkflowStartResponse
import org.dallasmakerspace.models.workflows.WorkflowSubject
import org.dallasmakerspace.workflows.InvalidWorkflowInputException
import org.dallasmakerspace.workflows.OperateResult
import org.dallasmakerspace.workflows.RunFilter
import org.dallasmakerspace.workflows.StartResult
import org.dallasmakerspace.workflows.UnknownWorkflowException
import org.dallasmakerspace.workflows.WorkflowEventBus
import org.dallasmakerspace.workflows.WorkflowService

/** Header carrying the end user the UI is acting for; required on preflight, start and operate. */
const val ACTING_USER_HEADER = "X-Acting-User"

/** Body of every non-2xx response except the 422 plan; [activeRun] is set on 409 locks. */
@Serializable
data class WorkflowErrorResponse(val message: String, val activeRun: WorkflowRunDto? = null)

internal val workflowJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

private const val DEFAULT_LIST_LIMIT = 100
private const val MAX_LIST_LIMIT = 500
private const val TRIGGERED_BY_UI = "UI"

/**
 * `/workflows` API, all requiring [Permission.RUN_WORKFLOWS]; call inside an `authenticate` block.
 * Bodies are encoded with kotlinx.serialization directly (not the app's Gson ContentNegotiation) so
 * the kotlinx `JsonObject` input fields round-trip.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun Route.workflowRoutes(
    service: WorkflowService,
    bus: WorkflowEventBus,
    streamsClosed: Job,
    heartbeatMillis: Long = DEFAULT_HEARTBEAT_MILLIS,
) {
  authorize(Permission.RUN_WORKFLOWS) {
    route("/workflows") {
      get("/definitions") {
        call.respondJson(
            HttpStatusCode.OK,
            ListSerializer(WorkflowDefinitionDto.serializer()),
            service.definitions(),
        )
      }

      get("/admin/in-progress") {
        call.respondJson(
            HttpStatusCode.OK, WorkflowAdminInProgressDto.serializer(), service.adminInProgress())
      }

      get("/admin/stream") { call.adminStream(service, bus, streamsClosed, heartbeatMillis) }

      get("/runs") {
        val filter =
            call.parseRunFilter(allowStatusAndActor = true) ?: return@get
        call.respondJson(
            HttpStatusCode.OK,
            ListSerializer(WorkflowRunDto.serializer()),
            service.listRuns(filter),
        )
      }

      get("/runs/stream") { call.runListStream(service, bus, streamsClosed, heartbeatMillis) }

      get("/runs/{runId}") {
        val runId = call.runId() ?: return@get
        val run = service.getRun(runId, withSteps = true)
        if (run == null) {
          call.respondError(HttpStatusCode.NotFound, "Run $runId not found")
        } else {
          call.respondJson(HttpStatusCode.OK, WorkflowRunDto.serializer(), run)
        }
      }

      get("/runs/{runId}/events") {
        val runId = call.runId() ?: return@get
        val afterParam = call.request.queryParameters["after"]
        val after = afterParam?.toLongOrNull()
        if (afterParam != null && after == null) {
          return@get call.respondError(HttpStatusCode.BadRequest, "after must be a number")
        }
        if (service.getRun(runId, withSteps = false) == null) {
          return@get call.respondError(HttpStatusCode.NotFound, "Run $runId not found")
        }
        call.respondJson(
            HttpStatusCode.OK,
            ListSerializer(WorkflowEventDto.serializer()),
            service.listEvents(runId, after ?: 0L),
        )
      }

      get("/runs/{runId}/stream") {
        val runId = call.runId() ?: return@get
        call.runStream(runId, service, bus, streamsClosed, heartbeatMillis)
      }

      post("/runs/{runId}/{action}") {
        val action =
            call.parameters["action"]?.let { name ->
              WorkflowOperatorAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            } ?: return@post call.respondError(HttpStatusCode.NotFound, "Unknown action")
        val runId = call.runId() ?: return@post
        val actor = call.actingUser() ?: return@post
        when (val result = service.operate(runId, action, actor)) {
          is OperateResult.Ok ->
              call.respondJson(HttpStatusCode.OK, WorkflowRunDto.serializer(), result.run)
          OperateResult.NotFound ->
              call.respondError(HttpStatusCode.NotFound, "Run $runId not found")
          is OperateResult.NotAllowed -> call.respondError(HttpStatusCode.Conflict, result.message)
        }
      }

      post("/{id}/preflight") {
        val id = call.parameters["id"].orEmpty()
        val actor = call.actingUser() ?: return@post
        val request =
            call.receiveBody(WorkflowPreflightRequest.serializer()) ?: return@post
        try {
          call.respondJson(
              HttpStatusCode.OK,
              WorkflowPreflightResponse.serializer(),
              service.preflight(id, request.input, actor),
          )
        } catch (e: UnknownWorkflowException) {
          call.respondError(HttpStatusCode.NotFound, e.message ?: "Unknown workflow")
        } catch (e: InvalidWorkflowInputException) {
          call.respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid input")
        }
      }

      post("/{id}/runs") {
        val id = call.parameters["id"].orEmpty()
        val actor = call.actingUser() ?: return@post
        val request = call.receiveBody(WorkflowStartRequest.serializer()) ?: return@post
        if (request.idempotencyToken.isBlank()) {
          return@post call.respondError(HttpStatusCode.BadRequest, "idempotencyToken is required")
        }
        val result =
            try {
              service.start(id, request.input, actor, TRIGGERED_BY_UI, request.idempotencyToken)
            } catch (e: UnknownWorkflowException) {
              return@post call.respondError(
                  HttpStatusCode.NotFound, e.message ?: "Unknown workflow")
            } catch (e: InvalidWorkflowInputException) {
              return@post call.respondError(
                  HttpStatusCode.BadRequest, e.message ?: "Invalid input")
            }
        when (result) {
          is StartResult.Started ->
              call.respondJson(
                  HttpStatusCode.Created,
                  WorkflowStartResponse.serializer(),
                  WorkflowStartResponse(result.run, existing = false),
              )
          is StartResult.Existing ->
              call.respondJson(
                  HttpStatusCode.OK,
                  WorkflowStartResponse.serializer(),
                  WorkflowStartResponse(result.run, existing = true),
              )
          is StartResult.Blocked ->
              call.respondJson(
                  HttpStatusCode.UnprocessableEntity,
                  WorkflowPreflightResponse.serializer(),
                  result.plan,
              )
          is StartResult.SubjectLocked ->
              call.respondJson(
                  HttpStatusCode.Conflict,
                  WorkflowErrorResponse.serializer(),
                  WorkflowErrorResponse(
                      "Workflow run ${result.activeRun.id} is already active for this subject",
                      result.activeRun,
                  ),
              )
        }
      }
    }
  }
}

internal suspend fun <T> ApplicationCall.respondJson(
    status: HttpStatusCode,
    serializer: KSerializer<T>,
    body: T,
) = respondText(workflowJson.encodeToString(serializer, body), ContentType.Application.Json, status)

internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) =
    respondJson(status, WorkflowErrorResponse.serializer(), WorkflowErrorResponse(message))

private suspend fun <T> ApplicationCall.receiveBody(serializer: KSerializer<T>): T? =
    try {
      workflowJson.decodeFromString(serializer, receiveText())
    } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
      // SerializationException is an IllegalArgumentException
      respondError(HttpStatusCode.BadRequest, "Invalid request body")
      null
    }

private suspend fun ApplicationCall.actingUser(): String? {
  val actor = request.headers[ACTING_USER_HEADER]?.trim()?.takeIf { it.isNotEmpty() }
  if (actor == null) {
    respondError(HttpStatusCode.BadRequest, "$ACTING_USER_HEADER header is required")
  }
  return actor
}

private suspend fun ApplicationCall.runId(): Long? {
  val id = parameters["runId"]?.toLongOrNull()
  if (id == null) respondError(HttpStatusCode.BadRequest, "runId must be a number")
  return id
}

/** Parses workflow/subject/status/actor/active/limit; responds 400 and returns null if invalid. */
internal suspend fun ApplicationCall.parseRunFilter(allowStatusAndActor: Boolean): RunFilter? {
  val q = request.queryParameters
  return try {
    RunFilter(
        workflowId = q["workflow"]?.trim()?.takeIf { it.isNotEmpty() },
        subject = q["subject"]?.takeIf { it.isNotBlank() }?.let(::parseSubject),
        statuses =
            if (allowStatusAndActor) {
              q.getAll("status")
                  ?.flatMap { it.split(',') }
                  ?.map { it.trim() }
                  ?.filter { it.isNotEmpty() }
                  ?.map { WorkflowRunStatus.valueOf(it.uppercase()) }
                  ?.toSet()
                  ?.takeIf { it.isNotEmpty() }
            } else {
              null
            },
        actor = if (allowStatusAndActor) q["actor"]?.trim()?.takeIf { it.isNotEmpty() } else null,
        activeOnly = q["active"]?.equals("true", ignoreCase = true) == true,
        limit = q["limit"]?.toInt()?.coerceIn(1, MAX_LIST_LIMIT) ?: DEFAULT_LIST_LIMIT,
    )
  } catch (e: IllegalArgumentException) {
    respondError(HttpStatusCode.BadRequest, "Invalid query parameter: ${e.message}")
    null
  }
}

private fun parseSubject(raw: String): WorkflowSubject {
  val sep = raw.indexOf(':')
  require(sep > 0 && sep < raw.length - 1) { "subject must be <type>:<key>" }
  return WorkflowSubject(raw.substring(0, sep), raw.substring(sep + 1))
}
