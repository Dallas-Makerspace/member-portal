package org.dallasmakerspace.webhook.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.handlers.ActivityLogWebhook
import org.dallasmakerspace.webhook.handlers.GroupHistoryWebhook
import org.dallasmakerspace.webhook.handlers.NotificationWebhook
import org.dallasmakerspace.webhook.handlers.SmartWaiverWebhook
import org.dallasmakerspace.webhook.handlers.SparkPostWebhook

@Module
class WebhookModule {
  @Provides
  @ElementsIntoSet
  fun provideWebhookHandlers(
      groupHistoryWebhook: GroupHistoryWebhook,
      activityLogWebhook: ActivityLogWebhook,
      smartWaiverWebhook: SmartWaiverWebhook,
      notificationWebhook: NotificationWebhook,
      sparkPostWebhook: SparkPostWebhook,
  ): Set<WebhookHandler> {
    return setOf(
        groupHistoryWebhook,
        activityLogWebhook,
        smartWaiverWebhook,
        notificationWebhook,
        sparkPostWebhook)
  }
}
