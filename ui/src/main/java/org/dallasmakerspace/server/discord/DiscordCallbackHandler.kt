package org.dallasmakerspace.server.discord

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.plugins.UserSession
import org.dallasmakerspace.server.routes.IRouteHandler

/**
 * Handles the OAuth2 callback from Discord after a user has authorized account linking.
 * - Validates the state parameter for CSRF protection
 * - Exchanges the authorization code for an access token
 * - Fetches user info from Discord API
 * - Links the Discord account to the DMS member profile
 */
class DiscordCallbackHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val discordOAuthProvider: DiscordOAuthProvider,
    private val memberService: MemberService,
    private val userInfoProvider: UserInfoProvider,
    private val discordStateCache: DiscordStateCache
) : IRouteHandler {

  private val log = loggerFactory.create(javaClass)

  override suspend fun handle(call: ApplicationCall) {
    // Get code and state from query parameters
    val code = call.request.queryParameters["code"]
    val state = call.request.queryParameters["state"]
    val error = call.request.queryParameters["error"]

    // Handle error response from Discord
    if (error != null) {
      val errorDescription = call.request.queryParameters["error_description"] ?: error
      log.error("Discord authorization failed: $errorDescription")
      throw DiscordException("Discord authorization failed: $errorDescription")
    }

    // Validate required parameters
    if (code == null) {
      throw DiscordException("Missing authorization code")
    }
    if (state == null) {
      throw DiscordException("Missing state parameter")
    }

    // Get session and validate user
    val session = call.sessions.get<UserSession>()
    val accessToken = session?.accessToken ?: throw DiscordException("No session found")

    val username =
        try {
          val jsonMap = userInfoProvider.getUserInfo(accessToken)
          jsonMap["preferred_username"] as? String
              ?: throw DiscordException("No username in user info")
        } catch (e: AuthException) {
          call.sessions.clear<UserSession>()
          log.error("Failed to get user info", e)
          throw DiscordException("Failed to get user info")
        }

    // Validate state parameter for CSRF protection
    val originalState =
        discordStateCache.getState(username)
            ?: throw DiscordException("No state found in cache - session may have expired")

    if (state != originalState) {
      throw DiscordException("State mismatch - possible CSRF attack")
    }

    // Delete the state from cache
    discordStateCache.deleteState(username)

    // Exchange code for access token
    val discordAccessToken = discordOAuthProvider.exchangeCodeForToken(code)

    // Fetch Discord user info
    val discordUserInfo = discordOAuthProvider.fetchUserInfo(discordAccessToken)

    log.info(
        "Linking Discord account ${discordUserInfo.username} (${discordUserInfo.userId}) to DMS user $username")

    // Link the Discord account
    memberService.linkDiscordAccount(
        username,
        discordUserInfo.userId,
        discordUserInfo.username,
        discordUserInfo.avatarUrl,
        session.sessionId)

    val redirectPath = applyPendingPreferenceIfAny(session, username)

    // Set success flag in session
    session.isDiscordLinkSuccess = true
    call.sessions.set(session)

    // Redirect back to the settings page when a preference was pending, otherwise the profile
    call.respondRedirect(redirectPath, permanent = false)
  }

  /**
   * If the user started the link from the settings page with a pending preference, applies it now
   * that the account is linked and returns the redirect path to the settings page; otherwise
   * returns the profile path.
   */
  private suspend fun applyPendingPreferenceIfAny(session: UserSession, username: String): String {
    val pendingPreference = session.pendingCommunicationPreference
    session.pendingCommunicationPreference = null
    if (pendingPreference == "DISCORD") {
      memberService.updateMemberSettings(username, username, "DISCORD", session.sessionId)
      session.isSettingsUpdateSuccess = true
      return "/profile/@$username/settings"
    }
    return "/profile/@$username"
  }
}
