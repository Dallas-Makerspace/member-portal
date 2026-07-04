package org.dallasmakerspace.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.thymeleaf.*
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.memberservice.ShortLinkResult

class ShortLinkRedirectHandler(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
    val username = userInfo["preferred_username"] as? String
    log.info("Resolving short link: $path")

    when (val result = memberServiceClient.resolveShortLink(path, session.sessionId, username)) {
      is ShortLinkResult.Redirect -> {
        val dmsEmail = username?.let { "$it@dallasmakerspace.org" }
        val googleDestination = googleDocsDestinationOrNull(result.location, dmsEmail)
        if (googleDestination != null) {
          log.info("Showing Google Doc interstitial for: ${result.location}")
          call.respond(
              ThymeleafContent(
                  "google-doc-interstitial",
                  mapOf(
                      "destinationUrl" to googleDestination,
                      "dmsEmail" to dmsEmail,
                  ),
              )
          )
        } else {
          log.info("Redirecting to: ${result.location}")
          call.respondRedirect(result.location, permanent = false)
        }
      }
      is ShortLinkResult.NotFound -> {
        log.warn("Short link not found: $path")
        call.respondText("Short link not found: $path", status = HttpStatusCode.NotFound)
      }
      is ShortLinkResult.Error -> {
        log.error("Error resolving short link: ${result.message}")
        call.respondText(result.message, status = HttpStatusCode.InternalServerError)
      }
    }
  }
}
