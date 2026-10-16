package org.dallasmakerspace.notifications

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.calendar.CalendarService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.db.ProfileSubscriptionsRepository

private val CHICAGO_ZONE = ZoneId.of("America/Chicago")
private const val CANDIDATE_POOL = 15
private const val TIER_ONE_LIMIT = 30
private const val TOP_ORGANIZERS = 30
private const val MAX_CACHED_MEMBERS = 2000

data class FollowSuggestionDebug(
    val date: String,
    val cached: Boolean,
    val classmateCount: Int,
    val tierOne: List<String>,
    val tierTwo: List<String>,
    val candidates: List<String>,
    val following: Int,
)

data class FollowSuggestionResult(val usernames: List<String>, val debug: FollowSuggestionDebug)

/**
 * "Members to follow" suggestions. Candidates are members followed by the member's classmates
 * (most-followed first), then top organizers in random order. The candidate pool is fixed for the
 * Chicago calendar day so it doesn't reshuffle on every page load; already-followed members are
 * dropped on every call so a new follow takes effect immediately.
 */
@Singleton
class FollowSuggestionService
internal constructor(
    private val calendarService: CalendarService,
    private val repository: ProfileSubscriptionsRepository,
    private val clock: Clock,
    loggerFactory: LoggerFactory,
) {
  @Inject
  constructor(
      calendarService: CalendarService,
      repository: ProfileSubscriptionsRepository,
      loggerFactory: LoggerFactory,
  ) : this(calendarService, repository, Clock.systemUTC(), loggerFactory)

  private val log = loggerFactory.create(javaClass)

  private class Built(
      val classmateCount: Int,
      val tierOne: List<String>,
      val tierTwo: List<String>,
      val candidates: List<String>,
  )

  private class Daily(val date: LocalDate, val built: Built)

  private val perMember = ConcurrentHashMap<String, Daily>()
  @Volatile private var organizers: Pair<LocalDate, List<String>>? = null

  suspend fun suggest(username: String, limit: Int = 6): List<String> =
      explain(username, limit).usernames

  /** Same as [suggest] plus how the list was put together, for the infra debug panel. */
  suspend fun explain(username: String, limit: Int = 6): FollowSuggestionResult {
    val today = LocalDate.now(clock.withZone(CHICAGO_ZONE))
    val following = repository.getFollowing(username).toSet()
    val cachedBuilt = perMember[username]?.takeIf { it.date == today }?.built
    val built =
        cachedBuilt
            ?: buildCandidates(username, today, following).also {
              if (it.candidates.isNotEmpty()) {
                if (perMember.size >= MAX_CACHED_MEMBERS) perMember.clear()
                perMember[username] = Daily(today, it)
              }
            }
    val result = built.candidates.filter { it !in following }.take(limit)
    log.debug(
        "Follow suggestions for $username: cached=${cachedBuilt != null} " +
            "candidates=${built.candidates} following=${following.size} result=$result")
    return FollowSuggestionResult(
        result,
        FollowSuggestionDebug(
            date = today.toString(),
            cached = cachedBuilt != null,
            classmateCount = built.classmateCount,
            tierOne = built.tierOne,
            tierTwo = built.tierTwo,
            candidates = built.candidates,
            following = following.size,
        ))
  }

  private suspend fun buildCandidates(
      username: String,
      today: LocalDate,
      followed: Set<String>,
  ): Built = coroutineScope {
    val classmates = async { calendarService.getClassmates(username) }
    val topOrganizers = async { topOrganizers(today) }
    val classmateList = classmates.await()
    val tierOne =
        if (classmateList.isEmpty()) emptyList()
        else repository.getMostFollowedBy(classmateList, TIER_ONE_LIMIT)
    val tierTwo = topOrganizers.await().shuffled(Random("$username|$today".hashCode()))
    log.debug(
        "Building follow candidates for $username: classmates=${classmateList.size} " +
            "tierOne=$tierOne tierTwo=$tierTwo following=${followed.size}")
    val candidates =
        (tierOne + tierTwo)
            .filter { it != username && it !in followed }
            .distinct()
            .take(CANDIDATE_POOL)
    Built(classmateList.size, tierOne, tierTwo, candidates)
  }

  private suspend fun topOrganizers(today: LocalDate): List<String> {
    organizers?.takeIf { it.first == today }?.let {
      return it.second
    }
    val fetched = calendarService.getTopOrganizers(TOP_ORGANIZERS)
    log.debug("Fetched ${fetched.size} top organizers for $today")
    // An empty result is usually a timeout; don't pin it for the whole day.
    if (fetched.isNotEmpty()) organizers = today to fetched
    return fetched
  }
}
