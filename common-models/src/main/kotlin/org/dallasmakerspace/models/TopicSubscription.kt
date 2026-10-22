package org.dallasmakerspace.models

import kotlinx.serialization.Serializable

/** Shared notification topic keys the UI needs; the service's topic objects reference these. */
object TopicKeys {
  const val FOLLOWING = "EVENT_ANNOUNCEMENT"
  const val COMMITTEE_UPDATES = "COMMITTEE_UPDATES"
  const val BAMBU_PRINT_FINISHED = "bambu.print.finished"
  const val BAMBU_PRINT_ACTION_REQUIRED = "bambu.print.action-required"
  const val BAMBU_PRINTER_AVAILABLE = "bambu.printer.available"
  const val BAMBU_WATCH = "bambu.watch"
  const val BAMBU_COMMITTEE_ALERTS = "bambu.committee-alerts"
}

@Serializable
data class ConfirmCopy(val title: String, val message: String, val confirmLabel: String)

/**
 * Everything the subscribe button shows for one topic and its args; a null confirm means no dialog.
 *
 * The button has three looks: not subscribed ([subscribeLabel]/[subscribeIcon], the action it
 * offers), subscribed at rest ([subscribedLabel]/[subscribedIcon], the state, e.g. "Following"),
 * and subscribed while hovered ([unsubscribeLabel]/[unsubscribeIcon], the action it offers).
 */
@Serializable
data class SubscriptionCopy(
    val subscribeLabel: String,
    val subscribedLabel: String,
    val unsubscribeLabel: String,
    val subscribeIcon: String,
    val subscribedIcon: String,
    val unsubscribeIcon: String,
    val subscribeBusy: String,
    val unsubscribeBusy: String,
    val subscribedToast: String,
    val unsubscribedToast: String,
    val hint: String? = null,
    val subscribedHint: String? = null,
    val subscribeConfirm: ConfirmCopy? = null,
    val unsubscribeConfirm: ConfirmCopy? = null,
)

@Serializable
data class SubscriptionStatus(
    val topic: String,
    val args: Map<String, String>,
    val subscribed: Boolean,
    val copy: SubscriptionCopy,
)
