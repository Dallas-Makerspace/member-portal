package org.dallasmakerspace.webhook.handlers

import javax.inject.Inject
import kotlinx.serialization.json.Json
import org.dallasmakerspace.calendar.CalendarService
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.db.CommitteeSubscriptionsRepository
import org.dallasmakerspace.notifications.templates.CommitteeClassData
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic
import org.dallasmakerspace.webhook.models.CalendarEventWebhookData

/**
 * Tells members who follow a committee that it has a new class they haven't taken yet. Calendar's
 * event webhook says nothing about committees, so the event is looked up in the calendar DB: the AD
 * group its prerequisite grants is matched to the committee that owns it (the same prefix rule as
 * the committee page). Followers who already hold that group have taken the class and are skipped.
 * Events that grant no group can't be tied to a committee and are ignored.
 */
class CommitteeClassAnnouncer
@Inject
constructor(
    private val calendarService: CalendarService,
    private val subscriptions: CommitteeSubscriptionsRepository,
    private val notificationService: NotificationService,
    private val followerFilter: CommitteeFollowerFilter,
) {
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Returns a one-line summary for the webhook result. [eventStart] is already formatted for
   * display; [alreadyNotified] are members the organizer-follow fan-out just emailed about this
   * same event, so they aren't told twice; [preview] fetches the event's image and description teaser, and is only called once there is
   * someone to notify.
   */
  suspend fun announce(
      event: CalendarEventWebhookData,
      eventStart: String,
      alreadyNotified: Set<String>,
      preview: EventPreviewProvider,
  ): String {
    val prerequisite =
        calendarService.getEventPrerequisite(event.id)
            ?: return "Committee followers: prerequisite lookup failed, skipped"
    val adGroup = prerequisite.adGroup ?: return "Committee followers: event grants no group"
    val committees = Committees.forGroup(adGroup)
    if (committees.isEmpty()) return "Committee followers: no committee owns the group"

    val subscribers =
        subscriptions.getSubscribers(committees.map { it.id }) -
            alreadyNotified -
            event.organizerUsername
    if (subscribers.isEmpty()) return "Committee followers: none to notify"

    val eligible = followerFilter.withoutGroup(subscribers, adGroup)
    if (eligible.isEmpty()) return "Committee followers: all ${subscribers.size} already have it"

    val eventPreview = preview()
    val payload =
        json.encodeToString(
            CommitteeClassData.serializer(),
            CommitteeClassData(
                eventName = event.name,
                committeeName = committees.joinToString(", ") { it.name },
                eventStart = eventStart,
                eventUrl = event.url,
                imageUrl = eventPreview.imageUrl,
                excerpt = eventPreview.excerpt))
    // One webhook per session of a multi-part event is possible, so key on the parent event.
    val seriesId = prerequisite.partOfId ?: event.id
    val result =
        notificationService.createForMembers(
            memberUsernames = eligible,
            topic = CommitteeUpdatesTopic,
            templateName = "committee-class",
            payload = payload,
            channel = Channel.EMAIL,
            dedupeKey = { member -> "committee-class-$seriesId-$member" })
    return "Committee followers: notified ${result.created} of ${eligible.size}" +
        " (already have it=${subscribers.size - eligible.size}," +
        " duplicates=${result.duplicates}, failed=${result.failed})"
  }
}
