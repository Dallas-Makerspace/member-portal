package org.dallasmakerspace.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import javax.inject.Inject
import org.dallasmakerspace.models.normalizeAvatarUrl
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.ProfilePhotoClient

private const val PROFILE_PIC_FALLBACK = "/static/images/profile-pic-not-set.svg"

/**
 * Stable, cacheable avatar URL for any member: `/avatar/@{username}`. Not flag-gated - existing
 * uploaded photos keep working even if `profile-photo.enabled` is later turned off for everyone.
 * Streams the member's uploaded photo when one exists; otherwise redirects to their Discourse
 * avatar, or the placeholder SVG if they have neither.
 */
class AvatarHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
    private val profilePhotoClient: ProfilePhotoClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username = call.parameters["preferred_username"]
    if (username.isNullOrBlank()) {
      call.respond(HttpStatusCode.BadRequest)
      return
    }

    val discourseAvatarUrl =
        try {
          memberService.getMember(username, session.sessionId).discourseAvatarUrl
        } catch (e: Exception) {
          log.warn("Failed to look up $username for avatar fallback: ${e.message}")
          null
        }
    val fallbackUrl = normalizeAvatarUrl(discourseAvatarUrl).ifEmpty { PROFILE_PIC_FALLBACK }

    profilePhotoClient.streamOrRedirect(username, fallbackUrl, call)
  }
}
