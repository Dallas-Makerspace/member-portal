package org.dallasmakerspace.server.chronoring

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.logging.LoggerFactory

@Suppress("TooGenericExceptionCaught")
@Singleton
class ChronoRingClient
@Inject
constructor(
    loggerFactory: LoggerFactory,
    appConfig: AppConfig,
) {
  private val log = loggerFactory.create(javaClass)
  private val baseUrl =
      appConfig.getStringProperty("app.chrono-ring.base-url", "http://localhost:3000")

  private val http =
      HttpClient(CIO) {
        engine {
          endpoint {
            keepAliveTime = 0 // zoneminder closes idle connections; disable reuse to avoid EOF
            requestTimeout = REQUEST_TIMEOUT_MS
            connectTimeout = CONNECT_TIMEOUT_MS
            connectAttempts = 2
          }
        }
      }

  companion object {
    private const val REQUEST_TIMEOUT_MS = 5_000L
    private const val CONNECT_TIMEOUT_MS = 3_000L
  }

  suspend fun proxyJson(path: String, call: ApplicationCall) {
    val url = "$baseUrl/$path"
    try {
      http.prepareGet(url).execute { response ->
        val body = response.bodyAsText()
        call.respondText(body, ContentType.Application.Json, response.status)
      }
    } catch (e: Exception) {
      log.warn("ChronoRing GET $path failed: ${e.message}")
      call.respond(HttpStatusCode.BadGateway, mapOf("error" to "chrono-ring unreachable"))
    }
  }

  suspend fun proxyJsonPost(path: String, body: String, call: ApplicationCall) {
    val url = "$baseUrl/$path"
    try {
      http
          .preparePost(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
          }
          .execute { response ->
            val responseBody = response.bodyAsText()
            call.respondText(responseBody, ContentType.Application.Json, response.status)
          }
    } catch (e: Exception) {
      log.warn("ChronoRing POST $path failed: ${e.message}")
      call.respond(HttpStatusCode.BadGateway, mapOf("error" to "chrono-ring unreachable"))
    }
  }

  suspend fun proxyBinary(path: String, call: ApplicationCall) {
    val url = "$baseUrl/$path"
    try {
      http.prepareGet(url).execute { response ->
        val contentType = response.contentType() ?: ContentType.Image.JPEG
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
      log.warn("ChronoRing binary GET $path failed: ${e.message}")
      call.respond(HttpStatusCode.BadGateway)
    }
  }
}
