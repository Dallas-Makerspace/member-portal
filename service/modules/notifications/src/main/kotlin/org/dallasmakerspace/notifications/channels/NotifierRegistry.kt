package org.dallasmakerspace.notifications.channels

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.notifications.Channel

/** The Dagger-contributed [Notifier] for each deliverable [Channel]. */
@Singleton
class NotifierRegistry
@Inject
constructor(private val notifiers: Map<Channel, @JvmSuppressWildcards Notifier>) {

  /** Channels that have a notifier wired up, in declaration order. */
  val supportedChannels: List<Channel>
    get() = Channel.entries.filter { it in notifiers }

  fun find(channel: Channel): Notifier? = notifiers[channel]

  fun supportsDigest(channel: Channel): Boolean = notifiers[channel]?.supportsDigest == true
}
