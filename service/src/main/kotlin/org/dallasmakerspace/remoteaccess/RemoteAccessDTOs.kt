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
    val activeConnectionId: String,
    val username: String,
    val sessionStartEpochMs: Long,
)

@Serializable
data class RemoteAccessMachineDTO(
    val connectionId: String,
    val displayName: String,
    val protocol: String,
    val status: MachineStatus,
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
