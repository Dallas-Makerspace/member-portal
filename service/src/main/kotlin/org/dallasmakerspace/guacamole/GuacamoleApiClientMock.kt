package org.dallasmakerspace.guacamole

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory

@Singleton
class GuacamoleApiClientMock @Inject constructor(loggerFactory: LoggerFactory) :
    IGuacamoleApiClient {
  private val log = loggerFactory.create(javaClass)

  override suspend fun getConnections(): List<GuacamoleConnection> {
    log.info("Mock: getConnections")
    return listOf(
        GuacamoleConnection(identifier = "1", name = "Mastercam Workstation", protocol = "rdp"),
        GuacamoleConnection(identifier = "2", name = "Windows 10 VM 1", protocol = "rdp"),
        GuacamoleConnection(
            identifier = "3",
            name = "Ubuntu Server 1",
            protocol = "ssh",
            attributes = mapOf("max-connections" to "1"),
        ),
        GuacamoleConnection(identifier = "4", name = "Win 11 VM 1", protocol = "rdp"),
        // Mirrors the real prwjump.dms.local Guacamole connection (id=5): a multi-login RDS
        // host, capacity 2 concurrent sessions total, 1 per user.
        GuacamoleConnection(
            identifier = "5",
            name = "PRW Jump Server",
            protocol = "rdp",
            attributes = mapOf("max-connections" to "2", "max-connections-per-user" to "1"),
        ),
    )
  }

  override suspend fun getActiveConnections(): List<GuacamoleActiveConnection> {
    log.info("Mock: getActiveConnections")
    return listOf(
        // Fills connection 3 to its capacity of 1 - demonstrates the "full" (IN_USE) state.
        GuacamoleActiveConnection(
            identifier = "active-1",
            connectionIdentifier = "3",
            username = "jdoe",
            startDate = System.currentTimeMillis() - 300_000L,
        ),
        // One of two available slots on prwjump - demonstrates "occupied but still connectable"
        // plus multi-session rendering.
        GuacamoleActiveConnection(
            identifier = "active-2",
            connectionIdentifier = "5",
            username = "asmith",
            startDate = System.currentTimeMillis() - 600_000L,
        ),
    )
  }

  override suspend fun killActiveConnection(identifier: String) {
    log.info("Mock: killActiveConnection identifier=$identifier")
  }

  override suspend fun getConnectionHostname(id: String): String {
    log.info("Mock: getConnectionHostname id=$id")
    return ""
  }
}
