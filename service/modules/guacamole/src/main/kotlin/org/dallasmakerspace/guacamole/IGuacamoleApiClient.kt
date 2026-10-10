package org.dallasmakerspace.guacamole

interface IGuacamoleApiClient {
  suspend fun getConnections(): List<GuacamoleConnection>

  suspend fun getActiveConnections(): List<GuacamoleActiveConnection>

  suspend fun killActiveConnection(identifier: String)

  suspend fun getConnectionHostname(id: String): String
}
