package org.dallasmakerspace.server.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NotificationsInboxHandlerTest {

  @Test
  fun enrichNotificationAddsSentBadge() {
    val enriched =
        enrichNotification(
            mapOf("id" to 1L, "subject" to "Hello", "status" to "SENT", "attempts" to 1))
    assertEquals("Sent", enriched["status_display"])
    assertEquals("success", enriched["status_class"])
    assertEquals("Hello", enriched["subject"])
    assertEquals(1L, enriched["id"])
  }

  @Test
  fun enrichNotificationAddsDangerBadgeForFailures() {
    val failed = enrichNotification(mapOf("status" to "FAILED"))
    assertEquals("Failed", failed["status_display"])
    assertEquals("danger", failed["status_class"])

    val deadLetter = enrichNotification(mapOf("status" to "DEAD_LETTER"))
    assertEquals("Dead letter", deadLetter["status_display"])
    assertEquals("danger", deadLetter["status_class"])
  }

  @Test
  fun enrichNotificationFallsBackForUnknownStatus() {
    val enriched = enrichNotification(mapOf("status" to "WEIRD"))
    assertEquals("WEIRD", enriched["status_display"])
    assertEquals("secondary", enriched["status_class"])
  }

  @Test
  fun enrichNotificationHandlesMissingStatus() {
    val enriched = enrichNotification(mapOf("subject" to "x"))
    assertEquals("", enriched["status_display"])
    assertEquals("secondary", enriched["status_class"])
  }

  @Test
  fun groupPreferencesReturnsAllTopicsAndChannelsWithDefaults() {
    val grouped = groupPreferences(emptyList())

    assertEquals(listOf("EVENT_ANNOUNCEMENT", "ACCOUNT", "SYSTEM"), grouped.map { it["topic"] })
    assertEquals("Event announcements", grouped[0]["topicDisplayName"])
    assertEquals(
        listOf("EMAIL", "SMS", "DISCORD", "DISCOURSE"),
        grouped[0]["rows"].let { (it as List<*>).map { row -> (row as Map<*, *>)["channel"] } })
    val row = (grouped[0]["rows"] as List<*>)[0] as Map<*, *>
    assertTrue(row["enabled"] as Boolean)
    assertEquals("", row["effectiveAggregation"])
    assertEquals(null, row["aggregationOverride"])
  }

  @Test
  fun groupPreferencesMergesStoredOverrides() {
    val grouped =
        groupPreferences(
            listOf(
                mapOf(
                    "memberUsername" to "jdoe",
                    "topic" to "ACCOUNT",
                    "channel" to "EMAIL",
                    "enabled" to false,
                    "effectiveAggregation" to "WEEKLY",
                    "aggregationOverride" to "WEEKLY",
                ),
                mapOf(
                    "memberUsername" to "jdoe",
                    "topic" to "EVENT_ANNOUNCEMENT",
                    "channel" to "SMS",
                    "enabled" to true,
                    "effectiveAggregation" to "DAILY",
                    "aggregationOverride" to null,
                ),
            ))

    val accountEmail =
        (grouped[1]["rows"] as List<*>).filterIsInstance<Map<*, *>>().first {
          it["channel"] == "EMAIL"
        }
    assertFalse(accountEmail["enabled"] as Boolean)
    assertEquals("WEEKLY", accountEmail["effectiveAggregation"])
    assertEquals("WEEKLY", accountEmail["aggregationOverride"])

    val eventSms =
        (grouped[0]["rows"] as List<*>).filterIsInstance<Map<*, *>>().first {
          it["channel"] == "SMS"
        }
    assertTrue(eventSms["enabled"] as Boolean)
    assertEquals("DAILY", eventSms["effectiveAggregation"])
    assertEquals(null, eventSms["aggregationOverride"])
  }

  @Test
  fun groupPreferencesPreservesChannelOrderWithinTopic() {
    val grouped = groupPreferences(emptyList())
    val channels =
        (grouped[0]["rows"] as List<*>).filterIsInstance<Map<*, *>>().map {
          it["channelDisplayName"]
        }
    assertEquals(listOf("Email", "SMS", "Discord", "Discourse"), channels)
  }
}
