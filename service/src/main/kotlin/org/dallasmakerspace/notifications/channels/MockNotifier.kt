package org.dallasmakerspace.notifications.channels

import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.Notification

/** No-op notifier used in development mode; logs instead of sending. */
class MockNotifier @Inject constructor(loggerFactory: LoggerFactory) : Notifier {
  private val log = loggerFactory.create(javaClass)

  override val channel: Channel = Channel.EMAIL

  override suspend fun send(notification: Notification, recipientEmail: String?): SendResult {
    log.info(
        "MOCK send ${notification.channel} to ${notification.memberUsername}" +
            " (${recipientEmail ?: "no-email"}): ${notification.subject}")
    return SendResult.Success("mock-${notification.id}")
  }

  override suspend fun sendDigest(
      memberUsername: String,
      recipientEmail: String?,
      subject: String,
      textBody: String,
      htmlBody: String,
  ): SendResult {
    log.info("MOCK digest email to $memberUsername (${recipientEmail ?: "no-email"}): $subject")
    return SendResult.Success("mock-digest-$memberUsername")
  }
}
