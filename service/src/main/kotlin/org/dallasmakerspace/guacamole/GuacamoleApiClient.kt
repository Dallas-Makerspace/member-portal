package org.dallasmakerspace.guacamole

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DmsHttpEngine
import org.dallasmakerspace.core.LoggerFactory

private const val TOKEN_CACHE_TTL_MS = 5 * 60 * 1000L

@Singleton
class GuacamoleApiClient
@Inject
constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) : IGuacamoleApiClient {
  private val log = loggerFactory.create(javaClass)

  private val json = Json { ignoreUnknownKeys = true }

  private data class CachedToken(val token: String, val expiresAt: Long)

  private val tokenCache = AtomicReference<CachedToken?>(null)

  private val baseUrl: String by lazy { appConfig.requireStringProperty("app.guacamole.base-url") }

  private fun newClient() =
      HttpClient(DmsHttpEngine) {
        install(HttpTimeout) {
          requestTimeoutMillis = 10_000
          connectTimeoutMillis = 5_000
          socketTimeoutMillis = 10_000
        }
        install(Logging) {
          logger = Logger.DEFAULT
          level = LogLevel.INFO
        }
      }

  private suspend fun getToken(): String {
    val cached = tokenCache.get()
    if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
      return cached.token
    }

    val username = appConfig.requireStringProperty("app.guacamole.admin-username")
    val password = appConfig.requireStringProperty("app.guacamole.admin-password")

    val response =
        newClient().use { client ->
          client.post("$baseUrl/api/tokens") {
            setBody(
                FormDataContent(
                    Parameters.build {
                      append("username", username)
                      append("password", password)
                    }))
          }
        }

    if (response.status != HttpStatusCode.OK) {
      throw GuacamoleApiException(
          "Failed to obtain Guacamole token: ${response.status} - ${response.bodyAsText()}")
    }

    val body = response.bodyAsText()
    val token =
        json.parseToJsonElement(body).jsonObject["authToken"]?.jsonPrimitive?.content
            ?: throw GuacamoleApiException("No authToken in Guacamole token response")

    tokenCache.set(CachedToken(token, System.currentTimeMillis() + TOKEN_CACHE_TTL_MS))
    return token
  }

  private suspend fun authorized(
      request: suspend (client: HttpClient, token: String) -> HttpResponse
  ): HttpResponse {
    val response = newClient().use { request(it, getToken()) }
    if (response.status != HttpStatusCode.Unauthorized &&
        response.status != HttpStatusCode.Forbidden) {
      return response
    }
    tokenCache.set(null)
    return newClient().use { request(it, getToken()) }
  }

  override suspend fun getConnections(): List<GuacamoleConnection> {
    try {
      val response =
          authorized { client, token ->
            client.get("$baseUrl/api/session/data/postgresql/connections") {
              header("Guacamole-Token", token)
            }
          }
      if (response.status != HttpStatusCode.OK) {
        throw GuacamoleApiException(
            "Failed to fetch connections: ${response.status} - ${response.bodyAsText()}")
      }
      val body = response.bodyAsText()
      val obj = json.parseToJsonElement(body).jsonObject
      return obj.values.map { json.decodeFromJsonElement(GuacamoleConnection.serializer(), it) }
    } catch (e: GuacamoleApiException) {
      throw e
    } catch (e: Exception) {
      throw GuacamoleApiException("Failed to fetch Guacamole connections", e)
    }
  }

  override suspend fun getActiveConnections(): List<GuacamoleActiveConnection> {
    try {
      val response =
          authorized { client, token ->
            client.get("$baseUrl/api/session/data/postgresql/activeConnections") {
              header("Guacamole-Token", token)
            }
          }
      if (response.status != HttpStatusCode.OK) {
        throw GuacamoleApiException(
            "Failed to fetch active connections: ${response.status} - ${response.bodyAsText()}")
      }
      val body = response.bodyAsText()
      val obj = json.parseToJsonElement(body).jsonObject
      return obj.values.map {
        json.decodeFromJsonElement(GuacamoleActiveConnection.serializer(), it)
      }
    } catch (e: GuacamoleApiException) {
      throw e
    } catch (e: Exception) {
      throw GuacamoleApiException("Failed to fetch Guacamole active connections", e)
    }
  }

  override suspend fun getConnectionHostname(id: String): String {
    try {
      val response =
          authorized { client, token ->
            client.get("$baseUrl/api/session/data/postgresql/connections/$id/parameters") {
              header("Guacamole-Token", token)
            }
          }
      if (response.status != HttpStatusCode.OK) return ""
      return json
          .parseToJsonElement(response.bodyAsText())
          .jsonObject["hostname"]
          ?.jsonPrimitive
          ?.content ?: ""
    } catch (e: Exception) {
      log.warn("Failed to fetch hostname for connection $id", e)
      return ""
    }
  }

  override suspend fun killActiveConnection(identifier: String) {
    val response =
        authorized { client, token ->
          client.delete("$baseUrl/api/session/data/postgresql/activeConnections/$identifier") {
            header("Guacamole-Token", token)
          }
        }
    if (!response.status.isSuccess()) {
      throw GuacamoleApiException(
          "Failed to kill active connection $identifier: ${response.status} - ${response.bodyAsText()}")
    }
  }
}
