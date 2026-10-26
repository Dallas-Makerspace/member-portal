package org.dallasmakerspace.notifications.channels

import java.util.UUID
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.Channel

/** No-op notifier used in development mode; logs instead of sending. */
class MockNotifier(
    loggerFactory: LoggerFactory,
    override val channel: Channel = Channel.EMAIL,
    override val supportsDigest: Boolean = true,
) : Notifier {
  private val log = loggerFactory.create(javaClass)

  override suspend fun send(memberUsername: String, message: OutgoingMessage): SendResult {
    log.info("MOCK send $channel to $memberUsername: ${message.subject}")
    return SendResult.Success("mock-${UUID.randomUUID()}")
  }
}
