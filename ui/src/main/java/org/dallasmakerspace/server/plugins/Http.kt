package org.dallasmakerspace.server.plugins

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.webjars.*
import kotlin.collections.listOf
import kotlin.collections.set
import org.dallasmakerspace.server.auth.OAuthSettings
import org.dallasmakerspace.server.auth.getOAuthSettings
import org.dallasmakerspace.server.di.DaggerAppComponent
import org.dallasmakerspace.server.routes.RouteFactory

fun Application.configureHttp() {
  val appConfig = DaggerAppComponent.create().getAppConfig()
  val settings = getOAuthSettings(appConfig)
  val httpClient = getHttpClient()

  install(Compression) {
    gzip {
      condition { request.headers[HttpHeaders.Accept]?.contains("text/event-stream") != true }
    }
    deflate {
      condition { request.headers[HttpHeaders.Accept]?.contains("text/event-stream") != true }
    }
  }
  install(ForwardedHeaders)
  install(XForwardedHeaders)
  install(Webjars) { path = "assets" }
  install(Authentication) {
    oauth("DMS") {
      urlProvider = { "${settings.baseUrl}/oidc-callback" }
      providerLookup = { getOAuthServerSettings(settings) }
      client = httpClient
    }
    session<UserSession>("auth_session") {
      validate { session ->
        if (session.accessToken != null) {
          session
        } else {
          null
        }
      }
      challenge {
        val currentUri = call.request.uri
        val encodedUri = java.net.URLEncoder.encode(currentUri, "UTF-8")
        val loginPath =
            if (appConfig.isDevelopmentMode()) "/dev-login" else RouteFactory.Paths.LOGIN.path
        call.respondRedirect("$loginPath?redirectUrl=$encodedUri", permanent = false)
      }
    }
  }
  install(ContentNegotiation) { gson { setPrettyPrinting() } }
}

private fun getHttpClient(): HttpClient {
  return HttpClient(CIO) {
    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { gson { jackson {} } }
  }
}

private fun getOAuthServerSettings(
    settings: OAuthSettings
): OAuthServerSettings.OAuth2ServerSettings {
  return OAuthServerSettings.OAuth2ServerSettings(
      name = "DMS",
      authorizeUrl = settings.authorizeUrl,
      accessTokenUrl = settings.accessTokenUrl,
      requestMethod = HttpMethod.Post,
      clientId = settings.clientId,
      clientSecret = settings.clientSecret,
      defaultScopes = listOf("openid", "profile", "email"),
      onStateCreated = { call, state ->
        // saves new state with redirect url value
        call.request.queryParameters["redirectUrl"]?.let { RouteFactory.redirects[state] = it }
      })
}
