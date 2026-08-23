package org.dallasmakerspace.server.linkedin

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
 * Provides LinkedIn OAuth2 functionality for account linking. Handles authorization URL generation,
 * token exchange, and user info retrieval using OpenID Connect.
 */
class LinkedInOAuthProvider
@Inject
constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  private fun getHttpClient() =
      HttpClient(DmsHttpEngine) { install(ContentNegotiation) { jackson {} } }

  private val clientId: String
    get() = appConfig.requireStringProperty("app.linkedin.client-id")

  private val clientSecret: String
    get() = appConfig.requireStringProperty("app.linkedin.client-secret")

  private val redirectUrl: String
    get() = appConfig.requireStringProperty("app.linkedin.redirect-url")

  /**
   * Generates the LinkedIn OAuth2 authorization URL.
   *
   * @param state Random state value for CSRF protection
   * @return The full LinkedIn authorization URL
   */
  fun getLinkedInAuthorizationUrl(state: String): String {
    val encodedRedirectUrl = URLEncoder.encode(redirectUrl, "UTF-8")
    return "https://www.linkedin.com/oauth/v2/authorization" +
        "?response_type=code" +
        "&client_id=$clientId" +
        "&redirect_uri=$encodedRedirectUrl" +
        "&state=$state" +
        "&scope=openid%20profile"
  }

  /**
   * Exchanges an authorization code for an access token.
   *
   * @param code The authorization code received from LinkedIn callback
   * @return The access token
   */
  suspend fun exchangeCodeForToken(code: String): String {
    val response =
        getHttpClient().use { client ->
          client.post("https://www.linkedin.com/oauth/v2/accessToken") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                FormDataContent(
                    Parameters.build {
                      append("grant_type", "authorization_code")
                      append("code", code)
                      append("client_id", clientId)
                      append("client_secret", clientSecret)
                      append("redirect_uri", redirectUrl)
                    }))
          }
        }

    if (!response.status.isSuccess()) {
      log.error("Failed to exchange code for token: ${response.status}")
      throw LinkedInException("Failed to exchange code for token")
    }

    val tokenResponse = response.body<Map<String, Any?>>()
    return tokenResponse["access_token"] as? String
        ?: throw LinkedInException("No access token in response")
  }

  /**
   * Fetches user information from LinkedIn using the access token. Uses the OpenID Connect userinfo
   * endpoint.
   *
   * @param accessToken The LinkedIn access token
   * @return LinkedInUserInfo containing user details
   */
  suspend fun fetchUserInfo(accessToken: String): LinkedInUserInfo {
    val response =
        getHttpClient().use { client ->
          client.get("https://api.linkedin.com/v2/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
          }
        }

    if (!response.status.isSuccess()) {
      log.error("Failed to fetch LinkedIn user info: ${response.status}")
      throw LinkedInException("Failed to fetch LinkedIn user info")
    }

    val userInfo = response.body<Map<String, Any?>>()
    val sub = userInfo["sub"] as? String ?: throw LinkedInException("No sub in response")

    // Try different name fields - LinkedIn may return name, or given_name/family_name
    val name =
        userInfo["name"] as? String
            ?: listOfNotNull(userInfo["given_name"] as? String, userInfo["family_name"] as? String)
                .joinToString(" ")
                .ifEmpty { sub }

    val picture = userInfo["picture"] as? String
    return LinkedInUserInfo(sub, name, picture)
  }
}

/** Data class representing LinkedIn user information. */
data class LinkedInUserInfo(val userId: String, val name: String, val pictureUrl: String?)
