package org.dallasmakerspace.remoteaccess

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.guacamole.GuacamoleActiveConnection
import org.dallasmakerspace.guacamole.GuacamoleConnection
import org.dallasmakerspace.guacamole.IGuacamoleApiClient
import org.dallasmakerspace.winrm.IWinRmClient
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
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
    service = RemoteAccessService(guacamoleApiClient, winRmClient, configOverrideService, loggerFactory)

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
      whenever(winRmClient.getEstablishedRdpConnectionCount(org.mockito.kotlin.any())).thenReturn(0)
    }
  }

  private fun mockConnections(vararg connections: GuacamoleConnection) = runBlocking {
    whenever(guacamoleApiClient.getConnections()).thenReturn(connections.toList())
  }

  private fun mockActiveConnections(vararg sessions: GuacamoleActiveConnection) = runBlocking {
    whenever(guacamoleApiClient.getActiveConnections()).thenReturn(sessions.toList())
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
  fun `direct RDP session detected via WinRM alone can reach capacity`() = runBlocking {
    mockConnections(prwJump(maxConnections = 2), mastercam)
    mockActiveConnections() // no Guacamole sessions at all
    whenever(guacamoleApiClient.getConnectionHostname("5")).thenReturn("prwjump.dms.local")
    whenever(winRmClient.getEstablishedRdpConnectionCount("prwjump.dms.local")).thenReturn(2)

    val machine = jumpServerMachine(service.getCategories())

    assertEquals(MachineStatus.IN_USE, machine.status)
    assertFalse(machine.canConnect)
    assertEquals(2, machine.directRdpSessionCount)
  }

  @Test
  fun `WinRM count matching the known Guacamole session is not double-counted as direct`() =
      runBlocking {
        // Regression test: Guacamole itself connects over RDP, so WinRM sees it too. A WinRM
        // count equal to the already-known Guacamole session count means there are ZERO direct/
        // bypass sessions - not one extra, which the naive "add on top" formula would produce.
        mockConnections(prwJump(maxConnections = 2), mastercam)
        mockActiveConnections(GuacamoleActiveConnection("active-1", "5", "jdoe", 1_000L))
        whenever(guacamoleApiClient.getConnectionHostname("5")).thenReturn("prwjump.dms.local")
        whenever(winRmClient.getEstablishedRdpConnectionCount("prwjump.dms.local")).thenReturn(1)

        val machine = jumpServerMachine(service.getCategories())

        assertEquals(0, machine.directRdpSessionCount)
        assertEquals(MachineStatus.AVAILABLE, machine.status)
        assertTrue(machine.canConnect)
      }

  @Test
  fun `null WinRM result fails open and does not block connecting`() = runBlocking {
    mockConnections(prwJump(maxConnections = 2), mastercam)
    mockActiveConnections()
    whenever(guacamoleApiClient.getConnectionHostname("5")).thenReturn("prwjump.dms.local")
    whenever(winRmClient.getEstablishedRdpConnectionCount("prwjump.dms.local")).thenReturn(null)

    val machine = jumpServerMachine(service.getCategories())

    assertNull(machine.directRdpSessionCount)
    assertEquals(MachineStatus.AVAILABLE, machine.status)
    assertTrue(machine.canConnect)
  }

  @Test
  fun `directRdpSessionCount is 0, not null, when the WinRM check succeeds with no sessions`() =
      runBlocking {
        mockConnections(prwJump(), mastercam)
        mockActiveConnections()
        whenever(guacamoleApiClient.getConnectionHostname("5")).thenReturn("prwjump.dms.local")
        whenever(winRmClient.getEstablishedRdpConnectionCount("prwjump.dms.local")).thenReturn(0)

        val machine = jumpServerMachine(service.getCategories())

        assertEquals(0, machine.directRdpSessionCount)
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
        .getEstablishedRdpConnectionCount(org.mockito.kotlin.any())
    Unit
  }

  @Test
  fun `blank hostname skips the WinRM check entirely`() = runBlocking {
    mockConnections(prwJump(), mastercam)
    mockActiveConnections()
    // getConnectionHostname("5") uses the default blank-string stub from setUp().

    val machine = jumpServerMachine(service.getCategories())

    assertNull(machine.directRdpSessionCount)
    org.mockito.kotlin.verify(winRmClient, org.mockito.kotlin.never())
        .getEstablishedRdpConnectionCount(org.mockito.kotlin.any())
    Unit
  }
}
