package org.dallasmakerspace.winrm

import java.net.ServerSocket
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

/**
 * Exercises the REAL [WinRmClient] (not mocked) against a socket that accepts a TCP connection
 * but never responds, to validate that the hard client-side timeout (`Future.get`) actually
 * bounds wall-clock time - a coroutine timeout alone would not interrupt the underlying blocking
 * SOAP call (see [WinRmClient]'s class doc). Uses `application.conf`'s real dev-mode WinRM
 * defaults (port 5985), so this test briefly binds that port locally for its duration.
 */
class WinRmClientTest {

  private fun newClient(): WinRmClient {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(WinRmClient::class.java)).thenReturn(mock<Logger>())
    return WinRmClient(AppConfig(), loggerFactory)
  }

  @Test
  fun `getRdpLogonSessions returns null within the configured deadline when the target hangs`() =
      runBlocking {
        val client = newClient()

        // Bind (but never accept from) the real WinRM port so the TCP pre-flight dial succeeds -
        // the OS completes the handshake once the socket is listening, even without an explicit
        // accept() - and the subsequent SOAP call then hangs indefinitely waiting for an HTTP
        // response that never comes, exercising the Future.get timeout path specifically.
        ServerSocket(5985).use {
          val startMs = System.currentTimeMillis()
          val result = client.getRdpLogonSessions("127.0.0.1")
          val elapsedMs = System.currentTimeMillis() - startMs

          assertNull(result)
          // connect-timeout-ms (500) + call-timeout-ms (1500) from application.conf is the
          // configured budget; 15s gives headroom for JVM/classloading variance on a cold run
          // while still conclusively proving this returns in seconds, not "hangs forever" (which
          // is what this test guards against; the precise millisecond budget is exercised by
          // mocked RemoteAccessServiceTest cases instead).
          assertTrue(elapsedMs < 15_000, "Expected to return well under 15s, took ${elapsedMs}ms")
        }
      }

  // Real captured quser output (header + one row) from a live target - see the commit history for
  // the standalone proof harness this was validated against before this parser was written.
  private val quserHeader = " USERNAME              SESSIONNAME        ID  STATE   IDLE TIME  LOGON TIME"

  @Test
  fun `parseQuserOutput extracts username and logon time from a normal row`() {
    val stdout =
        "$quserHeader\n mandarlimaye          rdp-tcp#140       110  Active          3  8/17/2026 8:28 AM"

    val sessions = newClient().parseQuserOutput(stdout)

    assertEquals(1, sessions.size)
    assertEquals("mandarlimaye", sessions.single().username)
    assertTrue(sessions.single().isActive)
    val expectedEpochMs =
        ZonedDateTime.of(2026, 8, 17, 8, 28, 0, 0, ZoneId.of("America/Chicago")).toInstant().toEpochMilli()
    assertEquals(expectedEpochMs, sessions.single().logonStartEpochMs)
  }

  @Test
  fun `parseQuserOutput marks a Disc-state row as isActive=false`() {
    // A real, common scenario: the member's RDP client closed without logging off - the session
    // (and their running programs) is still alive server-side, just not currently Active. The UI
    // layer relies on this distinction to let them reconnect to themselves without being blocked
    // as "already connected" - see RemoteAccessHandler.buildMachineViewModel.
    val stdout = "$quserHeader\n mandarlimaye          rdp-tcp#140       110  Disc            3  8/17/2026 8:28 AM"

    val sessions = newClient().parseQuserOutput(stdout)

    assertFalse(sessions.single().isActive)
  }

  @Test
  fun `parseQuserOutput handles multiple concurrent sessions`() {
    val stdout =
        "$quserHeader\n" +
            " mandarlimaye          rdp-tcp#140       110  Active          3  8/17/2026 8:28 AM\n" +
            " jdoe                  rdp-tcp#141       111  Active          0  8/17/2026 9:00 AM"

    val sessions = newClient().parseQuserOutput(stdout)

    assertEquals(2, sessions.size)
    assertEquals(setOf("mandarlimaye", "jdoe"), sessions.map { it.username }.toSet())
  }

  @Test
  fun `parseQuserOutput handles a blank SESSIONNAME column (a real disconnected-session quirk)`() {
    // quser leaves SESSIONNAME empty for some disconnected sessions, shifting the remaining
    // columns left by one - the regex's backtracking (optional SESSIONNAME group) must still land
    // ID/STATE/IDLE-TIME/LOGON-TIME correctly rather than mis-consuming the numeric ID as a name.
    val stdout = "$quserHeader\n jdoe                                       2  Disc        5:23  8/10/2026 3:00 PM"

    val sessions = newClient().parseQuserOutput(stdout)

    assertEquals(1, sessions.size)
    assertEquals("jdoe", sessions.single().username)
  }

  @Test
  fun `parseQuserOutput strips the current-session ampersand marker rather than treating it as part of the username`() {
    // quser prefixes whichever row corresponds to the CALLER's own session with ">" instead of a
    // leading space.
    val stdout = "$quserHeader\n>svc_member_portal    rdp-tcp#5           1  Active          0  8/17/2026 9:00 AM"

    val sessions = newClient().parseQuserOutput(stdout)

    assertEquals("svc_member_portal", sessions.single().username)
  }

  @Test
  fun `parseQuserOutput skips an unparseable line rather than throwing`() {
    val stdout = "$quserHeader\nthis is not a real quser row at all"

    val sessions = newClient().parseQuserOutput(stdout)

    assertTrue(sessions.isEmpty())
  }

  @Test
  fun `parseQuserOutput returns an empty list when only the header is present`() {
    val sessions = newClient().parseQuserOutput(quserHeader)

    assertTrue(sessions.isEmpty())
  }

  @Test
  fun `parseQuserLogonTime parses the 12-hour AM-PM format`() {
    val epochMs = newClient().parseQuserLogonTime("8/17/2026 8:28 AM")

    val expected =
        ZonedDateTime.of(2026, 8, 17, 8, 28, 0, 0, ZoneId.of("America/Chicago")).toInstant().toEpochMilli()
    assertEquals(expected, epochMs)
  }

  @Test
  fun `parseQuserLogonTime parses the 24-hour format as a fallback`() {
    val epochMs = newClient().parseQuserLogonTime("8/17/2026 20:28")

    val expected =
        ZonedDateTime.of(2026, 8, 17, 20, 28, 0, 0, ZoneId.of("America/Chicago")).toInstant().toEpochMilli()
    assertEquals(expected, epochMs)
  }

  @Test
  fun `parseQuserLogonTime returns null, not a crash, for an unrecognized format`() {
    assertNull(newClient().parseQuserLogonTime("not a date at all"))
  }

  @Test
  fun `parseReplicationPartnerJson handles a single object (unwrapped by ConvertTo-Json for a one-row result)`() {
    val stdout =
        """
        {
          "Server": "dc1",
          "Partner": "CN=NTDS Settings,CN=DC2,CN=Servers,CN=Default-First-Site-Name,CN=Sites,CN=Configuration,DC=example,DC=internal",
          "Partition": "DC=example,DC=internal",
          "LastReplicationSuccess": "2026-09-19T10:00:00.0000000Z",
          "LastReplicationAttempt": "2026-09-19T10:00:00.0000000Z",
          "ConsecutiveReplicationFailures": 0,
          "LastReplicationResult": 0
        }
        """
            .trimIndent()

    val results = newClient().parseReplicationPartnerJson("dc1", stdout)

    assertEquals(1, results.size)
    val status = results.single()
    assertEquals("dc1", status.server)
    assertEquals("DC2", status.partner)
    assertEquals(0, status.consecutiveReplicationFailures)
    assertEquals(Instant.parse("2026-09-19T10:00:00Z"), status.lastReplicationSuccess)
  }

  @Test
  fun `parseReplicationPartnerJson handles an array (the multi-partner case)`() {
    val stdout =
        """
        [
          {
            "Server": "dc1",
            "Partner": "CN=NTDS Settings,CN=DC2,CN=Servers,CN=Default-First-Site-Name,CN=Sites,CN=Configuration,DC=example,DC=internal",
            "Partition": "DC=example,DC=internal",
            "LastReplicationSuccess": "2026-09-19T10:00:00.0000000Z",
            "LastReplicationAttempt": "2026-09-19T10:00:00.0000000Z",
            "ConsecutiveReplicationFailures": 0,
            "LastReplicationResult": 0
          },
          {
            "Server": "dc1",
            "Partner": "CN=NTDS Settings,CN=DC3,CN=Servers,CN=Default-First-Site-Name,CN=Sites,CN=Configuration,DC=example,DC=internal",
            "Partition": "DC=example,DC=internal",
            "LastReplicationSuccess": null,
            "LastReplicationAttempt": "2026-09-19T09:00:00.0000000Z",
            "ConsecutiveReplicationFailures": 3,
            "LastReplicationResult": 8524
          }
        ]
        """
            .trimIndent()

    val results = newClient().parseReplicationPartnerJson("dc1", stdout)

    assertEquals(2, results.size)
    assertEquals(setOf("DC2", "DC3"), results.map { it.partner }.toSet())
    val failing = results.single { it.partner == "DC3" }
    assertEquals(3, failing.consecutiveReplicationFailures)
    assertNull(failing.lastReplicationSuccess)
  }

  @Test
  fun `parseReplicationPartnerJson returns an empty list for blank stdout`() {
    assertTrue(newClient().parseReplicationPartnerJson("dc1", "").isEmpty())
  }

  @Test
  fun `parseReplicationPartnerJson returns an empty list rather than throwing on unparsable JSON`() {
    assertTrue(newClient().parseReplicationPartnerJson("dc1", "not json at all").isEmpty())
  }
}
