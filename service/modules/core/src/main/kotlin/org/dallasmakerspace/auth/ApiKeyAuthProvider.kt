package org.dallasmakerspace.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.dallasmakerspace.core.AppConfig
import org.slf4j.LoggerFactory

class ApiKeyAuthProvider internal constructor(appConfig: AppConfig, authConfig: Configuration) :
    AuthenticationProvider(authConfig) {

  private val log = LoggerFactory.getLogger(ApiKeyAuthProvider::class.java)

  data class ApiKeyPrincipal(
      val key: String,
      val client: String,
      val permissions: Set<Permission>
  ) : Principal

  private val apiClientHeaderName: String =
      requireNotNull(authConfig.apiClientHeaderName) { "authConfig.apiClientHeaderName" }
  private val apiKeyHeaderName: String =
      requireNotNull(authConfig.apiKeyHeaderName) { "authConfig.apiKeyHeaderName" }

  private data class ApiClientConfig(
      val name: String,
      val key: String,
      val permissions: Set<Permission>
  )

  private val apiClients: List<ApiClientConfig> = run {
    val clientCount =
        try {
          appConfig.requireStringProperty("app.api.client-count").toInt()
        } catch (e: Exception) {
          log.warn("API client count not configured (app.api.client-count), defaulting to 0")
          0
        }

    val loadedClients =
        (0 until clientCount).mapNotNull { i ->
          val name = System.getenv("API_CLIENT_${i}_NAME")?.trim()
          val key = System.getenv("API_CLIENT_${i}_KEY")?.trim()
          val roleName = System.getenv("API_CLIENT_${i}_ROLE")?.trim()

          when {
            name.isNullOrBlank() -> {
              log.warn("API_CLIENT_${i}_NAME is not set or empty, skipping client at index $i")
              null
            }
            key.isNullOrBlank() -> {
              log.warn("API_CLIENT_${i}_KEY is not set or empty for client '$name', skipping")
              null
            }
            else -> {
              val role = roleName?.let { Role.fromName(it) }
              val permissions = role?.permissions ?: emptySet()

              if (permissions.isEmpty()) {
                log.warn("No role configured for client '$name' (API_CLIENT_${i}_ROLE)")
              } else {
                log.info(
                    "Loaded client '$name' with role '${role?.name}' (${permissions.size} permission(s))")
              }

              ApiClientConfig(name, key, permissions)
            }
          }
        }

    loadedClients.ifEmpty {
      log.warn("No API clients loaded from environment variables, using fallback configuration")
      try {
        val fallbackName = appConfig.requireStringProperty("app.api.fallback.name").trim()
        val fallbackKey = appConfig.requireStringProperty("app.api.fallback.key").trim()
        val fallbackRoleName = appConfig.requireStringProperty("app.api.fallback.role").trim()
        val fallbackRole = Role.fromName(fallbackRoleName)
        val fallbackPermissions = fallbackRole?.permissions ?: emptySet()

        log.info(
            "Loaded fallback client '$fallbackName' with role '$fallbackRoleName' " +
                "(${fallbackPermissions.size} permission(s))")
        listOf(ApiClientConfig(fallbackName, fallbackKey, fallbackPermissions))
      } catch (e: Exception) {
        log.error("Failed to load fallback client configuration: ${e.message}")
        emptyList()
      }
    }
  }

  private val mapClientsToApiKeys = apiClients.associate { it.name to it.key }
  private val mapClientsToPermissions = apiClients.associate { it.name to it.permissions }

  private val challengeFunction = authConfig.challengeFunction

  private val authScheme = authConfig.authScheme

  override suspend fun onAuthenticate(context: AuthenticationContext) {
    val apiKey = context.call.request.header(apiKeyHeaderName)
    val apiClient = context.call.request.header(apiClientHeaderName)

    val principal =
        apiKey?.let {
          if (apiClient == null) {
            log.warn(
                "Authentication failed: Missing client header. " +
                    "Key prefix: ${maskApiKey(it)}, Client: <not provided>")
            return@let null
          } else {
            if (mapClientsToApiKeys[apiClient] != apiKey) {
              log.warn(
                  "Authentication failed: Invalid credentials. " +
                      "Key prefix: ${maskApiKey(it)}, Client: '$apiClient'")
              return@let null
            }
            val permissions = mapClientsToPermissions[apiClient] ?: emptySet()
            log.info(
                "Authentication successful for client: '$apiClient' with ${permissions.size} permission(s)")
            ApiKeyPrincipal(it, apiClient, permissions)
          }
        }

    val cause =
        when {
          apiKey == null && apiClient == null -> {
            log.warn("Authentication failed: Missing both API key and client headers")
            AuthenticationFailedCause.NoCredentials
          }
          apiKey == null -> {
            log.warn(
                "Authentication failed: Missing API key header. Client: '${apiClient ?: "<none>"}'")
            AuthenticationFailedCause.NoCredentials
          }
          apiClient == null -> {
            AuthenticationFailedCause.NoCredentials
          }
          principal == null -> {
            AuthenticationFailedCause.InvalidCredentials
          }
          else -> null
        }
    if (cause != null) {
      context.challenge(authScheme, cause) { challenge, call ->
        challengeFunction(call)
        challenge.complete()
      }
    }
    if (principal != null) {
      context.principal(principal)
    }
  }

  private fun maskApiKey(key: String): String {
    return if (key.length <= 4) "***" else "${key.take(4)}***"
  }

  class Configuration internal constructor(name: String?) : Config(name) {

    internal lateinit var authenticationFunction: ApiKeyAuthenticationFunction

    internal var challengeFunction: ApiKeyAuthChallengeFunction = { call ->
      call.respond(HttpStatusCode.Unauthorized)
    }

    var authScheme: String = "apiKey"

    var apiKeyHeaderName: String = X_API_KEY

    var apiClientHeaderName: String = X_API_CLIENT

    fun validate(body: suspend ApplicationCall.(String) -> Principal?) {
      authenticationFunction = body
    }

    fun challenge(body: ApiKeyAuthChallengeFunction) {
      challengeFunction = body
    }
  }

  companion object {
    const val X_API_KEY = "X-Api-Key"
    const val X_API_CLIENT = "X-Api-Client"
  }
}

fun AuthenticationConfig.apiKey(
    appConfig: AppConfig,
    name: String? = null,
    configure: ApiKeyAuthProvider.Configuration.() -> Unit,
) {
  val provider =
      ApiKeyAuthProvider(appConfig, ApiKeyAuthProvider.Configuration(name).apply(configure))
  register(provider)
}

typealias ApiKeyAuthenticationFunction = suspend ApplicationCall.(String) -> Principal?

typealias ApiKeyAuthChallengeFunction = suspend (ApplicationCall) -> Unit
