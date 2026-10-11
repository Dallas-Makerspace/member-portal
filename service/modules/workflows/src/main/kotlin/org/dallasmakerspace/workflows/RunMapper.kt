package org.dallasmakerspace.workflows

import io.ktor.util.logging.Logger
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.models.workflows.*

internal fun iso(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()

internal class RunMapper(private val registry: WorkflowRegistry) {
  fun toDto(run: RunRecord, steps: List<StepRecord>?, includeLease: Boolean = false): WorkflowRunDto {
    val wf = registry.get(run.workflowId)
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
                )
              }
            } ?: emptyList(),
    )
  }

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
