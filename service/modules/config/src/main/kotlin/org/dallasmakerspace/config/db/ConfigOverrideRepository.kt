package org.dallasmakerspace.config.db

import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.db.suspendTransaction
import org.jetbrains.exposed.sql.SortOrder

class ConfigOverrideRepository @Inject constructor(loggerFactory: LoggerFactory) :
    IConfigOverrideRepository {
  private val log = loggerFactory.create(javaClass)

  override suspend fun getCurrent(key: String): ConfigOverrideDAO? = suspendTransaction {
    ConfigOverrideDAO.find { ConfigOverrideTable.configKey eq key }
        .orderBy(ConfigOverrideTable.changedAt to SortOrder.DESC)
        .limit(1)
        .firstOrNull()
  }

  override suspend fun getHistory(key: String): List<ConfigOverrideDAO> = suspendTransaction {
    ConfigOverrideDAO.find { ConfigOverrideTable.configKey eq key }
        .orderBy(ConfigOverrideTable.changedAt to SortOrder.DESC)
        .toList()
  }

  override suspend fun getAllCurrent(): Map<String, ConfigOverrideDAO> = suspendTransaction {
    ConfigOverrideDAO.all()
        .orderBy(ConfigOverrideTable.changedAt to SortOrder.DESC)
        .toList()
        .distinctBy { it.configKey }
        .associateBy { it.configKey }
  }

  @Suppress("LongParameterList")
  override suspend fun append(
      key: String,
      value: String,
      type: String,
      changedBy: String,
      reason: String,
      isReset: Boolean,
      infraOnly: Boolean,
  ): ConfigOverrideDAO = suspendTransaction {
    ConfigOverrideDAO.new {
      configKey = key
      configValue = value
      configType = type
      this.changedBy = changedBy
      changeReason = reason
      changedAt = LocalDateTime.now(ZoneOffset.UTC)
      this.isReset = isReset
      this.infraOnly = infraOnly
    }
  }
}
