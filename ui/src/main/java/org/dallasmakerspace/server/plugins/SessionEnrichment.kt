package org.dallasmakerspace.server.plugins

import io.ktor.server.application.*
import io.ktor.server.sessions.*
import org.dallasmakerspace.server.common.HttpException
import org.dallasmakerspace.server.di.AppComponentHolder
import org.slf4j.LoggerFactory

/**
 * Plugin that enriches the UserSession with user information after authentication. This ensures
 * userId is available early in the request pipeline for logging (MDC).
 */
val SessionEnrichmentPlugin =
    createApplicationPlugin(name = "SessionEnrichment") {
      val appComponent = AppComponentHolder.instance
      val userInfoProvider = appComponent.getUserInfoProvider()
      val log = LoggerFactory.getLogger("SessionEnrichment")

      onCall { call ->
        val session = call.sessions.get<UserSession>()

        // Only enrich if session exists, has accessToken, but userId is missing
        if (session?.accessToken != null && session.userId == null) {
          try {
            val userInfo = userInfoProvider.getUserInfo(session.accessToken!!)
            val userId = userInfo["preferred_username"] as? String

            if (userId != null) {
              log.debug("Enriching session with userId: $userId")
              call.sessions.set(session.copy(userId = userId))
            }
          } catch (e: HttpException) {
            log.warn("Failed to enrich session with user info, skipping enrichment: ${e.message}")
          } catch (e: Exception) {
            log.error("Unexpected error enriching session", e)
          }
        }
      }
    }

fun Application.configureSessionEnrichment() {
  install(SessionEnrichmentPlugin)
}
