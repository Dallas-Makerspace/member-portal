package org.dallasmakerspace.workflows.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.dallasmakerspace.models.workflows.WorkflowStreamMessage
import org.dallasmakerspace.workflows.StreamFilter
import org.dallasmakerspace.workflows.WorkflowEventBus
import org.dallasmakerspace.workflows.WorkflowService

internal const val DEFAULT_HEARTBEAT_MILLIS = 15_000L
private const val INBOX_CAPACITY = 256
private const val LAST_EVENT_ID_HEADER = "Last-Event-ID"
private const val SNAPSHOT_RUN_LIMIT = 50

/**
 * Admin page stream. First message is a "snapshot" (carries `admin` and `runs`), then an "admin"
 * message after any run change (coalesced). No timer-driven reads: heartbeats only write a comment,
 * and clients advance `leaseAgeSeconds` locally.
 */
internal suspend fun ApplicationCall.adminStream(
    service: WorkflowService,
    bus: WorkflowEventBus,
    streamsClosed: Job,
    heartbeatMillis: Long,
) {
  respondSse(
      bus,
      StreamFilter(adminAll = true),
      streamsClosed,
      heartbeatMillis,
      initial = {
        val admin = service.adminInProgress()
        listOf(WorkflowStreamMessage(type = "snapshot", admin = admin, runs = admin.runs))
      },
      live = { message, inbox ->
        if (message.type != "run") {
          emptyList()
        } else {
          while (inbox.tryReceive().isSuccess) Unit
          listOf(WorkflowStreamMessage(type = "admin", admin = service.adminInProgress()))
        }
      },
  )
}

/**
 * Subject/workflow stream. First message is a "snapshot" carrying `runs` (newest first, at most
 * `limit`, default 50), then a "run" message for each change to a matching run. No "event"
 * messages.
 */
internal suspend fun ApplicationCall.runListStream(
    service: WorkflowService,
    bus: WorkflowEventBus,
    streamsClosed: Job,
    heartbeatMillis: Long,
) {
  val base = parseRunFilter(allowStatusAndActor = false) ?: return
  val filter =
      if (request.queryParameters["limit"] == null) base.copy(limit = SNAPSHOT_RUN_LIMIT) else base
  respondSse(
      bus,
      StreamFilter(subject = filter.subject, workflowId = filter.workflowId),
      streamsClosed,
      heartbeatMillis,
      initial = {
        listOf(WorkflowStreamMessage(type = "snapshot", runs = service.listRuns(filter)))
      },
      live = { message, _ -> if (message.type == "run") listOf(message) else emptyList() },
  )
}

/**
 * Single-run stream. First message is a "snapshot" carrying `run` (with steps), followed by an
 * "event" message for every log event after Last-Event-ID (header, or `after` query; all events
 * when absent), then live "run" (re-read with steps) and "event" messages. SSE `id:` is the event
 * id.
 */
internal suspend fun ApplicationCall.runStream(
    runId: Long,
    service: WorkflowService,
    bus: WorkflowEventBus,
    streamsClosed: Job,
    heartbeatMillis: Long,
) {
  if (service.getRun(runId, withSteps = false) == null) {
    return respondError(HttpStatusCode.NotFound, "Run $runId not found")
  }
  val resumeFrom =
      request.headers[LAST_EVENT_ID_HEADER]?.trim()?.toLongOrNull()
          ?: request.queryParameters["after"]?.toLongOrNull()
          ?: 0L
  var lastEventId = resumeFrom
  respondSse(
      bus,
      StreamFilter(runId = runId),
      streamsClosed,
      heartbeatMillis,
      initial = {
        val run = service.getRun(runId, withSteps = true)
        val snapshot = WorkflowStreamMessage(type = "snapshot", run = run)
        val replay =
            service.listEvents(runId, resumeFrom).map {
              lastEventId = maxOf(lastEventId, it.id)
              WorkflowStreamMessage(type = "event", event = it)
            }
        listOf(snapshot) + replay
      },
      live = { message, _ ->
        when (message.type) {
          "run" -> {
            val fresh = service.getRun(runId, withSteps = true) ?: message.run
            listOf(WorkflowStreamMessage(type = "run", run = fresh))
          }
          "event" -> {
            val event = message.event
            if (event != null && event.id > lastEventId) {
              lastEventId = event.id
              listOf(message)
            } else {
              emptyList()
            }
          }
          else -> emptyList()
        }
      },
  )
}

/**
 * Subscribes to [bus] BEFORE calling [initial] so nothing published in between is lost, then
 * writes initial messages, live messages from [live] (the inbox is passed so a handler can coalesce
 * a burst), and a ":" comment every [heartbeatMillis] of silence (no store reads on that tick).
 * Ends when the client disconnects or [streamsClosed] completes.
 */
@Suppress("LongParameterList")
private suspend fun ApplicationCall.respondSse(
    bus: WorkflowEventBus,
    filter: StreamFilter,
    streamsClosed: Job,
    heartbeatMillis: Long,
    initial: suspend () -> List<WorkflowStreamMessage>,
    live:
        suspend (WorkflowStreamMessage, Channel<WorkflowStreamMessage>) -> List<WorkflowStreamMessage>,
) {
  response.header(HttpHeaders.CacheControl, "no-cache")
  response.header("X-Accel-Buffering", "no")
  respondBytesWriter(contentType = ContentType.Text.EventStream) {
    coroutineScope {
      val inbox = Channel<WorkflowStreamMessage>(INBOX_CAPACITY, BufferOverflow.DROP_OLDEST)
      val subscription =
          launch(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe(filter).collect { inbox.trySend(it) }
          }
      val closeHandle = streamsClosed.invokeOnCompletion { inbox.close() }
      try {
        writeMessages(initial())
        while (true) {
          val received = withTimeoutOrNull(heartbeatMillis) { inbox.receiveCatching() }
          when {
            received == null -> {
              writeStringUtf8(": heartbeat\n\n")
              flush()
            }
            received.isClosed -> break
            else -> writeMessages(live(received.getOrThrow(), inbox))
          }
        }
      } finally {
        subscription.cancel()
        closeHandle.dispose()
      }
    }
  }
}

private suspend fun ByteWriteChannel.writeMessages(messages: List<WorkflowStreamMessage>) {
  if (messages.isEmpty()) return
  val frame = StringBuilder()
  messages.forEach { message ->
    frame.append("event: ").append(message.type).append('\n')
    message.event?.let { frame.append("id: ").append(it.id).append('\n') }
    frame.append("data: ")
        .append(workflowJson.encodeToString(WorkflowStreamMessage.serializer(), message))
        .append("\n\n")
  }
  writeStringUtf8(frame.toString())
  flush()
}
