package org.dallasmakerspace.workflows

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowStepStatus
import org.dallasmakerspace.models.workflows.WorkflowSubject

/**
 * Persistence contract for workflow runs. Implemented by `ExposedWorkflowStore` (MariaDB,
 * `:service:workflows-store`) and `InMemoryWorkflowStore` (`:service:workflows`, tests/mock mode).
 *
 * Rules every implementation must honour (see docs/features/workflows/README.md, "Durability"):
 * - All lease timing uses the DATABASE clock (`NOW()`), never the JVM clock.
 * - Every run mutation made by an executor takes a [Fence]; if the fence no longer matches
 *   (`locked_by` + `lease_gen`) nothing is written and [FenceLostException] is thrown.
 * - Subject locks are acquired in [createRun] and released in the same transaction as the run reaching
 *   a status whose `holdsSubjectLocks` is false. Subject keys are normalized by [normalizeSubject].
 * - Each mutation also appends its workflow_event rows in the same transaction when `events` are given.
 */
interface WorkflowStore {
  /** Atomically inserts the run, its step rows (PENDING), and subject locks. */
  suspend fun createRun(new: NewRun): CreateRunResult

  suspend fun getRun(runId: Long): RunRecord?

  suspend fun getSteps(runId: Long): List<StepRecord>

  suspend fun listRuns(filter: RunFilter): List<RunRecord>

  /** Active (lock-holding) run for a subject, if any. */
  suspend fun activeRunForSubject(subject: WorkflowSubject): RunRecord?

  /** Events for a run, oldest first, with id > [afterEventId]. */
  suspend fun listEvents(runId: Long, afterEventId: Long = 0, limit: Int = 500): List<EventRecord>

  /** Events across all runs with id > [afterEventId] (SSE replay / future cross-instance fan-out). */
  suspend fun listEventsAfter(afterEventId: Long, limit: Int = 500): List<EventRecord>

  suspend fun appendEvent(
      runId: Long,
      stepIndex: Int?,
      level: String,
      message: String,
      actor: String? = null,
  ): EventRecord

  /**
   * Claims one runnable run: status in PENDING/RUNNING/WAITING_RETRY/ROLLING_BACK, `next_attempt_at`
   * null or due, lease null or expired. Increments `lease_gen`, sets `locked_by` and
   * `lease_until = NOW() + leaseSeconds`. Never claims PAUSED, ROLLBACK_FAILED or terminal runs.
   */
  suspend fun claimNext(instanceId: String, leaseSeconds: Int): ClaimedRun?

  /**
   * One cheap read, DB clock: milliseconds until some run could next need an executor, or null if
   * none ever will without an outside event. 0 = a run is claimable now (PENDING, or RUNNING /
   * ROLLING_BACK with a null or expired lease, or WAITING_RETRY that is due). Otherwise the minimum of
   * `next_attempt_at` over WAITING_RETRY runs and `lease_until` over leased RUNNING / ROLLING_BACK
   * runs, minus now. The executor sleeps until then or until an in-process wake signal; it never polls.
   */
  suspend fun millisUntilNextWork(): Long?

  /** Extends the lease; false (and no change) if the fence is lost. */
  suspend fun heartbeat(fence: Fence, leaseSeconds: Int): Boolean

  /** Sets `lease_until = NOW()` so another instance can claim immediately. Fence-guarded, best effort. */
  suspend fun releaseLease(fence: Fence)

  /** Commits the STARTED record for a step attempt (status RUNNING, attempts incremented). */
  suspend fun markStepStarted(fence: Fence, stepIndex: Int, events: List<NewEvent> = emptyList())

  /** Sets final step state. [outputJson] is the serialized step output (never secrets / bulk PII). */
  suspend fun finishStep(
      fence: Fence,
      stepIndex: Int,
      status: WorkflowStepStatus,
      outputJson: String? = null,
      outputSummary: String? = null,
      error: String? = null,
      events: List<NewEvent> = emptyList(),
  )

  /** Updates run state. Releases subject locks if [RunUpdate.status] does not hold them. */
  suspend fun updateRun(fence: Fence, update: RunUpdate, events: List<NewEvent> = emptyList())

  /**
   * Operator/API transition for runs NOT currently leased (PAUSED, ROLLBACK_FAILED, or
   * PENDING/WAITING_RETRY for cancel/abandon). Applies only if the run's status is in [fromStatuses]
   * and its lease is null/expired; returns false otherwise. Also may reset a step row via [stepReset].
   * Exception: an update that ONLY sets `cancelRequested` ignores the lease (the lease holder reads the flag),
   * but still requires a non-terminal status.
   */
  suspend fun operatorTransition(
      runId: Long,
      fromStatuses: Set<WorkflowRunStatus>,
      update: RunUpdate,
      stepReset: StepReset? = null,
      events: List<NewEvent> = emptyList(),
  ): Boolean

  /** Runs with status RUNNING, WAITING_RETRY, ROLLING_BACK, PAUSED, ROLLBACK_FAILED (admin page). */
  suspend fun listInProgress(): List<RunRecord>
}

class FenceLostException(message: String) : RuntimeException(message)

/** Proof of lease ownership returned by claimNext and required by every executor write. */
data class Fence(val runId: Long, val instanceId: String, val leaseGen: Long)

data class ClaimedRun(val run: RunRecord, val fence: Fence)

data class NewRun(
    val workflowId: String,
    val workflowVersion: Int,
    val inputJson: String,
    val actor: String,
    val triggeredBy: String,
    val stepIds: List<String>,
    val subjects: List<WorkflowSubject>,
    val idempotencyToken: String,
)

sealed interface CreateRunResult {
  data class Created(val run: RunRecord) : CreateRunResult

  /** Same (workflowId, subjects, idempotencyToken) already exists. */
  data class Existing(val run: RunRecord) : CreateRunResult

  data class SubjectLocked(val subject: WorkflowSubject, val activeRun: RunRecord) : CreateRunResult
}

data class RunRecord(
    val id: Long,
    val workflowId: String,
    val workflowVersion: Int,
    val status: WorkflowRunStatus,
    val inputJson: String,
    val actor: String,
    val triggeredBy: String,
    val stepIds: List<String>,
    val subjects: List<WorkflowSubject>,
    val currentStepIndex: Int,
    val lockedBy: String?,
    val leaseGen: Long,
    /** Seconds since the lease was last extended (DB clock); null when not leased. */
    val leaseAgeSeconds: Long?,
    val nextAttemptAt: Instant?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val errorSummary: String?,
    val cancelRequested: Boolean,
)

data class StepRecord(
    val runId: Long,
    val index: Int,
    val stepId: String,
    val status: WorkflowStepStatus,
    val attempts: Int,
    val outputJson: String?,
    val outputSummary: String?,
    val error: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
)

data class EventRecord(
    val id: Long,
    val runId: Long,
    val stepIndex: Int?,
    val timestamp: Instant,
    val level: String,
    val message: String,
    val actor: String?,
)

data class NewEvent(
    val stepIndex: Int?,
    val level: String,
    val message: String,
    val actor: String? = null,
)

/** Fields left null are unchanged. [clearNextAttempt]/[clearError] set the column to NULL. */
data class RunUpdate(
    val status: WorkflowRunStatus? = null,
    val currentStepIndex: Int? = null,
    val nextAttemptAt: Instant? = null,
    val clearNextAttempt: Boolean = false,
    val errorSummary: String? = null,
    val clearError: Boolean = false,
    val markStarted: Boolean = false,
    val markFinished: Boolean = false,
    val cancelRequested: Boolean? = null,
)

/** Resets one step row (e.g. RETRY puts a FAILED step back to PENDING and keeps its attempts). */
data class StepReset(val stepIndex: Int, val status: WorkflowStepStatus, val clearError: Boolean = true)

data class RunFilter(
    val workflowId: String? = null,
    val subject: WorkflowSubject? = null,
    val statuses: Set<WorkflowRunStatus>? = null,
    val actor: String? = null,
    val activeOnly: Boolean = false,
    val limit: Int = 100,
)

/** Lowercase + trim, so case-insensitive collation can't create a lock that conflicts with itself. */
fun normalizeSubject(subject: WorkflowSubject) =
    WorkflowSubject(subject.type.trim().lowercase(), subject.key.trim().lowercase())

/** Convenience for callers that only have JSON. */
typealias WorkflowInput = JsonObject
