package org.dallasmakerspace.server.memberservice

/** What a viewer can do about messaging a committee's followers (service: message-eligibility). */
data class CommitteeMessageEligibility(
    /** The viewer is a chair of the committee and the feature is on for them. */
    val canMessage: Boolean,
    val talkLinked: Boolean,
    val canCreateThread: Boolean,
    val followerCount: Int,
    val maxLength: Int,
    val perDayLimit: Int,
    val perWeekLimit: Int,
    val sentToday: Int,
    val sentThisWeek: Int,
    /** ISO-8601 UTC instant while a send limit is reached; null otherwise. */
    val nextAllowedAt: String?,
)

/** Outcome of sending a committee message: the service's HTTP status, text and error details. */
data class CommitteeMessageCallResult(
    val statusCode: Int,
    val message: String?,
    val data: Map<String, Any?>?,
)

internal fun committeeMessageEligibilityFromMap(map: Map<*, *>): CommitteeMessageEligibility {
  fun int(key: String, default: Int) = (map[key] as? Number)?.toInt() ?: default
  return CommitteeMessageEligibility(
      canMessage = map["canMessage"] == true,
      talkLinked = map["talkLinked"] == true,
      canCreateThread = map["canCreateThread"] == true,
      followerCount = int("followerCount", 0),
      maxLength = int("maxLength", MAX_MESSAGE_LENGTH),
      perDayLimit = int("perDayLimit", 0),
      perWeekLimit = int("perWeekLimit", 0),
      sentToday = int("sentToday", 0),
      sentThisWeek = int("sentThisWeek", 0),
      nextAllowedAt = (map["nextAllowedAt"] as? String)?.takeIf { it.isNotBlank() })
}

private const val MAX_MESSAGE_LENGTH = 120
