package org.dallasmakerspace.bambuddy.notifications.subscriptions

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.models.ConfirmCopy
import org.dallasmakerspace.models.SubscriptionCopy
import org.dallasmakerspace.bambuddy.notifications.db.BambuCommitteeSubscriptionsRepository
import org.dallasmakerspace.notifications.subscriptions.TopicSubscriptionHandler
import org.dallasmakerspace.notifications.topics.BambuCommitteeAlertsTopic

/**
 * "Notify me when a 3D printer goes offline" — a standing subscription with no args (one topic,
 * all printers). Registered into the topic-handler map by the app module's BambuddyModule.
 */
@Singleton
class BambuCommitteeAlertsSubscriptionHandler
@Inject
constructor(private val repository: BambuCommitteeSubscriptionsRepository) :
    TopicSubscriptionHandler {

  override val topic = BambuCommitteeAlertsTopic
  override val featureFlag = ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED
  override val argNames: Set<String> = emptySet()

  override suspend fun isSubscribed(actor: String, args: Map<String, String>): Boolean =
      repository.isSubscribed(actor)

  override suspend fun subscribe(actor: String, args: Map<String, String>) =
      repository.subscribe(actor)

  override suspend fun unsubscribe(actor: String, args: Map<String, String>) =
      repository.unsubscribe(actor)

  override fun copy(args: Map<String, String>): SubscriptionCopy =
      SubscriptionCopy(
          subscribeLabel = "Notify me",
          subscribedLabel = "Notifying",
          unsubscribeLabel = "Stop notifying",
          subscribeIcon = "notifications",
          subscribedIcon = "notifications_active",
          unsubscribeIcon = "notifications_off",
          subscribeBusy = "Subscribing…",
          unsubscribeBusy = "Unsubscribing…",
          subscribedToast =
              "You're subscribed to 3D printer offline alerts. You'll be notified when a " +
                  "printer stays offline for about 5 minutes.",
          unsubscribedToast = "You unsubscribed from 3D printer offline alerts.",
          hint = "Get notified when a 3D printer goes offline",
          subscribedHint = "You'll be notified when a 3D printer goes offline",
          subscribeConfirm =
              ConfirmCopy(
                  title = "Get printer offline alerts?",
                  message =
                      "You'll be emailed, along with every other subscriber, whenever a 3D " +
                          "printer stays offline for about 5 minutes. You can change how in " +
                          "Notification Preferences.",
                  confirmLabel = "Notify me"),
      )
}
