package org.dallasmakerspace.activitybot

import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.dataviz.reports.interlock.InterlockEventsService
import org.dallasmakerspace.doorcontroller.DoorControllerConfig
import org.dallasmakerspace.doorcontroller.DoorEventsRepository
import org.dallasmakerspace.doorcontroller.DoorMapping
import org.dallasmakerspace.doorcontroller.DoorPlace
import org.dallasmakerspace.doorcontroller.DoorSwipe
import org.dallasmakerspace.models.Tool

/** One use of an interlocked tool; [end] is null while the session never closed in the logs. */
data class ToolSession(
    val tool: Tool,
    val start: Instant,
    val end: Instant?,
    val username: String?,
    val badgeTail: String,
    val authorized: Boolean,
    /** How the session closed: IDLE_TIMEOUT, STARTUP_CURRENT, MANUAL_BADGE_OUT or LOCKED. */
    val endedBy: String?,
)

/** Read-only door swipes. */
@Suppress("LongParameterList")
interface SwipeSearch {
  suspend fun search(
      doors: Collection<String>,
      from: Instant,
      to: Instant,
      usernames: Set<String>?,
      deniedOnly: Boolean,
      limit: Int,
  ): List<DoorSwipe>

  /** The newest event recorded by the controllers of [doors], or null if none. */
  suspend fun latestEvent(doors: Collection<String>): Instant?
}

/** Read-only interlock sessions. */
interface SessionSearch {
  suspend fun search(tools: List<Tool>, from: Instant, to: Instant): List<ToolSession>
}

@Singleton
class DoorSwipeSearch
@Inject
constructor(
    private val repository: DoorEventsRepository,
    private val config: DoorControllerConfig,
) : SwipeSearch {
  override suspend fun search(
      doors: Collection<String>,
      from: Instant,
      to: Instant,
      usernames: Set<String>?,
      deniedOnly: Boolean,
      limit: Int,
  ) = repository.searchSwipes(placesOf(doors), from, to, usernames, deniedOnly, limit)

  override suspend fun latestEvent(doors: Collection<String>) =
      repository.latestEventTime(placesOf(doors))

  /** Doors that have a controller in the configuration, as serial number and door number. */
  private fun placesOf(doors: Collection<String>): List<DoorPlace> =
      DoorMapping.DOOR_NAMES.filterValues { it in doors }.mapNotNull { (key, name) ->
        config.controllers.firstOrNull { it.name == key.substringBeforeLast('+') }?.let {
          DoorPlace(name, it.serial, key.substringAfterLast('+').toInt())
        }
      }
}

/** Rebuilds sessions from the interlock logs, reading a little beyond the window so none are cut. */
@Singleton
class InterlockSessionSearch @Inject constructor(private val events: InterlockEventsService) :
    SessionSearch {
  override suspend fun search(tools: List<Tool>, from: Instant, to: Instant): List<ToolSession> {
    val byTag = tools.associateBy { it.interlockTag }
    if (byTag.isEmpty()) return emptyList()
    val sessions =
        events
            .reconstructSessions(
                events.fetchEventsBetween(byTag.keys.toList(), from - PADDING, to + PADDING))
            .mapNotNull { session ->
              val start = runCatching { Instant.parse(session.startTime) }.getOrNull() ?: return@mapNotNull null
              val end = session.shutdownTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
              if (start > to || (end ?: start) < from) return@mapNotNull null
              val tool = byTag[session.host] ?: return@mapNotNull null
              Triple(session, tool, start to end)
            }
    val usernames = events.fetchBadgeToUsername(sessions.map { it.first.badge }.toSet())
    return sessions
        .map { (session, tool, times) ->
          ToolSession(
              tool,
              times.first,
              times.second,
              usernames[session.badge],
              session.badge.takeLast(BADGE_TAIL),
              session.authorized,
              session.shutdownType)
        }
        .sortedBy { it.start }
  }

  private companion object {
    val PADDING: Duration = Duration.ofHours(12)
    const val BADGE_TAIL = 4
  }
}

/** Display names for usernames, from the directory; unknown or failed lookups just have no name. */
@Singleton
class MemberNames @Inject constructor(private val directory: IActiveDirectoryService) {
  @Suppress("TooGenericExceptionCaught", "SwallowedException")
  suspend fun of(usernames: Set<String>): Map<String, String> {
    if (usernames.isEmpty()) return emptyMap()
    return withContext(Dispatchers.IO) {
      try {
        directory.getMembersByUsernameList(usernames.toList()).mapNotNull { (username, user) ->
          user?.displayName?.let(::clean)?.takeIf { it.isNotEmpty() }?.let { username to it }
        }.toMap()
      } catch (e: Exception) {
        emptyMap()
      }
    }
  }

  private fun clean(name: String) = name.replace(Regex("[<>\\s]+"), " ").trim().take(MAX_NAME)

  private companion object {
    const val MAX_NAME = 60
  }
}
