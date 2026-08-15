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
import org.dallasmakerspace.guacamole.IGuacamoleApiClient

private const val PROBE_TIMEOUT_MS = 2_000
private const val PORT_RDP = 3389
private const val PORT_SSH = 22

@Singleton
class RemoteAccessService
@Inject
constructor(
    private val guacamoleApiClient: IGuacamoleApiClient,
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

    // Fetch hostname from Guacamole and TCP-probe each VM in parallel
    val probeJobs =
        allConnectionIds.mapNotNull { id ->
          connectionById[id]?.let { conn ->
            id to
                async {
                  val hostname = guacamoleApiClient.getConnectionHostname(id)
                  probeConnectivity(hostname, conn.protocol)
                }
          }
        }
    // All jobs are running concurrently; await each in turn
    val reachabilityById =
        buildMap<String, Boolean> { for ((id, deferred) in probeJobs) put(id, deferred.await()) }

    RemoteAccessCategory.entries.map { category ->
      val connectionIdsRaw = configOverrideService.get(category.connectionIdsConfigKey)
      val requiredAdGroup = configOverrideService.get(category.adGroupConfigKey)
      val connectionIds = connectionIdsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

      val machines =
          connectionIds.mapNotNull { id ->
            val conn = connectionById[id]
            if (conn == null) {
              log.warn("Guacamole connection ID $id (category ${category.slug}) not found")
              null
            } else {
              val reachable = reachabilityById[id] ?: true
              val sessions =
                  (activeSessions[id] ?: emptyList()).map {
                    RemoteAccessSessionDTO(
                        activeConnectionId = it.identifier,
                        username = it.username,
                        sessionStartEpochMs = it.startDate,
                    )
                  }
              // null/0 means unlimited.
              val maxConnections = conn.maxConnections?.takeIf { it > 0 }
              val hasCapacity = maxConnections == null || sessions.size < maxConnections
              val status =
                  when {
                    !reachable -> MachineStatus.OFFLINE
                    sessions.isNotEmpty() && !hasCapacity -> MachineStatus.IN_USE
                    else -> MachineStatus.AVAILABLE
                  }
              RemoteAccessMachineDTO(
                  connectionId = id,
                  displayName = conn.name,
                  protocol = conn.protocol,
                  status = status,
                  sessions = sessions,
                  maxConnections = conn.maxConnections,
                  maxConnectionsPerUser = conn.maxConnectionsPerUser,
                  canConnect = reachable && hasCapacity,
              )
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

  private suspend fun probeConnectivity(hostname: String, protocol: String): Boolean {
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
