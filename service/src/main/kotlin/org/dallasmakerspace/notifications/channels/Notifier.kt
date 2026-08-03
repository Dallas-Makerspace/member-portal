package org.dallasmakerspace.notifications.channels

import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.Notification

/** Outcome of a send attempt, classified for the worker's retry/backoff logic. */
sealed interface SendResult {
  /** Accepted by the provider; [providerId] is the provider-side id when available. */
  data class Success(val providerId: String?) : SendResult

  /** Retryable failure (rate limited, sending limit, provider 5xx, network). */
  data class TransientFailure(val reason: String) : SendResult

  /** Permanent failure (bad recipient, auth, retries exhausted) - do not retry. */
  data class PermanentFailure(val reason: String) : SendResult
}

/** Delivers a notification over a single channel (email in v1; SMS/Discord/Discourse later). */
interface Notifier {
  val channel: Channel

  /**
   * Attempts to send [notification]. [recipientEmail] is the resolved email address for the EMAIL
   * channel (null for other channels or when no address is on file).
   */
  suspend fun send(notification: Notification, recipientEmail: String?): SendResult

  /**
   * Attempts to send a digest email containing multiple notifications to [memberUsername].
   * [recipientEmail] is the resolved email address for the EMAIL channel.
   *
   * Channels that do not support digests keep the default (permanent failure).
   */
  suspend fun sendDigest(
      memberUsername: String,
      recipientEmail: String?,
      subject: String,
      textBody: String,
      htmlBody: String,
  ): SendResult =
      SendResult.PermanentFailure("Digest delivery is not supported on the $channel channel")
}
