package org.dallasmakerspace.config.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.ConfigValueType
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status

@Serializable data class FeatureFlagStatus(val enabled: Boolean)

/**
 * Runtime check for [ConfigValueType.FeatureFlagType] keys, e.g. "is the follow feature on for
 * user X" - separate from the admin-only config routes since callers here are just reading a
 * yes/no for a specific member (including their AD group membership), not managing config.
 */
fun Route.featureFlagRoutes(featureFlagService: FeatureFlagService) {
  authenticate(ApiKeyAuthProvider.X_API_KEY) {
    authorize(Permission.MANAGE_MEMBERS) {
      get("/feature-flags/{key}") {
        val key =
            call.parameters["key"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest, ApiResponse(Status.ERROR, "Key is required", null))
        val username =
            call.request.queryParameters["username"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse(Status.ERROR, "username query param is required", null))

        val configKey = ConfigRegistry.ALL.find { it.key == key }
        if (configKey == null || configKey.type !is ConfigValueType.FeatureFlagType) {
          return@get call.respond(
              HttpStatusCode.NotFound, ApiResponse(Status.ERROR, "Feature flag not found: $key", null))
        }

        @Suppress("UNCHECKED_CAST")
        val enabled =
            featureFlagService.isEnabledForUsername(
                configKey as ConfigKey<FeatureFlagValue>, username)
        call.respond(
            ApiResponse(Status.SUCCESS, "Feature flag $key for $username", FeatureFlagStatus(enabled)))
      }
    }
  }
}
