package org.dallasmakerspace.members.observers

import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.discourse.DiscourseService
import org.dallasmakerspace.members.ActivityLogService
import org.dallasmakerspace.models.ActivityLogEvent
import org.dallasmakerspace.models.DMSMember

class DiscourseMemberStatusObserver
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val discourseService: DiscourseService,
    private val activityLogService: ActivityLogService
) : IMemberPropChangeObserver {
  val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun onMemberPropChange(
      propName: String,
      oldValue: Any?,
      newValue: Any?,
      affectedMembers: List<DMSMember>
  ): Boolean {
    log.info(
        "DiscourseMemberStatusObserver.onMemberPropChange: propName=$propName, oldValue=$oldValue, " +
            "newValue=$newValue, affectedMembers=$affectedMembers")
    try { // Validate that we have affected members with discourse accounts linked
      val discourseUsernames = affectedMembers.mapNotNull { it.discourseUsername }
      if (discourseUsernames.isEmpty()) {
        log.debug("No Discourse usernames found for affected members: {}", affectedMembers)
        // No need to process further
      } else if (propName == "enabled" && oldValue == true && newValue == false) {
        /** Handle member disabled */
        val dmsUsernames =
            affectedMembers.filter { it.discourseUsername != null }.map { it.username }
        log.info("Removing users from Discourse members group: $discourseUsernames")
        discourseService.removeUserFromDmsMembersV2Group(discourseUsernames)
        activityLogService.insertBulkActivityLogEntry(
            subjectUsernames = dmsUsernames,
            event = ActivityLogEvent.REMOVE_FROM_DISCOURSE_MEMBERS_GROUP)
      } else if (propName == "enabled" && oldValue == false && newValue == true) {
        /** Handle member enabled */
        val dmsUsernames =
            affectedMembers.filter { it.discourseUsername != null }.map { it.username }
        log.info("Adding users to Discourse members group: $discourseUsernames")
        discourseService.addUserToDmsMembersV2Group(discourseUsernames)
        activityLogService.insertBulkActivityLogEntry(
            subjectUsernames = dmsUsernames,
            event = ActivityLogEvent.ADD_TO_DISCOURSE_MEMBERS_GROUP)
      }
      return true
    } catch (e: Exception) {
      log.error("Failed to process member prop change: $e")
      return false
    }
  }
}
