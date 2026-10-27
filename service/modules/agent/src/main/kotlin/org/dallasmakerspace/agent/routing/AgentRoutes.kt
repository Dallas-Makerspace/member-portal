package org.dallasmakerspace.agent.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.dallasmakerspace.agent.ActiveTurn
import org.dallasmakerspace.agent.AgentEvalService
import org.dallasmakerspace.agent.ChatService
import org.dallasmakerspace.agent.EvalOutcome
import org.dallasmakerspace.agent.TurnOutcome
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status
import org.dallasmakerspace.models.agent.AgentCreateConversationRequest
import org.dallasmakerspace.models.agent.AgentEvalRequest
import org.dallasmakerspace.models.agent.AgentFeedbackRequest
import org.dallasmakerspace.models.agent.AgentSendMessageRequest
import org.dallasmakerspace.models.agent.AgentStreamEvent
import org.dallasmakerspace.models.agent.AgentTurnStarted

private const val HEARTBEAT_MILLIS = 15_000L
private const val MAX_MESSAGE_CHARS = 4_000
private const val DEFAULT_LIST_LIMIT = 20
private const val MAX_LIST_LIMIT = 100
private val streamJson = Json { explicitNulls = false }

/**
 * AI assistant chat API. The UI is the only caller; it passes the member as X-Username and every
 * route re-checks that member's access.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun Route.agentRoutes(chatService: ChatService, evalService: AgentEvalService) {
  authenticate(ApiKeyAuthProvider.X_API_KEY) {
    authorize(Permission.USE_ASKAI) {
      route("/agent") {
        get("/bots") {
          val username = call.username() ?: return@get
          ok(chatService.availableBots(username))
        }

        get("/{bot}/conversations") {
          val username = call.username() ?: return@get
          val mine = call.request.queryParameters["scope"] != "public"
          val limit =
              (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIST_LIMIT)
                  .coerceIn(1, MAX_LIST_LIMIT)
          val list = chatService.list(call.parameters["bot"]!!, username, mine, limit)
          if (list == null) call.error(HttpStatusCode.NotFound, "Assistant not found")
          else ok(list)
        }

        post("/{bot}/conversations") {
          val username = call.username() ?: return@post
          val text = call.receive<AgentCreateConversationRequest>().firstMessage.orEmpty()
          if (!call.validText(text)) return@post
          call.respondOutcome(
              chatService.startConversation(call.parameters["bot"]!!, username, text.trim()))
        }

        get("/conversations/{id}") {
          val username = call.username() ?: return@get
          val detail = chatService.detail(call.parameters["id"]!!, username)
          if (detail == null) call.error(HttpStatusCode.NotFound, "Conversation not found")
          else ok(detail)
        }

        post("/conversations/{id}/messages") {
          val username = call.username() ?: return@post
          val text = call.receive<AgentSendMessageRequest>().text
          if (!call.validText(text)) return@post
          call.respondOutcome(chatService.sendMessage(call.parameters["id"]!!, username, text.trim()))
        }

        get("/conversations/{id}/stream") {
          val username = call.username() ?: return@get
          val id = call.parameters["id"]!!
          chatService.viewable(id, username)
              ?: return@get call.error(HttpStatusCode.NotFound, "Conversation not found")
          val turn =
              chatService.activeTurn(id)
                  ?: return@get call.respond(HttpStatusCode.NoContent)
          val lastEventId =
              call.request.headers["Last-Event-ID"] ?: call.request.queryParameters["lastEventId"]
          call.streamTurn(turn, lastEventId)
        }

        post("/conversations/{id}/cancel") {
          val username = call.username() ?: return@post
          if (chatService.cancel(call.parameters["id"]!!, username)) ok(true)
          else call.error(HttpStatusCode.NotFound, "Conversation not found")
        }

        post("/conversations/{id}/feedback") {
          val username = call.username() ?: return@post
          val request = call.receive<AgentFeedbackRequest>()
          val saved =
              chatService.feedback(
                  call.parameters["id"]!!, username, request.messageId, request.helpful)
          if (saved) ok(true) else call.error(HttpStatusCode.NotFound, "Message not found")
        }

        post("/{bot}/eval-turn") {
          call.username() ?: return@post
          val request = call.receive<AgentEvalRequest>()
          when (val outcome = evalService.run(call.parameters["bot"]!!, request.messages, request.model)) {
            is EvalOutcome.Done -> ok(outcome.response)
            EvalOutcome.Disabled -> call.error(HttpStatusCode.NotFound, "Not found")
            EvalOutcome.UnknownBot -> call.error(HttpStatusCode.NotFound, "Assistant not found")
            is EvalOutcome.BadRequest -> call.error(HttpStatusCode.BadRequest, outcome.message)
          }
        }

        delete("/conversations/{id}") {
          val username = call.username() ?: return@delete
          if (chatService.delete(call.parameters["id"]!!, username)) ok(true)
          else call.error(HttpStatusCode.NotFound, "Conversation not found")
        }
      }
    }
  }
}

private suspend inline fun <reified T> RoutingContext.ok(data: T) {
  call.respond(ApiResponse(Status.SUCCESS, "OK", data))
}

private suspend fun ApplicationCall.username(): String? {
  val username = request.headers["X-Username"]?.trim()
  if (username.isNullOrEmpty()) error(HttpStatusCode.BadRequest, "Missing X-Username header")
  return username?.ifEmpty { null }
}

private suspend fun ApplicationCall.validText(text: String): Boolean =
    when {
      text.isBlank() -> {
        error(HttpStatusCode.BadRequest, "Message is empty")
        false
      }
      text.length > MAX_MESSAGE_CHARS -> {
        error(HttpStatusCode.BadRequest, "Message is too long (max $MAX_MESSAGE_CHARS characters)")
        false
      }
      else -> true
    }

private suspend fun ApplicationCall.error(status: HttpStatusCode, message: String) {
  respond(status, ApiResponse(Status.ERROR, message, null))
}

private suspend fun ApplicationCall.respondOutcome(outcome: TurnOutcome) {
  when (outcome) {
    is TurnOutcome.Started ->
        respond(
            HttpStatusCode.Accepted,
            ApiResponse(Status.SUCCESS, "Started", AgentTurnStarted(outcome.conversationId, outcome.turnSeq)))
    is TurnOutcome.Refused -> error(HttpStatusCode.TooManyRequests, outcome.message)
    TurnOutcome.NotFound -> error(HttpStatusCode.NotFound, "Not found")
    TurnOutcome.Forbidden -> error(HttpStatusCode.Forbidden, "Not allowed")
    TurnOutcome.AlreadyRunning ->
        error(HttpStatusCode.Conflict, "Wait for the current answer to finish")
  }
}

/** Replays the turn's events after [lastEventId], then follows it live until it ends. */
private suspend fun ApplicationCall.streamTurn(turn: ActiveTurn, lastEventId: String?) {
  val skipThrough = lastEventId?.let(::eventIndex)?.takeIf { lastEventId.startsWith("${turn.turnSeq}:") }
  response.header(HttpHeaders.CacheControl, "no-cache")
  response.header("X-Accel-Buffering", "no")
  respondBytesWriter(contentType = ContentType.Text.EventStream) {
    coroutineScope {
      val inbox = Channel<AgentStreamEvent>(Channel.UNLIMITED)
      val subscription =
          launch(start = CoroutineStart.UNDISPATCHED) {
            turn.events.collect { inbox.send(it) }
          }
      try {
        while (true) {
          val received = withTimeoutOrNull(HEARTBEAT_MILLIS) { inbox.receive() }
          if (received == null) {
            writeStringUtf8(": heartbeat\n\n")
            flush()
            continue
          }
          if (skipThrough != null && (eventIndex(received.id) ?: 0) <= skipThrough) continue
          writeEvent(received)
          if (received.type == "turn-end") break
        }
      } finally {
        subscription.cancel()
      }
    }
  }
}

private fun eventIndex(id: String): Int? = id.substringAfter(':', "").toIntOrNull()

private suspend fun ByteWriteChannel.writeEvent(event: AgentStreamEvent) {
  writeStringUtf8(
      "event: ${event.type}\nid: ${event.id}\ndata: " +
          streamJson.encodeToString(AgentStreamEvent.serializer(), event) +
          "\n\n")
  flush()
}
