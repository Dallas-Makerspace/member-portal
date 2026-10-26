package org.dallasmakerspace.server.agent

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.ClosedWriteChannelException
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.HttpException
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.routes.AuthenticatedHandler
import org.dallasmakerspace.server.workflows.SseRelay

/** Browser-facing `/api/agent/...`: the chat JSON API and event stream, for the signed-in member. */
class AgentApiHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val userInfoProvider: UserInfoProvider,
    private val client: AgentServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = userInfo["preferred_username"] as? String
    if (username == null) {
      call.respondJson(HttpStatusCode.Unauthorized, """{"message":"No username in session"}""")
      return
    }
    val method = call.request.httpMethod
    val servicePath = AgentRoutes.servicePath(method.value, call.request.path())
    if (servicePath == null) {
      call.respondJson(HttpStatusCode.NotFound, """{"message":"Not found"}""")
      return
    }
    try {
      if (servicePath.endsWith("/stream")) {
        stream(call, servicePath, username)
      } else {
        forward(call, method, servicePath, username)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.error("Agent API error on ${call.request.path()}", e)
      if (!call.response.isCommitted) {
        call.respondJson(HttpStatusCode.BadGateway, """{"message":"Assistant unavailable"}""")
      }
    }
  }

  private suspend fun forward(
      call: ApplicationCall,
      method: HttpMethod,
      servicePath: String,
      username: String,
  ) {
    val query = AgentRoutes.allowedQuery(call.request.queryParameters.entries().associate { it.key to it.value.first() })
    val body =
        if (method == HttpMethod.Post) {
          if (!call.request.contentType().match(ContentType.Application.Json)) {
            call.respondJson(
                HttpStatusCode.UnsupportedMediaType, """{"message":"Content-Type must be application/json"}""")
            return
          }
          call.receiveText().take(MAX_BODY_CHARS)
        } else null
    val resp = client.call(method, servicePath, username, session.sessionId, query, body)
    call.respondJson(HttpStatusCode.fromValue(resp.status), resp.body)
  }

  private suspend fun stream(call: ApplicationCall, servicePath: String, username: String) {
    val lastEventId =
        (call.request.headers["Last-Event-ID"] ?: call.request.queryParameters["lastEventId"])
            ?.takeIf { LAST_EVENT_ID.matches(it) }
    val token = session.accessToken
    val sid = session.sessionId
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.response.header("X-Accel-Buffering", "no")
    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
      try {
        client.openStream(servicePath, username, sid, lastEventId) { status, body ->
          if (status in 200..299) {
            SseRelay.relay(body, this) { stillSignedIn(token) }
          } else {
            SseRelay.writeEvent(this, SseRelay.ERROR_EVENT, """{"status":$status}""")
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: ClosedWriteChannelException) {
        log.debug("Agent stream closed by browser")
      } catch (e: IOException) {
        log.debug("Agent stream ended: ${e.message}")
      } catch (e: Exception) {
        log.warn("Agent stream failed", e)
        runCatching { SseRelay.writeEvent(this, SseRelay.ERROR_EVENT, """{"status":502}""") }
      }
    }
  }

  /** A transient lookup failure keeps the stream; only a rejected session ends it. */
  private suspend fun stillSignedIn(token: String?): Boolean {
    if (token == null) return false
    return try {
      userInfoProvider.getUserInfo(token)
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: HttpException) {
      e.message?.contains("401") != true
    } catch (e: Exception) {
      true
    }
  }

  private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(body, ContentType.Application.Json, status)
  }

  companion object {
    private const val MAX_BODY_CHARS = 20_000
    private val LAST_EVENT_ID = Regex("""\d{1,6}:\d{1,9}""")
  }
}

/** The browser paths that map onto service routes; everything else is a 404. */
internal object AgentRoutes {
  private const val BOT = """[a-z0-9-]{1,32}"""
  private const val ID = """[0-9a-fA-F-]{36}"""
  private const val PREFIX = "/api/agent"

  private val routes =
      listOf(
          "GET" to Regex("""$PREFIX/bots"""),
          "GET" to Regex("""$PREFIX/($BOT)/conversations"""),
          "POST" to Regex("""$PREFIX/($BOT)/conversations"""),
          "GET" to Regex("""$PREFIX/conversations/($ID)"""),
          "DELETE" to Regex("""$PREFIX/conversations/($ID)"""),
          "POST" to Regex("""$PREFIX/conversations/($ID)/(messages|cancel|feedback)"""),
          "GET" to Regex("""$PREFIX/conversations/($ID)/stream"""),
      )

  fun servicePath(method: String, path: String): String? =
      if (routes.any { (m, regex) -> m == method && regex.matches(path) }) {
        "/agent" + path.removePrefix(PREFIX)
      } else null

  private val QUERY_KEYS = setOf("scope", "limit")

  fun allowedQuery(query: Map<String, String>): Map<String, String> =
      query.filterKeys { it in QUERY_KEYS }.filterValues { it.length <= 16 }
}
