package org.dallasmakerspace.config.db

import javax.inject.Inject

class ConfigOverrideRepositoryMock @Inject constructor() : IConfigOverrideRepository {
  override suspend fun getCurrent(key: String): ConfigOverrideDAO? = null

  override suspend fun getHistory(key: String): List<ConfigOverrideDAO> = emptyList()

  override suspend fun getAllCurrent(): Map<String, ConfigOverrideDAO> = emptyMap()

  override suspend fun append(
      key: String,
      value: String,
      type: String,
      changedBy: String,
      reason: String,
      isReset: Boolean,
      infraOnly: Boolean,
  ): ConfigOverrideDAO =
      throw UnsupportedOperationException("Config writes are not supported in mock mode")
}
