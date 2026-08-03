package org.dallasmakerspace.server.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.models.EventSummary

private val CHICAGO_ZONE = ZoneId.of("America/Chicago")
private val EVENT_INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val EVENT_OUTPUT_FORMATTER = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' h:mm a")
private val FALLBACK_KEYWORDS = listOf("woodshop", "ceramics")
private const val ORGANIZER_EVENT_CAP = 2

class SuggestedEventsHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) = coroutineScope {
    try {
      val sessionUsername =
          userInfo["preferred_username"] as? String
              ?: run {
                call.respond(emptyResponse())
                return@coroutineScope
              }

      // Infra members can pass ?username= to inspect suggestions for any member.
      // Guard against blank value (e.g. empty ?username= param) — fall back to session user.
      val targetUsername =
          if (authz.can(Permission.MANAGE_MEMBERS.name))
              call.request.queryParameters["username"]?.takeIf { it.isNotBlank() }
                  ?: sessionUsername
          else sessionUsername

      // ── Cache check ──────────────────────────────────────────────────────
      val cached = SuggestionsCache.getUser(targetUsername)
      if (cached != null) {
        log.debug("Suggested events cache hit for: $targetUsername")
        call.respond(withCacheFlag(cached, hit = true))
        return@coroutineScope
      }

      // ── Compute fresh ────────────────────────────────────────────────────

      // Resolve the target member's AD group names (normalized: trim leading '/', lowercase)
      // used to de-prioritize events that fulfill a prerequisite the member already satisfies.
      val targetGroups: Set<String> =
          if (targetUsername == sessionUsername) {
            ((userInfo["groups"] as? List<*>) ?: emptyList<Any>())
                .filterIsInstance<String>()
                .map { it.trimStart('/').lowercase() }
                .toSet()
          } else {
            // Infra ?username= inspection: fetch the inspected member's groups so
            // de-prioritization reflects them, not the infra user.
            try {
              memberService
                  .getMember(targetUsername, session.sessionId)
                  .groups
                  .map { it.name.lowercase() }
                  .toSet()
            } catch (e: Exception) {
              log.debug("Could not fetch groups for de-prioritization: $targetUsername")
              emptySet()
            }
          }

      // Fetch attendance history signals in parallel
      val namesDeferred = async {
        memberService.getAttendedEventNames(targetUsername, session.sessionId)
      }
      val organizersDeferred = async {
        memberService.getAttendedOrganizers(targetUsername, session.sessionId)
      }

      val attendedNames = namesDeferred.await()
      val topOrganizers = organizersDeferred.await()

      // New member: no history at all — use shared default cache, then woodshop/ceramics
      if (attendedNames.isEmpty() && topOrganizers.isEmpty()) {
        val defaultCached = SuggestionsCache.getDefault()
        if (defaultCached != null) {
          log.debug("Suggested events default cache hit for new member: $targetUsername")
          call.respond(withCacheFlag(defaultCached, hit = true))
          return@coroutineScope
        }

        val events = memberService.getUpcomingEventsByKeywords(FALLBACK_KEYWORDS, session.sessionId)
        val finalEvents =
            events
                .sortedWith(
                    compareBy(
                        { if (alreadySatisfies(it, targetGroups)) 1 else 0 }, { it.eventStart }))
                .take(4)
        val deprioritizedCount = finalEvents.count { alreadySatisfies(it, targetGroups) }
        val organizerMembers = fetchOrganizerProfiles(finalEvents, session.sessionId)
        val response =
            buildResponse(
                events = finalEvents,
                organizerIds = emptySet(),
                fallbackUsed = true,
                organizerMembers = organizerMembers,
                debug =
                    mapOf(
                        "organizers" to emptyList<String>(),
                        "keywords" to emptyList<String>(),
                        "fallbackUsed" to true,
                        "deprioritized" to deprioritizedCount,
                        "reason" to
                            "No attendance history — showing default woodshop/ceramics events",
                        "cached" to false,
                    ))
        SuggestionsCache.putDefault(response)
        call.respond(response)
        return@coroutineScope
      }

      // Extract topic keywords from attended event names (words >2 chars, i.e. length >= 3)
      val keywords =
          attendedNames
              .flatMap { name ->
                name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
              }
              .distinct()
              .take(5)

      // Fetch organizer-affinity and keyword-topic events in parallel.
      // Organizer results appear first in the merged list — that is their higher weight.
      val organizerEventsDeferred = async {
        if (topOrganizers.isNotEmpty())
            memberService.getUpcomingEventsByOrganizers(topOrganizers, session.sessionId)
        else emptyList()
      }
      val keywordEventsDeferred = async {
        if (keywords.isNotEmpty())
            memberService.getUpcomingEventsByKeywords(keywords, session.sessionId)
        else emptyList()
      }

      val organizerEvents = organizerEventsDeferred.await()
      val keywordEvents = keywordEventsDeferred.await()

      // Merge with dedup: at most ORGANIZER_EVENT_CAP organizer-affinity events (higher weight),
      // keyword events fill the remaining slots up to 4 total.
      val seenIds = mutableSetOf<Int>()
      val cappedOrganizerEvents =
          organizerEvents.take(ORGANIZER_EVENT_CAP).filter { seenIds.add(it.id) }
      val keywordFill = keywordEvents.filter { seenIds.add(it.id) }
      val merged = cappedOrganizerEvents + keywordFill
      val organizerIds = cappedOrganizerEvents.map { it.id }.toSet()

      // Fallback if both signals returned nothing
      val (finalEvents, fallbackUsed) =
          if (merged.isEmpty())
              Pair(
                  memberService.getUpcomingEventsByKeywords(FALLBACK_KEYWORDS, session.sessionId),
                  true)
          else Pair(merged, false)

      val eventsForResponse =
          finalEvents
              .sortedWith(
                  compareBy(
                      { if (alreadySatisfies(it, targetGroups)) 1 else 0 }, { it.eventStart }))
              .take(4)
      val deprioritizedCount = eventsForResponse.count { alreadySatisfies(it, targetGroups) }
      val organizerMembers = fetchOrganizerProfiles(eventsForResponse, session.sessionId)

      val response =
          buildResponse(
              events = eventsForResponse,
              organizerIds = organizerIds,
              fallbackUsed = fallbackUsed,
              organizerMembers = organizerMembers,
              debug =
                  mapOf(
                      "organizers" to topOrganizers,
                      "keywords" to keywords,
                      "fallbackUsed" to fallbackUsed,
                      "deprioritized" to deprioritizedCount,
                      "organizerEventCount" to organizerEvents.size,
                      "keywordEventCount" to keywordEvents.size,
                      "reason" to
                          if (fallbackUsed)
                              "Organizer and keyword signals returned no results — using woodshop/ceramics fallback"
                          else
                              "Merged ${organizerEvents.size} organizer-affinity + ${keywordEvents.size} keyword-topic events, deduped to ${merged.size}",
                      "cached" to false,
                  ))
      SuggestionsCache.putUser(targetUsername, response)
      call.respond(response)
    } catch (e: Exception) {
      log.warn("Failed to get suggested events", e)
      call.respond(emptyResponse())
    }
  }

  /**
   * Returns true when [event] fulfills a prerequisite whose AD group is already held by the member
   * with the given [groups] set (normalized: no leading slash, lowercase). Used to de-prioritize
   * events the member has no reason to attend for the first time.
   */
  private fun alreadySatisfies(event: EventSummary, groups: Set<String>): Boolean {
    val prereqGroup = event.fulfillsPrerequisiteGroup ?: return false
    return prereqGroup.lowercase() in groups
  }

  /**
   * Fetches member profiles for the unique organizer usernames present in [events]. Checks the
   * organizer profile cache first to avoid redundant HTTP calls. Failures for individual organizers
   * are silently ignored.
   */
  private suspend fun fetchOrganizerProfiles(
      events: List<EventSummary>,
      sessionId: String?
  ): Map<String, SuggestionsCache.OrganizerProfile> {
    val usernames = events.mapNotNull { it.organizerUsername }.distinct()
    if (usernames.isEmpty()) return emptyMap()
    return coroutineScope {
      usernames
          .map { username ->
            async {
              // Check organizer profile cache first
              val cached = SuggestionsCache.getOrganizer(username)
              if (cached != null) return@async username to cached

              try {
                val member = memberService.getMember(username, sessionId)
                val rawUrl = member.avatarUrl?.takeIf { it.isNotEmpty() }
                val avatarUrl =
                    rawUrl?.let { url ->
                      when {
                        url.startsWith("https://") -> url
                        url.startsWith("//") -> "https:$url"
                        else -> "https://talk.dallasmakerspace.org$url"
                      }.replace("{size}", "44")
                    } ?: ""
                val profile =
                    SuggestionsCache.OrganizerProfile(
                        displayName = member.displayName,
                        avatarUrl = avatarUrl,
                    )
                SuggestionsCache.putOrganizer(username, profile)
                username to profile
              } catch (e: Exception) {
                log.debug("Could not fetch organizer profile for: $username")
                null
              }
            }
          }
          .mapNotNull { it.await() }
          .toMap()
    }
  }

  /** Returns a copy of [response] with `debug.cached` set to [hit]. */
  private fun withCacheFlag(response: Map<String, Any>, hit: Boolean): Map<String, Any> {
    @Suppress("UNCHECKED_CAST")
    val debug = (response["debug"] as? Map<String, Any> ?: emptyMap()).toMutableMap()
    debug["cached"] = hit
    return mapOf(
        "events" to ((response["events"] as? List<*> ?: emptyList<Any>()).toList()),
        "debug" to debug,
    )
  }

  private fun buildResponse(
      events: List<EventSummary>,
      organizerIds: Set<Int>,
      fallbackUsed: Boolean,
      organizerMembers: Map<String, SuggestionsCache.OrganizerProfile>,
      debug: Map<String, Any>
  ): Map<String, Any> {
    val formattedEvents =
        events.map { event ->
          val source =
              when {
                fallbackUsed -> "fallback"
                event.id in organizerIds -> "organizer"
                else -> "keyword"
              }
          val organizerProfile = event.organizerUsername?.let { organizerMembers[it] }
          val avatarUrl = organizerProfile?.avatarUrl ?: ""
          mapOf(
              "id" to event.id,
              "name" to event.name,
              "eventStart" to formatEventDate(event.eventStart),
              "organizer" to
                  mapOf(
                      "username" to (event.organizerUsername ?: ""),
                      "displayName" to
                          (organizerProfile?.displayName ?: event.organizerUsername ?: ""),
                      "avatarUrl" to avatarUrl,
                  ),
              "source" to source,
              "url" to "https://calendar.dallasmakerspace.org/events/view/${event.id}",
          )
        }
    return mapOf("events" to formattedEvents, "debug" to debug)
  }

  private fun emptyResponse(): Map<String, Any> =
      mapOf("events" to emptyList<Any>(), "debug" to emptyMap<String, Any>())

  private fun formatEventDate(eventStart: String): String {
    return try {
      val cleanedEventStart = eventStart.substringBefore(".")
      val dateTime = LocalDateTime.parse(cleanedEventStart, EVENT_INPUT_FORMATTER)
      val zonedDateTime = dateTime.atZone(CHICAGO_ZONE)
      zonedDateTime.format(EVENT_OUTPUT_FORMATTER)
    } catch (e: Exception) {
      log.warn("Failed to parse event date: $eventStart", e)
      eventStart
    }
  }
}
