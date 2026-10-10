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
import org.dallasmakerspace.winrm.WinRmRdpSession

private const val PROBE_TIMEOUT_MS = 2_000
private const val PORT_RDP = 3389
private const val PORT_SSH = 22

/** Result of probing a single machine: TCP reachability, and (RDP machines only) the RDP logon
 * sessions observed via WinRM - INCLUDES Guacamole's own session(s), see [IWinRmClient]. */
private data class MachineProbeResult(
    val reachable: Boolean,
    val winRmSessions: List<WinRmRdpSession>?,
)

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
          connectionById[id]?.let { conn -> id to async { probeMachine(id, conn) } }
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

  /** TCP-probes reachability and (RDP machines only) lists RDP logon sessions via WinRM,
   * concurrently with each other. */
  private suspend fun probeMachine(id: String, conn: GuacamoleConnection): MachineProbeResult {
    val hostname = guacamoleApiClient.getConnectionHostname(id)
    return coroutineScope {
      val reachableDeferred = async { probeConnectivity(hostname, conn.protocol) }
      val winRmSessionsDeferred = async {
        if (conn.protocol == "rdp" && hostname.isNotBlank()) {
          winRmClient.getRdpLogonSessions(hostname)
        } else {
          null
        }
      }
      MachineProbeResult(reachableDeferred.await(), winRmSessionsDeferred.await())
    }
  }

  private fun buildMachineDto(
      id: String,
      conn: GuacamoleConnection,
      probeResult: MachineProbeResult?,
      activeSessionsForMachine: List<GuacamoleActiveConnection>,
  ): RemoteAccessMachineDTO {
    val reachable = probeResult?.reachable ?: true
    val guacSessions =
        activeSessionsForMachine.map {
          RemoteAccessSessionDTO(
              activeConnectionId = it.identifier,
              username = it.username,
              sessionStartEpochMs = it.startDate,
              isActive = true, // an active Guacamole tunnel is, by definition, connected right now
          )
        }
    val sessions = guacSessions + reconcileDirectSessions(guacSessions, probeResult?.winRmSessions)
    // Windows doesn't cap the number of Disconnected sessions a machine can hold (quser will
    // happily show several) - only genuinely Active ones compete for the connection slots that
    // maxConnections is actually limiting.
    val activeSessionCount = sessions.count { it.isActive }
    // null/0 means unlimited.
    val maxConnections = conn.maxConnections?.takeIf { it > 0 }
    val hasCapacity = maxConnections == null || activeSessionCount < maxConnections
    val status =
        when {
          !reachable -> MachineStatus.OFFLINE
          activeSessionCount > 0 && !hasCapacity -> MachineStatus.IN_USE
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
    )
  }

  /**
   * Reconciles WinRM's observed RDP logon sessions against Guacamole's own already-known active
   * connections to find genuinely direct/bypass sessions Guacamole doesn't know about. Guacamole
   * itself connects over RDP using the connecting member's own AD identity (not a shared account -
   * see [IWinRmClient]), so a Guacamole session and a direct one are indistinguishable by identity
   * alone: up to [guacSessions]'s own per-username count is treated as "already accounted for" and
   * skipped, and any WinRM-observed session for a username beyond that count is surfaced as a new,
   * kill-button-less entry (no Guacamole activeConnectionId exists for a session Guacamole never
   * routed). null/empty WinRM results fail open - contribute nothing extra, never treated as "no
   * one's here" on their own.
   */
  private fun reconcileDirectSessions(
      guacSessions: List<RemoteAccessSessionDTO>,
      winRmSessions: List<WinRmRdpSession>?,
  ): List<RemoteAccessSessionDTO> {
    if (winRmSessions.isNullOrEmpty()) return emptyList()
    val remainingGuacByUsername = guacSessions.groupingBy { it.username }.eachCount().toMutableMap()
    // A Guacamole session is always Active, so when a username has more WinRM entries than known
    // Guacamole sessions, a Disconnected entry is absorbed into that "already known" count before
    // an Active one is - processed in isActive-ascending order rather than quser's arbitrary
    // iteration order. Without this, buildMachineDto's activeSessionCount could silently undercount
    // real capacity: e.g. one Guacamole session for "jdoe" plus a stale Disconnected WinRM entry AND
    // a genuinely second Active one for "jdoe" - if the Active entry happened to be absorbed instead
    // of the Disconnected one, the real second occupied slot would vanish from the count entirely.
    val absorbedIndices =
        winRmSessions
            .withIndex()
            .sortedBy { (_, session) -> session.isActive }
            .mapNotNull { (index, session) ->
              val remaining = remainingGuacByUsername[session.username] ?: 0
              if (remaining > 0) {
                remainingGuacByUsername[session.username] = remaining - 1
                index
              } else {
                null
              }
            }
            .toSet()
    return winRmSessions.withIndex().mapNotNull { (index, winRmSession) ->
      if (index in absorbedIndices) {
        null // already represented by a known Guacamole session for this user
      } else {
        RemoteAccessSessionDTO(
            activeConnectionId = null,
            username = winRmSession.username,
            sessionStartEpochMs = winRmSession.logonStartEpochMs,
            isActive = winRmSession.isActive,
        )
      }
    }
  }

  // internal + open so tests can spy() and stub this one collaborator, avoiding real socket I/O
  // (and the well-known-port bind conflicts that come with it - e.g. 3389 is often already bound
  // by a local RDP-related service on a Windows dev machine even though it's free on CI runners).
  //
  // Retries once on failure: production logs showed ~6% of probes taking 2000-2200ms (i.e.
  // hitting PROBE_TIMEOUT_MS) against machines that were reachable on every surrounding request -
  // a transient connect stall, not a real outage - which was enough to flip a reachable machine to
  // OFFLINE for a single page load until the next poll self-corrected it. A single retry absorbs
  // that class of blip; a machine that's actually down still fails both attempts.
  internal open suspend fun probeConnectivity(hostname: String, protocol: String): Boolean {
    val host = hostname
    if (host.isBlank()) return true
    val port = if (protocol == "rdp") PORT_RDP else PORT_SSH
    return attemptConnect(host, port) || attemptConnect(host, port)
  }

  // internal + open for the same reason as probeConnectivity above - lets tests verify the retry
  // itself (one failure followed by one success still reports reachable) without real socket I/O.
  internal open suspend fun attemptConnect(host: String, port: Int): Boolean =
      withContext(Dispatchers.IO) {
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
