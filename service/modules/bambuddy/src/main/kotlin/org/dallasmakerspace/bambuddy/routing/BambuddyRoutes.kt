package org.dallasmakerspace.bambuddy.routing

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import org.dallasmakerspace.bambuddy.BambuddyStatusService
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status

fun Route.bambuddyRoutes(bambuddyStatusService: BambuddyStatusService) {
  get("/bambu/summary") {
    val summary = bambuddyStatusService.getSummary()
    call.respond(ApiResponse(Status.SUCCESS, "Bambu printer summary", summary))
  }

  get("/bambu/printers") {
    val printers = bambuddyStatusService.getPrinterDetails()
    call.respond(ApiResponse(Status.SUCCESS, "Bambu printers: ${printers.size}", printers))
  }

  get("/bambu/printers/{id}/camera") {
    val printerId =
        call.parameters["id"]?.toIntOrNull()
            ?: run {
              call.respond(HttpStatusCode.BadRequest)
              return@get
            }
    val snapshot = bambuddyStatusService.getCameraSnapshot(printerId)
    if (snapshot == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val (bytes, contentType) = snapshot
    call.respondBytes(bytes, ContentType.parse(contentType))
  }

  // Live MJPEG passthrough; runs until the caller disconnects or the camera stops sending.
  get("/bambu/printers/{id}/camera/stream") {
    val printerId =
        call.parameters["id"]?.toIntOrNull()
            ?: run {
              call.respond(HttpStatusCode.BadRequest)
              return@get
            }
    val opened =
        bambuddyStatusService.streamCamera(printerId) { contentType, body ->
          call.response.header(HttpHeaders.CacheControl, "no-cache, no-store")
          call.response.header("X-Accel-Buffering", "no")
          call.respondBytesWriter(ContentType.parse(contentType)) { pipe(body, this) }
        }
    if (!opened && !call.response.isCommitted) call.respond(HttpStatusCode.NotFound)
  }

  get("/bambu/models/{model}/icon") {
    val model =
        call.parameters["model"]
            ?: run {
              call.respond(HttpStatusCode.BadRequest)
              return@get
            }
    val icon = bambuddyStatusService.getPrinterModelIcon(model)
    if (icon == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val (bytes, contentType) = icon
    // Static product art, unlike the live camera snapshot above - safe to cache client-side.
    call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
    call.respondBytes(bytes, ContentType.parse(contentType))
  }
}

private const val STREAM_BUFFER_BYTES = 16 * 1024

private suspend fun pipe(from: ByteReadChannel, to: ByteWriteChannel) {
  val buffer = ByteArray(STREAM_BUFFER_BYTES)
  while (true) {
    val read = from.readAvailable(buffer)
    if (read < 0) break
    if (read > 0) {
      to.writeFully(buffer, 0, read)
      to.flush()
    }
  }
}
