package org.dallasmakerspace.server.subscriptions

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.dallasmakerspace.models.SubscriptionCopy
import org.dallasmakerspace.models.SubscriptionStatus

private val json = Json { encodeDefaults = true }

const val VARIANT_FULL = "full"
const val VARIANT_ICON = "icon"

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
    unsubscribeLabel: String? = null,
    subscribeIcon: String? = null,
    unsubscribeIcon: String? = null,
): Map<String, Any?> {
  val copy =
      status.copy.copy(
          subscribeLabel = subscribeLabel ?: status.copy.subscribeLabel,
          unsubscribeLabel = unsubscribeLabel ?: status.copy.unsubscribeLabel,
          subscribeIcon = subscribeIcon ?: status.copy.subscribeIcon,
          unsubscribeIcon = unsubscribeIcon ?: status.copy.unsubscribeIcon)
  val subscribed = status.subscribed
  val iconOnly = variant == VARIANT_ICON
  return mapOf(
      "topic" to status.topic,
      "argsJson" to json.encodeToString(MapSerializer(String.serializer(), String.serializer()), status.args),
      "copyJson" to json.encodeToString(SubscriptionCopy.serializer(), copy),
      "subscribed" to subscribed,
      "iconOnly" to iconOnly,
      "label" to if (subscribed) copy.unsubscribeLabel else copy.subscribeLabel,
      "icon" to if (subscribed) copy.unsubscribeIcon else copy.subscribeIcon,
      "btnClass" to if (subscribed) "btn-outline-secondary" else "btn-primary",
      "hint" to if (iconOnly) null else copy.hint,
  )
}
