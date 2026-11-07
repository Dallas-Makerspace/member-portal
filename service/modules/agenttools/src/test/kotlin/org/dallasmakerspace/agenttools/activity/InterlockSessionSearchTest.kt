package org.dallasmakerspace.agenttools.activity

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.dataviz.reports.interlock.EsEvent
import org.dallasmakerspace.dataviz.reports.interlock.InterlockEventsService
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class InterlockSessionSearchTest {
  private val real = InterlockEventsService(mock<IActiveDirectoryService>(), mock<AppConfig>())

  private fun event(hhmm: String, message: String, day: String = "2026-11-05") =
      EsEvent("auto-lift-1", at(hhmm, day).toString(), message)

  private fun user(badge: String, authorized: Boolean = true) =
      "User: {'id': '$badge', 'authorized': ${if (authorized) "True" else "False"}}"

  private fun search(events: List<EsEvent>, badges: Map<String, String> = emptyMap()) =
      InterlockSessionSearch(
          mock<InterlockEventsService> {
            onBlocking { fetchEventsBetween(any(), any(), any()) } doReturn events
            on { reconstructSessions(any()) } doAnswer { real.reconstructSessions(it.getArgument(0)) }
            onBlocking { fetchBadgeToUsername(any()) } doReturn badges
          })

  @Test
  fun `sessions are rebuilt with the member, last four digits and how they ended`() = blocking {
    val found =
        search(
                listOf(event("18:00", user("0012345678")), event("18:45", "Idle timeout reached")),
                mapOf("0012345678" to "jdoe"))
            .search(listOf(LIFT), at("17:00"), at("23:59"))

    val s = found.single()
    assertEquals("jdoe", s.username)
    assertEquals("5678", s.badgeTail)
    assertEquals(at("18:00"), s.start)
    assertEquals(at("18:45"), s.end)
    assertEquals("IDLE_TIMEOUT", s.endedBy)
  }

  @Test
  fun `a session that began before the window but ran into it is kept, one that ended before is not`() = blocking {
    val events =
        listOf(
            event("15:00", user("1111")),
            event("15:30", "Badged out while awaiting timeout"),
            event("16:30", user("2222")),
            event("17:30", "Idle timeout reached"))

    val found = search(events).search(listOf(LIFT), at("17:00"), at("23:59"))

    assertEquals(listOf("2222"), found.map { it.badgeTail })
  }

  @Test
  fun `a denied attempt has no member when the badge is unknown`() = blocking {
    val found = search(listOf(event("19:00", user("9999", authorized = false)))).search(listOf(LIFT), at("17:00"), at("23:59"))

    val s = found.single()
    assertEquals(false, s.authorized)
    assertNull(s.username)
  }

  @Test
  fun `no tools means no search`() = blocking {
    assertEquals(emptyList(), search(emptyList()).search(emptyList(), Instant.EPOCH, Instant.now()))
  }
}
