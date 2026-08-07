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
  // authenticated request — every page, every /api/* call — hit Keycloak fresh. That alone was a
  // real cost, but it also turned out to be the trigger for a much worse problem: Ktor's CIO client
  // engine visibly degrades under this exact pattern (external, multi-IP host via Cloudflare DNS
  // round-robin, brand-new connection every time, never reused) - getUserInfo climbed from
  // ~100-250ms right after a deploy to 600ms+ within about a minute of real traffic, while every
  // other outbound call sharing the same HttpClient stayed fast throughout. That looks like a known
  // class of CIO engine issue (see https://github.com/ktorio/ktor/issues/1509 and
  // https://github.com/ktorio/ktor/issues/1966) rather than anything on our end, so instead of
  // patching around engine internals, we just stop needing so many calls to begin with.
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
  // process (session cookies live up to 8h — see Sessions.kt — so that's the natural upper bound
  // per token, but we still want expired entries reclaimed rather than sitting forever).
  private fun pruneExpired(now: Instant) {
    cache.entries.removeIf { now - it.value.cachedAt >= CACHE_TTL }
  }

  companion object {
    const val DEV_TOKEN = "dev-token"
    private val CACHE_TTL = 60.seconds
  }
}
