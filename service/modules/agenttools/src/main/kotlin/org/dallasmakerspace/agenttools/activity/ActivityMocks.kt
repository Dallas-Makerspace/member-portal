@file:Suppress("MagicNumber", "MaxLineLength")

package org.dallasmakerspace.agenttools.activity

import java.time.Duration
import java.time.Instant
import org.dallasmakerspace.doorcontroller.DoorSwipe
import org.dallasmakerspace.models.Tool

/** Canned swipes for mock mode: two fake members and one unlinked badge near the window start. */
class MockSwipeSearch : SwipeSearch {
  override suspend fun search(
      doors: Collection<String>,
      from: Instant,
      to: Instant,
      usernames: Set<String>?,
      deniedOnly: Boolean,
      limit: Int,
  ): List<DoorSwipe> {
    val door = doors.firstOrNull() ?: return emptyList()
    return listOf(
            DoorSwipe(from + Duration.ofMinutes(20), door, true, "jdoe", "0001"),
            DoorSwipe(from + Duration.ofMinutes(45), door, true, "asmith", "0002"),
            DoorSwipe(from + Duration.ofMinutes(50), door, false, null, "9999"),
        )
        .filter { it.time < to }
        .filter { usernames == null || it.username in usernames }
        .filter { !deniedOnly || !it.granted }
        .take(limit)
  }

  override suspend fun latestEvent(doors: Collection<String>): Instant? = Instant.now()
}

/** Canned sessions for mock mode: one that ended by idle timeout and one badged out. */
class MockSessionSearch : SessionSearch {
  override suspend fun search(tools: List<Tool>, from: Instant, to: Instant): List<ToolSession> {
    val tool = tools.firstOrNull() ?: return emptyList()
    return listOf(
            ToolSession(tool, from + Duration.ofMinutes(25), from + Duration.ofMinutes(70), "jdoe", "0001", true, "IDLE_TIMEOUT"),
            ToolSession(tool, from + Duration.ofMinutes(80), from + Duration.ofMinutes(95), "asmith", "0002", true, "MANUAL_BADGE_OUT"),
        )
        .filter { it.start < to }
  }
}
