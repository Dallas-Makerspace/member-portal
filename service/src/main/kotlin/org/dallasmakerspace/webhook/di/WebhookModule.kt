package org.dallasmakerspace.webhook.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import org.dallasmakerspace.notifications.templates.EventAnnouncementTemplate
import org.dallasmakerspace.notifications.templates.NotificationTemplate
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.handlers.ActivityLogWebhook
import org.dallasmakerspace.webhook.handlers.CalendarEventWebhook
import org.dallasmakerspace.webhook.handlers.GroupHistoryWebhook
import org.dallasmakerspace.webhook.handlers.NotificationWebhook
import org.dallasmakerspace.webhook.handlers.SmartWaiverWebhook
import org.dallasmakerspace.webhook.handlers.SparkPostWebhook

@Module
class WebhookModule {
  /**
   * CalendarEventWebhook is the real producer for EVENT_ANNOUNCEMENT - this registration is what
   * makes that topic exist at all (TemplateRegistry derives known topics from registered
   * templates) and marks it visible in Notification Preferences (EventAnnouncementTopic.
   * visibleInPreferences = true). Return type must stay the explicit `NotificationTemplate<*>`.
   */
  @Provides
  @IntoSet
  fun provideEventAnnouncementTemplate(): NotificationTemplate<*> = EventAnnouncementTemplate()

  @Provides
  @ElementsIntoSet
  fun provideWebhookHandlers(
      groupHistoryWebhook: GroupHistoryWebhook,
      activityLogWebhook: ActivityLogWebhook,
      smartWaiverWebhook: SmartWaiverWebhook,
      notificationWebhook: NotificationWebhook,
      sparkPostWebhook: SparkPostWebhook,
      calendarEventWebhook: CalendarEventWebhook,
  ): Set<WebhookHandler> {
    return setOf(
        groupHistoryWebhook,
        activityLogWebhook,
        smartWaiverWebhook,
        notificationWebhook,
        sparkPostWebhook,
        calendarEventWebhook)
  }
}
