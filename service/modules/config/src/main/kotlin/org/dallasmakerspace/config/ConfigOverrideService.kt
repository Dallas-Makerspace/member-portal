package org.dallasmakerspace.config

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import org.dallasmakerspace.config.db.ConfigOverrideDAO
import org.dallasmakerspace.config.db.IConfigOverrideRepository
import org.dallasmakerspace.core.LoggerFactory

@Singleton
class ConfigOverrideService
@Inject
constructor(
    private val repository: IConfigOverrideRepository,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private data class CachedEntry(
      val rawValue: String,
      val isReset: Boolean,
      val infraOnly: Boolean,
      val cachedAt: Long,
  )

  private val cache = ConcurrentHashMap<String, CachedEntry>()
  private val cacheTtlMs = 5 * 60 * 1000L

  private val json = Json { ignoreUnknownKeys = true }

  private val UTC_ZONE = ZoneId.of("UTC")
  private val CHICAGO_ZONE = ZoneId.of("America/Chicago")
  private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

  suspend fun warmCache() {
    log.info("Warming config override cache")
    val allCurrent = repository.getAllCurrent()
    allCurrent.forEach { (key, dao) ->
      cache[key] =
          CachedEntry(dao.configValue, dao.isReset, dao.infraOnly, System.currentTimeMillis())
    }
    log.info("Config override cache warmed with ${allCurrent.size} entries")
  }

  @Suppress("UNCHECKED_CAST")
  suspend fun <T> get(configKey: ConfigKey<T>, isInfraUser: Boolean = false): T {
    val cached = cache[configKey.key]
    if (cached != null && System.currentTimeMillis() - cached.cachedAt < cacheTtlMs) {
      if (cached.isReset) return configKey.defaultValue
      if (cached.infraOnly && !isInfraUser) return configKey.defaultValue
      return deserialize(cached.rawValue, configKey)
    }

    val dao = repository.getCurrent(configKey.key)
    if (dao != null) {
      cache[configKey.key] =
          CachedEntry(dao.configValue, dao.isReset, dao.infraOnly, System.currentTimeMillis())
      if (dao.isReset) return configKey.defaultValue
      if (dao.infraOnly && !isInfraUser) return configKey.defaultValue
      return deserialize(dao.configValue, configKey)
    }

    return configKey.defaultValue
  }

  suspend fun <T> set(
      configKey: ConfigKey<T>,
      value: T,
      changedBy: String,
      reason: String,
      infraOnly: Boolean = false
  ) {
    configKey.validators.forEach { validator ->
      val result = validator.validate(value)
      if (!result.valid) {
        throw IllegalArgumentException("Validation failed for ${configKey.key}: ${result.error}")
      }
    }

    val serialized = serialize(value, configKey)
    val typeName = configKey.type::class.simpleName ?: "Unknown"

    repository.append(
        key = configKey.key,
        value = serialized,
        type = typeName,
        changedBy = changedBy,
        reason = reason,
        isReset = false,
        infraOnly = infraOnly,
    )

    cache.remove(configKey.key)

    val displayValue = if (configKey.sensitive) "***" else serialized
    log.info(
        "Config key '${configKey.key}' set to '$displayValue' by $changedBy (reason: $reason, infraOnly: $infraOnly)")
  }

  suspend fun <T> resetToDefault(configKey: ConfigKey<T>, changedBy: String, reason: String) {
    val typeName = configKey.type::class.simpleName ?: "Unknown"

    repository.append(
        key = configKey.key,
        value = "",
        type = typeName,
        changedBy = changedBy,
        reason = reason,
        isReset = true,
    )

    cache.remove(configKey.key)
    log.info("Config key '${configKey.key}' reset to default by $changedBy (reason: $reason)")
  }

  suspend fun getHistory(key: String): List<ConfigOverrideEntry> {
    return repository.getHistory(key).map { dao -> daoToEntry(dao) }
  }

  suspend fun getCurrentValues(): List<ConfigCurrentValue> {
    val allCurrent = repository.getAllCurrent()
    return ConfigRegistry.ALL.map { configKey ->
      @Suppress("UNCHECKED_CAST") val key = configKey as ConfigKey<Any>
      val dao = allCurrent[key.key]
      val isOverridden = dao != null && !dao.isReset
      val currentValue =
          if (isOverridden) {
            dao!!.configValue
          } else {
            serialize(key.defaultValue, key)
          }
      val lastChangedAt = dao?.let { formatTimestamp(it) }

      ConfigCurrentValue(
          key = key.key,
          description = key.description,
          category = key.category,
          currentValue = if (key.sensitive && isOverridden) "***" else currentValue,
          defaultValue = if (key.sensitive) "***" else serialize(key.defaultValue, key),
          type = key.type::class.simpleName ?: "Unknown",
          isOverridden = isOverridden,
          sensitive = key.sensitive,
          overridable = key.overridable,
          lastChangedBy = dao?.changedBy,
          lastChangedAt = lastChangedAt,
          lastChangeReason = dao?.changeReason,
          infraOnly = isOverridden && (dao?.infraOnly ?: false),
      )
    }
  }

  fun invalidateCache(key: String? = null) {
    if (key != null) {
      cache.remove(key)
      log.info("Config cache invalidated for key: $key")
    } else {
      cache.clear()
      log.info("Config cache fully invalidated")
    }
  }

  suspend fun setRaw(
      key: String,
      rawValue: String,
      changedBy: String,
      reason: String,
      infraOnly: Boolean = false
  ) {
    @Suppress("UNCHECKED_CAST")
    val configKey =
        ConfigRegistry.ALL.find { it.key == key } as? ConfigKey<Any>
            ?: throw IllegalArgumentException("Unknown config key: $key")
    val typedValue = deserialize(rawValue, configKey)
    set(configKey, typedValue, changedBy, reason, infraOnly)
  }

  @Suppress("UNCHECKED_CAST")
  fun <T> serialize(value: T, configKey: ConfigKey<T>): String {
    return when (val type = configKey.type) {
      is ConfigValueType.StringType -> value as String
      is ConfigValueType.IntType -> (value as Int).toString()
      is ConfigValueType.BooleanType -> (value as Boolean).toString()
      is ConfigValueType.DateType -> (value as LocalDate).toString()
      is ConfigValueType.FeatureFlagType ->
          json.encodeToString(FeatureFlagValue.serializer(), value as FeatureFlagValue)
      is ConfigValueType.ComplexType<*> -> {
        val serializer = (type as ConfigValueType.ComplexType<T>).serializer
        json.encodeToString(serializer, value)
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  fun <T> deserialize(raw: String, configKey: ConfigKey<T>): T {
    return when (val type = configKey.type) {
      is ConfigValueType.StringType -> raw as T
      is ConfigValueType.IntType -> raw.toInt() as T
      is ConfigValueType.BooleanType -> raw.toBoolean() as T
      is ConfigValueType.DateType -> LocalDate.parse(raw) as T
      is ConfigValueType.FeatureFlagType ->
          json.decodeFromString(FeatureFlagValue.serializer(), raw) as T
      is ConfigValueType.ComplexType<*> -> {
        val serializer = (type as ConfigValueType.ComplexType<T>).serializer
        json.decodeFromString(serializer, raw)
      }
    }
  }

  /**
   * Whether a feature-flagged behavior is enabled for [username] - true if the flag is globally
   * enabled, [username] is explicitly allow-listed, or [username] belongs to one of the
   * allow-listed groups (checked even when the flag is globally disabled, so a feature can be
   * piloted before a full rollout).
   */
  suspend fun isFeatureEnabledFor(
      configKey: ConfigKey<FeatureFlagValue>,
      username: String,
      groupNames: Collection<String>,
  ): Boolean {
    val flag = get(configKey)
    return flag.enabled ||
        username in flag.enabledUsernames ||
        groupNames.any { it in flag.enabledGroupNames }
  }

  private fun formatTimestamp(dao: ConfigOverrideDAO): String {
    val chicagoTime =
        dao.changedAt.atZone(UTC_ZONE).withZoneSameInstant(CHICAGO_ZONE).toLocalDateTime()
    return chicagoTime.format(TIMESTAMP_FORMATTER)
  }

  private fun daoToEntry(dao: ConfigOverrideDAO): ConfigOverrideEntry {
    return ConfigOverrideEntry(
        id = dao.id.value,
        key = dao.configKey,
        value = dao.configValue,
        type = dao.configType,
        changedBy = dao.changedBy,
        changeReason = dao.changeReason,
        changedAt = formatTimestamp(dao),
        isReset = dao.isReset,
        infraOnly = dao.infraOnly,
    )
  }
}
