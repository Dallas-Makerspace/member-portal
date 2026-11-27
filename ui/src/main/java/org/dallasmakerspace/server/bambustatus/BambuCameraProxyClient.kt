package org.dallasmakerspace.server.bambustatus

import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Singleton
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DmsHttpEngine
import org.dallasmakerspace.server.common.logging.LoggerFactory

/**
 * Streams a printer's camera snapshot or model icon from the backend service module to the
 * browser. A dedicated client (mirroring [org.dallasmakerspace.server.chronoring.ChronoRingClient]'s
 * binary-proxy shape) rather than [org.dallasmakerspace.server.common.DMSHttpClient], which only
 * speaks JSON. Goes through the service module (not straight to Bambuddy) so the Bambuddy API key
 * never has to live in the UI module.
 */
// A camera that stops sending frames for this long is dropped.
private const val STREAM_IDLE_TIMEOUT_MS = 30_000L

@Suppress("TooGenericExceptionCaught")
@Singleton
class BambuCameraProxyClient
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
          requestTimeoutMillis = 10_000
          connectTimeoutMillis = 5_000
        }
      }

  suspend fun proxyCameraSnapshot(printerId: String, call: ApplicationCall) =
      proxyBinary("$baseUrl/bambu/printers/$printerId/camera", call, "camera snapshot for $printerId")

  /** Live MJPEG passthrough: no overall deadline, flushed per chunk so frames aren't held back. */
  suspend fun proxyCameraStream(printerId: String, call: ApplicationCall) {
    try {
      http
          .prepareGet("$baseUrl/bambu/printers/$printerId/camera/stream") {
            authHeaders.forEach { (k, v) -> header(k, v) }
            timeout {
              requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
              socketTimeoutMillis = STREAM_IDLE_TIMEOUT_MS
            }
          }
          .execute { response ->
            if (!response.status.isSuccess()) {
              call.respond(HttpStatusCode.NotFound)
              return@execute
            }
            val contentType = response.contentType() ?: ContentType("multipart", "x-mixed-replace")
            call.response.header(HttpHeaders.CacheControl, "no-cache, no-store")
            call.response.header("X-Accel-Buffering", "no")
            val channel = response.bodyAsChannel()
            call.respondBytesWriter(contentType = contentType) {
              val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
              while (true) {
                val read = channel.readAvailable(buffer)
                if (read < 0) break
                if (read > 0) {
                  writeFully(buffer, 0, read)
                  flush()
                }
              }
            }
          }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.info("Bambu camera stream for $printerId ended: ${e.message}")
      if (!call.response.isCommitted) call.respond(HttpStatusCode.BadGateway)
    }
  }

  suspend fun proxyModelIcon(model: String, call: ApplicationCall) =
      proxyBinary("$baseUrl/bambu/models/${encodePathSegment(model)}/icon", call, "model icon for $model")

  // Path segment, not query string - encodeURLQueryComponent would turn a space into "+" rather
  // than "%20", which the service module's route parameter decoding wouldn't undo correctly.
  private fun encodePathSegment(value: String): String =
      java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

  private suspend fun proxyBinary(url: String, call: ApplicationCall, description: String) {
    try {
      http
          .prepareGet(url) { authHeaders.forEach { (k, v) -> header(k, v) } }
          .execute { response ->
            if (!response.status.isSuccess()) {
              call.respond(HttpStatusCode.NotFound)
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
      log.warn("Failed to proxy Bambu $description: ${e.message}")
      call.respond(HttpStatusCode.BadGateway)
    }
  }
}
