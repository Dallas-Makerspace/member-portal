package org.dallasmakerspace.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*

class AuthorizationPlugin(private val requiredPermissions: Set<Permission>) {

  class Configuration {
    internal val requiredPermissions = mutableSetOf<Permission>()

    fun permission(p: Permission) {
      requiredPermissions.add(p)
    }

    fun permissions(vararg p: Permission) {
      requiredPermissions.addAll(p)
    }
  }

  companion object Plugin : BaseRouteScopedPlugin<Configuration, AuthorizationPlugin> {

    override val key = AttributeKey<AuthorizationPlugin>("Authorization")

    override fun install(
        pipeline: ApplicationCallPipeline,
        configure: Configuration.() -> Unit,
    ): AuthorizationPlugin {
      val config = Configuration().apply(configure)
      val plugin = AuthorizationPlugin(config.requiredPermissions.toSet())

      pipeline.intercept(ApplicationCallPipeline.Call) {
        val principal = call.principal<ApiKeyAuthProvider.ApiKeyPrincipal>()

        if (principal == null) {
          call.respond(HttpStatusCode.Unauthorized, "Authentication required")
          finish()
          return@intercept
        }

        val hasPermission = plugin.requiredPermissions.any { it in principal.permissions }

        if (!hasPermission) {
          call.respond(
              HttpStatusCode.Forbidden,
              "Client '${principal.client}' lacks required permissions: " +
                  plugin.requiredPermissions.joinToString(", "),
          )
          finish()
          return@intercept
        }
      }

      return plugin
    }
  }
}

fun Route.authorize(vararg permissions: Permission, build: Route.() -> Unit): Route {
  val authorizedRoute =
      createChild(
          object : RouteSelector() {
            override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
                RouteSelectorEvaluation.Constant
          })

  authorizedRoute.install(AuthorizationPlugin) { permissions(*permissions) }
  authorizedRoute.apply(build)
  return authorizedRoute
}
