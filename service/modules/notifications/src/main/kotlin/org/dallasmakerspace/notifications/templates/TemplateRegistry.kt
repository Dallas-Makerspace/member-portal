package org.dallasmakerspace.notifications.templates

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import org.dallasmakerspace.notifications.NotificationTopic

/**
 * Registry of all Dagger-registered notification templates, keyed by template name. Renders a
 * template from its JSON payload string; every registered template must render a subject and body.
 *
 * [templates] is contributed via Dagger multibinding - each producer module adds its own template
 * with a separate `@Provides @IntoSet` method (see WebhookModule.kt's registration of
 * EventAnnouncementTemplate, NotificationsModule.kt's registration of SystemTestTemplate). This
 * registry never hardcodes which templates or topics exist; [topics] is derived from whatever
 * templates got registered, not tracked separately.
 */
@Singleton
class TemplateRegistry @Inject constructor(templates: Set<@JvmSuppressWildcards NotificationTemplate<*>>) {
  private val json = Json { ignoreUnknownKeys = true }

  private val templatesByName: Map<String, NotificationTemplate<*>> = templates.associateBy { it.name }

  /** Every topic with at least one registered template - a topic no template targets doesn't exist. */
  val topics: Set<NotificationTopic> = templates.map { it.topic }.toSet()

  private val topicsByKey: Map<String, NotificationTopic> = topics.associateBy { it.key }

  fun find(name: String): NotificationTemplate<*>? = templatesByName[name]

  /** Resolves a topic by its persisted [NotificationTopic.key]; null if unknown. */
  fun findTopic(key: String): NotificationTopic? = topicsByKey[key]

  /** Renders [templateName] with [payload] (a JSON object string; defaults to `{}`). */
  fun render(templateName: String, payload: String?): RenderedNotification {
    val template =
        requireNotNull(templatesByName[templateName]) {
          "Unknown notification template: $templateName"
        }
    return renderTemplate(template, payload ?: "{}")
  }

  /**
   * The inbox card for a stored notification, or null if the template is unknown/retired, has no
   * card, or the stored payload no longer decodes (an old row must never break the inbox).
   */
  @Suppress("TooGenericExceptionCaught")
  fun card(templateName: String, payload: String?): NotificationCard? {
    val template = templatesByName[templateName] ?: return null
    return try {
      cardFor(template, payload ?: "{}")
    } catch (e: Exception) {
      null
    }
  }

  private fun <T : Any> cardFor(template: NotificationTemplate<T>, payload: String): NotificationCard? =
      template.card(json.decodeFromString(template.serializer, payload))

  private fun <T : Any> renderTemplate(
      template: NotificationTemplate<T>,
      payload: String,
  ): RenderedNotification {
    val data = json.decodeFromString(template.serializer, payload)
    return template.render(data)
  }
}
