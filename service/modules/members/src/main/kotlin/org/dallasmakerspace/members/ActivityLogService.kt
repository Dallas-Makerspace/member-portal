package org.dallasmakerspace.members

import javax.inject.Inject
import org.dallasmakerspace.models.ActivityLog
import org.dallasmakerspace.models.ActivityLogEvent
import org.dallasmakerspace.models.ActivityLogSource

class ActivityLogService
@Inject
constructor(
    private val memberRepository: MemberRepository,
    private val activityLogRepository: ActivityLogRepository
) {
  /**
   * Fetches the activity log for a member.
   *
   * @param username The username of the member.
   * @return The activity log for the member.
   */
  suspend fun getMemberActivityLog(username: String): List<ActivityLog> {
    val member = memberRepository.getMemberOrInsert(username, null)
    return activityLogRepository.getMemberActivityLog(member.id)
  }

  /** Inserts a new activity log entry. */
  suspend fun insertActivityLogEntry(subjectUsername: String, event: ActivityLogEvent) {
    // TODO(mandarl): add actor user context here
    activityLogRepository.insertActivityLogEntry(
        ActivityLog(
            source = ActivityLogSource.PROFILE,
            actorProfileUsername = subjectUsername,
            subjectProfileUsername = subjectUsername,
            event = event,
            attributes = null))
  }

  /**
   * Inserts a new activity log entry with separate actor and subject, and optional attributes.
   *
   * @param actorUsername The username of the user performing the action.
   * @param subjectUsername The username of the user being acted upon.
   * @param event The activity log event type.
   * @param attributes Optional JSON attributes to store with the log entry.
   */
  suspend fun insertActivityLogEntry(
      actorUsername: String,
      subjectUsername: String,
      event: ActivityLogEvent,
      attributes: String? = null
  ) {
    activityLogRepository.insertActivityLogEntry(
        ActivityLog(
            source = ActivityLogSource.PROFILE,
            actorProfileUsername = actorUsername,
            subjectProfileUsername = subjectUsername,
            event = event,
            attributes = attributes))
  }

  suspend fun insertBulkActivityLogEntry(subjectUsernames: List<String>, event: ActivityLogEvent) {
    activityLogRepository.insertBulkActivityLogEntry(
        subjectUsernames.map {
          ActivityLog(
              source = ActivityLogSource.PROFILE,
              actorProfileUsername = it,
              subjectProfileUsername = it,
              event = event,
              attributes = null)
        })
  }
}
