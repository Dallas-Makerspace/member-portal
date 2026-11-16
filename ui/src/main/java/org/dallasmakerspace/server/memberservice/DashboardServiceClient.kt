package org.dallasmakerspace.server.memberservice

import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.HttpMethod
import io.ktor.util.StringValues
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DMSHttpClient
import org.dallasmakerspace.server.common.DmsHttpEngine
import org.dallasmakerspace.server.common.HttpException
import org.dallasmakerspace.server.common.logging.LoggerFactory

/** The service's answer to a dashboard call: HTTP status, member-facing message, payload. */
data class DashboardCallResult(val statusCode: Int, val message: String?, val data: Any?)

/** A picture (or the reason there isn't one) from the service. */
class DashboardImageResult(val statusCode: Int, val bytes: ByteArray?, val contentType: String?)

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

  private val raw = HttpClient(DmsHttpEngine) { install(HttpTimeout) { requestTimeoutMillis = IMAGE_TIMEOUT_MS } }

  /** Sends [bytes] as a new picture for [committeeId], acting as [username]. */
  suspend fun uploadImage(
      committeeId: Int,
      username: String,
      bytes: ByteArray,
      mimeType: String,
      sessionId: String?,
  ): DashboardCallResult =
      try {
        val response =
            raw.post("$baseUrl/committees/$committeeId/dashboard/images") {
              authHeaders.forEach { (k, v) -> header(k, v) }
              sessionId?.let { header("X-Request-Id", it) }
              header("X-Username", username)
              contentType(ContentType.parse(mimeType))
              setBody(bytes)
            }
        parse(response)
      } catch (ex: Exception) {
        log.warn("Picture upload failed", ex)
        DashboardCallResult(BAD_GATEWAY, "Could not reach the member service. Please try again.", null)
      }

  /** The editor's own copy of a picture, read as [username]. */
  suspend fun editorImage(
      committeeId: Int,
      file: String,
      username: String,
      sessionId: String?,
  ): DashboardImageResult =
      fetchImage("committees/$committeeId/dashboard/images/$file") {
        sessionId?.let { header("X-Request-Id", it) }
        header("X-Username", username)
      }

  /** A picture for the TV holding [token]. */
  suspend fun tvImage(file: String, token: String): DashboardImageResult =
      fetchImage("tv/images/$file") { header("X-Dashboard-Token", token) }

  /** An uploaded member photo for the TV holding [token]. */
  suspend fun tvAvatar(username: String, token: String): DashboardImageResult =
      fetchImage("tv/avatars/$username") { header("X-Dashboard-Token", token) }

  private suspend fun fetchImage(
      path: String,
      extra: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
  ): DashboardImageResult =
      try {
        val response =
            raw.get("$baseUrl/$path") {
              authHeaders.forEach { (k, v) -> header(k, v) }
              extra()
            }
        if (response.status.isSuccess()) {
          DashboardImageResult(
              response.status.value, response.readRawBytes(), response.contentType()?.toString())
        } else {
          DashboardImageResult(response.status.value, null, null)
        }
      } catch (ex: Exception) {
        log.warn("Picture fetch failed", ex)
        DashboardImageResult(BAD_GATEWAY, null, null)
      }

  private suspend fun parse(response: HttpResponse): DashboardCallResult {
    val body = runCatching { gson.fromJson(response.bodyAsText(), Map::class.java) }.getOrNull()
    val message = (body?.get("message") as? String)?.takeIf { it.isNotBlank() }
    return if (response.status.isSuccess()) {
      DashboardCallResult(response.status.value, message, body?.get("data"))
    } else {
      DashboardCallResult(response.status.value, message ?: "Something went wrong. Please try again.", null)
    }
  }

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
    const val IMAGE_TIMEOUT_MS = 30_000L
  }
}
