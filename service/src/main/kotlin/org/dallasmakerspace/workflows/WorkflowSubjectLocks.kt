package org.dallasmakerspace.workflows

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import org.dallasmakerspace.models.workflows.WorkflowSubject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WorkflowSubjectLocks")

/**
 * Message for a 409 when a workflow run holds the lock on [groupName], else null. Fails open
 * (null) if the lookup itself fails, so group management keeps working if the workflow tables are
 * missing or the store is down.
 */
suspend fun WorkflowService.groupLockMessage(groupName: String): String? =
    try {
      activeRunFor(WorkflowSubject("group", groupName))?.let {
        "Group '$groupName' is locked by workflow run ${it.id} " +
            "(${it.displayName}, ${it.status}); try again when it finishes"
      }
    } catch (e: CancellationException) {
      throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      log.warn("Workflow lock check failed for group; allowing the change: ${e.message}")
      null
    }

/** Closed on shutdown so open SSE streams end and clients reconnect to the live instance. */
@Singleton
class WorkflowStreamLifecycle @Inject constructor() {
  val streamsClosed: Job = Job()

  fun closeStreams() {
    streamsClosed.cancel()
  }
}
