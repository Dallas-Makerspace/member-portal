package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.SystemTopic

/** Template data for a system test notification. */
@Serializable data class SystemTestData(val message: String = "")

/** Simple test template used to exercise the notification pipeline. */
class SystemTestTemplate :
    NotificationTemplate<SystemTestData>(
        name = "system-test",
        topic = SystemTopic,
    ) {
  override val serializer: KSerializer<SystemTestData> = SystemTestData.serializer()

  override fun renderSubject(data: SystemTestData): String =
      if (data.message.isBlank()) "Test notification from DMS"
      else "Test notification: ${data.message}"

  override fun renderText(data: SystemTestData): String = data.message

  override fun renderHtml(data: SystemTestData): String = "<p>${data.message}</p>"
}
