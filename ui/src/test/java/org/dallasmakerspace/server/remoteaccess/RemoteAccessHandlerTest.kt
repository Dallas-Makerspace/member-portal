package org.dallasmakerspace.server.remoteaccess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteAccessHandlerTest {

  private fun machine(
      sessions: List<Map<String, Any?>> = emptyList(),
      canConnect: Boolean = true,
      maxConnectionsPerUser: Int? = null,
  ): Map<String, Any?> =
      mapOf(
          "connectionId" to "5",
          "displayName" to "PRW Jump Server",
          "protocol" to "rdp",
          "status" to "AVAILABLE",
          "sessions" to sessions,
          "maxConnectionsPerUser" to maxConnectionsPerUser,
          "canConnect" to canConnect,
      )

  private fun session(username: String, epochMs: Long, activeConnectionId: String = "active-1") =
      mapOf(
          "activeConnectionId" to activeConnectionId,
          "username" to username,
          "sessionStartEpochMs" to epochMs,
      )

  @Test
  fun `no sessions produces an empty session list and no occupant block`() {
    val viewModel = buildMachineViewModel(machine(), currentUsername = "jdoe")

    @Suppress("UNCHECKED_CAST")
    val sessions = viewModel["sessions"] as List<Map<String, Any?>>
    assertTrue(sessions.isEmpty())
    assertTrue(viewModel["canConnect"] as Boolean)
  }

  @Test
  fun `multiple sessions all appear in the view model`() {
    val raw =
        listOf(session("jdoe", 1_000L, "active-1"), session("asmith", 2_000L, "active-2"))

    val viewModel = buildMachineViewModel(machine(sessions = raw), currentUsername = "jdoe")

    @Suppress("UNCHECKED_CAST")
    val sessions = viewModel["sessions"] as List<Map<String, Any?>>
    assertEquals(2, sessions.size)
    assertEquals(setOf("jdoe", "asmith"), sessions.map { it["username"] }.toSet())
  }

  @Test
  fun `epoch millis convert to a Chicago-local ISO display string`() {
    // 2026-01-15T12:00:00Z -> 2026-01-15T06:00:00 America/Chicago (CST, UTC-6)
    val epochMs = 1768478400000L

    val display = epochMsToChicagoDisplay(epochMs)

    assertEquals("2026-01-15T06:00:00", display)
  }

  @Test
  fun `user already at their personal session cap cannot connect even with global capacity`() {
    val raw = listOf(session("jdoe", 1_000L))

    val viewModel =
        buildMachineViewModel(
            machine(sessions = raw, canConnect = true, maxConnectionsPerUser = 1),
            currentUsername = "jdoe",
        )

    assertFalse(viewModel["canConnect"] as Boolean)
  }

  @Test
  fun `a different user is unaffected by another user's personal session cap`() {
    val raw = listOf(session("jdoe", 1_000L))

    val viewModel =
        buildMachineViewModel(
            machine(sessions = raw, canConnect = true, maxConnectionsPerUser = 1),
            currentUsername = "asmith",
        )

    assertTrue(viewModel["canConnect"] as Boolean)
  }

  @Test
  fun `global canConnect false always wins regardless of per-user cap`() {
    val viewModel =
        buildMachineViewModel(
            machine(canConnect = false, maxConnectionsPerUser = 1), currentUsername = "jdoe")

    assertFalse(viewModel["canConnect"] as Boolean)
  }
}
