package org.dallasmakerspace.workflows

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowStepStatus
import org.dallasmakerspace.models.workflows.WorkflowSubject

/**
 * In-memory [WorkflowStore] for tests and mock mode. [clock] stands in for the database clock: all
 * lease timing uses it, never the JVM clock directly. A lease counts as expired when
 * `lease_until <= now`. A [RunUpdate] that only sets `cancelRequested` bypasses the lease check in
 * [operatorTransition] so a running run can be asked to cancel.
 */
class InMemoryWorkflowStore(private val clock: Clock = Clock.systemUTC()) : WorkflowStore {
  private class RunState(
      val id: Long,
      val workflowId: String,
      val workflowVersion: Int,
      var status: WorkflowRunStatus,
      val inputJson: String,
      val actor: String,
      val triggeredBy: String,
      val stepIds: List<String>,
      val subjects: List<WorkflowSubject>,
      val idempotencyToken: String,
      val createdAt: Instant,
  ) {
    var currentStepIndex = 0
    var lockedBy: String? = null
    var leaseGen = 0L
    var leaseUntil: Instant? = null
    var heartbeatAt: Instant? = null
    var nextAttemptAt: Instant? = null
    var startedAt: Instant? = null
    var finishedAt: Instant? = null
    var errorSummary: String? = null
    var cancelRequested = false
  }

  private class StepState(val runId: Long, val index: Int, val stepId: String) {
    var status = WorkflowStepStatus.PENDING
    var attempts = 0
    var outputJson: String? = null
    var outputSummary: String? = null
    var error: String? = null
    var startedAt: Instant? = null
    var finishedAt: Instant? = null
  }

  private val mutex = Mutex()
  private val runs = LinkedHashMap<Long, RunState>()
  private val steps = HashMap<Long, List<StepState>>()
  private val events = ArrayList<EventRecord>()
  private val locks = HashMap<WorkflowSubject, Long>()
  private var runSeq = 0L
  private var eventSeq = 0L

  private fun now(): Instant = clock.instant()

  private fun RunState.record(): RunRecord {
    val n = now()
    val leased = leaseUntil?.let { it.isAfter(n) } == true
    return RunRecord(
        id = id,
        workflowId = workflowId,
        workflowVersion = workflowVersion,
        status = status,
        inputJson = inputJson,
        actor = actor,
        triggeredBy = triggeredBy,
        stepIds = stepIds,
        subjects = subjects,
        currentStepIndex = currentStepIndex,
        lockedBy = lockedBy,
        leaseGen = leaseGen,
        leaseAgeSeconds = if (leased) heartbeatAt?.let { Duration.between(it, n).seconds } else null,
        nextAttemptAt = nextAttemptAt,
        createdAt = createdAt,
        startedAt = startedAt,
        finishedAt = finishedAt,
        errorSummary = errorSummary,
        cancelRequested = cancelRequested,
    )
  }

  private fun StepState.record() =
      StepRecord(runId, index, stepId, status, attempts, outputJson, outputSummary, error, startedAt, finishedAt)

  private fun leaseExpired(run: RunState) = run.leaseUntil?.let { !it.isAfter(now()) } ?: true

  private fun requireFence(fence: Fence): RunState {
    val run = runs[fence.runId]
    if (run == null || run.lockedBy != fence.instanceId || run.leaseGen != fence.leaseGen) {
      throw FenceLostException("Lease lost for run ${fence.runId}")
    }
    return run
  }

  private fun addEvents(runId: Long, newEvents: List<NewEvent>) {
    newEvents.forEach { addEvent(runId, it.stepIndex, it.level, it.message, it.actor) }
  }

  private fun addEvent(runId: Long, stepIndex: Int?, level: String, message: String, actor: String?) =
      EventRecord(++eventSeq, runId, stepIndex, now(), level, message, actor).also { events.add(it) }

  private fun applyUpdate(run: RunState, update: RunUpdate) {
    update.status?.let {
      run.status = it
      if (!it.holdsSubjectLocks) releaseLocks(run.id)
    }
    update.currentStepIndex?.let { run.currentStepIndex = it }
    if (update.clearNextAttempt) run.nextAttemptAt = null
    update.nextAttemptAt?.let { run.nextAttemptAt = it }
    if (update.clearError) run.errorSummary = null
    update.errorSummary?.let { run.errorSummary = it }
    if (update.markStarted && run.startedAt == null) run.startedAt = now()
    if (update.markFinished) run.finishedAt = now()
    update.cancelRequested?.let { run.cancelRequested = it }
  }

  private fun releaseLocks(runId: Long) {
    locks.entries.removeIf { it.value == runId }
  }

  override suspend fun createRun(new: NewRun): CreateRunResult =
      mutex.withLock {
        val subjects = new.subjects.map(::normalizeSubject).distinct()
        val existing =
            runs.values.firstOrNull {
              it.workflowId == new.workflowId &&
                  it.idempotencyToken == new.idempotencyToken &&
                  it.subjects.toSet() == subjects.toSet()
            }
        if (existing != null) return@withLock CreateRunResult.Existing(existing.record())
        for (subject in subjects) {
          val holder = locks[subject]?.let { runs[it] }
          if (holder != null) {
            return@withLock CreateRunResult.SubjectLocked(subject, holder.record())
          }
        }
        val run =
            RunState(
                id = ++runSeq,
                workflowId = new.workflowId,
                workflowVersion = new.workflowVersion,
                status = WorkflowRunStatus.PENDING,
                inputJson = new.inputJson,
                actor = new.actor,
                triggeredBy = new.triggeredBy,
                stepIds = new.stepIds,
                subjects = subjects,
                idempotencyToken = new.idempotencyToken,
                createdAt = now(),
            )
        runs[run.id] = run
        steps[run.id] = new.stepIds.mapIndexed { i, id -> StepState(run.id, i, id) }
        subjects.forEach { locks[it] = run.id }
        addEvent(run.id, null, "INFO", "Run created", new.actor)
        CreateRunResult.Created(run.record())
      }

  override suspend fun getRun(runId: Long) = mutex.withLock { runs[runId]?.record() }

  override suspend fun getSteps(runId: Long) =
      mutex.withLock { steps[runId].orEmpty().map { it.record() } }

  override suspend fun listRuns(filter: RunFilter): List<RunRecord> =
      mutex.withLock {
        val subject = filter.subject?.let(::normalizeSubject)
        val statuses = filter.statuses
        runs.values
            .filter { filter.workflowId == null || it.workflowId == filter.workflowId }
            .filter { subject == null || subject in it.subjects }
            .filter { statuses == null || it.status in statuses }
            .filter { filter.actor == null || it.actor.equals(filter.actor, ignoreCase = true) }
            .filter { !filter.activeOnly || it.status.holdsSubjectLocks }
            .sortedByDescending { it.id }
            .take(filter.limit)
            .map { it.record() }
      }

  override suspend fun activeRunForSubject(subject: WorkflowSubject): RunRecord? =
      mutex.withLock { locks[normalizeSubject(subject)]?.let { runs[it]?.record() } }

  override suspend fun listEvents(runId: Long, afterEventId: Long, limit: Int) =
      mutex.withLock { events.filter { it.runId == runId && it.id > afterEventId }.take(limit) }

  override suspend fun listEventsAfter(afterEventId: Long, limit: Int) =
      mutex.withLock { events.filter { it.id > afterEventId }.take(limit) }

  override suspend fun appendEvent(
      runId: Long,
      stepIndex: Int?,
      level: String,
      message: String,
      actor: String?,
  ) = mutex.withLock { addEvent(runId, stepIndex, level, message, actor) }

  override suspend fun claimNext(instanceId: String, leaseSeconds: Int): ClaimedRun? =
      mutex.withLock {
        val n = now()
        val run =
            runs.values.firstOrNull {
              it.status in CLAIMABLE &&
                  (it.nextAttemptAt?.let { t -> !t.isAfter(n) } ?: true) &&
                  leaseExpired(it)
            } ?: return@withLock null
        run.leaseGen++
        run.lockedBy = instanceId
        run.leaseUntil = n.plusSeconds(leaseSeconds.toLong())
        run.heartbeatAt = n
        ClaimedRun(run.record(), Fence(run.id, instanceId, run.leaseGen))
      }

  override suspend fun millisUntilNextWork(): Long? =
      mutex.withLock {
        val n = now()
        runs.values
            .filter { it.status in CLAIMABLE }
            .map { maxOf(it.nextAttemptAt ?: n, it.leaseUntil ?: n) }
            .minOrNull()
            ?.let { Math.ceilDiv(Duration.between(n, it).toNanos(), 1_000_000L).coerceAtLeast(0) }
      }

  override suspend fun heartbeat(fence: Fence, leaseSeconds: Int): Boolean =
      mutex.withLock {
        val run = runs[fence.runId]
        if (run == null || run.lockedBy != fence.instanceId || run.leaseGen != fence.leaseGen) {
          return@withLock false
        }
        run.leaseUntil = now().plusSeconds(leaseSeconds.toLong())
        run.heartbeatAt = now()
        true
      }

  override suspend fun releaseLease(fence: Fence) {
    mutex.withLock {
      val run = runs[fence.runId] ?: return@withLock
      if (run.lockedBy == fence.instanceId && run.leaseGen == fence.leaseGen) run.leaseUntil = now()
    }
  }

  override suspend fun markStepStarted(fence: Fence, stepIndex: Int, events: List<NewEvent>) {
    mutex.withLock {
      val run = requireFence(fence)
      val step = steps.getValue(run.id)[stepIndex]
      step.status = WorkflowStepStatus.RUNNING
      step.attempts++
      step.startedAt = now()
      step.finishedAt = null
      addEvents(run.id, events)
    }
  }

  override suspend fun finishStep(
      fence: Fence,
      stepIndex: Int,
      status: WorkflowStepStatus,
      outputJson: String?,
      outputSummary: String?,
      error: String?,
      events: List<NewEvent>,
  ) {
    mutex.withLock {
      val run = requireFence(fence)
      val step = steps.getValue(run.id)[stepIndex]
      step.status = status
      step.outputJson = outputJson
      step.outputSummary = outputSummary
      step.error = error
      step.finishedAt = now()
      addEvents(run.id, events)
    }
  }

  override suspend fun updateRun(fence: Fence, update: RunUpdate, events: List<NewEvent>) {
    mutex.withLock {
      val run = requireFence(fence)
      applyUpdate(run, update)
      addEvents(run.id, events)
    }
  }

  override suspend fun operatorTransition(
      runId: Long,
      fromStatuses: Set<WorkflowRunStatus>,
      update: RunUpdate,
      stepReset: StepReset?,
      events: List<NewEvent>,
  ): Boolean =
      mutex.withLock {
        val run = runs[runId] ?: return@withLock false
        val cancelOnly = update.status == null && update.cancelRequested == true
        if (run.status !in fromStatuses || (!cancelOnly && !leaseExpired(run))) {
          return@withLock false
        }
        applyUpdate(run, update)
        stepReset?.let {
          val step = steps.getValue(runId)[it.stepIndex]
          step.status = it.status
          if (it.clearError) step.error = null
        }
        addEvents(runId, events)
        true
      }

  override suspend fun listInProgress(): List<RunRecord> =
      mutex.withLock {
        runs.values.filter { it.status in IN_PROGRESS }.sortedBy { it.id }.map { it.record() }
      }

  private companion object {
    val CLAIMABLE =
        setOf(
            WorkflowRunStatus.PENDING,
            WorkflowRunStatus.RUNNING,
            WorkflowRunStatus.WAITING_RETRY,
            WorkflowRunStatus.ROLLING_BACK,
        )
    val IN_PROGRESS =
        CLAIMABLE +
            setOf(
                WorkflowRunStatus.PAUSED,
                WorkflowRunStatus.ROLLBACK_FAILED,
            ) - WorkflowRunStatus.PENDING
  }
}
