package org.dallasmakerspace.server.routes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.inject.Inject
import kotlinx.serialization.Serializable
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.ProfilePhotoClient

private const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024

/**
 * Upload/remove a member's profile photo: `POST`/`DELETE` on
 * `/profile/@{preferred_username}/photo`. Proxies to the service's `PUT`/`DELETE
 * /members/{username}/profile-photo` via [ProfilePhotoClient]. There's no site-wide CSRF protection
 * in this app, so both mutating methods here require a same-origin `Origin` (or `Referer`) header
 * as a minimum defense against a cross-site form post.
 */
class ProfilePhotoHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val profilePhotoClient: ProfilePhotoClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  @Serializable
  data class PhotoResponse(val success: Boolean, val message: String, val photoUrl: String? = null)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = call.parameters["preferred_username"]
    if (username.isNullOrBlank()) {
      call.respond(HttpStatusCode.BadRequest, PhotoResponse(false, "Missing username"))
      return
    }
    if (!isSameOrigin(call)) {
      call.respond(HttpStatusCode.Forbidden, PhotoResponse(false, "Cross-site request rejected"))
      return
    }
    val currentUsername = userInfo["preferred_username"] as? String

    when (call.request.httpMethod) {
      HttpMethod.Post -> handleUpload(call, username, currentUsername)
      HttpMethod.Delete -> handleRemove(call, username, currentUsername)
      else ->
          call.respond(HttpStatusCode.MethodNotAllowed, PhotoResponse(false, "Method not allowed"))
    }
  }

  @Suppress("TooGenericExceptionCaught", "ReturnCount")
  private suspend fun handleUpload(
      call: ApplicationCall,
      username: String,
      currentUsername: String?
  ) {
    if (currentUsername != username) {
      call.respond(
          HttpStatusCode.Forbidden,
          PhotoResponse(false, "Only $username may upload their own profile photo"))
      return
    }

    var photoBytes: ByteArray? = null
    var oversized = false
    var override = false
    try {
      call.receiveMultipart().forEachPart { part ->
        when {
          part is PartData.FileItem && part.name == "photo" -> {
            val bytes = part.readBoundedBytes(MAX_UPLOAD_BYTES)
            if (bytes == null) oversized = true else photoBytes = bytes
          }
          part is PartData.FormItem && part.name == "override" -> override = part.value == "true"
        }
        part.release()
      }
    } catch (e: Exception) {
      log.warn("Failed to read profile photo upload for $username: ${e.message}")
      call.respond(HttpStatusCode.BadRequest, PhotoResponse(false, "Invalid upload"))
      return
    }

    if (oversized) {
      call.respond(
          HttpStatusCode.PayloadTooLarge,
          PhotoResponse(false, "Photo must be $MAX_UPLOAD_BYTES bytes or smaller"))
      return
    }
    val bytes = photoBytes
    if (bytes == null) {
      call.respond(HttpStatusCode.BadRequest, PhotoResponse(false, "No photo provided"))
      return
    }

    val result = profilePhotoClient.upload(username, bytes, override, session.sessionId)
    if (result.photoUrl != null) {
      call.respond(HttpStatusCode.OK, PhotoResponse(true, "Profile photo updated", result.photoUrl))
    } else {
      call.respond(
          HttpStatusCode.fromValue(result.statusCode),
          PhotoResponse(false, result.errorMessage ?: "Failed to upload photo"))
    }
  }

  private suspend fun handleRemove(
      call: ApplicationCall,
      username: String,
      currentUsername: String?
  ) {
    val isSelf = currentUsername == username
    if (!isSelf && !authz.can(Permission.MANAGE_MEMBERS.name)) {
      call.respond(
          HttpStatusCode.Forbidden, PhotoResponse(false, "Not allowed to remove this photo"))
      return
    }
    val actor =
        currentUsername
            ?: return call.respond(
                HttpStatusCode.BadRequest, PhotoResponse(false, "No acting user"))

    val removed = profilePhotoClient.remove(username, actor, session.sessionId)
    if (removed) {
      call.respond(HttpStatusCode.OK, PhotoResponse(true, "Profile photo removed"))
    } else {
      call.respond(
          HttpStatusCode.InternalServerError, PhotoResponse(false, "Failed to remove photo"))
    }
  }

  /** Reads a file part's bytes, returning null the moment [maxBytes] is exceeded. */
  private suspend fun PartData.FileItem.readBoundedBytes(maxBytes: Int): ByteArray? {
    val channel = provider()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    while (true) {
      val read = channel.readAvailable(buffer)
      if (read == -1) break
      output.write(buffer, 0, read)
      if (output.size() > maxBytes) return null
    }
    return output.toByteArray()
  }

  private fun isSameOrigin(call: ApplicationCall): Boolean =
      isSameOriginHeader(
          call.request.header("Host"),
          call.request.header("Origin") ?: call.request.header("Referer"))
}

/**
 * True if [originOrReferer]'s host matches [host] (a request's `Host` header), or if
 * [originOrReferer] is absent entirely (some legitimate same-site requests, e.g. from very old
 * browsers, don't send one). False if [host] itself is missing, or [originOrReferer] doesn't parse
 * as a URI. Pulled out as a pure function so the origin-check policy is unit-testable without
 * standing up a full request.
 */
@Suppress("ReturnCount")
fun isSameOriginHeader(host: String?, originOrReferer: String?): Boolean {
  if (host == null) return false
  if (originOrReferer == null) return true
  return runCatching { URI(originOrReferer).host == host.substringBefore(":") }.getOrDefault(false)
}
