package org.dallasmakerspace.activitybot

import java.time.Instant
import org.dallasmakerspace.doorcontroller.DoorSwipe

/** Everything seen of one person in an area and window. A null [username] is an unlinked badge. */
data class Candidate(
    val username: String?,
    val badgeTail: String?,
    val sessions: List<ToolSession>,
    val entries: List<DoorSwipe>,
    val denied: List<DoorSwipe>,
    val deniedAtTools: List<ToolSession>,
) {
  val firstSeen: Instant
    get() =
        (sessions.map { it.start } + entries.map { it.time } + denied.map { it.time } +
                deniedAtTools.map { it.start })
            .min()

  /** Tool use counts most, then a door entry. */
  val score: Int
    get() = sessions.size * SESSION_POINTS + (if (entries.isNotEmpty()) ENTRY_POINTS else 0)

  private companion object {
    const val SESSION_POINTS = 3
    const val ENTRY_POINTS = 1
  }
}

const val IDLE_TIMEOUT = "IDLE_TIMEOUT"

object Presence {
  /** People seen in the area, strongest evidence first (ties: whoever arrived first). */
  fun rank(sessions: List<ToolSession>, swipes: List<DoorSwipe>): List<Candidate> {
    fun key(username: String?, badgeTail: String) = username?.lowercase() ?: "badge:$badgeTail"

    val people = linkedMapOf<String, MutableList<Any>>()
    sessions.forEach { people.getOrPut(key(it.username, it.badgeTail)) { mutableListOf() }.add(it) }
    swipes.forEach { people.getOrPut(key(it.username, it.badgeTail)) { mutableListOf() }.add(it) }

    return people.values
        .map { items ->
          val sessionItems = items.filterIsInstance<ToolSession>()
          val swipeItems = items.filterIsInstance<DoorSwipe>()
          val first = items.first()
          Candidate(
              username = (first as? ToolSession)?.username ?: (first as? DoorSwipe)?.username,
              badgeTail = (first as? ToolSession)?.badgeTail ?: (first as? DoorSwipe)?.badgeTail,
              sessions = sessionItems.filter { it.authorized },
              entries = swipeItems.filter { it.granted },
              denied = swipeItems.filter { !it.granted },
              deniedAtTools = sessionItems.filter { !it.authorized },
          )
        }
        .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.firstSeen })
  }
}
