package org.dallasmakerspace.workflows.store

import java.security.MessageDigest
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowStepStatus
import org.dallasmakerspace.models.workflows.WorkflowSubject
import org.dallasmakerspace.workflows.ClaimedRun
import org.dallasmakerspace.workflows.CreateRunResult
import org.dallasmakerspace.workflows.EventRecord
import org.dallasmakerspace.workflows.Fence
import org.dallasmakerspace.workflows.FenceLostException
import org.dallasmakerspace.workflows.NewEvent
import org.dallasmakerspace.workflows.NewRun
import org.dallasmakerspace.workflows.RunFilter
import org.dallasmakerspace.workflows.RunRecord
import org.dallasmakerspace.workflows.RunUpdate
import org.dallasmakerspace.workflows.StepRecord
import org.dallasmakerspace.workflows.StepReset
import org.dallasmakerspace.workflows.WorkflowStore
import org.dallasmakerspace.workflows.normalizeSubject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Function
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.javatime.JavaLocalDateTimeColumnType
import org.jetbrains.exposed.sql.statements.UpdateStatement
import org.jetbrains.exposed.sql.update

/** MariaDB-backed [WorkflowStore]. Lease timing uses the DB clock (`UTC_TIMESTAMP()`) only. */
@Suppress("TooManyFunctions")
class ExposedWorkflowStore @Inject constructor() : WorkflowStore {

  override suspend fun createRun(new: NewRun): CreateRunResult {
    val subjects = new.subjects.map(::normalizeSubject).distinct()
    val key = idempotencyKey(new.workflowId, subjects, new.idempotencyToken)
    var attempt = 0
    while (true) {
      attempt++
      try {
        return suspendTransaction { createRunInTx(new, subjects, key) }
      } catch (e: ExposedSQLException) {
        // Lost a race on the idempotency key or a subject lock; the next attempt sees the winner.
        if (!e.isConstraintViolation() || attempt >= CREATE_ATTEMPTS) throw e
      }
    }
  }

  private fun Transaction.createRunInTx(
      new: NewRun,
      subjects: List<WorkflowSubject>,
      key: String,
  ): CreateRunResult =
      findByKey(key)
          ?: subjects.firstNotNullOfOrNull { subject ->
            activeRunForSubjectInTx(subject)?.let { holder ->
              // Re-check: the holder may be the run this same token just created.
              findByKey(key) ?: CreateRunResult.SubjectLocked(subject, holder)
            }
          }
          ?: CreateRunResult.Created(insertRun(new, subjects, key))

  private fun Transaction.findByKey(key: String): CreateRunResult.Existing? =
      selectRunRows { WorkflowRunTable.idempotencyKey eq key }
          .firstOrNull()
          ?.let { CreateRunResult.Existing(it) }

  private fun Transaction.insertRun(
      new: NewRun,
      subjects: List<WorkflowSubject>,
      key: String,
  ): RunRecord {
    val runId =
        WorkflowRunTable.insert {
          it[workflowId] = new.workflowId
          it[workflowVersion] = new.workflowVersion
          it[status] = WorkflowRunStatus.PENDING.name
          it[inputJson] = new.inputJson
          it[actor] = new.actor
          it[triggeredBy] = new.triggeredBy
          it[currentStepIndex] = 0
          it[stepIdsJson] = Json.encodeToString(STEP_IDS_SERIALIZER, new.stepIds)
          it[leaseGen] = 0
          it[idempotencyToken] = new.idempotencyToken
          it[idempotencyKey] = key
          it[cancelRequested] = false
          it[createdAt] = DbNow
          it[updatedAt] = DbNow
        }[WorkflowRunTable.id]
    new.stepIds.forEachIndexed { index, stepId ->
      WorkflowStepRunTable.insert {
        it[WorkflowStepRunTable.runId] = runId
        it[stepIndex] = index
        it[WorkflowStepRunTable.stepId] = stepId
        it[status] = WorkflowStepStatus.PENDING.name
        it[attempts] = 0
      }
    }
    for (subject in subjects) {
      WorkflowRunSubjectTable.insert {
        it[WorkflowRunSubjectTable.runId] = runId
        it[subjectType] = subject.type
        it[subjectKey] = subject.key
        it[activeKey] = ACTIVE
      }
    }
    return loadRun(runId)!!
  }

  override suspend fun getRun(runId: Long): RunRecord? = suspendTransaction { loadRun(runId) }

  override suspend fun getSteps(runId: Long): List<StepRecord> = suspendTransaction {
    WorkflowStepRunTable.select { WorkflowStepRunTable.runId eq runId }
        .orderBy(WorkflowStepRunTable.stepIndex)
        .map { it.toStep() }
  }

  override suspend fun listRuns(filter: RunFilter): List<RunRecord> = suspendTransaction {
    val subject = filter.subject?.let(::normalizeSubject)
    selectRunRows(limit = filter.limit.coerceIn(1, MAX_LIMIT), newestFirst = true) {
      var cond: Op<Boolean> = Op.TRUE
      filter.workflowId?.let { cond = cond and (WorkflowRunTable.workflowId eq it) }
      filter.actor?.let { cond = cond and (WorkflowRunTable.actor eq it) }
      filter.statuses?.let { statuses ->
        cond = cond and (WorkflowRunTable.status inList statuses.map { it.name })
      }
      if (filter.activeOnly) {
        val active = WorkflowRunStatus.values().filter { it.holdsSubjectLocks }.map { it.name }
        cond = cond and (WorkflowRunTable.status inList active)
      }
      if (subject != null) {
        cond =
            cond and
                (WorkflowRunTable.id inSubQuery
                    WorkflowRunSubjectTable.slice(WorkflowRunSubjectTable.runId).select {
                      (WorkflowRunSubjectTable.subjectType eq subject.type) and
                          (WorkflowRunSubjectTable.subjectKey eq subject.key)
                    })
      }
      cond
    }
  }

  override suspend fun activeRunForSubject(subject: WorkflowSubject): RunRecord? =
      suspendTransaction {
        activeRunForSubjectInTx(normalizeSubject(subject))
      }

  private fun Transaction.activeRunForSubjectInTx(subject: WorkflowSubject): RunRecord? {
    val runId =
        WorkflowRunSubjectTable.slice(WorkflowRunSubjectTable.runId)
            .select {
              (WorkflowRunSubjectTable.subjectType eq subject.type) and
                  (WorkflowRunSubjectTable.subjectKey eq subject.key) and
                  (WorkflowRunSubjectTable.activeKey eq ACTIVE)
            }
            .firstOrNull()
            ?.get(WorkflowRunSubjectTable.runId) ?: return null
    return loadRun(runId)
  }

  override suspend fun listEvents(runId: Long, afterEventId: Long, limit: Int): List<EventRecord> =
      suspendTransaction {
        WorkflowEventTable.select {
              (WorkflowEventTable.runId eq runId) and (WorkflowEventTable.id greater afterEventId)
            }
            .orderBy(WorkflowEventTable.id)
            .limit(limit.coerceIn(1, MAX_LIMIT))
            .map { it.toEvent() }
      }

  override suspend fun listEventsAfter(afterEventId: Long, limit: Int): List<EventRecord> =
      suspendTransaction {
        WorkflowEventTable.select { WorkflowEventTable.id greater afterEventId }
            .orderBy(WorkflowEventTable.id)
            .limit(limit.coerceIn(1, MAX_LIMIT))
            .map { it.toEvent() }
      }

  override suspend fun appendEvent(
      runId: Long,
      stepIndex: Int?,
      level: String,
      message: String,
      actor: String?,
  ): EventRecord = suspendTransaction {
    val id = insertEvent(runId, NewEvent(stepIndex, level, message, actor))
    WorkflowEventTable.select { WorkflowEventTable.id eq id }.first().toEvent()
  }

  override suspend fun claimNext(instanceId: String, leaseSeconds: Int): ClaimedRun? =
      suspendTransaction {
        // Candidates are read unlocked; the conditional UPDATE is the atomic claim. Re-read when
        // every candidate was lost to a concurrent claimer, so contention never hides runnable runs.
        repeat(CLAIM_ROUNDS) {
          val candidates =
              WorkflowRunTable.slice(WorkflowRunTable.id)
                  .select { claimable() }
                  .orderBy(WorkflowRunTable.id)
                  .limit(CLAIM_CANDIDATES)
                  .map { it[WorkflowRunTable.id] }
          if (candidates.isEmpty()) return@suspendTransaction null
          for (candidate in candidates) {
            val claimed =
                WorkflowRunTable.update({ (WorkflowRunTable.id eq candidate) and claimable() }) {
                  it[lockedBy] = instanceId
                  it[leaseGen] = leaseGen + 1
                  it[leaseUntil] = SecondsFromNow(leaseSeconds)
                  it[heartbeatAt] = DbNow
                  it[updatedAt] = DbNow
                }
            if (claimed == 1) {
              val run = loadRun(candidate)!!
              return@suspendTransaction ClaimedRun(run, Fence(candidate, instanceId, run.leaseGen))
            }
          }
        }
        null
      }

  override suspend fun millisUntilNextWork(): Long? = suspendTransaction {
    val row =
        WorkflowRunTable.slice(NextWorkAt, DB_NOW_ALIAS)
            .select { WorkflowRunTable.status inList CLAIMABLE_STATUSES.map { it.name } }
            .single()
    val at = row[NextWorkAt]
    val now = row[DB_NOW_ALIAS]
    if (at == null || now == null) return@suspendTransaction null
    val nanos = Duration.between(now, at).toNanos().coerceAtLeast(0)
    (nanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI
  }

  override suspend fun heartbeat(fence: Fence, leaseSeconds: Int): Boolean = suspendTransaction {
    WorkflowRunTable.update({ fenceMatches(fence) }) {
      it[leaseUntil] = SecondsFromNow(leaseSeconds)
      it[heartbeatAt] = DbNow
    } == 1
  }

  override suspend fun releaseLease(fence: Fence) = suspendTransaction {
    fenced(fence) {
      it[leaseUntil] = DbNow
      it[lockedBy] = null
      it[heartbeatAt] = null
    }
  }

  override suspend fun markStepStarted(fence: Fence, stepIndex: Int, events: List<NewEvent>) =
      suspendTransaction {
        fenced(fence) {}
        val rows =
            WorkflowStepRunTable.update({ stepOf(fence.runId, stepIndex) }) {
              it[status] = WorkflowStepStatus.RUNNING.name
              it[attempts] = attempts + 1
              it[startedAt] = DbNow
              it[finishedAt] = null
            }
        require(rows == 1) { "run ${fence.runId} has no step $stepIndex" }
        events.forEach { insertEvent(fence.runId, it) }
      }

  override suspend fun finishStep(
      fence: Fence,
      stepIndex: Int,
      status: WorkflowStepStatus,
      outputJson: String?,
      outputSummary: String?,
      error: String?,
      events: List<NewEvent>,
  ) = suspendTransaction {
    fenced(fence) {}
    val finished = status in FINISHED_STEP_STATUSES
    val rows =
        WorkflowStepRunTable.update({ stepOf(fence.runId, stepIndex) }) {
          it[WorkflowStepRunTable.status] = status.name
          it[WorkflowStepRunTable.outputJson] = outputJson
          it[WorkflowStepRunTable.outputSummary] = outputSummary?.take(SUMMARY_MAX)
          it[WorkflowStepRunTable.error] = error?.take(ERROR_MAX)
          if (finished) it[finishedAt] = DbNow else it[finishedAt] = null
        }
    require(rows == 1) { "run ${fence.runId} has no step $stepIndex" }
    events.forEach { insertEvent(fence.runId, it) }
  }

  override suspend fun updateRun(fence: Fence, update: RunUpdate, events: List<NewEvent>) =
      suspendTransaction {
        fenced(fence) { applyRunUpdate(it, update) }
        if (update.status?.holdsSubjectLocks == false) releaseSubjectLocks(fence.runId)
        events.forEach { insertEvent(fence.runId, it) }
      }

  override suspend fun operatorTransition(
      runId: Long,
      fromStatuses: Set<WorkflowRunStatus>,
      update: RunUpdate,
      stepReset: StepReset?,
      events: List<NewEvent>,
  ): Boolean = suspendTransaction {
    // A cancel request alone is only a flag the lease holder reads, so it ignores the lease
    // (but only on non-terminal runs).
    val flagOnly = update.isCancelFlagOnly() && stepReset == null
    val allowed =
        if (flagOnly) fromStatuses.filter { !it.isTerminal } else fromStatuses.toList()
    val rows =
        WorkflowRunTable.update({
          (WorkflowRunTable.id eq runId) and
              (WorkflowRunTable.status inList allowed.map { it.name }) and
              (if (flagOnly) Op.TRUE else leaseFree())
        }) {
          applyRunUpdate(it, update)
          it[updatedAt] = DbNow
          if (!flagOnly) {
            // Invalidates the fence of any stale executor still holding the old generation.
            it[leaseGen] = leaseGen + 1
            it[lockedBy] = null
            it[heartbeatAt] = null
          }
        }
    if (rows != 1) return@suspendTransaction false
    if (update.status?.holdsSubjectLocks == false) releaseSubjectLocks(runId)
    stepReset?.let { reset ->
      WorkflowStepRunTable.update({ stepOf(runId, reset.stepIndex) }) {
        it[status] = reset.status.name
        if (reset.clearError) it[error] = null
        if (reset.status == WorkflowStepStatus.PENDING) it[finishedAt] = null
      }
    }
    events.forEach { insertEvent(runId, it) }
    true
  }

  override suspend fun listInProgress(): List<RunRecord> = suspendTransaction {
    selectRunRows {
      WorkflowRunTable.status inList IN_PROGRESS_STATUSES.map { it.name }
    }
  }

  // --- helpers ---

  private fun RunUpdate.isCancelFlagOnly() =
      cancelRequested != null &&
          status == null &&
          currentStepIndex == null &&
          nextAttemptAt == null &&
          !clearNextAttempt &&
          errorSummary == null &&
          !clearError &&
          !markStarted &&
          !markFinished

  private fun WorkflowRunTable.applyRunUpdate(it: UpdateStatement, update: RunUpdate) {
    update.status?.let { s -> it[status] = s.name }
    update.currentStepIndex?.let { i -> it[currentStepIndex] = i }
    val next = update.nextAttemptAt
    if (next != null) it[nextAttemptAt] = next.toUtc()
    else if (update.clearNextAttempt) it[nextAttemptAt] = null
    val summary = update.errorSummary
    if (summary != null) it[errorSummary] = summary.take(ERROR_MAX)
    else if (update.clearError) it[errorSummary] = null
    if (update.markStarted) it[startedAt] = CoalesceTime(startedAt, DbNow)
    if (update.markFinished) it[finishedAt] = DbNow
    update.cancelRequested?.let { c -> it[cancelRequested] = c }
  }

  /** Writes to the run row only if the fence still matches, else throws before anything else is written. */
  private fun fenced(fence: Fence, body: WorkflowRunTable.(UpdateStatement) -> Unit) {
    val rows =
        WorkflowRunTable.update({ fenceMatches(fence) }) {
          body(WorkflowRunTable, it)
          it[updatedAt] = DbNow
        }
    if (rows != 1) throw FenceLostException("lease lost for run ${fence.runId}")
  }

  private fun SqlExpressionBuilder.fenceMatches(fence: Fence): Op<Boolean> =
      (WorkflowRunTable.id eq fence.runId) and
          (WorkflowRunTable.lockedBy eq fence.instanceId) and
          (WorkflowRunTable.leaseGen eq fence.leaseGen)

  private fun SqlExpressionBuilder.leaseFree(): Op<Boolean> =
      WorkflowRunTable.leaseUntil.isNull() or (WorkflowRunTable.leaseUntil lessEq DbNow)

  private fun SqlExpressionBuilder.claimable(): Op<Boolean> =
      (WorkflowRunTable.status inList CLAIMABLE_STATUSES.map { it.name }) and
          (WorkflowRunTable.nextAttemptAt.isNull() or (WorkflowRunTable.nextAttemptAt lessEq DbNow)) and
          leaseFree()

  private fun SqlExpressionBuilder.stepOf(runId: Long, stepIndex: Int): Op<Boolean> =
      (WorkflowStepRunTable.runId eq runId) and (WorkflowStepRunTable.stepIndex eq stepIndex)

  private fun releaseSubjectLocks(runId: Long) {
    WorkflowRunSubjectTable.update({ WorkflowRunSubjectTable.runId eq runId }) {
      it[activeKey] = null
    }
  }

  private fun insertEvent(runId: Long, event: NewEvent): Long =
      WorkflowEventTable.insert {
        it[WorkflowEventTable.runId] = runId
        it[stepIndex] = event.stepIndex
        it[ts] = DbNow
        it[level] = event.level.take(LEVEL_MAX)
        it[message] = event.message.take(MESSAGE_MAX)
        it[actor] = event.actor
      }[WorkflowEventTable.id]

  private fun Transaction.loadRun(runId: Long): RunRecord? =
      selectRunRows { WorkflowRunTable.id eq runId }.firstOrNull()

  private fun Transaction.selectRunRows(
      limit: Int? = null,
      newestFirst: Boolean = false,
      where: SqlExpressionBuilder.() -> Op<Boolean>,
  ): List<RunRecord> {
    var query =
        WorkflowRunTable.slice(WorkflowRunTable.columns + DB_NOW_ALIAS)
            .select(where)
            .orderBy(WorkflowRunTable.id, if (newestFirst) SortOrder.DESC else SortOrder.ASC)
    if (limit != null) query = query.limit(limit)
    val rows = query.toList()
    if (rows.isEmpty()) return emptyList()
    val subjectsByRun =
        WorkflowRunSubjectTable.select {
              WorkflowRunSubjectTable.runId inList rows.map { it[WorkflowRunTable.id] }
            }
            .orderBy(WorkflowRunSubjectTable.id)
            .groupBy({ it[WorkflowRunSubjectTable.runId] }) {
              WorkflowSubject(it[WorkflowRunSubjectTable.subjectType], it[WorkflowRunSubjectTable.subjectKey])
            }
    return rows.map { it.toRun(subjectsByRun[it[WorkflowRunTable.id]].orEmpty()) }
  }

  private fun ResultRow.toRun(subjects: List<WorkflowSubject>): RunRecord {
    val dbNow = this[DB_NOW_ALIAS]
    val heartbeat = this[WorkflowRunTable.heartbeatAt]
    val lockedBy = this[WorkflowRunTable.lockedBy]
    return RunRecord(
        id = this[WorkflowRunTable.id],
        workflowId = this[WorkflowRunTable.workflowId],
        workflowVersion = this[WorkflowRunTable.workflowVersion],
        status = WorkflowRunStatus.valueOf(this[WorkflowRunTable.status]),
        inputJson = this[WorkflowRunTable.inputJson],
        actor = this[WorkflowRunTable.actor],
        triggeredBy = this[WorkflowRunTable.triggeredBy],
        stepIds = Json.decodeFromString(STEP_IDS_SERIALIZER, this[WorkflowRunTable.stepIdsJson]),
        subjects = subjects,
        currentStepIndex = this[WorkflowRunTable.currentStepIndex],
        lockedBy = lockedBy,
        leaseGen = this[WorkflowRunTable.leaseGen],
        leaseAgeSeconds =
            if (lockedBy != null && heartbeat != null && dbNow != null) {
              Duration.between(heartbeat, dbNow).seconds.coerceAtLeast(0)
            } else {
              null
            },
        nextAttemptAt = this[WorkflowRunTable.nextAttemptAt]?.toInstantUtc(),
        createdAt = this[WorkflowRunTable.createdAt].toInstantUtc(),
        startedAt = this[WorkflowRunTable.startedAt]?.toInstantUtc(),
        finishedAt = this[WorkflowRunTable.finishedAt]?.toInstantUtc(),
        errorSummary = this[WorkflowRunTable.errorSummary],
        cancelRequested = this[WorkflowRunTable.cancelRequested],
    )
  }

  private fun ResultRow.toStep() =
      StepRecord(
          runId = this[WorkflowStepRunTable.runId],
          index = this[WorkflowStepRunTable.stepIndex],
          stepId = this[WorkflowStepRunTable.stepId],
          status = WorkflowStepStatus.valueOf(this[WorkflowStepRunTable.status]),
          attempts = this[WorkflowStepRunTable.attempts],
          outputJson = this[WorkflowStepRunTable.outputJson],
          outputSummary = this[WorkflowStepRunTable.outputSummary],
          error = this[WorkflowStepRunTable.error],
          startedAt = this[WorkflowStepRunTable.startedAt]?.toInstantUtc(),
          finishedAt = this[WorkflowStepRunTable.finishedAt]?.toInstantUtc(),
      )

  private fun ResultRow.toEvent() =
      EventRecord(
          id = this[WorkflowEventTable.id],
          runId = this[WorkflowEventTable.runId],
          stepIndex = this[WorkflowEventTable.stepIndex],
          timestamp = this[WorkflowEventTable.ts].toInstantUtc(),
          level = this[WorkflowEventTable.level],
          message = this[WorkflowEventTable.message],
          actor = this[WorkflowEventTable.actor],
      )

  private fun Instant.toUtc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

  private fun LocalDateTime.toInstantUtc(): Instant = toInstant(ZoneOffset.UTC)

  private fun SQLException.isIntegrity(): Boolean =
      this is SQLIntegrityConstraintViolationException || sqlState?.startsWith("23") == true

  private fun ExposedSQLException.isConstraintViolation(): Boolean =
      generateSequence<Throwable>(this) { it.cause }.any { it is SQLException && it.isIntegrity() }

  private fun idempotencyKey(
      workflowId: String,
      subjects: List<WorkflowSubject>,
      token: String,
  ): String {
    val material =
        listOf(workflowId, token, subjects.map { "${it.type}\u0000${it.key}" }.sorted().joinToString("\u0001"))
            .joinToString("\u0002")
    return MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
  }

  private companion object {
    const val ACTIVE = 1
    const val NANOS_PER_MILLI = 1_000_000L
    const val CREATE_ATTEMPTS = 3
    const val CLAIM_CANDIDATES = 5
    const val CLAIM_ROUNDS = 10
    const val MAX_LIMIT = 1000
    const val SUMMARY_MAX = 500
    const val ERROR_MAX = 8000
    const val MESSAGE_MAX = 8000
    const val LEVEL_MAX = 16
    val STEP_IDS_SERIALIZER = ListSerializer(String.serializer())
    val DB_NOW_ALIAS = DbNow.alias("db_now")
    val CLAIMABLE_STATUSES =
        listOf(
            WorkflowRunStatus.PENDING,
            WorkflowRunStatus.RUNNING,
            WorkflowRunStatus.WAITING_RETRY,
            WorkflowRunStatus.ROLLING_BACK,
        )
    val IN_PROGRESS_STATUSES =
        listOf(
            WorkflowRunStatus.RUNNING,
            WorkflowRunStatus.WAITING_RETRY,
            WorkflowRunStatus.ROLLING_BACK,
            WorkflowRunStatus.PAUSED,
            WorkflowRunStatus.ROLLBACK_FAILED,
        )
    val FINISHED_STEP_STATUSES =
        setOf(
            WorkflowStepStatus.SUCCEEDED,
            WorkflowStepStatus.SKIPPED,
            WorkflowStepStatus.FAILED,
            WorkflowStepStatus.ROLLED_BACK,
            WorkflowStepStatus.ROLLBACK_FAILED,
        )
  }
}

/** The database's UTC clock; the only time source for leases, claims and stored timestamps. */
internal object DbNow : Function<LocalDateTime>(JavaLocalDateTimeColumnType()) {
  override fun toQueryBuilder(queryBuilder: QueryBuilder) {
    queryBuilder.append("UTC_TIMESTAMP()")
  }
}

internal class SecondsFromNow(private val seconds: Int) :
    Function<LocalDateTime>(JavaLocalDateTimeColumnType()) {
  override fun toQueryBuilder(queryBuilder: QueryBuilder) {
    queryBuilder.append("TIMESTAMPADD(SECOND, $seconds, UTC_TIMESTAMP())")
  }
}

internal class CoalesceTime(
    private val column: Expression<*>,
    private val fallback: Expression<LocalDateTime>,
) : Function<LocalDateTime>(JavaLocalDateTimeColumnType()) {
  override fun toQueryBuilder(queryBuilder: QueryBuilder) {
    queryBuilder.append("COALESCE(").append(column).append(", ").append(fallback).append(")")
  }
}

/** Earliest time any claimable-status run satisfies the claimNext predicate (null if there is none). */
internal object NextWorkAt : Function<LocalDateTime?>(JavaLocalDateTimeColumnType()) {
  override fun toQueryBuilder(queryBuilder: QueryBuilder) {
    val never = "'1970-01-02 00:00:00'"
    queryBuilder.append("MIN(GREATEST(COALESCE(")
    queryBuilder.append(WorkflowRunTable.nextAttemptAt)
    queryBuilder.append(", $never), COALESCE(")
    queryBuilder.append(WorkflowRunTable.leaseUntil)
    queryBuilder.append(", $never)))")
  }
}
