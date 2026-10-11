package org.dallasmakerspace.workflows

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.models.workflows.*

fun testRun(
    id: Long,
    status: WorkflowRunStatus = WorkflowRunStatus.RUNNING,
    workflowId: String = "test.flow",
    subjects: List<WorkflowSubject> = listOf(WorkflowSubject("group", "Test Group")),
    steps: List<WorkflowStepRunDto> = emptyList(),
) =
    WorkflowRunDto(
        id = id,
        workflowId = workflowId,
        workflowVersion = 1,
        displayName = "Test flow",
        status = status,
        input = buildJsonObject { put("groupName", "Test Group") },
        subjects = subjects,
        actor = "jdoe",
        triggeredBy = "UI",
        currentStepIndex = 0,
        stepCount = 2,
        createdAt = "2026-01-02T03:04:05Z",
        steps = steps,
    )

fun testStep(index: Int, status: WorkflowStepStatus) =
    WorkflowStepRunDto(
        index = index,
        stepId = "test.step-$index",
        displayName = "Step $index",
        status = status,
        attempts = 1,
        reversibility = WorkflowReversibility.REVERSIBLE,
    )

fun testEvent(id: Long, runId: Long, message: String = "event $id") =
    WorkflowEventDto(
        id = id,
        runId = runId,
        timestamp = "2026-01-02T03:04:05Z",
        level = "INFO",
        message = message,
    )

fun emptyPlan(workflowId: String = "test.flow", canStart: Boolean = true) =
    WorkflowPreflightResponse(workflowId, canStart, steps = emptyList())

/** Scriptable [WorkflowService]; every field is a plain knob the tests set. */
class FakeWorkflowService : WorkflowService {
  val runs = linkedMapOf<Long, WorkflowRunDto>()
  val events = mutableListOf<WorkflowEventDto>()
  val locks = mutableMapOf<WorkflowSubject, WorkflowRunDto>()
  val operateCalls = mutableListOf<Triple<Long, WorkflowOperatorAction, String>>()
  val startCalls = mutableListOf<List<Any>>()
  var lastListFilter: RunFilter? = null
  var admin = WorkflowAdminInProgressDto(true, emptyList(), "2026-01-02T03:04:05Z")
  var adminCalls = 0
  var definitions = emptyList<WorkflowDefinitionDto>()
  var preflightResult: () -> WorkflowPreflightResponse = { emptyPlan() }
  var startResult: () -> StartResult = { StartResult.Started(testRun(1)) }
  var operateResult: (Long) -> OperateResult = { id ->
    runs[id]?.let { OperateResult.Ok(it) } ?: OperateResult.NotFound
  }
  var lockLookupFails = false

  /** Runs inside getRun so tests can publish while a stream builds its snapshot. */
  var duringSnapshot: () -> Unit = {}

  override fun definitions() = definitions

  override suspend fun preflight(workflowId: String, input: JsonObject, actor: String) =
      preflightResult()

  override suspend fun start(
      workflowId: String,
      input: JsonObject,
      actor: String,
      triggeredBy: String,
      idempotencyToken: String,
  ): StartResult {
    startCalls += listOf(workflowId, input, actor, triggeredBy, idempotencyToken)
    return startResult()
  }

  override suspend fun getRun(runId: Long, withSteps: Boolean): WorkflowRunDto? {
    duringSnapshot()
    return runs[runId]?.let { if (withSteps) it else it.copy(steps = emptyList()) }
  }

  override suspend fun listRuns(filter: RunFilter): List<WorkflowRunDto> {
    lastListFilter = filter
    return runs.values.toList()
  }

  override suspend fun listEvents(runId: Long, afterEventId: Long): List<WorkflowEventDto> =
      events.filter { it.runId == runId && it.id > afterEventId }

  override suspend fun operate(
      runId: Long,
      action: WorkflowOperatorAction,
      actor: String,
  ): OperateResult {
    operateCalls += Triple(runId, action, actor)
    return operateResult(runId)
  }

  override suspend fun adminInProgress(): WorkflowAdminInProgressDto {
    adminCalls++
    return admin
  }

  override suspend fun activeRunFor(subject: WorkflowSubject): WorkflowRunDto? {
    if (lockLookupFails) error("store down")
    return locks[normalizeSubject(subject)]
  }
}
