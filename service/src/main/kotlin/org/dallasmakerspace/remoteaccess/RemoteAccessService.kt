package org.dallasmakerspace.remoteaccess

import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.guacamole.GuacamoleActiveConnection
import org.dallasmakerspace.guacamole.GuacamoleConnection
import org.dallasmakerspace.guacamole.IGuacamoleApiClient
import org.dallasmakerspace.winrm.IWinRmClient

private const val PROBE_TIMEOUT_MS = 2_000
private const val PORT_RDP = 3389
private const val PORT_SSH = 22

/** Result of probing a single machine: TCP reachability, and (RDP machines only) the count of
 * direct/bypass RDP sessions observed via WinRM, already excluding Guacamole's own session(s). */
private data class MachineProbeResult(val reachable: Boolean, val directRdpSessionCount: Int?)

@Singleton
open class RemoteAccessService
@Inject
constructor(
    private val guacamoleApiClient: IGuacamoleApiClient,
    private val winRmClient: IWinRmClient,
    private val configOverrideService: ConfigOverrideService,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  suspend fun killActiveConnection(identifier: String) {
    guacamoleApiClient.killActiveConnection(identifier)
  }

  suspend fun getCategories(): List<RemoteAccessCategoryDTO> = coroutineScope {
    val connectionsDeferred = async {
      try {
        guacamoleApiClient.getConnections()
      } catch (e: Exception) {
        log.warn("Failed to fetch Guacamole connections", e)
        emptyList()
      }
    }
    val activeConnectionsDeferred = async {
      try {
        guacamoleApiClient.getActiveConnections()
      } catch (e: Exception) {
        log.warn("Failed to fetch Guacamole active connections", e)
        emptyList()
      }
    }

    val connections = connectionsDeferred.await()
    val activeConnections = activeConnectionsDeferred.await()

    val connectionById = connections.associateBy { it.identifier }
    val activeSessions = activeConnections.groupBy { it.connectionIdentifier }

    // Collect all unique connection IDs across all categories
    val allConnectionIds =
        RemoteAccessCategory.entries
            .flatMap { category ->
              configOverrideService
                  .get(category.connectionIdsConfigKey)
                  .split(",")
                  .map { it.trim() }
                  .filter { it.isNotEmpty() }
            }
            .distinct()

    // Fetch hostname from Guacamole once per VM, then TCP-probe reachability and (RDP machines
    // only) check for direct/bypass RDP sessions via WinRM concurrently with each other - all
    // machines' checks are themselves already running concurrently via this outer async wave.
    val probeJobs =
        allConnectionIds.mapNotNull { id ->
          connectionById[id]?.let { conn -> id to async { probeMachine(id, conn, activeSessions) } }
        }
    // All jobs are running concurrently; await each in turn
    val probeResultById =
        buildMap<String, MachineProbeResult> {
          for ((id, deferred) in probeJobs) put(id, deferred.await())
        }

    RemoteAccessCategory.entries.map { category ->
      val requiredAdGroup = configOverrideService.get(category.adGroupConfigKey)
      val connectionIds =
          configOverrideService
              .get(category.connectionIdsConfigKey)
              .split(",")
              .map { it.trim() }
              .filter { it.isNotEmpty() }

      val machines =
          connectionIds.mapNotNull { id ->
            val conn = connectionById[id]
            if (conn == null) {
              log.warn("Guacamole connection ID $id (category ${category.slug}) not found")
              null
            } else {
              buildMachineDto(id, conn, probeResultById[id], activeSessions[id] ?: emptyList())
            }
          }

      RemoteAccessCategoryDTO(
          slug = category.slug,
          displayName = category.displayName,
          description = category.description,
          requiredAdGroup = requiredAdGroup,
          machines = machines,
      )
    }
  }

  /** TCP-probes reachability and (RDP machines only) checks for direct/bypass RDP sessions via
   * WinRM, concurrently with each other. */
  private suspend fun probeMachine(
      id: String,
      conn: GuacamoleConnection,
      activeSessions: Map<String, List<GuacamoleActiveConnection>>,
  ): MachineProbeResult {
    val hostname = guacamoleApiClient.getConnectionHostname(id)
    return coroutineScope {
      val reachableDeferred = async { probeConnectivity(hostname, conn.protocol) }
      val directRdpDeferred = async {
        if (conn.protocol == "rdp" && hostname.isNotBlank()) {
          winRmClient.getEstablishedRdpConnectionCount(hostname)?.let { total ->
            // WinRM sees Guacamole's own RDP session too - subtract the count we already know
            // about from Guacamole to isolate direct/bypass sessions.
            (total - (activeSessions[id]?.size ?: 0)).coerceAtLeast(0)
          }
        } else {
          null
        }
      }
      MachineProbeResult(reachableDeferred.await(), directRdpDeferred.await())
    }
  }

  private fun buildMachineDto(
      id: String,
      conn: GuacamoleConnection,
      probeResult: MachineProbeResult?,
      activeSessionsForMachine: List<GuacamoleActiveConnection>,
  ): RemoteAccessMachineDTO {
    val reachable = probeResult?.reachable ?: true
    val directRdpSessionCount = probeResult?.directRdpSessionCount // null = fail-open
    val sessions =
        activeSessionsForMachine.map {
          RemoteAccessSessionDTO(
              activeConnectionId = it.identifier,
              username = it.username,
              sessionStartEpochMs = it.startDate,
          )
        }
    // null/0 means unlimited.
    val maxConnections = conn.maxConnections?.takeIf { it > 0 }
    val effectiveSessionCount = sessions.size + (directRdpSessionCount ?: 0)
    val hasCapacity = maxConnections == null || effectiveSessionCount < maxConnections
    val status =
        when {
          !reachable -> MachineStatus.OFFLINE
          effectiveSessionCount > 0 && !hasCapacity -> MachineStatus.IN_USE
          else -> MachineStatus.AVAILABLE
        }
    return RemoteAccessMachineDTO(
        connectionId = id,
        displayName = conn.name,
        protocol = conn.protocol,
        status = status,
        sessions = sessions,
        maxConnections = conn.maxConnections,
        maxConnectionsPerUser = conn.maxConnectionsPerUser,
        canConnect = reachable && hasCapacity,
        directRdpSessionCount = directRdpSessionCount,
    )
  }

  // internal + open so tests can spy() and stub this one collaborator, avoiding real socket I/O
  // (and the well-known-port bind conflicts that come with it - e.g. 3389 is often already bound
  // by a local RDP-related service on a Windows dev machine even though it's free on CI runners).
  internal open suspend fun probeConnectivity(hostname: String, protocol: String): Boolean {
    val host = hostname
    if (host.isBlank()) return true
    val port = if (protocol == "rdp") PORT_RDP else PORT_SSH
    return withContext(Dispatchers.IO) {
      try {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
          true
        }
      } catch (e: Exception) {
        log.debug("VM ({}:{}) unreachable: {}", host, port, e.message)
        false
      }
    }
  }
}
