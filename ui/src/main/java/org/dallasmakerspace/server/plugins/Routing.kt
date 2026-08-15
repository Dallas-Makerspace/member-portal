package org.dallasmakerspace.server.plugins

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import java.io.File
import java.net.URL
import java.security.MessageDigest
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.di.AppComponentHolder
import org.dallasmakerspace.server.routes.RouteFactory

fun Application.configureRouting() {
  val appConfig = AppComponentHolder.instance.getAppConfig()

  routing {
    // Dev-only: bypass Keycloak by injecting a fake session
    if (appConfig.isDevelopmentMode()) {
      get("/dev-login") {
        val redirectUrl = call.request.queryParameters["redirectUrl"] ?: "/"
        call.sessions.set(
            UserSession(
                accessToken = UserInfoProvider.DEV_TOKEN,
                sessionId = "devmode",
                userId = "user1",
            ))
        call.respondRedirect(redirectUrl, permanent = false)
      }
    }

    authenticate("DMS") {
      get(RouteFactory.Paths.LOGIN.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.OIDC_CALLBACK.path) {
        RouteFactory.getHandler(RouteFactory.Paths.OIDC_CALLBACK.path)?.handleBase(call)
      }
    }

    // Protected routes using session authentication
    authenticate("auth_session") {
      get(RouteFactory.Paths.INDEX.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.PROFILE.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.PROFILE_DEBUG_INFO.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.PROFILE_TOTAL_ACTIVE_TIME.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.PROFILE_FEATURED_PROJECTS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.PROFILEME.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.GROUPS.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.SEARCH_PRELOAD.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.REPORT.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.SHORT_LINKS.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.SHORT_LINKS_ADMIN.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.SHORT_LINK_DETAILS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.BACKEND_API.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      post(RouteFactory.Paths.BACKEND_API.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      patch(RouteFactory.Paths.BACKEND_API.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      delete(RouteFactory.Paths.BACKEND_API.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.DISCOURSE_LINK.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.DISCOURSE_UNLINK.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.DISCOURSE_CALLBACK.path) {
        RouteFactory.getHandler(RouteFactory.Paths.DISCOURSE_CALLBACK.path)?.handleBase(call)
      }
      get(RouteFactory.Paths.DISCORD_LINK.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.DISCORD_UNLINK.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.DISCORD_CALLBACK.path) {
        RouteFactory.getHandler(RouteFactory.Paths.DISCORD_CALLBACK.path)?.handleBase(call)
      }
      get(RouteFactory.Paths.LINKEDIN_LINK.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.LINKEDIN_UNLINK.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.LINKEDIN_CALLBACK.path) {
        RouteFactory.getHandler(RouteFactory.Paths.LINKEDIN_CALLBACK.path)?.handleBase(call)
      }
      get(RouteFactory.Paths.REGISTER_VOTING.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.UNREGISTER_VOTING.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.PROFILE_SETTINGS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      post(RouteFactory.Paths.PROFILE_SETTINGS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.PROFILE_FOLLOWS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.COMMITTEES.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.COMMITTEE_DETAIL.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.INTERLOCK_TOOLS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.INTERLOCK_CABINETS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      post(RouteFactory.Paths.ACTION_TRACK.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.SUGGESTED_EVENTS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.SETTINGS_BANNER.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.ASK_AI.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      post(RouteFactory.Paths.ASK_AI.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get("/ask-ai/stream") {
        RouteFactory.getHandler(RouteFactory.Paths.ASK_AI.path)?.handleBase(call)
      }
      get("/ask-ai/q/{slug}") {
        RouteFactory.getHandler(RouteFactory.Paths.ASK_AI.path)?.handleBase(call)
      }
      post("/ask-ai/feedback") {
        RouteFactory.getHandler(RouteFactory.Paths.ASK_AI.path)?.handleBase(call)
      }
      get(RouteFactory.Paths.CONFIG_ADMIN.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.REMOTE_ACCESS.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.REMOTE_ACCESS_REFRESH.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.REMOTE_ACCESS_CONNECT.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      post(RouteFactory.Paths.REMOTE_ACCESS_DISCONNECT.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      post(RouteFactory.Paths.GROUP_MEMBERS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      delete(RouteFactory.Paths.GROUP_MEMBER_DETAIL.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.GROUP_SETTINGS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      post(RouteFactory.Paths.GROUP_SETTINGS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.CAMERAS.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.CAMERAS_API.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      post(RouteFactory.Paths.CAMERAS_API.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      get(RouteFactory.Paths.PERSONAL_BIN_SCANNER.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.SCANNER_STATUS_API.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
      get(RouteFactory.Paths.NOTIFICATIONS.path) { RouteFactory.getHandler(call)?.handleBase(call) }
      post(RouteFactory.Paths.NOTIFICATIONS.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
    }

    // Short link redirect - auth is optional at the Ktor level so unauthenticated bot traffic
    // (Discord/Discourse link-preview crawlers) can be served an OG-tag preview instead of being
    // bounced to login; ShortLinkRedirectHandler itself enforces login for non-bot requests.
    authenticate("auth_session", optional = true) {
      get(RouteFactory.Paths.SHORT_LINK_REDIRECT.path) {
        RouteFactory.getHandler(call)?.handleBase(call)
      }
    }

    get(RouteFactory.Paths.PING.path) { RouteFactory.getHandler(call)?.handleBase(call) }

    post(RouteFactory.Paths.SMARTWAIVER_WEBHOOK.path) {
      RouteFactory.getHandler(call)?.handleBase(call)
    }

    post(RouteFactory.Paths.SPARKPOST_WEBHOOK.path) {
      RouteFactory.getHandler(call)?.handleBase(call)
    }

    // PWA routes - no authentication required
    get(RouteFactory.Paths.MANIFEST.path) { RouteFactory.getHandler(call)?.handleBase(call) }
    get(RouteFactory.Paths.OFFLINE.path) { RouteFactory.getHandler(call)?.handleBase(call) }

    // Service worker - must be served from root, no caching
    get("/sw.js") {
      val resource = Application::class.java.classLoader.getResource("static/sw.js")
      if (resource != null) {
        call.response.headers.append(
            HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
        call.response.headers.append(HttpHeaders.ContentType, "application/javascript")
        call.respondText(resource.readText(), ContentType.Application.JavaScript)
      } else {
        call.respond(HttpStatusCode.NotFound)
      }
    }

    // Block all bots from crawling the site
    get("robots.txt") {
      call.respond(
          TextContent(
              """
              User-agent: *
              Disallow: /
              """
                  .trimIndent(),
              ContentType.Text.Plain,
              HttpStatusCode.OK,
          ),
      )
    }

    staticResources(RouteFactory.Paths.STATIC.path, "static") {
      cacheControl {
        listOf(CacheControl.MaxAge(maxAgeSeconds = 300)) // 5 min
      }
      enableAutoHeadResponse()
      modify { url, call ->
        val file = url.toFile()
        if (file.exists()) {
          val eTag = calculateETag(file)
          call.response.headers.append(HttpHeaders.ETag, eTag)
        }
      }
    }
  }
}

private fun URL.toFile(): File {
  return File(this.file)
}

private fun calculateETag(file: File): String {
  val lastModified = file.lastModified()
  val size = file.length()
  val hash =
      MessageDigest.getInstance("MD5").digest("$lastModified$size".toByteArray()).fold("") {
          str,
          acc ->
        str + "%02x".format(acc)
      }
  return "\"$hash\""
}
