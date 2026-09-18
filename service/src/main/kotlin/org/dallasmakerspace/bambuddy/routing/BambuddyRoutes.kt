package org.dallasmakerspace.bambuddy.routing

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.dallasmakerspace.bambuddy.BambuddyStatusService
import org.dallasmakerspace.plugins.ApiResponse
import org.dallasmakerspace.plugins.Status

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
