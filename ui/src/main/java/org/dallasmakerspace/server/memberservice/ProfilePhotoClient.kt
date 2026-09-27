package org.dallasmakerspace.server.memberservice

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondRedirect
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DmsHttpEngine
import org.dallasmakerspace.server.common.logging.LoggerFactory

private const val REQUEST_TIMEOUT_MS = 10_000L
private const val CONNECT_TIMEOUT_MS = 5_000L

/** Result of [ProfilePhotoClient.upload]. Exactly one of [photoUrl]/[errorMessage] is set. */
data class ProfilePhotoUploadResult(
    val statusCode: Int,
    val photoUrl: String? = null,
    val errorMessage: String? = null,
)

/**
 * Streams a member's uploaded profile photo from the service module to the browser (mirroring
 * [org.dallasmakerspace.server.bambustatus.BambuCameraProxyClient]'s binary-proxy shape). Unlike
 * that client, a missing photo or any proxy failure redirects to the caller-supplied fallback URL
 * rather than erroring, so a member without an uploaded photo still gets their Discourse avatar or
 * the placeholder.
 */
@Suppress("TooGenericExceptionCaught")
@Singleton
class ProfilePhotoClient
@Inject
constructor(
    loggerFactory: LoggerFactory,
    appConfig: AppConfig,
) {
  private val log = loggerFactory.create(javaClass)
  private val baseUrl = appConfig.requireStringProperty("app.member-service.url")
  private val authHeaders =
      mapOf(
          "X-Api-Key" to appConfig.requireStringProperty("app.member-service.api-key"),
          "X-Api-Client" to appConfig.requireStringProperty("app.member-service.api-client"),
      )

  private val http =
      HttpClient(DmsHttpEngine) {
        install(HttpTimeout) {
          requestTimeoutMillis = REQUEST_TIMEOUT_MS
          connectTimeoutMillis = CONNECT_TIMEOUT_MS
        }
        install(ContentNegotiation) { jackson {} }
      }

  suspend fun streamOrRedirect(username: String, fallbackUrl: String, call: ApplicationCall) {
    try {
      http
          .prepareGet("$baseUrl/members/$username/profile-photo") {
            authHeaders.forEach { (k, v) -> header(k, v) }
          }
          .execute { response ->
            if (!response.status.isSuccess()) {
              call.respondRedirect(fallbackUrl)
              return@execute
            }
            val contentType = response.contentType() ?: ContentType.Image.JPEG
            response.headers[HttpHeaders.CacheControl]?.let {
              call.response.header(HttpHeaders.CacheControl, it)
            }
            val channel = response.bodyAsChannel()
            call.respondBytesWriter(contentType = contentType) {
              val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
              while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read > 0) writeFully(buffer, 0, read)
              }
            }
          }
    } catch (e: Exception) {
      log.warn("Failed to proxy profile photo for $username: ${e.message}")
      call.respondRedirect(fallbackUrl)
    }
  }

  /** Uploads [bytes] as [username]'s profile photo. [username] is always the acting member. */
  suspend fun upload(
      username: String,
      bytes: ByteArray,
      override: Boolean,
      sessionId: String?,
  ): ProfilePhotoUploadResult {
    val response =
        try {
          http.put("$baseUrl/members/$username/profile-photo") {
            authHeaders.forEach { (k, v) -> header(k, v) }
            header("X-Username", username)
            sessionId?.let { header("X-Request-Id", it) }
            if (override) header("X-Validation-Override", "true")
            contentType(ContentType.Image.JPEG)
            setBody(bytes)
          }
        } catch (e: Exception) {
          log.warn("Failed to upload profile photo for $username: ${e.message}")
          return ProfilePhotoUploadResult(
              statusCode = HTTP_BAD_GATEWAY,
              errorMessage = "Failed to upload photo. Please try again.")
        }
    val (message, photoUrl) = parseApiResponse(response)
    return if (response.status.isSuccess()) {
      ProfilePhotoUploadResult(statusCode = response.status.value, photoUrl = photoUrl)
    } else {
      ProfilePhotoUploadResult(
          statusCode = response.status.value, errorMessage = message ?: "Failed to upload photo.")
    }
  }

  /** Removes [username]'s profile photo, acting as [actorUsername] (self, or an Infra admin). */
  suspend fun remove(username: String, actorUsername: String, sessionId: String?): Boolean {
    return try {
      val response =
          http.delete("$baseUrl/members/$username/profile-photo") {
            authHeaders.forEach { (k, v) -> header(k, v) }
            header("X-Username", actorUsername)
            sessionId?.let { header("X-Request-Id", it) }
          }
      response.status.isSuccess()
    } catch (e: Exception) {
      log.warn("Failed to remove profile photo for $username: ${e.message}")
      false
    }
  }

  private suspend fun parseApiResponse(response: HttpResponse): Pair<String?, String?> {
    val body = runCatching { response.body<Map<String, Any?>>() }.getOrNull()
    val message = body?.get("message") as? String
    @Suppress("UNCHECKED_CAST") val data = body?.get("data") as? Map<String, Any?>
    return message to (data?.get("photoUrl") as? String)
  }
}

private const val HTTP_BAD_GATEWAY = 502
