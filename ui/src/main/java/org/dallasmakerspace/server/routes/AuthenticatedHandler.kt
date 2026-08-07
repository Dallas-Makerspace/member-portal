package org.dallasmakerspace.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import org.dallasmakerspace.server.auth.Authz
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.Role
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.HttpException
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.plugins.UserSession

/**
 * Base class for handlers that require authentication. Assumes the route is wrapped in
 * authenticate("auth_session") in Routing.kt. Provides common authenticated request context
 * (session, userInfo, authorization).
 */
abstract class AuthenticatedHandler(
    protected val loggerFactory: LoggerFactory,
    private val userInfoProvider: UserInfoProvider,
) : IRouteHandler {
  companion object {
    val IS_INFRA_USER_KEY = AttributeKey<Boolean>("isInfraUser")
  }

  protected lateinit var session: UserSession
  protected lateinit var userInfo: Map<String, Any>
  protected lateinit var authz: Authz

  final override suspend fun handle(call: ApplicationCall) {
    val log = loggerFactory.create(javaClass)
    val t0 = System.nanoTime()
    fun ms() = (System.nanoTime() - t0) / 1_000_000

    session = call.sessions.get<UserSession>() ?: throw AuthException("No session found")
    val sessionLookupMs = ms()

    try {
      userInfo = userInfoProvider.getUserInfo(session.accessToken!!)
    } catch (e: HttpException) {
      if (e.message?.contains("401") == true) {
        log.warn("Access token expired or invalid, redirecting to login")
        call.sessions.clear<UserSession>()
        val currentUri = call.request.uri
        val encodedUri = java.net.URLEncoder.encode(currentUri, "UTF-8")
        call.respondRedirect("${RouteFactory.Paths.LOGIN.path}?redirectUrl=$encodedUri")
        return
      }
      throw e
    }
    val userInfoMs = ms()

    val userGroups = (userInfo["groups"] as? List<*>) ?: emptyList<String>()
    val permissions = Role.fromKeycloakGroups(userGroups).flatMap { it.permissions }.toSet()
    authz = Authz(permissions)

    val isInfraUser = authz.can(Permission.MANAGE_CONFIG.name)
    call.attributes.put(IS_INFRA_USER_KEY, isInfraUser)
    val authzMs = ms()

    log.info(
        "AuthenticatedHandler timings for ${call.request.uri}: " +
            "sessionLookup=${sessionLookupMs}ms, getUserInfo=${userInfoMs - sessionLookupMs}ms, " +
            "authz=${authzMs - userInfoMs}ms, total-before-handler=${authzMs}ms")

    handleAuthenticated(call)
  }

  /** Responds with 403 Forbidden if the authenticated user lacks the given permission. */
  protected suspend fun ApplicationCall.require(permission: Permission): Boolean {
    if (!authz.can(permission.name)) {
      respond(HttpStatusCode.Forbidden)
      return false
    }
    return true
  }

  /** Handle the authenticated request. Session, userInfo, and authz are already populated. */
  protected abstract suspend fun handleAuthenticated(call: ApplicationCall)
}
