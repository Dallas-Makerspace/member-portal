package org.dallasmakerspace.members

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory

@Singleton
class MemberSettingsService
@Inject
constructor(
    private val repository: IMemberSettingsRepository,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")
  private val chicagoZone = ZoneId.of("America/Chicago")

  // Machine-readable Central Time timestamp (no timezone suffix) — parsed client-side as Chicago
  // time by <dms-relative-date>.
  private val historyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

  /**
   * Returns the member's stored settings as a map of setting_key to setting_value. Only keys that
   * are currently set are returned; unset and cleared settings are omitted.
   */
  suspend fun getSettings(username: String): Map<String, String> =
      repository.getLatest(username).filterKeys { key ->
        MemberSettingsRegistry.findByKey(key) != null
      }

  /**
   * Returns the full append-only change history for a member as a list of maps, newest first. Each
   * entry contains setting_key, setting_value (null if cleared), updated_by (actor username) and
   * updated_at (formatted in Central Time).
   */
  suspend fun getSettingsHistory(username: String): List<Map<String, String?>> {
    val records =
        repository.getHistory(username).filter { record ->
          MemberSettingsRegistry.findByKey(record.key) != null
        }
    return records.map { record ->
      mapOf(
          "setting_key" to record.key,
          "setting_value" to record.value,
          "updated_by" to record.updatedByUsername,
          "updated_at" to
              record.updatedAt
                  .atZone(utcZone)
                  .withZoneSameInstant(chicagoZone)
                  .toLocalDateTime()
                  .format(historyFormatter),
      )
    }
  }

  /**
   * Applies a partial settings update for a member. Every change is appended as a new record so an
   * audit trail is preserved. Keys with null or blank values are cleared (recorded with a NULL
   * value); non-empty values are validated and appended. Unknown keys are rejected.
   *
   * @param actorUsername The username of the member making the change, used to record who updated
   *   the settings. May be null if the change was not performed by a member.
   */
  suspend fun updateSettings(
      username: String,
      updates: Map<String, String?>,
      actorUsername: String?
  ) {
    val profileId =
        repository.getProfileId(username)
            ?: throw IllegalArgumentException("Unknown member: $username")
    val actorProfileId = actorUsername?.let { repository.getProfileId(it) }

    updates.forEach { (key, value) ->
      val settingsKey =
          MemberSettingsRegistry.findByKey(key)
              ?: throw IllegalArgumentException("Unknown member setting key: $key")

      if (value.isNullOrBlank()) {
        repository.append(profileId, key, null, actorProfileId)
        log.info("Cleared member setting '$key' for $username")
      } else {
        require(settingsKey.validate(value)) { "Invalid value for member setting '$key': $value" }
        repository.append(profileId, key, value, actorProfileId)
        log.info("Updated member setting '$key' for $username to '$value'")
      }
    }
  }
}
