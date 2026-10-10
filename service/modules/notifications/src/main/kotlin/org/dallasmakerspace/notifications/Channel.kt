package org.dallasmakerspace.notifications

/** The delivery channel (delivery medium) a notification is sent over. */
enum class Channel(val displayName: String) {
  EMAIL("Email"),
  SMS("SMS"),
  DISCORD("Discord"),
  DISCOURSE("Discourse");

  /** Default aggregation for notifications on this channel (overridable per member). */
  val defaultAggregation: Aggregation
    get() =
        when (this) {
          EMAIL -> Aggregation.IMMEDIATE
          SMS,
          DISCORD,
          DISCOURSE -> Aggregation.DAILY
        }
}
