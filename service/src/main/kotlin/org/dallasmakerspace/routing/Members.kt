package org.dallasmakerspace.routing

import io.ktor.resources.*

@Resource("/members")
class Members(val loggedInDays: Int = 90) {

  @Resource("batch") class Batch(val parent: Members = Members())

  @Resource("{username}")
  class DMSMember(
      val parent: Members = Members(),
      val username: String,
      val discourseUsername: String? = null,
      val discourseAvatarUrl: String? = null,
      val discordUserId: String? = null,
      val discordUsername: String? = null,
      val discordAvatarUrl: String? = null,
      val discordWebhookId: String? = null,
  ) {
    @Resource("update") class Update(val parent: DMSMember)

    @Resource("activity")
    class ActivityLog(val parent: DMSMember) {
      @Resource("add") class Add(val parent: ActivityLog)
    }

    @Resource("events") class Events(val parent: DMSMember, val limit: Int = 5)

    @Resource("attended-event-names")
    class AttendedEventNames(val parent: DMSMember, val limit: Int = 20)

    @Resource("attended-organizers")
    class AttendedOrganizers(val parent: DMSMember, val limit: Int = 5)

    @Resource("badge-mm") class BadgeMM(val parent: DMSMember)

    @Resource("badge-ad") class BadgeAD(val parent: DMSMember)

    @Resource("fix-badge") class FixBadge(val parent: DMSMember)

    @Resource("debug-info") class DebugInfo(val parent: DMSMember)

    @Resource("badge-swipes") class BadgeSwipes(val parent: DMSMember)

    @Resource("kiosk-waiver-photos") class KioskWaiverPhotos(val parent: DMSMember)

    @Resource("total-active-time") class TotalActiveTime(val parent: DMSMember)

    @Resource("scanner-status") class ScannerStatus(val parent: DMSMember)
  }
}
