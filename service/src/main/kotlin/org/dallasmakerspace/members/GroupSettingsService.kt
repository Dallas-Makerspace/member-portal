package org.dallasmakerspace.members

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory

@Singleton
class GroupSettingsService
@Inject
constructor(
    private val repository: IGroupSettingsRepository,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")
  private val chicagoZone = ZoneId.of("America/Chicago")

  // Machine-readable Central Time timestamp (no timezone suffix) — parsed client-side as Chicago
  // time by <dms-relative-date>.
  private val historyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

  /**
   * Returns the group's stored settings as a map of setting_key to setting_value. Only keys that
   * are currently set are returned; unset and cleared settings are omitted. Does not create a
   * `groups` row for groups that don't have one yet — read-only lookup.
   */
  suspend fun getSettings(groupName: String): Map<String, String> {
    val groupId = repository.getGroupId(groupName) ?: return emptyMap()
    return repository.getLatest(groupId).filterKeys { key ->
      GroupSettingsRegistry.findByKey(key) != null
    }
  }

  /**
   * Returns the full append-only change history for a group as a list of maps, newest first. Each
   * entry contains setting_key, setting_value (null if cleared), updated_by (actor username) and
   * updated_at (formatted in Central Time).
   */
  suspend fun getSettingsHistory(groupName: String): List<Map<String, String?>> {
    val groupId = repository.getGroupId(groupName) ?: return emptyList()
    val records =
        repository.getHistory(groupId).filter { record ->
          GroupSettingsRegistry.findByKey(record.key) != null
        }
    val usernames = repository.getUsernames(records.mapNotNull { it.updatedByProfileId }.toSet())
    return records.map { record ->
      mapOf(
          "setting_key" to record.key,
          "setting_value" to record.value,
          "updated_by" to record.updatedByProfileId?.let { usernames[it] },
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
   * Applies a partial settings update for a group. Every change is appended as a new record so an
   * audit trail is preserved. Keys with null or blank values are cleared (recorded with a NULL
   * value); non-empty values are validated and appended. Unknown keys are rejected. This is the
   * only call site allowed to create a `groups` row for a group that doesn't have one yet (AD
   * groups are only lazily mirrored into the `groups` table).
   *
   * @param dn The group's distinguished name, used if a `groups` row needs to be created. May be
   *   null, in which case a placeholder DN is generated (see [IGroupHistoryRepository.getOrCreateGroup]).
   * @param actorUsername The username of the member making the change, used to record who updated
   *   the settings. May be null if the change was not performed by a member.
   */
  suspend fun updateSettings(
      groupName: String,
      dn: String?,
      updates: Map<String, String?>,
      actorUsername: String?,
  ) {
    val groupId = repository.getOrCreateGroupId(groupName, dn)
    val actorProfileId = actorUsername?.let { repository.getProfileId(it) }

    updates.forEach { (key, value) ->
      val settingsKey =
          GroupSettingsRegistry.findByKey(key)
              ?: throw IllegalArgumentException("Unknown group setting key: $key")

      if (value.isNullOrBlank()) {
        repository.append(groupId, key, null, actorProfileId)
        log.info("Cleared group setting '$key' for $groupName")
      } else {
        require(settingsKey.validate(value)) { "Invalid value for group setting '$key': $value" }
        repository.append(groupId, key, value, actorProfileId)
        log.info("Updated group setting '$key' for $groupName to '$value'")
      }
    }
  }
}
