package org.dallasmakerspace.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Principal
import io.ktor.server.sessions.SessionTransportTransformer
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.util.hex
import kotlinx.serialization.Serializable
import org.dallasmakerspace.server.di.AppComponentHolder
import org.slf4j.LoggerFactory

private val sessionLog = LoggerFactory.getLogger("Sessions")
private const val COOKIE_WARN_BYTES = 3800

class SessionTooLargeException(val actualBytes: Int) :
    Exception(
        "Session cookie is $actualBytes bytes, exceeding the $COOKIE_WARN_BYTES byte safe limit. " +
            "The user likely has too many Keycloak group memberships.")

private class SizeCheckingTransformer(
    private val delegate: SessionTransportTransformer,
) : SessionTransportTransformer {
  override fun transformRead(transportValue: String) = delegate.transformRead(transportValue)

  override fun transformWrite(transportValue: String): String {
    val result = delegate.transformWrite(transportValue)
    if (result.length > COOKIE_WARN_BYTES) {
      sessionLog.error(
          "Session cookie is {} bytes — exceeds safe limit of {}. " +
              "User has too many Keycloak group memberships.",
          result.length,
          COOKIE_WARN_BYTES)
      throw SessionTooLargeException(result.length)
    }
    return result
  }
}

fun Application.configureSessions() {
  val appConfig = AppComponentHolder.instance.getAppConfig()
  val encryptKey = hex(appConfig.requireStringProperty("app.session.encrypt-key"))
  val signKey = hex(appConfig.requireStringProperty("app.session.sign-key"))
  install(Sessions) {
    cookie<UserSession>("user_session") {
      cookie.path = "/"
      cookie.httpOnly = true
      cookie.secure = !appConfig.isDevelopmentMode()
      cookie.extensions["SameSite"] = "Lax"
      cookie.maxAgeInSeconds = 8 * 3600
      transform(SizeCheckingTransformer(SessionTransportTransformerEncrypt(encryptKey, signKey)))
    }
  }
}

@Serializable
data class UserSession(
    var accessToken: String? = null,
    var isDiscourseLinkSuccess: Boolean = false,
    var isDiscordLinkSuccess: Boolean = false,
    var isVoterRegistrationSuccess: Boolean = false,
    var isSettingsUpdateSuccess: Boolean = false,
    var isGroupSettingsUpdateSuccess: Boolean = false,
    var pendingCommunicationPreference: String? = null,
    var sessionId: String? = null,
    var userId: String? = null,
) : Principal
