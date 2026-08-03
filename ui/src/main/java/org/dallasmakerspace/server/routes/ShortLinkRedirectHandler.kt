package org.dallasmakerspace.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import io.ktor.server.thymeleaf.*
import java.net.URLEncoder
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.HttpException
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.memberservice.ShortLinkResult
import org.dallasmakerspace.server.plugins.UserSession

private val BOT_USER_AGENT_MARKERS = listOf("Discordbot", "Discourse Forum Onebox")

private fun isPreviewBot(userAgent: String?): Boolean {
  if (userAgent == null) return false
  return BOT_USER_AGENT_MARKERS.any { userAgent.contains(it, ignoreCase = true) }
}

/**
 * Handles /go/{path...} short link resolution. Auth is optional at the Ktor routing level (see
 * Routing.kt) so that Discord/Discourse link-preview crawlers - which can't log in - can be shown
 * an OG-tag preview instead of a login redirect. This handler enforces login itself for every other
 * request, matching what AuthenticatedHandler would have done.
 */
class ShortLinkRedirectHandler(
    private val loggerFactory: LoggerFactory,
    private val userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
    private val appConfig: AppConfig,
) : IRouteHandler {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handle(call: ApplicationCall) {
    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
    val userAgent = call.request.headers[HttpHeaders.UserAgent]

    if (isPreviewBot(userAgent)) {
      handleBotPreview(call, path)
      return
    }

    val session = call.sessions.get<UserSession>()
    if (session == null) {
      redirectToLogin(call)
      return
    }

    val userInfo =
        try {
          userInfoProvider.getUserInfo(session.accessToken!!)
        } catch (e: HttpException) {
          if (e.message?.contains("401") == true) {
            log.warn("Access token expired or invalid, redirecting to login")
            call.sessions.clear<UserSession>()
            redirectToLogin(call)
            return
          }
          throw e
        }
    val username = userInfo["preferred_username"] as? String

    handleHumanRedirect(call, path, session.sessionId, username)
  }

  private suspend fun redirectToLogin(call: ApplicationCall) {
    val currentUri = call.request.uri
    val encodedUri = URLEncoder.encode(currentUri, "UTF-8")
    val loginPath =
        if (appConfig.isDevelopmentMode()) "/dev-login" else RouteFactory.Paths.LOGIN.path
    call.respondRedirect("$loginPath?redirectUrl=$encodedUri", permanent = false)
  }

  private suspend fun handleHumanRedirect(
      call: ApplicationCall,
      path: String,
      sessionId: String?,
      username: String?
  ) {
    log.info("Resolving short link: $path")

    when (val result = memberServiceClient.resolveShortLink(path, sessionId, username)) {
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
              ))
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

  private suspend fun handleBotPreview(call: ApplicationCall, path: String) {
    log.info("Serving link preview to bot for: $path")

    when (val result =
        memberServiceClient.resolveShortLink(
            path, sessionId = null, username = null, isPreview = true)) {
      is ShortLinkResult.Redirect -> {
        val baseUrl = appConfig.requireStringProperty("app.base-url")
        call.respond(
            ThymeleafContent(
                "short-link-preview",
                mapOf(
                    "shortUrl" to "https://dallas.ms/$path",
                    "ogDescription" to (result.description ?: "Dallas Makerspace Short Link"),
                    "ogImage" to "$baseUrl/static/icons/icon-512x512.png",
                ),
            ))
      }
      is ShortLinkResult.NotFound -> {
        call.respondText("Short link not found: $path", status = HttpStatusCode.NotFound)
      }
      is ShortLinkResult.Error -> {
        call.respondText(result.message, status = HttpStatusCode.InternalServerError)
      }
    }
  }
}
