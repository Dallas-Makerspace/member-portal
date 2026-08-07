package org.dallasmakerspace.server.auth

import io.ktor.http.*
import io.ktor.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DMSHttpClient

@Singleton
class UserInfoProvider
@Inject
constructor(private val httpClient: DMSHttpClient, private val appConfig: AppConfig) {

  private data class CacheEntry(val userInfo: Map<String, Any>, val cachedAt: Instant)

  // Keyed by access token: the same token is presented on every request within a session, and its
  // groups/username don't change from one page load to the next. Without this, every single
  // authenticated request — every page, every /api/* call — hit Keycloak fresh.
  private val cache = ConcurrentHashMap<String, CacheEntry>()

  suspend fun getUserInfo(accessToken: String): Map<String, Any> {
    if (appConfig.isDevelopmentMode() && accessToken == DEV_TOKEN) {
      return mapOf(
          "preferred_username" to "user1",
          "groups" to listOf("/Infrastructure"),
          "email" to "user1@dallasmakerspace.org",
          "name" to "Dev User One",
      )
    }

    val now = Clock.System.now()
    cache[accessToken]?.let { entry ->
      if (now - entry.cachedAt < CACHE_TTL) {
        return entry.userInfo
      }
    }

    val resp =
        httpClient.get(
            appConfig.requireStringProperty("app.oidc.user-info-url"),
            StringValues.build { append(HttpHeaders.Authorization, "Bearer $accessToken") })
    cache[accessToken] = CacheEntry(resp, now)
    pruneExpired(now)
    return resp
  }

  // Lazy cleanup so the map doesn't grow unbounded across many distinct logins over a long-running
  // process.
  private fun pruneExpired(now: Instant) {
    cache.entries.removeIf { now - it.value.cachedAt >= CACHE_TTL }
  }

  companion object {
    const val DEV_TOKEN = "dev-token"
    // Short TTL: this cache backs AuthenticatedHandler's authz (what the logged-in user is allowed
    // to do), not just display data. A longer TTL (e.g. matching the 8h session cookie) would mean
    // a revoked/granted permission could take that long to take effect for an active session. 60s
    // still eliminates the vast majority of repeat Keycloak calls during a normal browsing burst.
    private val CACHE_TTL = 60.seconds
  }
}
