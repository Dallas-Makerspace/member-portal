package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import org.dallasmakerspace.notifications.NotificationTopic

/** Rendered content of a notification. */
data class RenderedNotification(
    /** Subject line; used as the entire message body for short channels (SMS/Discord). */
    val subject: String,
    /** Plain-text body (email). */
    val text: String,
    /** HTML body (email). */
    val html: String,
)

/**
 * A code-defined notification template. Every template must render a subject (used verbatim by
 * short channels like SMS) plus text and HTML bodies (email).
 *
 * Rendered bodies are content fragments only - the branded email shell (logo, layout, footer) is
 * applied once per email at send time by
 * [org.dallasmakerspace.notifications.channels.EmailNotifier], not per-template, so digest emails
 * (which concatenate several rendered fragments) get exactly one shell instead of one per item.
 *
 * @param T the template's typed payload, decoded from the notification's JSON payload string.
 */
abstract class NotificationTemplate<T : Any>(
    val name: String,
    val topic: NotificationTopic,
) {
  abstract val serializer: KSerializer<T>

  abstract fun renderSubject(data: T): String

  abstract fun renderText(data: T): String

  abstract fun renderHtml(data: T): String

  fun render(data: T): RenderedNotification =
      RenderedNotification(
          subject = renderSubject(data),
          text = renderText(data),
          html = renderHtml(data),
      )
}
