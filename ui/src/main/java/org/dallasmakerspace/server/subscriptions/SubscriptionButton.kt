package org.dallasmakerspace.server.subscriptions

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.dallasmakerspace.models.SubscriptionCopy
import org.dallasmakerspace.models.SubscriptionStatus

private val json = Json { encodeDefaults = true }

const val VARIANT_FULL = "full"
const val VARIANT_ICON = "icon"

/** Must match STYLES in static/js/dms-subscribe-button.js, which swaps them on toggle. */
private fun buttonClass(iconOnly: Boolean, subscribed: Boolean) =
    when {
      iconOnly && subscribed -> "btn-outline-primary dms-subscribe-on"
      iconOnly -> "btn-outline-secondary"
      subscribed -> "btn-outline-primary"
      else -> "btn-primary"
    }

/**
 * Template model for `fragments/subscribe-button.html`. The optional label/icon arguments replace
 * the topic's defaults (they feed both the rendered button and the copy the script switches
 * between), and [variant] picks the full button or the icon-only one.
 */
@Suppress("LongParameterList")
fun subscriptionButtonModel(
    status: SubscriptionStatus,
    variant: String = VARIANT_FULL,
    subscribeLabel: String? = null,
    subscribedLabel: String? = null,
    unsubscribeLabel: String? = null,
    subscribeIcon: String? = null,
    subscribedIcon: String? = null,
    unsubscribeIcon: String? = null,
): Map<String, Any?> {
  val copy =
      status.copy.copy(
          subscribeLabel = subscribeLabel ?: status.copy.subscribeLabel,
          subscribedLabel = subscribedLabel ?: status.copy.subscribedLabel,
          unsubscribeLabel = unsubscribeLabel ?: status.copy.unsubscribeLabel,
          subscribeIcon = subscribeIcon ?: status.copy.subscribeIcon,
          subscribedIcon = subscribedIcon ?: status.copy.subscribedIcon,
          unsubscribeIcon = unsubscribeIcon ?: status.copy.unsubscribeIcon)
  val subscribed = status.subscribed
  val iconOnly = variant == VARIANT_ICON
  return mapOf(
      "topic" to status.topic,
      "argsJson" to
          json.encodeToString(MapSerializer(String.serializer(), String.serializer()), status.args),
      "copyJson" to json.encodeToString(SubscriptionCopy.serializer(), copy),
      "subscribed" to subscribed,
      "iconOnly" to iconOnly,
      // What the full button shows at rest: the action while off, the state ("Following") while on.
      "label" to if (subscribed) copy.subscribedLabel else copy.subscribeLabel,
      "icon" to if (subscribed) copy.subscribedIcon else copy.subscribeIcon,
      // Icon-only buttons have no visible text, so name the action a click performs.
      "actionLabel" to if (subscribed) copy.unsubscribeLabel else copy.subscribeLabel,
      "btnClass" to buttonClass(iconOnly, subscribed),
      "hint" to if (iconOnly) null else if (subscribed) copy.subscribedHint else copy.hint,
  )
}
