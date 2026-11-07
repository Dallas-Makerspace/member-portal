package org.dallasmakerspace.agenttools.activity

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class PresenceTest {

  @Test
  fun `tool use outranks a door entry`() {
    val ranked =
        Presence.rank(
            sessions = listOf(session("18:00", "18:30", "jdoe")),
            swipes = listOf(swipe("17:00", "asmith"), swipe("17:55", "jdoe")))

    assertEquals(listOf("jdoe", "asmith"), ranked.map { it.username })
  }

  @Test
  fun `how a session ended does not change the ranking`() {
    val ranked =
        Presence.rank(
            sessions =
                listOf(
                    session("18:00", "18:30", "asmith", endedBy = "MANUAL_BADGE_OUT"),
                    session("19:00", "19:30", "jdoe", endedBy = IDLE_TIMEOUT)),
            swipes = emptyList())

    assertEquals(listOf("asmith", "jdoe"), ranked.map { it.username })
  }

  @Test
  fun `ties go to whoever arrived first`() {
    val ranked = Presence.rank(emptyList(), listOf(swipe("19:00", "asmith"), swipe("17:00", "jdoe")))

    assertEquals(listOf("jdoe", "asmith"), ranked.map { it.username })
  }

  @Test
  fun `one person's swipes and sessions merge, ignoring username case`() {
    val ranked =
        Presence.rank(
            sessions = listOf(session("18:00", "18:30", "JDoe")),
            swipes = listOf(swipe("17:55", "jdoe"), swipe("19:00", "jdoe")))

    val person = ranked.single()
    assertEquals(1, person.sessions.size)
    assertEquals(2, person.entries.size)
  }

  @Test
  fun `denied attempts are listed but add no score`() {
    val ranked =
        Presence.rank(
            sessions = listOf(session("18:00", null, "asmith", endedBy = null, authorized = false)),
            swipes = listOf(swipe("17:00", "jdoe", granted = false)))

    assertEquals(listOf(0, 0), ranked.map { it.score })
    assertEquals(1, ranked.first { it.username == "jdoe" }.denied.size)
    assertEquals(1, ranked.first { it.username == "asmith" }.deniedAtTools.size)
  }

  @Test
  fun `unlinked badges are told apart by their last four digits`() {
    val ranked =
        Presence.rank(
            emptyList(),
            listOf(swipe("17:00", null, tail = "1111"), swipe("17:10", null, tail = "2222"), swipe("17:20", null, tail = "1111")))

    assertEquals(listOf("1111", "2222"), ranked.map { it.badgeTail })
    assertNull(ranked.first().username)
    assertEquals(2, ranked.first().entries.size)
  }
}
