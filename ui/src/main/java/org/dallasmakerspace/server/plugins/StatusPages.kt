package org.dallasmakerspace.server.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.sessions.*
import io.ktor.server.thymeleaf.ThymeleafContent
import org.dallasmakerspace.server.auth.getOAuthSettings
import org.dallasmakerspace.server.di.AppComponentHolder

fun Application.configureStatusPages() {
  val appConfig = AppComponentHolder.instance.getAppConfig()
  val settings = getOAuthSettings(appConfig)
  val ssoProfileUrl = settings.ssoProfileUrl
  install(StatusPages) {
    status(HttpStatusCode.NotFound) { call, status ->
      call.respond(
          status = status,
          ThymeleafContent("error404", mapOf("message" to "Sorry! Page was not found.")))
    }
    exception<SessionTooLargeException> { call, _ ->
      call.respond(
          status = HttpStatusCode.BadRequest,
          ThymeleafContent(
              "error5xx",
              mapOf(
                  "message" to
                      "Sign-in failed: your account has too many group memberships to fit in a session cookie. " +
                          "Please contact an administrator.",
                  "cause" to "",
                  "sessionid" to "N/A",
                  "userid" to "N/A",
                  "sso_profile_url" to ssoProfileUrl)))
    }
    exception<Throwable> { call, cause ->
      val session =
          try {
            call.sessions.get<UserSession>()
          } catch (e: Exception) {
            null
          }
      val sessionId: String = session?.sessionId ?: "NO_SESSION"
      val userId: String = session?.userId ?: "NO_USER"
      call.respond(
          status = HttpStatusCode.InternalServerError,
          ThymeleafContent(
              "error5xx",
              mapOf(
                  "message" to "There was an error processing your request. ${cause.message}.",
                  "cause" to "$cause: ${cause.cause?.message}\n ${cause.stackTraceToString()}",
                  "sessionid" to sessionId,
                  "userid" to userId,
                  "sso_profile_url" to ssoProfileUrl)))
    }
  }
}
