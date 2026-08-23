package org.dallasmakerspace.server.discord

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import java.net.URLEncoder
import javax.inject.Inject
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DmsHttpEngine
import org.dallasmakerspace.server.common.logging.LoggerFactory

/**
 * Provides Discord OAuth2 functionality for account linking. Handles authorization URL generation,
 * token exchange, and user info retrieval.
 */
class DiscordOAuthProvider
@Inject
constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  private fun getHttpClient() =
      HttpClient(DmsHttpEngine) { install(ContentNegotiation) { jackson {} } }

  private val clientId: String
    get() = appConfig.requireStringProperty("app.discord.client-id")

  private val clientSecret: String
    get() = appConfig.requireStringProperty("app.discord.client-secret")

  private val redirectUrl: String
    get() = appConfig.requireStringProperty("app.discord.redirect-url")

  /**
   * Generates the Discord OAuth2 authorization URL.
   *
   * @param state Random state value for CSRF protection
   * @return The full Discord authorization URL
   */
  fun getDiscordAuthorizationUrl(state: String): String {
    val encodedRedirectUrl = withContextBlocking { URLEncoder.encode(redirectUrl, "UTF-8") }
    return "https://discord.com/api/oauth2/authorize" +
        "?client_id=$clientId" +
        "&redirect_uri=$encodedRedirectUrl" +
        "&response_type=code" +
        "&scope=identify" +
        "&state=$state"
  }

  private fun withContextBlocking(block: () -> String): String {
    return block()
  }

  /**
   * Exchanges an authorization code for an access token.
   *
   * @param code The authorization code received from Discord callback
   * @return The access token
   */
  suspend fun exchangeCodeForToken(code: String): String {
    val response =
        getHttpClient().use { client ->
          client.post("https://discord.com/api/oauth2/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                FormDataContent(
                    Parameters.build {
                      append("client_id", clientId)
                      append("client_secret", clientSecret)
                      append("grant_type", "authorization_code")
                      append("code", code)
                      append("redirect_uri", redirectUrl)
                    }))
          }
        }

    if (!response.status.isSuccess()) {
      log.error("Failed to exchange code for token: ${response.status}")
      throw DiscordException("Failed to exchange code for token")
    }

    val tokenResponse = response.body<Map<String, Any?>>()
    return tokenResponse["access_token"] as? String
        ?: throw DiscordException("No access token in response")
  }

  /**
   * Fetches user information from Discord using the access token.
   *
   * @param accessToken The Discord access token
   * @return A map containing user info (id, username, avatar, etc.)
   */
  suspend fun fetchUserInfo(accessToken: String): DiscordUserInfo {
    val response =
        getHttpClient().use { client ->
          client.get("https://discord.com/api/users/@me") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
          }
        }

    if (!response.status.isSuccess()) {
      log.error("Failed to fetch Discord user info: ${response.status}")
      throw DiscordException("Failed to fetch Discord user info")
    }

    val userInfo = response.body<Map<String, Any?>>()
    val userId = userInfo["id"] as? String ?: throw DiscordException("No user ID in response")
    val username =
        userInfo["username"] as? String ?: throw DiscordException("No username in response")
    val avatar = userInfo["avatar"] as? String

    // Construct avatar URL if avatar hash is present
    val avatarUrl =
        if (avatar != null) {
          "https://cdn.discordapp.com/avatars/$userId/$avatar.png"
        } else {
          null
        }

    return DiscordUserInfo(userId, username, avatarUrl)
  }
}

/** Data class representing Discord user information. */
data class DiscordUserInfo(val userId: String, val username: String, val avatarUrl: String?)
