package org.dallasmakerspace.server.routes

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-tier in-memory cache for suggested events responses.
 * - **Per-user** (`putUser` / `getUser`): keyed by AD username. Short TTL (30 min), capped at 500
 *   entries. Entries are evicted by TTL on read; expired entries are bulk-removed when the map
 *   reaches capacity before a new write.
 * - **Default** (`putDefault` / `getDefault`): single shared entry for new-member fallback results
 *   (woodshop / ceramics). Longer TTL (2 h) — the fallback events change rarely.
 * - **Organizer** (`putOrganizer` / `getOrganizer`): short-lived profiles for event organizers to
 *   avoid repeated HTTP calls per cache miss. TTL 15 min, capped at 1000 entries.
 *
 * The object is a JVM singleton so it survives across handler instances, which are created fresh
 * per request by `DaggerRoutesComponent.create()`.
 */
internal object SuggestionsCache {

  // Shorter TTLs so that changes in event status (e.g. cancelled) and capacity (full events)
  // are reflected quickly — an event can fill up or be cancelled at any time.
  private val USER_TTL: Duration = Duration.ofMinutes(5)
  private val DEFAULT_TTL: Duration = Duration.ofMinutes(15)
  private const val MAX_USER_ENTRIES = 500

  private data class Entry(val data: Map<String, Any>, val expiresAt: Instant)

  private val userEntries = ConcurrentHashMap<String, Entry>()

  @Volatile private var defaultEntry: Entry? = null

  // ── Organizer profile cache ────────────────────────────────────────────────

  data class OrganizerProfile(val displayName: String?, val avatarUrl: String)

  private data class OrganizerEntry(val profile: OrganizerProfile, val expiresAt: Instant)

  private val ORGANIZER_TTL: Duration = Duration.ofMinutes(15)
  private const val MAX_ORGANIZER_ENTRIES = 1000
  private val organizerCache = ConcurrentHashMap<String, OrganizerEntry>()

  // ── Per-user ──────────────────────────────────────────────────────────────

  fun getUser(username: String): Map<String, Any>? {
    val entry = userEntries[username] ?: return null
    return if (Instant.now().isBefore(entry.expiresAt)) {
      entry.data
    } else {
      userEntries.remove(username)
      null
    }
  }

  fun putUser(username: String, data: Map<String, Any>) {
    // Size check is approximate under concurrency; MAX_USER_ENTRIES is a soft cap.
    if (userEntries.size >= MAX_USER_ENTRIES) evictExpiredUsers()
    userEntries[username] = Entry(data, Instant.now().plus(USER_TTL))
  }

  // ── Default (new-member fallback) ─────────────────────────────────────────

  fun getDefault(): Map<String, Any>? {
    val entry = defaultEntry ?: return null
    return if (Instant.now().isBefore(entry.expiresAt)) {
      entry.data
    } else {
      defaultEntry = null
      null
    }
  }

  fun putDefault(data: Map<String, Any>) {
    defaultEntry = Entry(data, Instant.now().plus(DEFAULT_TTL))
  }

  // ── Housekeeping ──────────────────────────────────────────────────────────

  private fun evictExpiredUsers() {
    val now = Instant.now()
    userEntries.entries.removeIf { it.value.expiresAt.isBefore(now) }
  }

  // ── Organizer profile ──────────────────────────────────────────────────────

  fun getOrganizer(username: String): OrganizerProfile? {
    val entry = organizerCache[username] ?: return null
    return if (Instant.now().isBefore(entry.expiresAt)) entry.profile
    else {
      organizerCache.remove(username)
      null
    }
  }

  fun putOrganizer(username: String, profile: OrganizerProfile) {
    if (organizerCache.size >= MAX_ORGANIZER_ENTRIES) {
      val now = Instant.now()
      organizerCache.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }
    organizerCache[username] = OrganizerEntry(profile, Instant.now().plus(ORGANIZER_TTL))
  }
}
