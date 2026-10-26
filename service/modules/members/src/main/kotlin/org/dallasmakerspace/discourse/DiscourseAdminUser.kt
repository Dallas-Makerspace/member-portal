package org.dallasmakerspace.discourse

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A user from GET /admin/users/list.json; [toString] leaves the email out. */
@Serializable
data class DiscourseAdminUser(
    @SerialName("id") val id: Int,
    @SerialName("username") val username: String,
    @SerialName("email") val email: String? = null,
    @SerialName("active") val active: Boolean = false,
    @SerialName("staged") val staged: Boolean = false,
    @SerialName("suspended_at") val suspendedAt: String? = null,
    @SerialName("suspended_till") val suspendedTill: String? = null,
    @SerialName("silenced") val silenced: Boolean = false,
    @SerialName("silenced_till") val silencedTill: String? = null,
) {
  /** Real, confirmed, and not staged, suspended or silenced; anything unclear counts as not. */
  fun isEligible(now: Instant = Instant.now()): Boolean =
      id > 0 &&
          active &&
          !staged &&
          !isBlocked(suspendedAt, suspendedTill, now) &&
          !silenced &&
          !isBlocked(null, silencedTill, now)

  override fun toString() = "DiscourseAdminUser(id=$id, username=$username)"

  private fun isBlocked(at: String?, till: String?, now: Instant): Boolean {
    if (till == null) return at != null
    return runCatching { Instant.parse(till).isAfter(now) }.getOrDefault(true)
  }
}

private val adminUserJson = Json {
  ignoreUnknownKeys = true
  coerceInputValues = true
}

internal fun parseAdminUsers(body: String): List<DiscourseAdminUser> =
    adminUserJson.decodeFromString(body)
