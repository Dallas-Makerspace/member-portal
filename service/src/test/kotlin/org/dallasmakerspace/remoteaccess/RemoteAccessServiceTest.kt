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
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class RemoteAccessServiceTest {

  private lateinit var guacamoleApiClient: IGuacamoleApiClient
  private lateinit var configOverrideService: ConfigOverrideService
  private lateinit var service: RemoteAccessService

  @Before
  fun setUp() {
    guacamoleApiClient = mock()
    configOverrideService = mock()
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(RemoteAccessService::class.java)).thenReturn(mock<Logger>())
    service = RemoteAccessService(guacamoleApiClient, configOverrideService, loggerFactory)

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
      // treated as reachable by RemoteAccessService.probeConnectivity, avoiding network I/O.
      whenever(guacamoleApiClient.getConnectionHostname(org.mockito.kotlin.any())).thenReturn("")
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
}
