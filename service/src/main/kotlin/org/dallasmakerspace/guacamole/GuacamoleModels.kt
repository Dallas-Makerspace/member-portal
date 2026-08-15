package org.dallasmakerspace.guacamole

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GuacamoleTokenResponse(
    @SerialName("authToken") val authToken: String,
    @SerialName("username") val username: String,
)

@Serializable
data class GuacamoleConnection(
    @SerialName("identifier") val identifier: String,
    @SerialName("name") val name: String,
    @SerialName("protocol") val protocol: String,
    // Guacamole exposes per-connection capacity as string-valued attributes (hyphenated keys),
    // not top-level fields - e.g. {"max-connections": "2", "max-connections-per-user": "1"}.
    // Null/blank/absent means "unlimited" per Guacamole's own convention.
    @SerialName("attributes") val attributes: Map<String, String?> = emptyMap(),
) {
  val maxConnections: Int?
    get() = attributes["max-connections"]?.toIntOrNull()

  val maxConnectionsPerUser: Int?
    get() = attributes["max-connections-per-user"]?.toIntOrNull()
}

@Serializable
data class GuacamoleActiveConnection(
    @SerialName("identifier") val identifier: String,
    @SerialName("connectionIdentifier") val connectionIdentifier: String,
    @SerialName("username") val username: String,
    @SerialName("startDate") val startDate: Long,
    @SerialName("remoteHost") val remoteHost: String = "",
)
