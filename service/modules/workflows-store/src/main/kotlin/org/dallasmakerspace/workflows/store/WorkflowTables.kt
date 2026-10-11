package org.dallasmakerspace.workflows.store

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

// Mirrors the workflow_* tables in service/docs/sql/create.sql. All datetimes are UTC.

@Suppress("MagicNumber")
object WorkflowRunTable : Table("workflow_run") {
  val id = long("id").autoIncrement()
  val workflowId = varchar("workflow_id", 100)
  val workflowVersion = integer("workflow_version")
  val status = varchar("status", 32)
  val inputJson = text("input_json")
  val actor = varchar("actor", 100)
  val triggeredBy = varchar("triggered_by", 32)
  val currentStepIndex = integer("current_step_index").default(0)
  val stepIdsJson = text("step_ids_json")
  val lockedBy = varchar("locked_by", 100).nullable()
  val leaseGen = long("lease_gen").default(0)
  val leaseUntil = datetime("lease_until").nullable()
  val heartbeatAt = datetime("heartbeat_at").nullable()
  val nextAttemptAt = datetime("next_attempt_at").nullable()
  val idempotencyToken = varchar("idempotency_token", 128)
  val idempotencyKey = varchar("idempotency_key", 64)
  val cancelRequested = bool("cancel_requested").default(false)
  val createdAt = datetime("created_at")
  val startedAt = datetime("started_at").nullable()
  val finishedAt = datetime("finished_at").nullable()
  val updatedAt = datetime("updated_at")
  val errorSummary = text("error_summary").nullable()

  override val primaryKey = PrimaryKey(id)

  init {
    uniqueIndex("workflow_run_idempotency_key_UNQ", idempotencyKey)
    index("workflow_run_status_next_attempt_IDX", false, status, nextAttemptAt)
    index("workflow_run_status_lease_IDX", false, status, leaseUntil)
    index("workflow_run_workflow_status_IDX", false, workflowId, status)
    index("workflow_run_actor_IDX", false, actor)
  }
}

@Suppress("MagicNumber")
object WorkflowStepRunTable : Table("workflow_step_run") {
  val runId =
      long("run_id").references(WorkflowRunTable.id)
  val stepIndex = integer("step_index")
  val stepId = varchar("step_id", 100)
  val status = varchar("status", 32)
  val attempts = integer("attempts").default(0)
  val outputJson = text("output_json").nullable()
  val outputSummary = varchar("output_summary", 500).nullable()
  val error = text("error").nullable()
  val startedAt = datetime("started_at").nullable()
  val finishedAt = datetime("finished_at").nullable()

  override val primaryKey = PrimaryKey(runId, stepIndex)
}

/** `activeKey` is 1 while the run holds the lock and NULL after, so the unique index only binds active runs. */
@Suppress("MagicNumber")
object WorkflowRunSubjectTable : Table("workflow_run_subject") {
  val id = long("id").autoIncrement()
  val runId =
      long("run_id").references(WorkflowRunTable.id)
  val subjectType = varchar("subject_type", 64)
  val subjectKey = varchar("subject_key", 255)
  val activeKey = integer("active_key").nullable()

  override val primaryKey = PrimaryKey(id)

  init {
    uniqueIndex("workflow_run_subject_active_UNQ", subjectType, subjectKey, activeKey)
    index("workflow_run_subject_run_id_IDX", false, runId)
  }
}

@Suppress("MagicNumber")
object WorkflowEventTable : Table("workflow_event") {
  val id = long("id").autoIncrement()
  val runId =
      long("run_id").references(WorkflowRunTable.id)
  val stepIndex = integer("step_index").nullable()
  val ts = datetime("ts")
  val level = varchar("level", 16)
  val message = text("message")
  val actor = varchar("actor", 100).nullable()

  override val primaryKey = PrimaryKey(id)

  init {
    index("workflow_event_run_id_IDX", false, runId, id)
  }
}
