package org.dallasmakerspace.dashboards

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.setCookie
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DmsHttpEngine
import org.dallasmakerspace.core.LoggerFactory

class InterlockException(message: String) : Exception(message)

/**
 * Read-only client for the tool interlock system's GraphQL API. It signs in with a service account
 * (the system has no API keys), keeps the session cookie, and signs in again when it is rejected.
 * Only queries are ever sent.
 */
@Singleton
class InterlockClient
@Inject
constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)
  private val json = Json { ignoreUnknownKeys = true }
  private val loginLock = Mutex()
  @Volatile private var accessToken: String? = null

  private val baseUrl: String
    get() = appConfig.getStringProperty("app.interlock.base-url", DEFAULT_URL).trimEnd('/')

  private val email: String
    get() = appConfig.getStringProperty("app.interlock.email", "")

  private val password: String
    get() = appConfig.getStringProperty("app.interlock.password", "")

  val isConfigured: Boolean
    get() = email.isNotBlank() && password.isNotBlank()

  /** Runs a GraphQL query and returns its `data` object. */
  suspend fun query(query: String): JsonObject {
    if (!isConfigured) throw InterlockException("The tool system is not configured")
    var response = send(query)
    if (response.status == HttpStatusCode.Unauthorized) {
      accessToken = null
      response = send(query)
    }
    if (response.status != HttpStatusCode.OK) {
      throw InterlockException("The tool system answered ${response.status.value}")
    }
    val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
    root["errors"]?.jsonArray?.firstOrNull()?.let {
      throw InterlockException("The tool system rejected the query")
    }
    return root["data"]?.jsonObject ?: throw InterlockException("The tool system sent no data")
  }

  private suspend fun send(query: String): HttpResponse {
    val token = accessToken ?: login()
    return newClient().use { client ->
      client.post("$baseUrl/api/graphql") {
        header(HttpHeaders.Cookie, "accessToken=$token")
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("query", query) }.toString())
      }
    }
  }

  private suspend fun login(): String =
      loginLock.withLock {
        accessToken?.let {
          return it
        }
        val response =
            newClient().use { client ->
              client.post("$baseUrl/api/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                          put("email", email)
                          put("password", password)
                        }
                        .toString())
              }
            }
        if (response.status != HttpStatusCode.OK) {
          log.warn("Tool system sign-in was refused (${response.status.value})")
          throw InterlockException("The tool system refused the sign-in")
        }
        val token =
            response.setCookie().firstOrNull { it.name == COOKIE }?.value
                ?: throw InterlockException("The tool system sent no session")
        accessToken = token
        token
      }

  private fun newClient() =
      HttpClient(DmsHttpEngine) {
        install(HttpTimeout) {
          requestTimeoutMillis = 10_000
          connectTimeoutMillis = 5_000
          socketTimeoutMillis = 10_000
        }
      }

  private companion object {
    const val DEFAULT_URL = "https://dashboard.rfidinterlock.com"
    const val COOKIE = "accessToken"
  }
}
