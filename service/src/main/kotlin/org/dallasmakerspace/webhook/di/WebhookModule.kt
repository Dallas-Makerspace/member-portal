package org.dallasmakerspace.webhook.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import org.dallasmakerspace.notifications.templates.CommitteeClassTemplate
import org.dallasmakerspace.notifications.templates.CommitteeCourseTemplate
import org.dallasmakerspace.notifications.templates.EventAnnouncementTemplate
import org.dallasmakerspace.notifications.templates.NotificationTemplate
import org.dallasmakerspace.notifications.templates.ProjectPostTemplate
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.handlers.ActivityLogWebhook
import org.dallasmakerspace.webhook.handlers.CalendarEventWebhook
import org.dallasmakerspace.webhook.handlers.DiscoursePostWebhook
import org.dallasmakerspace.webhook.handlers.GroupHistoryWebhook
import org.dallasmakerspace.webhook.handlers.MoodleCourseWebhook
import org.dallasmakerspace.webhook.handlers.NotificationWebhook
import org.dallasmakerspace.webhook.handlers.SmartWaiverWebhook
import org.dallasmakerspace.webhook.handlers.SparkPostWebhook

@Module
class WebhookModule {
  /**
   * CalendarEventWebhook is the real producer for EVENT_ANNOUNCEMENT - this registration is what
   * makes that topic exist at all (TemplateRegistry derives known topics from registered
   * templates) and marks it visible in Notification Preferences (FollowingTopic.
   * visibleInPreferences = true). Return type must stay the explicit `NotificationTemplate<*>`.
   */
  @Provides
  @IntoSet
  fun provideEventAnnouncementTemplate(): NotificationTemplate<*> = EventAnnouncementTemplate()

  /** Produced by [DiscoursePostWebhook] when a member posts a Show & Tell project. */
  @Provides
  @IntoSet
  fun provideProjectPostTemplate(): NotificationTemplate<*> = ProjectPostTemplate()

  /** Produced by [CalendarEventWebhook] (via CommitteeClassAnnouncer) for committee followers. */
  @Provides
  @IntoSet
  fun provideCommitteeClassTemplate(): NotificationTemplate<*> = CommitteeClassTemplate()

  /** Produced by [MoodleCourseWebhook] (via MoodleCourseAnnouncer) for committee followers. */
  @Provides
  @IntoSet
  fun provideCommitteeCourseTemplate(): NotificationTemplate<*> = CommitteeCourseTemplate()

  @Provides
  @ElementsIntoSet
  fun provideWebhookHandlers(
      groupHistoryWebhook: GroupHistoryWebhook,
      activityLogWebhook: ActivityLogWebhook,
      smartWaiverWebhook: SmartWaiverWebhook,
      notificationWebhook: NotificationWebhook,
      sparkPostWebhook: SparkPostWebhook,
      calendarEventWebhook: CalendarEventWebhook,
      discoursePostWebhook: DiscoursePostWebhook,
      moodleCourseWebhook: MoodleCourseWebhook,
  ): Set<WebhookHandler> {
    return setOf(
        groupHistoryWebhook,
        activityLogWebhook,
        smartWaiverWebhook,
        notificationWebhook,
        sparkPostWebhook,
        calendarEventWebhook,
        discoursePostWebhook,
        moodleCourseWebhook)
  }
}
