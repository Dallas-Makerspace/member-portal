package org.dallasmakerspace.models.workflows

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire contract between the service (/workflows routes) and the UI. All timestamps are ISO-8601
// instants in UTC (e.g. 2026-01-02T03:04:05Z); the UI converts to Central Time for display.
// See docs/features/workflows/README.md.

@Serializable
enum class WorkflowRunStatus(val isTerminal: Boolean, val holdsSubjectLocks: Boolean) {
  PENDING(false, true),
  RUNNING(false, true),
  WAITING_RETRY(false, true),
  PAUSED(false, true),
  ROLLING_BACK(false, true),
  ROLLBACK_FAILED(false, true),
  SUCCEEDED(true, false),
  ROLLED_BACK(true, false),
  ABANDONED(true, false),
  CANCELLED(true, false),
}

@Serializable
enum class WorkflowStepStatus {
  PENDING,
  /** STARTED record: committed before the step runs; also the state of a step that may be partly applied. */
  RUNNING,
  SUCCEEDED,
  /** check() said NoOp and this step had no earlier attempt. */
  SKIPPED,
  FAILED,
  ROLLING_BACK,
  ROLLED_BACK,
  ROLLBACK_FAILED,
}

@Serializable enum class WorkflowReversibility { REVERSIBLE, BEST_EFFORT, IRREVERSIBLE }

@Serializable enum class PlanStepStatus { WILL_RUN, NO_OP, BLOCKED, WARNING, UNKNOWN }

@Serializable
enum class WorkflowOperatorAction { RETRY, SKIP, ROLLBACK, ABANDON, CANCEL }

@Serializable data class WorkflowSubject(val type: String, val key: String)

@Serializable
data class WorkflowInputField(
    val name: String,
    val label: String,
    val required: Boolean = true,
    /** "string" only for now. */
    val type: String = "string",
    /** Render as a multi-line text box. */
    val multiline: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    /** Helper text shown under the field (e.g. who can read what is entered). */
    val hint: String? = null,
)

@Serializable
data class WorkflowStepDefinitionDto(
    val id: String,
    val displayName: String,
    val reversibility: WorkflowReversibility,
    val skippable: Boolean,
    /** Public steps are reusable across workflows; private steps belong to one workflow. */
    val isPublic: Boolean,
)

@Serializable
data class WorkflowDefinitionDto(
    val id: String,
    val version: Int,
    val displayName: String,
    val description: String,
    /** Name of the org.dallasmakerspace.auth.Permission needed to launch it; enforced by the UI. */
    val requiredPermission: String?,
    val inputFields: List<WorkflowInputField>,
    val steps: List<WorkflowStepDefinitionDto>,
    /**
     * Per-subject authorization on top of [requiredPermission]; null = none. "GROUP_ADMIN": the
     * user must administer the group named by the "groupName" input (listed group admin, Domain
     * Admins or Infrastructure member). Enforced by the UI and re-checked by the service.
     */
    val authorization: String? = null,
    /** Name of the input field whose exact value the user must type to confirm; null = none. */
    val confirmTypingField: String? = null,
)

@Serializable data class WorkflowPreflightRequest(val input: JsonObject)

@Serializable
data class PlanStepDto(
    val stepId: String,
    val displayName: String,
    val status: PlanStepStatus,
    val messages: List<String> = emptyList(),
)

@Serializable
data class WorkflowPreflightResponse(
    val workflowId: String,
    /** False if any step is BLOCKED or the subject is locked by another run. */
    val canStart: Boolean,
    val steps: List<PlanStepDto>,
    /** Standing notes, e.g. about hardcoded group names. */
    val notes: List<String> = emptyList(),
    val activeRun: WorkflowRunDto? = null,
)

@Serializable
data class WorkflowStartRequest(val input: JsonObject, val idempotencyToken: String)

@Serializable
data class WorkflowStartResponse(
    val run: WorkflowRunDto,
    /** True when the idempotency token matched an existing run (no new run was created). */
    val existing: Boolean = false,
)

@Serializable
data class WorkflowStepRunDto(
    val index: Int,
    val stepId: String,
    val displayName: String,
    val status: WorkflowStepStatus,
    val attempts: Int,
    val reversibility: WorkflowReversibility,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val error: String? = null,
    /** Short human-readable result (never bulk data). */
    val outputSummary: String? = null,
    /** Files this step produced; names and labels only, never storage keys. */
    val artifacts: List<WorkflowArtifactDto> = emptyList(),
)

/** A downloadable file produced by a step. The download URL is created on click, never stored. */
@Serializable
data class WorkflowArtifactDto(
    /** Unique within the step, e.g. "member-list". */
    val name: String,
    val label: String,
    val contentType: String,
    /** Only infrastructure admins may download it; the UI enforces this. */
    val infraOnly: Boolean = false,
)

/** Answer of the artifact URL route; the URL is secret-bearing, so never log or store it. */
@Serializable
data class WorkflowArtifactUrlResponse(val url: String, val expiresInSeconds: Long)

@Serializable
data class WorkflowRunDto(
    val id: Long,
    val workflowId: String,
    val workflowVersion: Int,
    val displayName: String,
    val status: WorkflowRunStatus,
    val input: JsonObject,
    val subjects: List<WorkflowSubject>,
    val actor: String,
    val triggeredBy: String,
    val currentStepIndex: Int,
    val stepCount: Int,
    val createdAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val nextAttemptAt: String? = null,
    val errorSummary: String? = null,
    /** Service instance currently holding the lease (admin page only). */
    val lockedBy: String? = null,
    /** Seconds since the lease holder last heartbeat (admin page only). */
    val leaseAgeSeconds: Long? = null,
    /** Populated on run-detail reads; empty on list reads. */
    val steps: List<WorkflowStepRunDto> = emptyList(),
)

@Serializable
data class WorkflowEventDto(
    val id: Long,
    val runId: Long,
    val stepIndex: Int? = null,
    val timestamp: String,
    /** INFO, WARN or ERROR. */
    val level: String,
    val message: String,
    val actor: String? = null,
)

@Serializable
data class WorkflowAdminInProgressDto(
    /** True when no run is RUNNING or ROLLING_BACK (WAITING_RETRY / PAUSED survive a restart). */
    val safeToDeploy: Boolean,
    val runs: List<WorkflowRunDto>,
    val generatedAt: String,
)

/**
 * One SSE message. `type` is also used as the SSE event name:
 * - "snapshot": sent first on every stream; carries `runs` (list streams) or `run` (single-run streams)
 * - "run": a run changed; carries `run` (steps populated on single-run streams)
 * - "event": a log/audit event was appended; carries `event`
 * - "admin": admin stream only; carries `admin`
 * The SSE `id:` field is the workflow_event id when `event` is set, so clients can resume with
 * Last-Event-ID.
 */
@Serializable
data class WorkflowStreamMessage(
    val type: String,
    val run: WorkflowRunDto? = null,
    val runs: List<WorkflowRunDto>? = null,
    val event: WorkflowEventDto? = null,
    val admin: WorkflowAdminInProgressDto? = null,
)
