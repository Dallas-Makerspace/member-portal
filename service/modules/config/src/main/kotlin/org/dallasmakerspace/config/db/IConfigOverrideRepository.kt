package org.dallasmakerspace.config.db

interface IConfigOverrideRepository {
  suspend fun getCurrent(key: String): ConfigOverrideDAO?

  suspend fun getHistory(key: String): List<ConfigOverrideDAO>

  suspend fun getAllCurrent(): Map<String, ConfigOverrideDAO>

  suspend fun append(
      key: String,
      value: String,
      type: String,
      changedBy: String,
      reason: String,
      isReset: Boolean = false,
      infraOnly: Boolean = false,
  ): ConfigOverrideDAO
}
