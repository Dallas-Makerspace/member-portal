package org.dallasmakerspace.server.discourse

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import javax.inject.Inject
import kotlin.random.Random
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.routes.AuthenticatedHandler

private const val NONCE_RANGE_START = 1000000
private const val NONCE_RANGE_END = 9999999

class LinkDiscourseHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val discourseNonceCache: DiscourseNonceCache,
    private val discourseLinkProvider: DiscourseSSOProvider
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  override suspend fun handleAuthenticated(call: ApplicationCall) {

    val username =
        userInfo["preferred_username"] as? String
            ?: throw AuthException("No username found in user info")

    // If the user came from the settings page with a pending preference (e.g. chose Talk before
    // linking), remember it so the callback can apply it after a successful link.
    if (call.request.queryParameters["pref"] == "DISCOURSE") {
      call.sessions.set(session.copy(pendingCommunicationPreference = "DISCOURSE"))
    }

    val randomNonce = Random.nextInt(NONCE_RANGE_START, NONCE_RANGE_END).toString()
    discourseNonceCache.setNonce(username, randomNonce)
    val discourseSSOConnectUrl = discourseLinkProvider.getDiscourseSSOConnectUrl(randomNonce)
    call.respondRedirect(discourseSSOConnectUrl, permanent = false)
  }
}
