package org.dallasmakerspace.server.workflows

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpTimeout
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import org.dallasmakerspace.models.workflows.WorkflowAdminInProgressDto
import org.dallasmakerspace.models.workflows.WorkflowDefinitionDto
import org.dallasmakerspace.models.workflows.WorkflowRunDto
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DmsHttpEngine
import org.dallasmakerspace.server.common.logging.LoggerFactory

const val ACTING_USER_HEADER = "X-Acting-User"

data class ServiceResponse(val status: Int, val body: String) {
  val isSuccess: Boolean
    get() = status in 200..299
}

/**
 * Calls the service's /workflows API. Plain calls use a client with a 30s timeout; SSE relays use a
 * separate client with no request timeout and its own connection pool, so long-lived streams are
 * neither cut off nor able to starve ordinary API calls.
 */
@Suppress("TooManyFunctions")
@Singleton
class WorkflowServiceClient(
    private val baseUrl: String,
    private val authHeaders: Map<String, String>,
    private val http: HttpClient,
    private val streamHttp: HttpClient,
    loggerFactory: LoggerFactory,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
  @Inject
  constructor(
      loggerFactory: LoggerFactory,
      appConfig: AppConfig,
  ) : this(
      baseUrl = appConfig.requireStringProperty("app.member-service.url"),
      authHeaders =
          mapOf(
              "X-Api-Key" to appConfig.requireStringProperty("app.member-service.api-key"),
              "X-Api-Client" to appConfig.requireStringProperty("app.member-service.api-client"),
          ),
      http = newRequestClient(),
      streamHttp = newStreamClient(),
      loggerFactory = loggerFactory,
  )

  private val log = loggerFactory.create(javaClass)

  @Volatile private var cachedDefinitions: Pair<Long, List<WorkflowDefinitionDto>>? = null

  suspend fun rawDefinitions(sessionId: String?): ServiceResponse =
      call(HttpMethod.Get, "/workflows/definitions", sessionId = sessionId)

  /** Definitions rarely change (they are code), so they are cached for a short time. */
  suspend fun definitions(sessionId: String?): List<WorkflowDefinitionDto> {
    cachedDefinitions
        ?.takeIf { nowMs() - it.first < DEFINITIONS_TTL_MS }
        ?.let {
          return it.second
        }
    val resp = rawDefinitions(sessionId)
    check(resp.isSuccess) { "definitions failed: ${resp.status}" }
    val parsed = json.decodeFromString(ListSerializer(WorkflowDefinitionDto.serializer()), resp.body)
    cachedDefinitions = nowMs() to parsed
    return parsed
  }

  suspend fun definition(id: String, sessionId: String?): WorkflowDefinitionDto? =
      definitions(sessionId).firstOrNull { it.id == id }

  suspend fun preflight(id: String, body: String, actor: String, sessionId: String?) =
      call(HttpMethod.Post, "/workflows/${enc(id)}/preflight", actor, sessionId, body = body)

  suspend fun start(id: String, body: String, actor: String, sessionId: String?) =
      call(HttpMethod.Post, "/workflows/${enc(id)}/runs", actor, sessionId, body = body)

  suspend fun listRuns(query: Map<String, String>, actor: String, sessionId: String?) =
      call(HttpMethod.Get, "/workflows/runs", actor, sessionId, query = query)

  suspend fun rawRun(runId: Long, actor: String?, sessionId: String?) =
      call(HttpMethod.Get, "/workflows/runs/$runId", actor, sessionId)

  /** The run, or null when the service answers 404. Other failures throw. */
  suspend fun run(runId: Long, actor: String?, sessionId: String?): WorkflowRunDto? {
    val resp = rawRun(runId, actor, sessionId)
    if (resp.status == 404) return null
    check(resp.isSuccess) { "run lookup failed: ${resp.status}" }
    return json.decodeFromString(WorkflowRunDto.serializer(), resp.body)
  }

  suspend fun events(runId: Long, after: Long?, actor: String?, sessionId: String?) =
      call(
          HttpMethod.Get,
          "/workflows/runs/$runId/events",
          actor,
          sessionId,
          query = if (after != null) mapOf("after" to after.toString()) else emptyMap())

  suspend fun operate(runId: Long, action: String, actor: String, sessionId: String?) =
      call(HttpMethod.Post, "/workflows/runs/$runId/$action", actor, sessionId, body = "{}")

  /** Never log the response: on success its body holds a presigned URL. */
  suspend fun artifactUrl(
      runId: Long,
      stepIndex: Int,
      name: String,
      actor: String,
      sessionId: String?,
  ) =
      call(
          HttpMethod.Get,
          "/workflows/runs/$runId/steps/$stepIndex/artifacts/${enc(name)}/url",
          actor,
          sessionId)

  suspend fun rawAdminInProgress(actor: String?, sessionId: String?) =
      call(HttpMethod.Get, "/workflows/admin/in-progress", actor, sessionId)

  suspend fun adminInProgress(actor: String?, sessionId: String?): WorkflowAdminInProgressDto {
    val resp = rawAdminInProgress(actor, sessionId)
    check(resp.isSuccess) { "in-progress failed: ${resp.status}" }
    return json.decodeFromString(WorkflowAdminInProgressDto.serializer(), resp.body)
  }

  /**
   * Opens an SSE stream on the service and hands the response (status + body channel) to [block]
   * while the connection is open. [lastEventId] is forwarded as Last-Event-ID.
   */
  suspend fun <T> openStream(
      path: String,
      query: Map<String, String>,
      lastEventId: String?,
      actor: String?,
      sessionId: String?,
      block: suspend (status: Int, body: ByteReadChannel) -> T,
  ): T =
      streamHttp
          .prepareGet("$baseUrl$path") {
            applyHeaders(actor, sessionId)
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            lastEventId?.let { header("Last-Event-ID", it) }
            query.forEach { (k, v) -> parameter(k, v) }
          }
          .execute { resp -> block(resp.status.value, resp.bodyAsChannel()) }

  private suspend fun call(
      method: HttpMethod,
      path: String,
      actor: String? = null,
      sessionId: String? = null,
      query: Map<String, String> = emptyMap(),
      body: String? = null,
  ): ServiceResponse {
    val resp =
        http.request("$baseUrl$path") {
          this.method = method
          applyHeaders(actor, sessionId)
          query.forEach { (k, v) -> parameter(k, v) }
          if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
          }
        }
    val text = resp.bodyAsText()
    if (resp.status.value >= SERVER_ERROR) log.warn("workflows $method $path -> ${resp.status}")
    return ServiceResponse(resp.status.value, text)
  }

  private fun io.ktor.client.request.HttpRequestBuilder.applyHeaders(
      actor: String?,
      sessionId: String?
  ) {
    authHeaders.forEach { (k, v) -> header(k, v) }
    sessionId?.let { header("X-Request-Id", it) }
    actor?.let { header(ACTING_USER_HEADER, it) }
  }

  companion object {
    internal val json = Json { ignoreUnknownKeys = true }
    private const val DEFINITIONS_TTL_MS = 60_000L
    private const val REQUEST_TIMEOUT_MS = 30_000L
    private const val CONNECT_TIMEOUT_S = 10L
    private const val SERVER_ERROR = 500
    private const val MAX_STREAMS = 256

    private fun enc(segment: String) = java.net.URLEncoder.encode(segment, "UTF-8")

    fun newRequestClient(): HttpClient =
        HttpClient(DmsHttpEngine) {
          expectSuccess = false
          install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
          engine { config { connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS) } }
        }

    fun newStreamClient(): HttpClient =
        HttpClient(DmsHttpEngine) {
          expectSuccess = false
          engine { streamConfig() }
        }

    private fun OkHttpConfig.streamConfig() {
      config {
        connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        readTimeout(0, TimeUnit.MILLISECONDS)
        writeTimeout(0, TimeUnit.MILLISECONDS)
        callTimeout(0, TimeUnit.MILLISECONDS)
        retryOnConnectionFailure(false)
        dispatcher(
            Dispatcher().apply {
              maxRequests = MAX_STREAMS
              maxRequestsPerHost = MAX_STREAMS
            })
        connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
      }
    }
  }
}
