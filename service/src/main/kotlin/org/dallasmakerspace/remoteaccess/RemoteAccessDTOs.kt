package org.dallasmakerspace.remoteaccess

import kotlinx.serialization.Serializable

@Serializable
enum class MachineStatus {
  AVAILABLE,
  IN_USE,
  OFFLINE
}

@Serializable
data class RemoteAccessSessionDTO(
    // Present only for a Guacamole-routed session - its Guacamole active-connection ID, usable to
    // force-kill it. Null for a direct/bypass RDP session detected via WinRM alone (see
    // RemoteAccessService.reconcileDirectSessions) - Guacamole has no record of it, so there's
    // nothing to kill it by.
    val activeConnectionId: String?,
    val username: String,
    val sessionStartEpochMs: Long,
    // Always true for a Guacamole-routed session (an active Guacamole tunnel is, by definition,
    // actively connected right now). For a direct/bypass session, reflects quser's own STATE
    // column - false means the session is merely Disconnected (client closed without logging off,
    // programs still alive server-side, resumable) rather than genuinely in use. The UI layer uses
    // this to avoid blocking a user from reconnecting to their OWN disconnected session as if it
    // were a duplicate active one - see RemoteAccessHandler.buildMachineViewModel.
    val isActive: Boolean,
)

@Serializable
data class RemoteAccessMachineDTO(
    val connectionId: String,
    val displayName: String,
    val protocol: String,
    val status: MachineStatus,
    // The full, unified set of occupants - both Guacamole-routed and direct/bypass RDP sessions -
    // rendered identically by the UI. Guacamole itself connects over RDP using the connecting
    // member's own AD identity (not a shared account), so a Guacamole session and a direct one are
    // indistinguishable by identity; see RemoteAccessService.reconcileDirectSessions for how the
    // two sources are merged without double-counting the same underlying session.
    val sessions: List<RemoteAccessSessionDTO>,
    // null/0 means unlimited. Global capacity only - per-user capacity (maxConnectionsPerUser)
    // is checked by the UI layer, which knows the requesting user's identity.
    val maxConnections: Int?,
    val maxConnectionsPerUser: Int?,
    val canConnect: Boolean,
)

@Serializable
data class RemoteAccessCategoryDTO(
    val slug: String,
    val displayName: String,
    val description: String,
    val requiredAdGroup: String,
    val machines: List<RemoteAccessMachineDTO>,
)
