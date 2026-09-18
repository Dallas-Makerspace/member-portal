package org.dallasmakerspace.server.bambustatus

import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import javax.inject.Inject
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
