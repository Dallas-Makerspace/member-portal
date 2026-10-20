package org.dallasmakerspace.server.discord

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import javax.inject.Inject
import kotlin.random.Random
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.routes.AuthenticatedHandler

private const val STATE_RANGE_START = 1000000
private const val STATE_RANGE_END = 9999999

/**
 * Initiates the Discord OAuth2 account linking flow. Generates a random state for CSRF protection
 * and redirects to Discord authorization.
 */
class LinkDiscordHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val discordStateCache: DiscordStateCache,
    private val discordOAuthProvider: DiscordOAuthProvider
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val username =
        userInfo["preferred_username"] as? String
            ?: throw AuthException("No username found in user info")

    // If the user came from the settings page with a pending preference (e.g. chose Discord before
    // linking), remember it so the callback can apply it after a successful link.
    // Any other start clears a stale one left by an abandoned link attempt.
    val pending = if (call.request.queryParameters["pref"] == "DISCORD") "DISCORD" else null
    if (pending != session.pendingCommunicationPreference) {
      call.sessions.set(session.copy(pendingCommunicationPreference = pending))
    }

    // Generate random state for CSRF protection
    val randomState = Random.nextInt(STATE_RANGE_START, STATE_RANGE_END).toString()
    discordStateCache.setState(username, randomState)

    // Redirect to Discord authorization URL
    val discordAuthUrl = discordOAuthProvider.getDiscordAuthorizationUrl(randomState)
    call.respondRedirect(discordAuthUrl, permanent = false)
  }
}
