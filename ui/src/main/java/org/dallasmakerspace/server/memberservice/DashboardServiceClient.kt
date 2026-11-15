package org.dallasmakerspace.server.memberservice

import com.google.gson.Gson
import io.ktor.http.HttpMethod
import io.ktor.util.StringValues
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DMSHttpClient
import org.dallasmakerspace.server.common.HttpException
import org.dallasmakerspace.server.common.logging.LoggerFactory

/** The service's answer to a dashboard call: HTTP status, member-facing message, payload. */
data class DashboardCallResult(val statusCode: Int, val message: String?, val data: Any?)

/**
 * Calls the service's committee dashboard routes. Failures keep their status and message so the
 * editor can show them (a rejected page, a rate limit) rather than a generic error.
 */
@Suppress("TooGenericExceptionCaught")
@Singleton
class DashboardServiceClient
@Inject
constructor(
    loggerFactory: LoggerFactory,
    appConfig: AppConfig,
    private val dmsHttpClient: DMSHttpClient,
) {
  private val log = loggerFactory.create(javaClass)
  private val gson = Gson()
  private val baseUrl = appConfig.requireStringProperty("app.member-service.url")
  private val authHeaders =
      mapOf(
          "X-Api-Key" to appConfig.requireStringProperty("app.member-service.api-key"),
          "X-Api-Client" to appConfig.requireStringProperty("app.member-service.api-client"),
      )

  /** Calls `$service/[path]` acting as [username] (the signed-in member). */
  suspend fun call(
      method: HttpMethod,
      path: String,
      username: String,
      body: Any?,
      sessionId: String?,
  ): DashboardCallResult =
      send(method, path, body) {
        sessionId?.let { append("X-Request-Id", it) }
        append("X-Username", username)
      }

  /** The dashboard for a TV's [token]; 401 when the token is bad, expired or revoked. */
  suspend fun tvDashboard(token: String): DashboardCallResult =
      send(HttpMethod.Get, "tv/dashboard", null) { append("X-Dashboard-Token", token) }

  private suspend fun send(
      method: HttpMethod,
      path: String,
      body: Any?,
      extra: io.ktor.util.StringValuesBuilder.() -> Unit,
  ): DashboardCallResult {
    val headers =
        StringValues.build {
          authHeaders.forEach(this::append)
          extra()
        }
    val url = "$baseUrl/$path"
    return try {
      val result =
          when (method) {
            HttpMethod.Get -> dmsHttpClient.get(url, headers)
            HttpMethod.Post -> dmsHttpClient.post(url, headers, body ?: emptyMap<String, Any>())
            HttpMethod.Put -> dmsHttpClient.put(url, headers, body ?: emptyMap<String, Any>())
            HttpMethod.Delete -> dmsHttpClient.delete(url, headers)
            else -> throw IllegalArgumentException("Unsupported method $method")
          }
      DashboardCallResult(HTTP_OK, result["message"] as? String, result["data"])
    } catch (ex: HttpException) {
      failure(ex)
    } catch (ex: Exception) {
      log.warn("Dashboard call $method $path failed", ex)
      DashboardCallResult(BAD_GATEWAY, "Could not reach the member service. Please try again.", null)
    }
  }

  private fun failure(ex: HttpException): DashboardCallResult {
    val text = ex.message ?: ""
    val status =
        ex.httpStatus.takeIf { it != 0 }
            ?: Regex("Status: ([0-9]+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: BAD_GATEWAY
    val embedded =
        runCatching {
              gson.fromJson(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1), Map::class.java)
            }
            .getOrNull()
    val message =
        (embedded?.get("message") as? String)?.takeIf { it.isNotBlank() }
            ?: text.takeIf { ex.httpStatus != 0 && it.length < MAX_MESSAGE && '{' !in it }
            ?: "Something went wrong. Please try again."
    return DashboardCallResult(status, message, null)
  }

  private companion object {
    const val HTTP_OK = 200
    const val BAD_GATEWAY = 502
    const val MAX_MESSAGE = 200
  }
}
