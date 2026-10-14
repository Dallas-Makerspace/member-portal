package org.dallasmakerspace.workflows

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.workflows.*

@Singleton
class DefaultWorkflowService
@Inject
constructor(
    private val store: WorkflowStore,
    private val registry: WorkflowRegistry,
    private val bus: WorkflowEventBus,
    private val settings: EngineSettings,
    private val wake: WorkflowWakeSignal,
    loggerFactory: LoggerFactory,
) : WorkflowService {
  private val log = loggerFactory.create(DefaultWorkflowService::class.java)
  private val mapper = RunMapper(registry, settings.clock)
  private val publisher = RunPublisher(store, bus, mapper, log)

  override fun definitions() = registry.definitions()

  override suspend fun preflight(
      workflowId: String,
      input: JsonObject,
      actor: String,
  ): WorkflowPreflightResponse {
    val wf = workflow(workflowId)
    val prepared = wf.prepare(input)
    val active = activeRun(prepared.subjects)
    return buildPlan(wf, prepared, actor, active?.let { mapper.toDto(it, null) })
  }

  override suspend fun start(
      workflowId: String,
      input: JsonObject,
      actor: String,
      triggeredBy: String,
      idempotencyToken: String,
  ): StartResult {
    val wf = workflow(workflowId)
    val prepared = wf.prepare(input)
    // A locked subject skips preflight: createRun answers Existing (same token) or SubjectLocked.
    if (activeRun(prepared.subjects) == null) {
      val plan = buildPlan(wf, prepared, actor, null)
      if (!plan.canStart) return StartResult.Blocked(plan)
    }
    val result =
        store.createRun(
            NewRun(
                workflowId = wf.id,
                workflowVersion = wf.version,
                inputJson = prepared.inputJson,
                actor = actor,
                triggeredBy = triggeredBy,
                stepIds = prepared.steps.map { it.id },
                subjects = prepared.subjects,
                idempotencyToken = idempotencyToken,
            ))
    return when (result) {
      is CreateRunResult.Created -> {
        publisher.publish(result.run.id, 0)
        wake.wake()
        StartResult.Started(detail(result.run))
      }
      is CreateRunResult.Existing -> StartResult.Existing(detail(result.run))
      is CreateRunResult.SubjectLocked -> StartResult.SubjectLocked(mapper.toDto(result.activeRun, null))
    }
  }

  override suspend fun getRun(runId: Long, withSteps: Boolean): WorkflowRunDto? {
    val run = store.getRun(runId) ?: return null
    return mapper.toDto(run, if (withSteps) store.getSteps(runId) else null)
  }

  override suspend fun listRuns(filter: RunFilter) =
      store.listRuns(filter).map { mapper.toDto(it, null) }

  override suspend fun listEvents(runId: Long, afterEventId: Long) =
      store.listEvents(runId, afterEventId).map(mapper::toDto)

  override suspend fun activeRunFor(subject: WorkflowSubject) =
      store.activeRunForSubject(normalizeSubject(subject))?.let { mapper.toDto(it, null) }

  override suspend fun resolveArtifact(
      runId: Long,
      stepIndex: Int,
      artifactName: String,
  ): ResolvedArtifact? {
    val run = store.getRun(runId) ?: return null
    val ref =
        mapper.artifacts(run, store.getSteps(runId))[stepIndex]?.firstOrNull { it.name == artifactName }
            ?: return null
    return ResolvedArtifact(
        name = ref.name,
        label = ref.label,
        contentType = ref.contentType,
        namespace = ref.namespace,
        key = ref.key,
        infraOnly = ref.infraOnly,
        workflowId = run.workflowId,
        requiredPermission = registry.get(run.workflowId)?.permission,
    )
  }

  override suspend fun recordArtifactDownload(
      runId: Long,
      stepIndex: Int,
      artifactName: String,
      actor: String,
  ): WorkflowEventDto {
    val event =
        store.appendEvent(
            runId, stepIndex, "INFO", "Artifact $artifactName download requested by $actor", actor)
    return mapper.toDto(event).also(bus::publishEvent)
  }

  override suspend fun adminInProgress(): WorkflowAdminInProgressDto {
    val runs = store.listInProgress()
    return WorkflowAdminInProgressDto(
        safeToDeploy =
            runs.none {
              it.status == WorkflowRunStatus.RUNNING || it.status == WorkflowRunStatus.ROLLING_BACK
            },
        runs = runs.map { mapper.toDto(it, null, includeLease = true) },
        generatedAt = iso(settings.clock.instant()),
    )
  }

  override suspend fun operate(
      runId: Long,
      action: WorkflowOperatorAction,
      actor: String,
  ): OperateResult {
    val run = store.getRun(runId) ?: return OperateResult.NotFound
    val s = run.status
    val idx = run.currentStepIndex
    fun ev(msg: String) = listOf(NewEvent(null, "INFO", msg, actor))

    val plan: Transition =
        when (action) {
          WorkflowOperatorAction.RETRY ->
              when (s) {
                WorkflowRunStatus.PAUSED ->
                    Transition(
                        setOf(s),
                        RunUpdate(status = WorkflowRunStatus.WAITING_RETRY, clearNextAttempt = true, clearError = true),
                        StepReset(idx, WorkflowStepStatus.PENDING),
                        ev("Retry requested by $actor"))
                WorkflowRunStatus.WAITING_RETRY ->
                    Transition(setOf(s), RunUpdate(clearNextAttempt = true), null, ev("Immediate retry requested by $actor"))
                WorkflowRunStatus.ROLLBACK_FAILED ->
                    Transition(
                        setOf(s),
                        RunUpdate(status = WorkflowRunStatus.ROLLING_BACK),
                        null,
                        ev("Rollback retry requested by $actor"))
                else -> return notAllowed(action, s)
              }
          WorkflowOperatorAction.SKIP -> {
            if (s != WorkflowRunStatus.PAUSED) return notAllowed(action, s)
            val stepId = run.stepIds.getOrNull(idx) ?: return notAllowed(action, s)
            val skippable = registry.get(run.workflowId)?.stepInfo(stepId)?.skippable == true
            if (!skippable) return OperateResult.NotAllowed("Step '$stepId' is not skippable")
            Transition(
                setOf(s),
                RunUpdate(
                    status = WorkflowRunStatus.WAITING_RETRY,
                    currentStepIndex = idx + 1,
                    clearNextAttempt = true,
                    clearError = true),
                StepReset(idx, WorkflowStepStatus.SKIPPED),
                listOf(NewEvent(idx, "WARN", "Step '$stepId' skipped by $actor", actor)))
          }
          WorkflowOperatorAction.ROLLBACK -> {
            if (s != WorkflowRunStatus.PAUSED) return notAllowed(action, s)
            Transition(
                setOf(s),
                RunUpdate(status = WorkflowRunStatus.ROLLING_BACK),
                null,
                ev("Rollback requested by $actor"))
          }
          WorkflowOperatorAction.ABANDON -> {
            if (s !in ABANDONABLE) return notAllowed(action, s)
            Transition(
                setOf(s),
                RunUpdate(status = WorkflowRunStatus.ABANDONED, markFinished = true, clearNextAttempt = true),
                null,
                listOf(NewEvent(null, "WARN", "Run abandoned by $actor; state left as is", actor)))
          }
          WorkflowOperatorAction.CANCEL ->
              when (s) {
                WorkflowRunStatus.PENDING,
                WorkflowRunStatus.WAITING_RETRY ->
                    Transition(
                        setOf(s),
                        RunUpdate(status = WorkflowRunStatus.CANCELLED, markFinished = true, clearNextAttempt = true),
                        null,
                        listOf(NewEvent(null, "WARN", "Run cancelled by $actor", actor)))
                WorkflowRunStatus.RUNNING ->
                    Transition(
                        setOf(s),
                        RunUpdate(cancelRequested = true),
                        null,
                        listOf(NewEvent(null, "WARN", "Cancellation requested by $actor", actor)))
                else -> return notAllowed(action, s)
              }
        }

    val cursor = publisher.cursor(runId)
    val applied = store.operatorTransition(runId, plan.from, plan.update, plan.stepReset, plan.events)
    if (!applied) {
      return OperateResult.NotAllowed("Run is being executed or changed state; refresh and try again")
    }
    publisher.publish(runId, cursor)
    if (action == WorkflowOperatorAction.CANCEL && s == WorkflowRunStatus.RUNNING) wake.requestCancel(runId)
    wake.wake()
    return OperateResult.Ok(getRun(runId)!!)
  }

  private class Transition(
      val from: Set<WorkflowRunStatus>,
      val update: RunUpdate,
      val stepReset: StepReset?,
      val events: List<NewEvent>,
  )

  private fun notAllowed(action: WorkflowOperatorAction, status: WorkflowRunStatus) =
      OperateResult.NotAllowed("$action is not allowed while the run is $status")

  private fun workflow(id: String) = registry.get(id) ?: throw UnknownWorkflowException(id)

  private suspend fun activeRun(subjects: List<Subject>) =
      subjects.firstNotNullOfOrNull { store.activeRunForSubject(normalizeSubject(it)) }

  private suspend fun detail(run: RunRecord) = mapper.toDto(run, store.getSteps(run.id))

  private suspend fun buildPlan(
      wf: Workflow<*>,
      prepared: PreparedInput,
      actor: String,
      activeRun: WorkflowRunDto?,
  ): WorkflowPreflightResponse {
    val outputs = mutableMapOf<String, String>()
    val now = settings.clock.instant()
    val planSteps = mutableListOf<PlanStepDto>()
    for (step in prepared.steps) {
      val ctx =
          StepContext(0, actor, "preflight", now, settings.clock, 1, StepMode.PREFLIGHT, outputs.toMap())
      val evaluated =
          try {
            withTimeoutOrNull(settings.preflightStepTimeoutMillis) { evaluate(step, ctx, outputs) }
                ?: PlanStepDto(step.id, step.displayName, PlanStepStatus.UNKNOWN, listOf("Timed out"))
          } catch (e: CancellationException) {
            throw e
          } catch (e: MissingOutputException) {
            PlanStepDto(
                step.id,
                step.displayName,
                PlanStepStatus.UNKNOWN,
                listOf("Depends on the output of step '${e.stepId}', known only once the run executes"))
          } catch (e: Exception) {
            log.warn("Preflight of ${wf.id}/${step.id} failed: ${e.message}")
            PlanStepDto(step.id, step.displayName, PlanStepStatus.UNKNOWN, listOf("Check failed: ${e.message}"))
          }
      planSteps.add(evaluated)
    }
    return WorkflowPreflightResponse(
        workflowId = wf.id,
        canStart = activeRun == null && planSteps.none { it.status == PlanStepStatus.BLOCKED },
        steps = planSteps,
        notes = wf.notes,
        activeRun = activeRun,
    )
  }

  private suspend fun evaluate(step: ErasedStep, ctx: StepContext, outputs: MutableMap<String, String>): PlanStepDto {
    fun dto(status: PlanStepStatus, vararg messages: String?) =
        PlanStepDto(step.id, step.displayName, status, messages.filterNotNull())

    val check = step.check(ctx)
    if (check is ErasedCheck.NoOp) {
      check.outputJson?.let { outputs[step.id] = it }
      return dto(PlanStepStatus.NO_OP, check.reason)
    }
    val (base, note) =
        when (check) {
          is ErasedCheck.WillRun -> PlanStepStatus.WILL_RUN to check.summary
          is ErasedCheck.Unknown -> PlanStepStatus.UNKNOWN to check.reason
          is ErasedCheck.NoOp -> error("unreachable")
        }
    return when (val v = step.validate(ctx)) {
      is Validation.Blocked ->
          PlanStepDto(step.id, step.displayName, PlanStepStatus.BLOCKED, v.messages)
      is Validation.Warnings -> {
        val status = if (base == PlanStepStatus.UNKNOWN) base else PlanStepStatus.WARNING
        PlanStepDto(step.id, step.displayName, status, listOfNotNull(note) + v.messages)
      }
      Validation.Ok -> dto(base, note)
    }
  }

  private companion object {
    val ABANDONABLE =
        setOf(
            WorkflowRunStatus.PAUSED,
            WorkflowRunStatus.ROLLBACK_FAILED,
            WorkflowRunStatus.WAITING_RETRY,
            WorkflowRunStatus.PENDING,
        )
  }
}
