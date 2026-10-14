package org.dallasmakerspace.workflows

import io.ktor.util.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.models.workflows.*

internal fun iso(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()

internal class RunMapper(
    private val registry: WorkflowRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
  fun toDto(run: RunRecord, steps: List<StepRecord>?, includeLease: Boolean = false): WorkflowRunDto {
    val wf = registry.get(run.workflowId)
    val artifacts = steps?.let { artifacts(run, it) }.orEmpty()
    return WorkflowRunDto(
        id = run.id,
        workflowId = run.workflowId,
        workflowVersion = run.workflowVersion,
        displayName = wf?.displayName ?: run.workflowId,
        status = run.status,
        input = parseObject(run.inputJson),
        subjects = run.subjects,
        actor = run.actor,
        triggeredBy = run.triggeredBy,
        currentStepIndex = run.currentStepIndex,
        stepCount = run.stepIds.size,
        createdAt = iso(run.createdAt),
        startedAt = run.startedAt?.let(::iso),
        finishedAt = run.finishedAt?.let(::iso),
        nextAttemptAt = run.nextAttemptAt?.let(::iso),
        errorSummary = run.errorSummary,
        lockedBy = if (includeLease) run.lockedBy else null,
        leaseAgeSeconds = if (includeLease) run.leaseAgeSeconds else null,
        steps =
            steps?.let { recs ->
              run.stepIds.mapIndexed { i, stepId ->
                val def = wf?.stepInfo(stepId)
                val rec = recs.firstOrNull { it.index == i }
                WorkflowStepRunDto(
                    index = i,
                    stepId = stepId,
                    displayName = def?.displayName ?: stepId,
                    status = rec?.status ?: WorkflowStepStatus.PENDING,
                    attempts = rec?.attempts ?: 0,
                    reversibility = def?.reversibility ?: WorkflowReversibility.BEST_EFFORT,
                    startedAt = rec?.startedAt?.let(::iso),
                    finishedAt = rec?.finishedAt?.let(::iso),
                    error = rec?.error,
                    outputSummary = rec?.outputSummary,
                    artifacts = artifacts[i].orEmpty().map(::toDto),
                )
              }
            } ?: emptyList(),
    )
  }

  private fun toDto(a: ArtifactRef) =
      WorkflowArtifactDto(a.name, a.label, a.contentType, a.infraOnly)

  /**
   * Artifacts of SUCCEEDED/SKIPPED steps by step index, re-derived from each step's persisted
   * output. A step whose output no longer decodes just yields none.
   */
  @Suppress("TooGenericExceptionCaught")
  fun artifacts(run: RunRecord, recs: List<StepRecord>): Map<Int, List<ArtifactRef>> {
    val eligible = recs.filter { it.status in ARTIFACT_STATUSES && it.outputJson != null }
    if (eligible.isEmpty()) return emptyMap()
    val bound =
        try {
          registry.get(run.workflowId)?.bind(run.inputJson)?.associateBy { it.id }
        } catch (e: Exception) {
          null
        } ?: return emptyMap()
    val outputs = recs.mapNotNull { r -> r.outputJson?.let { r.stepId to it } }.toMap()
    val ctx =
        StepContext(
            run.id,
            run.actor,
            run.triggeredBy,
            run.startedAt ?: run.createdAt,
            clock,
            1,
            StepMode.EXECUTE,
            outputs)
    return eligible
        .mapNotNull { rec ->
          val step = run.stepIds.getOrNull(rec.index)?.takeIf { it == rec.stepId }?.let(bound::get)
          val refs =
              try {
                step?.artifacts(ctx, rec.outputJson!!).orEmpty()
              } catch (e: Exception) {
                emptyList()
              }
          refs
              .filter { it.isUsable() }
              .distinctBy { it.name }
              .takeIf { it.isNotEmpty() }
              ?.let { rec.index to it }
        }
        .toMap()
  }

  private fun ArtifactRef.isUsable() =
      NAME_PATTERN.matches(name) &&
          label.isNotBlank() &&
          contentType.isNotBlank() &&
          namespace.isNotBlank() &&
          key.isNotBlank()

  fun toDto(e: EventRecord) =
      WorkflowEventDto(
          id = e.id,
          runId = e.runId,
          stepIndex = e.stepIndex,
          timestamp = iso(e.timestamp),
          level = e.level,
          message = e.message,
          actor = e.actor,
      )

  private companion object {
    val ARTIFACT_STATUSES = setOf(WorkflowStepStatus.SUCCEEDED, WorkflowStepStatus.SKIPPED)
    val NAME_PATTERN = ArtifactRef.NAME_PATTERN
  }

  private fun parseObject(json: String): JsonObject =
      try {
        EngineJson.parseToJsonElement(json) as? JsonObject ?: JsonObject(emptyMap())
      } catch (e: Exception) {
        JsonObject(emptyMap())
      }
}

/** Publishes run/event updates to the bus; callers invoke it only after the store call returned. */
internal class RunPublisher(
    private val store: WorkflowStore,
    private val bus: WorkflowEventBus,
    private val mapper: RunMapper,
    private val log: Logger,
) {
  /** Highest event id currently stored for the run (the cursor to pass to [publish]). */
  suspend fun cursor(runId: Long): Long =
      try {
        store.listEvents(runId, 0, 100_000).lastOrNull()?.id ?: 0
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        0
      }

  /** Publishes events after [afterEventId] and the run's current state; returns the new cursor. */
  suspend fun publish(runId: Long, afterEventId: Long): Long {
    try {
      val events = store.listEvents(runId, afterEventId, 100_000)
      events.forEach { bus.publishEvent(mapper.toDto(it)) }
      val run = store.getRun(runId)
      if (run != null) bus.publishRun(mapper.toDto(run, store.getSteps(runId)))
      return events.lastOrNull()?.id ?: afterEventId
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.warn("Failed to publish workflow run $runId update: ${e.message}")
      return afterEventId
    }
  }
}
