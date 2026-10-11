package org.dallasmakerspace.workflows

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import org.dallasmakerspace.models.workflows.WorkflowEventDto
import org.dallasmakerspace.models.workflows.WorkflowRunDto
import org.dallasmakerspace.models.workflows.WorkflowStreamMessage
import org.dallasmakerspace.models.workflows.WorkflowSubject

/**
 * Publishers never block: the shared buffer drops the oldest message for a lagging subscriber. A
 * collector registers synchronously on collect (no operator in between), which the SSE routes rely
 * on to subscribe before reading their snapshot.
 */
@Singleton
class InProcessWorkflowEventBus @Inject constructor() : WorkflowEventBus {
  private val messages =
      MutableSharedFlow<WorkflowStreamMessage>(
          replay = 0,
          extraBufferCapacity = BUS_BUFFER,
          onBufferOverflow = BufferOverflow.DROP_OLDEST,
      )

  override fun publishRun(run: WorkflowRunDto) {
    messages.tryEmit(WorkflowStreamMessage(type = "run", run = run))
  }

  override fun publishEvent(event: WorkflowEventDto) {
    messages.tryEmit(WorkflowStreamMessage(type = "event", event = event))
  }

  /**
   * - runId: that run's "run" and "event" messages.
   * - subject/workflowId (both must match when both are set): "run" messages only.
   * - adminAll or an empty filter: everything.
   */
  override fun subscribe(filter: StreamFilter): Flow<WorkflowStreamMessage> {
    val subject = filter.subject?.let(::normalizeSubject)
    return messages
        .filter { matches(it, filter, subject) }
  }

  private fun matches(
      message: WorkflowStreamMessage,
      filter: StreamFilter,
      subject: WorkflowSubject?,
  ): Boolean {
    val criteria = filter.runId != null || subject != null || filter.workflowId != null
    if (filter.adminAll || !criteria) return true
    val run = message.run
    val event = message.event
    return when {
      filter.runId != null -> (run?.id ?: event?.runId) == filter.runId
      run == null -> false
      else ->
          (subject == null || run.subjects.any { normalizeSubject(it) == subject }) &&
              (filter.workflowId == null || run.workflowId == filter.workflowId)
    }
  }

  private companion object {
    const val BUS_BUFFER = 1024
  }
}
