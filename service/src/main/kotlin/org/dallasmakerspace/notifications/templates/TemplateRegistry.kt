package org.dallasmakerspace.notifications.templates

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Registry of all code-defined notification templates, keyed by template name. Renders a template
 * from its JSON payload string; every registered template must render a subject and body.
 */
@Singleton
class TemplateRegistry @Inject constructor() {
  private val json = Json { ignoreUnknownKeys = true }

  private val templates: Map<String, NotificationTemplate<*>> =
      listOf(EventAnnouncementTemplate(), SystemTestTemplate()).associateBy { it.name }

  fun find(name: String): NotificationTemplate<*>? = templates[name]

  /** Renders [templateName] with [payload] (a JSON object string; defaults to `{}`). */
  fun render(templateName: String, payload: String?): RenderedNotification {
    val template =
        requireNotNull(templates[templateName]) { "Unknown notification template: $templateName" }
    return renderTemplate(template, payload ?: "{}")
  }

  private fun <T : Any> renderTemplate(
      template: NotificationTemplate<T>,
      payload: String,
  ): RenderedNotification {
    val data = json.decodeFromString(template.serializer, payload)
    return template.render(data)
  }
}
