package org.dallasmakerspace.notifications.channels

import dagger.MapKey
import org.dallasmakerspace.notifications.Channel

/** Outcome of a send attempt, classified for the worker's retry/backoff logic. */
sealed interface SendResult {
  /** Accepted by the provider; [providerId] is the provider-side id when available. */
  data class Success(val providerId: String?) : SendResult

  /** Retryable failure (rate limited, sending limit, provider 5xx, network). */
  data class TransientFailure(val reason: String) : SendResult

  /** Permanent failure (bad recipient, auth, retries exhausted) - do not retry. */
  data class PermanentFailure(val reason: String) : SendResult
}

/** Fully rendered content handed to a [Notifier]; the notifier applies its own shell/footer. */
data class OutgoingMessage(
    val subject: String,
    val text: String,
    val html: String,
)

/**
 * Delivers messages over a single [Channel]. Each implementation is contributed to the
 * `Map<Channel, Notifier>` multibinding with `@IntoMap @ChannelKey(...)` (see NotificationsModule)
 * and resolves its own recipient address (email, phone, Discord id, ...) from the member username,
 * so the worker and digest processor stay channel-agnostic.
 */
interface Notifier {
  val channel: Channel

  /** Whether several notifications may be combined into one [send] (daily/weekly digests). */
  val supportsDigest: Boolean
    get() = false

  suspend fun send(memberUsername: String, message: OutgoingMessage): SendResult
}

/** Dagger map key binding a [Notifier] to the [Channel] it delivers. */
@MapKey annotation class ChannelKey(val value: Channel)
