package org.dallasmakerspace.models

import kotlinx.serialization.Serializable

/** Shared notification topic keys the UI needs; the service's topic objects reference these. */
object TopicKeys {
  const val FOLLOWING = "EVENT_ANNOUNCEMENT"
}

@Serializable
data class ConfirmCopy(val title: String, val message: String, val confirmLabel: String)

/** Everything the subscribe button shows for one topic and its args; a null confirm means no dialog. */
@Serializable
data class SubscriptionCopy(
    val subscribeLabel: String,
    val unsubscribeLabel: String,
    val subscribeIcon: String,
    val unsubscribeIcon: String,
    val subscribeBusy: String,
    val unsubscribeBusy: String,
    val subscribedToast: String,
    val unsubscribedToast: String,
    val hint: String? = null,
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
