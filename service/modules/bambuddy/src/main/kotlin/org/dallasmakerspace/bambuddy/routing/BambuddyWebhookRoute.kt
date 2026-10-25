package org.dallasmakerspace.bambuddy.routing

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.dallasmakerspace.bambuddy.notifications.webhook.BambuddyWebhookHandler

/** Max accepted BamBuddy webhook body (~256 KB); legitimate v1 payloads are a few hundred bytes. */
const val BAMBU_WEBHOOK_MAX_BODY_BYTES = 256L * 1024L

/** The body, or null when it is longer than [maxBytes] (never buffers more than maxBytes + 1). */
internal suspend fun ByteReadChannel.readAtMost(maxBytes: Long): ByteArray? {
  val bytes = readRemaining(maxBytes + 1).readByteArray()
  return if (bytes.size > maxBytes) null else bytes
}

/**
 * BamBuddy's outbound notification webhook: a dedicated route, NOT the generic webhook router —
 * BamBuddy authenticates with an Authorization bearer secret (which the generic route cannot
 * see and must never log; it logs every header). Authenticates BEFORE reading the body, then
 * caps it: a declared Content-Length over the limit is refused outright, and the read itself is
 * bounded so a chunked or understated body cannot be buffered without limit.
 */
fun Route.bambuddyWebhookRoute(handlerProvider: () -> BambuddyWebhookHandler) {
  post("/webhook/bambuddy") {
    val handler = handlerProvider()
    val authorization = call.request.header(HttpHeaders.Authorization)
    if (!handler.isRequestAuthorized(authorization)) {
      call.respond(HttpStatusCode.Unauthorized)
      return@post
    }
    val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (contentLength != null && contentLength > BAMBU_WEBHOOK_MAX_BODY_BYTES) {
      call.respond(HttpStatusCode.PayloadTooLarge)
      return@post
    }
    val bytes = call.receiveChannel().readAtMost(BAMBU_WEBHOOK_MAX_BODY_BYTES)
    if (bytes == null) {
      call.respond(HttpStatusCode.PayloadTooLarge)
      return@post
    }
    call.respond(
        handler.handle(authorization, bytes.toString(Charsets.UTF_8), call.request.origin.remoteHost))
  }
}
