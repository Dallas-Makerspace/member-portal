package org.dallasmakerspace.discourse

import java.util.concurrent.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.dallasmakerspace.core.LoggerFactory

/**
 * Service for handling Discourse avatar template fetching. Provides caching and rate limiting to
 * prevent excessive API calls. Returns raw avatar templates that should be processed by the UI
 * layer.
 */
@Singleton
class DiscourseAvatarService
@Inject
constructor(private val discourseApiClient: IDiscourseApiClient, loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  // Cache to store last fetch times for usernames to prevent excessive API calls
  private val lastFetchTimes = ConcurrentHashMap<String, Instant>()

  // Default cache duration - avatars won't be refreshed more often than this
  private val defaultCacheDuration = 30.minutes

  /**
   * Refreshes the avatar template for a given username. Implements caching to prevent excessive API
   * calls.
   *
   * @param username The Discourse username to fetch avatar template for
   * @param forceRefresh If true, bypasses cache and forces a fresh API call
   * @return The raw avatar template from Discourse API or null if unable to fetch
   */
  suspend fun refreshAvatarUrl(username: String, forceRefresh: Boolean = false): String? {
    log.debug("Refreshing avatar template for username: $username (forceRefresh: $forceRefresh)")

    // Check cache unless force refresh is requested
    if (!forceRefresh && shouldSkipRefresh(username)) {
      log.debug("Skipping avatar refresh for $username due to recent fetch")
      return null
    }

    return try {
      val userProfile = discourseApiClient.getUserProfile(username)
      val avatarTemplate = userProfile.user.avatarTemplate

      // Update cache with current timestamp
      lastFetchTimes[username] = Clock.System.now()

      log.debug("Successfully refreshed avatar template for $username: $avatarTemplate")
      avatarTemplate
    } catch (e: DiscourseUserNotFoundException) {
      log.info("Skipping avatar refresh for $username — user not found in Discourse")
      null
    } catch (e: DiscourseApiException) {
      log.warn("Failed to refresh avatar template for $username: ${e.message}")
      null
    } catch (e: Exception) {
      log.error("Unexpected error refreshing avatar template for $username", e)
      null
    }
  }

  /**
   * Checks if we should skip refreshing the avatar for a given username based on the last fetch
   * time and cache duration.
   *
   * @param username The username to check
   * @return true if refresh should be skipped, false otherwise
   */
  private fun shouldSkipRefresh(username: String): Boolean {
    val lastFetch = lastFetchTimes[username] ?: return false
    val now = Clock.System.now()
    val timeSinceLastFetch = now - lastFetch

    return timeSinceLastFetch < defaultCacheDuration
  }

  /**
   * Clears the cache for a specific username, forcing the next refresh to make an API call.
   *
   * @param username The username to clear from cache
   */
  fun clearCache(username: String) {
    lastFetchTimes.remove(username)
    log.debug("Cleared avatar cache for username: $username")
  }

  /** Clears all cached avatar fetch times. */
  fun clearAllCache() {
    val cacheSize = lastFetchTimes.size
    lastFetchTimes.clear()
    log.info("Cleared all avatar cache entries (count: $cacheSize)")
  }

  /**
   * Gets the number of usernames currently in the cache.
   *
   * @return The number of cached usernames
   */
  fun getCacheSize(): Int = lastFetchTimes.size
}
