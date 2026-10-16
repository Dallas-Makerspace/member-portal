package org.dallasmakerspace.workflows

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.models.workflows.*

/**
 * What the HTTP routes (and other service code) use. Implemented by the engine in
 * `:service:workflows`. Never returns raw stored JSON that could hold secrets; DTOs only.
 */
interface WorkflowService {
  fun definitions(): List<WorkflowDefinitionDto>

  /**
   * Read-only plan: check() + validate() on every step. Throws [UnknownWorkflowException],
   * [InvalidWorkflowInputException], and [WorkflowForbiddenException] when the workflow's
   * authorization mode denies [actor].
   */
  suspend fun preflight(workflowId: String, input: JsonObject, actor: String): WorkflowPreflightResponse

  /** Re-runs preflight; refuses to start if blocked. Same token => returns the existing run. */
  suspend fun start(
      workflowId: String,
      input: JsonObject,
      actor: String,
      triggeredBy: String,
      idempotencyToken: String,
  ): StartResult

  /** [withSteps] populates [WorkflowRunDto.steps] (run-detail reads). */
  suspend fun getRun(runId: Long, withSteps: Boolean = true): WorkflowRunDto?

  suspend fun listRuns(filter: RunFilter): List<WorkflowRunDto>

  suspend fun listEvents(runId: Long, afterEventId: Long = 0): List<WorkflowEventDto>

  /** RETRY, SKIP, ROLLBACK, ABANDON, CANCEL; audited with [actor]. */
  suspend fun operate(runId: Long, action: WorkflowOperatorAction, actor: String): OperateResult

  suspend fun adminInProgress(): WorkflowAdminInProgressDto

  /**
   * The run holding a lock on this subject, or null. Service endpoints that mutate a subject (group
   * member add/remove, group settings) call this and return 409 while it is non-null.
   */
  suspend fun activeRunFor(subject: WorkflowSubject): WorkflowRunDto?

  /**
   * The file a SUCCEEDED/SKIPPED step declared under [artifactName], re-derived from the step's
   * persisted output (never from client input), or null if there is no such run, step or artifact.
   */
  suspend fun resolveArtifact(runId: Long, stepIndex: Int, artifactName: String): ResolvedArtifact?

  /** Appends and publishes the audit event for a download; returns it. */
  suspend fun recordArtifactDownload(
      runId: Long,
      stepIndex: Int,
      artifactName: String,
      actor: String,
  ): WorkflowEventDto
}

/** Server-side view of an artifact. [key] is relative to [namespace] and never leaves the service. */
data class ResolvedArtifact(
    val name: String,
    val label: String,
    val contentType: String,
    /** A `StorageNamespace` enum name. */
    val namespace: String,
    val key: String,
    val infraOnly: Boolean,
    val workflowId: String,
    /** Name of the Permission the workflow needs, as in [WorkflowDefinitionDto.requiredPermission]. */
    val requiredPermission: String?,
)

sealed interface StartResult {
  data class Started(val run: WorkflowRunDto) : StartResult

  data class Existing(val run: WorkflowRunDto) : StartResult

  data class Blocked(val plan: WorkflowPreflightResponse) : StartResult

  data class SubjectLocked(val activeRun: WorkflowRunDto) : StartResult

  /** The workflow's authorization mode denied the actor; [reason] is generic and safe to show. */
  data class Forbidden(val reason: String) : StartResult
}

sealed interface OperateResult {
  data class Ok(val run: WorkflowRunDto) : OperateResult

  data object NotFound : OperateResult

  /** The action isn't allowed in the run's current state (message explains). */
  data class NotAllowed(val message: String) : OperateResult
}

class UnknownWorkflowException(workflowId: String) : RuntimeException("Unknown workflow: $workflowId")

class InvalidWorkflowInputException(message: String) : RuntimeException(message)

/** Thrown by preflight when the actor is not authorized; [reason] is generic, never leaks state. */
class WorkflowForbiddenException(val reason: String) : RuntimeException(reason)

/** Authorization modes a workflow can name in its `authorization`. */
object WorkflowAuthorization {
  /** The actor must administer the group named by the "groupName" input. */
  const val GROUP_ADMIN = "GROUP_ADMIN"
}

sealed interface AuthorizationDecision {
  data object Allowed : AuthorizationDecision

  /** [reason] is shown to the actor: keep it generic. */
  data class Denied(val reason: String) : AuthorizationDecision
}

/**
 * Per-subject check for workflows that name an `authorization` mode; the engine fails closed when
 * no authorizer is registered for the mode or the authorizer throws or times out.
 */
interface WorkflowStartAuthorizer {
  suspend fun authorize(workflowId: String, input: JsonObject, actor: String): AuthorizationDecision
}

/**
 * In-process pub/sub used by the executor/service (publishers) and the SSE routes (subscribers).
 * Publish only AFTER the producing transaction commits. Implemented in the app module.
 */
interface WorkflowEventBus {
  fun publishRun(run: WorkflowRunDto)

  fun publishEvent(event: WorkflowEventDto)

  /** Live messages only; callers send the snapshot and any Last-Event-ID replay themselves. */
  fun subscribe(filter: StreamFilter): Flow<WorkflowStreamMessage>
}

/** Empty filter = everything (admin stream). */
data class StreamFilter(
    val runId: Long? = null,
    val subject: WorkflowSubject? = null,
    val workflowId: String? = null,
    val adminAll: Boolean = false,
)

/** Lifecycle of the background executor loop; started/stopped by the app module. */
interface WorkflowExecutor {
  /** Starts the claim loop in the given scope. Idempotent. */
  fun start(scope: kotlinx.coroutines.CoroutineScope)

  /** Stop claiming, let the current step finish within [graceMillis], release leases. */
  suspend fun shutdown(graceMillis: Long)
}
