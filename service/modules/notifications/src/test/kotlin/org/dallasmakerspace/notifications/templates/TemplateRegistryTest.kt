package org.dallasmakerspace.notifications.templates

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import org.dallasmakerspace.notifications.NotificationTopic
import org.dallasmakerspace.notifications.topics.FollowingTopic
import org.dallasmakerspace.notifications.topics.SystemTopic
import org.junit.Test

/** A second template also targeting [SystemTopic], to exercise topic dedup in [TemplateRegistry.topics]. */
private class OtherSystemTemplate :
    NotificationTemplate<SystemTestData>(name = "other-system-template", topic = SystemTopic) {
  override val serializer: KSerializer<SystemTestData> = SystemTestData.serializer()

  override fun renderSubject(data: SystemTestData) = "other"

  override fun renderText(data: SystemTestData) = "other"

  override fun renderHtml(data: SystemTestData) = "other"
}

class TemplateRegistryTest {

  @Test
  fun `find returns the registered template by name`() {
    val registry = TemplateRegistry(setOf(EventAnnouncementTemplate(), SystemTestTemplate()))

    assertTrue(registry.find("system-test") is SystemTestTemplate)
    assertTrue(registry.find("event-announcement") is EventAnnouncementTemplate)
  }

  @Test
  fun `find returns null for an unregistered template name`() {
    val registry = TemplateRegistry(setOf(SystemTestTemplate()))

    assertNull(registry.find("does-not-exist"))
  }

  @Test
  fun `topics is derived from the registered templates' own topics`() {
    val registry = TemplateRegistry(setOf(EventAnnouncementTemplate(), SystemTestTemplate()))

    assertEquals(setOf(FollowingTopic, SystemTopic), registry.topics)
  }

  @Test
  fun `two templates sharing one topic collapse to a single topics entry`() {
    val registry = TemplateRegistry(setOf(SystemTestTemplate(), OtherSystemTemplate()))

    assertEquals(setOf(SystemTopic), registry.topics)
  }

  @Test
  fun `findTopic resolves a registered topic by key`() {
    val registry = TemplateRegistry(setOf(EventAnnouncementTemplate(), SystemTestTemplate()))

    assertEquals(FollowingTopic, registry.findTopic("EVENT_ANNOUNCEMENT"))
    assertEquals(SystemTopic, registry.findTopic("SYSTEM"))
  }

  @Test
  fun `findTopic returns null for an unregistered topic key`() {
    val registry = TemplateRegistry(setOf(SystemTestTemplate()))

    assertNull(registry.findTopic("EVENT_ANNOUNCEMENT"))
  }

  @Test
  fun `an empty registry has no templates and no topics`() {
    val registry = TemplateRegistry(emptySet())

    assertEquals(emptySet<NotificationTopic>(), registry.topics)
    assertNull(registry.find("system-test"))
    assertNull(registry.findTopic("SYSTEM"))
    assertFailsWith<IllegalArgumentException> { registry.render("system-test", null) }
  }

  @Test
  fun `render decodes the payload and renders the registered template`() {
    val registry = TemplateRegistry(setOf(SystemTestTemplate()))

    val rendered = registry.render("system-test", """{"message":"hello"}""")

    assertEquals("Test notification: hello", rendered.subject)
    assertEquals("hello", rendered.text)
  }

  @Test
  fun `render defaults to an empty payload when none is given`() {
    val registry = TemplateRegistry(setOf(SystemTestTemplate()))

    val rendered = registry.render("system-test", null)

    assertEquals("Test notification from DMS", rendered.subject)
  }

  @Test
  fun `render throws for an unregistered template name`() {
    val registry = TemplateRegistry(setOf(SystemTestTemplate()))

    assertFailsWith<IllegalArgumentException> { registry.render("does-not-exist", null) }
  }
}
