package org.dallasmakerspace.remoteaccess

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.guacamole.GuacamoleActiveConnection
import org.dallasmakerspace.guacamole.GuacamoleConnection
import org.dallasmakerspace.guacamole.IGuacamoleApiClient
import org.dallasmakerspace.winrm.IWinRmClient
import org.dallasmakerspace.winrm.WinRmRdpSession
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class RemoteAccessServiceTest {

  private lateinit var guacamoleApiClient: IGuacamoleApiClient
  private lateinit var winRmClient: IWinRmClient
  private lateinit var configOverrideService: ConfigOverrideService
  private lateinit var service: RemoteAccessService

  @Before
  fun setUp() {
    guacamoleApiClient = mock()
    winRmClient = mock()
    configOverrideService = mock()
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(RemoteAccessService::class.java)).thenReturn(mock<Logger>())
    // spy(), not a plain instance, so individual tests can stub probeConnectivity directly instead
    // of depending on a real socket connection to a real host (unavailable in CI, and prone to
    // colliding with whatever's already bound to well-known ports like 3389 on a dev machine).
    service = spy(RemoteAccessService(guacamoleApiClient, winRmClient, configOverrideService, loggerFactory))

    runBlocking {
      whenever(configOverrideService.get(RemoteAccessCategory.JUMP_SERVER.connectionIdsConfigKey))
          .thenReturn("5")
      whenever(configOverrideService.get(RemoteAccessCategory.JUMP_SERVER.adGroupConfigKey))
          .thenReturn("")
      whenever(configOverrideService.get(RemoteAccessCategory.MASTERCAM.connectionIdsConfigKey))
          .thenReturn("1")
      whenever(configOverrideService.get(RemoteAccessCategory.MASTERCAM.adGroupConfigKey))
          .thenReturn("")
      // Reachability probing hits real sockets via getConnectionHostname; blank hostname is
      // treated as reachable by RemoteAccessService.probeConnectivity, avoiding network I/O, and
      // also means the WinRM check is skipped entirely (see "blank hostname" test below).
      whenever(guacamoleApiClient.getConnectionHostname(org.mockito.kotlin.any())).thenReturn("")
      // Default: WinRM check succeeds and observes no sessions at all. Individual tests override
      // this (and the hostname stub above) when they need the WinRM check to actually run.
      whenever(winRmClient.getRdpLogonSessions(org.mockito.kotlin.any())).thenReturn(emptyList())
    }
  }

  private fun mockConnections(vararg connections: GuacamoleConnection) = runBlocking {
    whenever(guacamoleApiClient.getConnections()).thenReturn(connections.toList())
  }

  private fun mockActiveConnections(vararg sessions: GuacamoleActiveConnection) = runBlocking {
    whenever(guacamoleApiClient.getActiveConnections()).thenReturn(sessions.toList())
  }

  /** Stubs the real TCP reachability probe so tests can force a non-blank hostname (to unlock the
   * WinRM check, which is skipped for blank hostnames) without depending on a real, reachable
   * network host - unavailable in CI and prone to colliding with whatever's already bound to
   * well-known ports like 3389 on a dev machine. */
  private fun stubReachable() = runBlocking {
    // doReturn(), not whenever().thenReturn() - the latter calls through to the real (spied)
    // method during stub setup, and the real probeConnectivity NPEs on the null that Mockito's
    // any() matcher substitutes for its non-nullable String parameters.
    doReturn(true)
        .whenever(service)
        .probeConnectivity(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    // Without this, the stubbing call above (which Mockito's recording proxy returns null from)
    // flows through as this block's - and stubReachable()'s - inferred non-null Boolean return
    // type, NPEing on unboxing at the call site.
    Unit
  }

  private fun jumpServerMachine(categories: List<RemoteAccessCategoryDTO>) =
      categories.first { it.slug == "jump-server" }.machines.first { it.connectionId == "5" }

  /** Guacamole exposes capacity as string attributes, not top-level fields - see GuacamoleConnection. */
  private fun prwJump(maxConnections: Int? = 2, maxConnectionsPerUser: Int? = 1) =
      GuacamoleConnection(
          identifier = "5",
          name = "PRW Jump Server",
          protocol = "rdp",
          attributes =
              buildMap {
                maxConnections?.let { put("max-connections", it.toString()) }
                maxConnectionsPerUser?.let { put("max-connections-per-user", it.toString()) }
              },
      )

  private val mastercam = GuacamoleConnection("1", "Mastercam Workstation", "rdp")

  @Test
  fun `machine with zero sessions is AVAILABLE with an empty session list`() = runBlocking {
    mockConnections(
        prwJump(),
        mastercam,
    )
    mockActiveConnections()

    val machine = jumpServerMachine(service.getCategories())

    assertEquals(MachineStatus.AVAILABLE, machine.status)
    assertTrue(machine.sessions.isEmpty())
    assertTrue(machine.canConnect)
  }

  @Test
  fun `all concurrent sessions on a connection are surfaced, not just the first`() = runBlocking {
    mockConnections(
        prwJump(),
        mastercam,
    )
    mockActiveConnections(
        GuacamoleActiveConnection("active-1", "5", "jdoe", 1_000L),
        GuacamoleActiveConnection("active-2", "5", "asmith", 2_000L),
    )

    val machine = jumpServerMachine(service.getCategories())

    assertEquals(2, machine.sessions.size)
    assertEquals(setOf("jdoe", "asmith"), machine.sessions.map { it.username }.toSet())
  }

  @Test
  fun `machine is AVAILABLE with occupants when session count is below maxConnections`() =
      runBlocking {
        mockConnections(prwJump(), mastercam)
        mockActiveConnections(GuacamoleActiveConnection("active-1", "5", "jdoe", 1_000L))

        val machine = jumpServerMachine(service.getCategories())

        assertEquals(MachineStatus.AVAILABLE, machine.status)
        assertEquals(1, machine.sessions.size)
        assertTrue(machine.canConnect)
      }

  @Test
  fun `machine is IN_USE (full) once session count reaches maxConnections`() = runBlocking {
    mockConnections(
        prwJump(),
        mastercam,
    )
    mockActiveConnections(
        GuacamoleActiveConnection("active-1", "5", "jdoe", 1_000L),
        GuacamoleActiveConnection("active-2", "5", "asmith", 2_000L),
    )

    val machine = jumpServerMachine(service.getCategories())

    assertEquals(MachineStatus.IN_USE, machine.status)
    assertFalse(machine.canConnect)
  }

  @Test
  fun `maxConnections null or 0 both mean unlimited`() = runBlocking {
    mockConnections(prwJump(maxConnections = null), mastercam)
    mockActiveConnections(
        GuacamoleActiveConnection("active-1", "5", "jdoe", 1_000L),
        GuacamoleActiveConnection("active-2", "5", "asmith", 2_000L),
        GuacamoleActiveConnection("active-3", "5", "bwayne", 3_000L),
    )

    val nullCapMachine = jumpServerMachine(service.getCategories())
    assertEquals(MachineStatus.AVAILABLE, nullCapMachine.status)
    assertTrue(nullCapMachine.canConnect)

    mockConnections(prwJump(maxConnections = 0), mastercam)
    val zeroCapMachine = jumpServerMachine(service.getCategories())
    assertEquals(MachineStatus.AVAILABLE, zeroCapMachine.status)
    assertTrue(zeroCapMachine.canConnect)
  }

  @Test
  fun `unreachable machine is OFFLINE regardless of capacity or session state`() = runBlocking {
    mockConnections(
        prwJump(),
        mastercam,
    )
    mockActiveConnections()
    whenever(guacamoleApiClient.getConnectionHostname("5")).thenReturn("unreachable.dms.local")

    val machine = jumpServerMachine(service.getCategories())

    assertEquals(MachineStatus.OFFLINE, machine.status)
    assertFalse(machine.canConnect)
  }

  @Test
  fun `direct RDP session detected via WinRM alone surfaces with real identity and can reach capacity`() =
      runBlocking {
        stubReachable()
        mockConnections(prwJump(maxConnections = 2), mastercam)
        mockActiveConnections() // no Guacamole sessions at all
        whenever(guacamoleApiClient.getConnectionHostname("5")).thenReturn("prwjump.dms.local")
        whenever(winRmClient.getRdpLogonSessions("prwjump.dms.local"))
            .thenReturn(
                listOf(
                    WinRmRdpSession("mrogers", 1_000L, isActive = true),
                    WinRmRdpSession("bwayne", 2_000L, isActive = true),
                ))

        val machine = jumpServerMachine(service.getCategories())

        assertEquals(MachineStatus.IN_USE, machine.status)
        assertFalse(machine.canConnect)
        assertEquals(2, machine.sessions.size)
        assertEquals(setOf("mrogers", "bwayne"), machine.sessions.map { it.username }.toSet())
        // No Guacamole activeConnectionId exists for a session Guacamole never routed.
        assertTrue(machine.sessions.all { it.activeConnectionId == null })
      }

  @Test
  fun `WinRM session matching a known Guacamole session is not double-counted`() = runBlocking {
    // Regression test: Guacamole itself connects over RDP using the member's own AD identity, so
    // WinRM sees that same session too. A WinRM session for a username Guacamole already reports
    // means there's ZERO extra direct/bypass sessions for that user - not one on top, which the
    // naive "always add" approach would produce.
    stubReachable()
    mockConnections(prwJump(maxConnections = 2), mastercam)
    mockActiveConnections(GuacamoleActiveConnection("active-1", "5", "jdoe", 1_000L))
    whenever(guacamoleApiClient.getConnectionHostname("5")).thenReturn("prwjump.dms.local")
    whenever(winRmClient.getRdpLogonSessions("prwjump.dms.local"))
        .thenReturn(listOf(WinRmRdpSession("jdoe", 1_000L, isActive = true)))

    val machine = jumpServerMachine(service.getCategories())

    assertEquals(1, machine.sessions.size)
    assertEquals("active-1", machine.sessions.single().activeConnectionId)
    assertEquals(MachineStatus.AVAILABLE, machine.status)
    assertTrue(machine.canConnect)
  }

  @Test
  fun `a second WinRM session for an already-known Guacamole user surfaces as an extra direct session`() =
      runBlocking {
        // Same user, but genuinely two distinct logons (e.g. a Guacamole tunnel AND a separate
        // direct RDP login as themselves) - only ONE should be absorbed as "already known".
        stubReachable()
        mockConnections(prwJump(maxConnections = 3), mastercam)
        mockActiveConnections(GuacamoleActiveConnection("active-1", "5", "jdoe", 1_000L))
        whenever(guacamoleApiClient.getConnectionHostname("5")).thenReturn("prwjump.dms.local")
        whenever(winRmClient.getRdpLogonSessions("prwjump.dms.local"))
            .thenReturn(
                listOf(
                    WinRmRdpSession("jdoe", 1_000L, isActive = true),
                    WinRmRdpSession("jdoe", 5_000L, isActive = true),
                ))

        val machine = jumpServerMachine(service.getCategories())

        assertEquals(2, machine.sessions.size)
        assertEquals(1, machine.sessions.count { it.activeConnectionId == "active-1" })
        assertEquals(1, machine.sessions.count { it.activeConnectionId == null })
        assertTrue(machine.sessions.all { it.username == "jdoe" })
      }

  @Test
  fun `a Disconnected direct RDP session propagates isActive=false, not the machine's overall status`() =
      runBlocking {
        // Regression test for a real production scenario: a member's RDP client closed without
        // logging off (quser reports Disc, not Active) - the session still occupies a real slot
        // (machine-wide status/capacity is unaffected, still IN_USE), but RemoteAccessSessionDTO
        // must carry isActive=false so the UI layer can let that SAME member reconnect to
        // themselves instead of being told "Already Connected" forever.
        stubReachable()
        mockConnections(prwJump(maxConnections = 1), mastercam)
        mockActiveConnections() // no Guacamole session - this member is only visible via WinRM
        whenever(guacamoleApiClient.getConnectionHostname("5")).thenReturn("prwjump.dms.local")
        whenever(winRmClient.getRdpLogonSessions("prwjump.dms.local"))
            .thenReturn(listOf(WinRmRdpSession("mandarlimaye", 1_000L, isActive = false)))

        val machine = jumpServerMachine(service.getCategories())

        assertEquals(MachineStatus.IN_USE, machine.status)
        assertFalse(machine.sessions.single().isActive)
      }

  @Test
  fun `null WinRM result fails open and does not block connecting`() = runBlocking {
    stubReachable()
    mockConnections(prwJump(maxConnections = 2), mastercam)
    mockActiveConnections()
    whenever(guacamoleApiClient.getConnectionHostname("5")).thenReturn("prwjump.dms.local")
    whenever(winRmClient.getRdpLogonSessions("prwjump.dms.local")).thenReturn(null)

    val machine = jumpServerMachine(service.getCategories())

    assertTrue(machine.sessions.isEmpty())
    assertEquals(MachineStatus.AVAILABLE, machine.status)
    assertTrue(machine.canConnect)
  }

  @Test
  fun `SSH-protocol machines never invoke the WinRM check`() = runBlocking {
    val sshMachine = GuacamoleConnection("3", "Ubuntu Server 1", "ssh")
    whenever(configOverrideService.get(RemoteAccessCategory.JUMP_SERVER.connectionIdsConfigKey))
        .thenReturn("3")
    mockConnections(sshMachine, mastercam)
    mockActiveConnections()
    whenever(guacamoleApiClient.getConnectionHostname("3")).thenReturn("ubuntu.dms.local")

    service.getCategories()

    org.mockito.kotlin.verify(winRmClient, org.mockito.kotlin.never())
        .getRdpLogonSessions(org.mockito.kotlin.any())
    Unit
  }

  @Test
  fun `blank hostname skips the WinRM check entirely`() = runBlocking {
    mockConnections(prwJump(), mastercam)
    mockActiveConnections()
    // getConnectionHostname("5") uses the default blank-string stub from setUp().

    val machine = jumpServerMachine(service.getCategories())

    assertTrue(machine.sessions.isEmpty())
    org.mockito.kotlin.verify(winRmClient, org.mockito.kotlin.never())
        .getRdpLogonSessions(org.mockito.kotlin.any())
    Unit
  }
}
