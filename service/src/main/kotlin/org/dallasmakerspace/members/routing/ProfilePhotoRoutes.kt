package org.dallasmakerspace.members.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.core.appLogger
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.members.ProfilePhotoRateLimitedException
import org.dallasmakerspace.members.ProfilePhotoService
import org.dallasmakerspace.members.ProfilePhotoValidationException
import org.dallasmakerspace.plugins.ApiResponse
import org.dallasmakerspace.plugins.Status

private const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024
private const val CACHE_CONTROL_ONE_YEAR = "private, max-age=31536000, immutable"
private val log = appLogger("ProfilePhotoRoutes")

/**
 * Upload/remove/serve a member's profile photo. `{username}` is the subject; the acting member
 * comes from the `X-Username` header, matching the convention used by
 * [org.dallasmakerspace.notifications.routing.profileSubscriptionRoutes]. Upload is gated behind
 * [ConfigRegistry.PROFILE_PHOTO_ENABLED] for the acting member as defense-in-depth, matching the
 * follow feature - the UI already hides the camera button when disabled, but this stops a direct
 * API call from bypassing that. Removal is allowed for the subject themselves or an Infrastructure
 * admin (moderation); serving a photo isn't flag-gated so an existing photo doesn't disappear if
 * the flag is later turned off.
 */
fun Route.profilePhotoRoutes(
    service: ProfilePhotoService,
    featureFlagService: FeatureFlagService,
    memberService: MemberService,
) {
  authenticate(ApiKeyAuthProvider.X_API_KEY) {
    authorize(Permission.MANAGE_MEMBERS) {
      put("/members/{username}/profile-photo") { handleUpload(call, service, featureFlagService) }
      delete("/members/{username}/profile-photo") { handleRemove(call, service, memberService) }
      get("/members/{username}/profile-photo") { handleGet(call, service) }
    }
  }
}

@Suppress("ReturnCount")
private suspend fun handleUpload(
    call: ApplicationCall,
    service: ProfilePhotoService,
    featureFlagService: FeatureFlagService,
) {
  val username = call.parameters["username"] ?: return call.respondBadRequest()
  val actor = call.actorUsername() ?: return call.respondMissingActor()
  if (actor != username) {
    return call.respond(
        HttpStatusCode.Forbidden,
        ApiResponse(Status.ERROR, "Only $username may upload their own profile photo", null))
  }
  if (!featureFlagService.isEnabledForUsername(ConfigRegistry.PROFILE_PHOTO_ENABLED, actor)) {
    return call.respond(
        HttpStatusCode.Forbidden,
        ApiResponse(Status.ERROR, "Profile photo upload is not enabled for $actor", null))
  }
  val contentLength = call.request.contentLength()
  if (contentLength != null && contentLength > MAX_UPLOAD_BYTES) {
    return call.respond(
        HttpStatusCode.PayloadTooLarge,
        ApiResponse(Status.ERROR, "Photo must be $MAX_UPLOAD_BYTES bytes or smaller", null))
  }
  if (call.request.headers["X-Validation-Override"] == "true") {
    log.info("$actor saved a profile photo that failed client-side validation (override)")
  }

  // Content-Length is a client-supplied hint (absent for chunked bodies, or falsifiable) - cap the
  // actual read too, so an oversized body can't be buffered into memory before ProfilePhotoService
  // gets a chance to reject it.
  val bytes = call.receiveChannel().toByteArrayOrNull(MAX_UPLOAD_BYTES)
  if (bytes == null) {
    return call.respond(
        HttpStatusCode.PayloadTooLarge,
        ApiResponse(Status.ERROR, "Photo must be $MAX_UPLOAD_BYTES bytes or smaller", null))
  }
  try {
    val photoUrl = service.save(username, bytes)
    call.respond(
        ApiResponse(Status.SUCCESS, "Profile photo updated", mapOf("photoUrl" to photoUrl)))
  } catch (e: ProfilePhotoValidationException) {
    call.respond(
        HttpStatusCode.UnsupportedMediaType,
        ApiResponse(Status.ERROR, e.message ?: "Invalid photo", null))
  } catch (e: ProfilePhotoRateLimitedException) {
    call.respond(
        HttpStatusCode.TooManyRequests,
        ApiResponse(Status.ERROR, e.message ?: "Too many uploads today", null))
  }
}

@Suppress("ReturnCount")
private suspend fun handleRemove(
    call: ApplicationCall,
    service: ProfilePhotoService,
    memberService: MemberService,
) {
  val username = call.parameters["username"] ?: return call.respondBadRequest()
  val actor = call.actorUsername() ?: return call.respondMissingActor()
  val actorIsInfra = withContext(Dispatchers.IO) { memberService.isUserInInfraGroup(actor) }
  if (actor != username && !actorIsInfra) {
    return call.respond(
        HttpStatusCode.Forbidden,
        ApiResponse(Status.ERROR, "Not allowed to remove $username's profile photo", null))
  }
  if (actor != username) {
    log.info("Infrastructure admin $actor removed $username's profile photo")
  }
  service.remove(username)
  call.respond(ApiResponse(Status.SUCCESS, "Profile photo removed", null))
}

private suspend fun handleGet(call: ApplicationCall, service: ProfilePhotoService) {
  val username = call.parameters["username"] ?: return call.respondBadRequest()
  val photo = service.get(username) ?: return call.respond(HttpStatusCode.NotFound)
  val (bytes, contentType) = photo
  call.response.header(HttpHeaders.CacheControl, CACHE_CONTROL_ONE_YEAR)
  call.respondBytes(bytes, ContentType.parse(contentType))
}

/**
 * Reads a channel into a ByteArray, stopping and returning null the moment [maxBytes] is exceeded.
 */
private suspend fun ByteReadChannel.toByteArrayOrNull(maxBytes: Int): ByteArray? {
  val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
  val output = ByteArrayOutputStream()
  while (true) {
    val read = readAvailable(buffer)
    if (read == -1) break
    output.write(buffer, 0, read)
    if (output.size() > maxBytes) return null
  }
  return output.toByteArray()
}

private fun ApplicationCall.actorUsername(): String? = request.headers["X-Username"]

private suspend fun ApplicationCall.respondBadRequest() =
    respond(HttpStatusCode.BadRequest, ApiResponse(Status.ERROR, "Username is required", null))

private suspend fun ApplicationCall.respondMissingActor() =
    respond(
        HttpStatusCode.BadRequest, ApiResponse(Status.ERROR, "X-Username header is required", null))
