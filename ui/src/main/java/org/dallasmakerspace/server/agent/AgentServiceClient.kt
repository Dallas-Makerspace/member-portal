package org.dallasmakerspace.server.agent

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.workflows.ServiceResponse
import org.dallasmakerspace.server.workflows.WorkflowServiceClient

const val AGENT_USER_HEADER = "X-Username"

/**
 * Calls the service's /agent API for the signed-in member. Plain calls have a 30s timeout; the
 * stream client has none and its own pool so open streams can't starve ordinary calls.
 */
@Singleton
class AgentServiceClient
@Inject
constructor(appConfig: AppConfig) {
  private val baseUrl = appConfig.requireStringProperty("app.member-service.url")
  private val authHeaders =
      mapOf(
          "X-Api-Key" to appConfig.requireStringProperty("app.member-service.api-key"),
          "X-Api-Client" to appConfig.requireStringProperty("app.member-service.api-client"),
      )
  private val http: HttpClient = WorkflowServiceClient.newRequestClient()
  private val streamHttp: HttpClient = WorkflowServiceClient.newStreamClient()

  suspend fun call(
      method: HttpMethod,
      path: String,
      username: String,
      sessionId: String?,
      query: Map<String, String> = emptyMap(),
      body: String? = null,
  ): ServiceResponse {
    val resp =
        http.request("$baseUrl$path") {
          this.method = method
          applyHeaders(username, sessionId)
          query.forEach { (k, v) -> parameter(k, v) }
          if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
          }
        }
    return ServiceResponse(resp.status.value, resp.bodyAsText())
  }

  /** Opens the event stream and hands status plus body to [block] while the connection is open. */
  suspend fun <T> openStream(
      path: String,
      username: String,
      sessionId: String?,
      lastEventId: String?,
      block: suspend (status: Int, body: ByteReadChannel) -> T,
  ): T =
      streamHttp
          .prepareGet("$baseUrl$path") {
            applyHeaders(username, sessionId)
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            lastEventId?.let { header("Last-Event-ID", it) }
          }
          .execute { resp -> block(resp.status.value, resp.bodyAsChannel()) }

  private fun io.ktor.client.request.HttpRequestBuilder.applyHeaders(
      username: String,
      sessionId: String?
  ) {
    authHeaders.forEach { (k, v) -> header(k, v) }
    sessionId?.let { header("X-Request-Id", it) }
    header(AGENT_USER_HEADER, username)
  }
}
