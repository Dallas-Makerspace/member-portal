package org.dallasmakerspace.notifications.subscriptions

import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.models.SubscriptionCopy
import org.dallasmakerspace.notifications.NotificationTopic

/**
 * What a "subscribe" button bound to one [topic] does, and the copy it shows. Register one per
 * topic in Dagger as `@Provides @Singleton @IntoMap @StringKey(topic.key)`.
 *
 * Handlers throw [IllegalArgumentException] for args that name nothing subscribable (unknown target,
 * subscribing to yourself); the service reports those as a bad request.
 */
interface TopicSubscriptionHandler {
  val topic: NotificationTopic

  /** Checked for the acting member on every call; null means always on. */
  val featureFlag: ConfigKey<FeatureFlagValue>?

  /** The exact set of arg names a request must carry. */
  val argNames: Set<String>

  suspend fun isSubscribed(actor: String, args: Map<String, String>): Boolean

  /** Idempotent. */
  suspend fun subscribe(actor: String, args: Map<String, String>)

  /** Idempotent. */
  suspend fun unsubscribe(actor: String, args: Map<String, String>)

  fun copy(args: Map<String, String>): SubscriptionCopy
}
