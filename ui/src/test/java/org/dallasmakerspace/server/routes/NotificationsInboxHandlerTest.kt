package org.dallasmakerspace.server.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            mapOf(
                "id" to 1L,
                "subject" to "Hello",
                "status" to "SENT",
                "topic" to "EVENT_ANNOUNCEMENT",
                "attempts" to 1))
    assertEquals("Sent", enriched["status_display"])
    assertEquals("success", enriched["status_class"])
    assertEquals("Event announcements", enriched["topic_display"])
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
  fun enrichNotificationFallsBackToRawTopicForUnknownTopic() {
    val enriched = enrichNotification(mapOf("topic" to "SOMETHING_NEW"))
    assertEquals("SOMETHING_NEW", enriched["topic_display"])
  }

  @Test
  fun enrichNotificationHandlesMissingTopic() {
    val enriched = enrichNotification(mapOf("subject" to "x"))
    assertEquals("", enriched["topic_display"])
  }

  @Test
  fun groupPreferencesReturnsOneRowPerTopicNoRowsKey() {
    val grouped = groupPreferences(emptyList())

    assertEquals(listOf("EVENT_ANNOUNCEMENT", "ACCOUNT", "SYSTEM"), grouped.map { it["topic"] })
    assertEquals("Event announcements", grouped[0]["topicDisplayName"])
    assertFalse(grouped[0].containsKey("rows"))
  }

  @Test
  fun groupPreferencesDefaultsEnabledTrueWhenUnset() {
    val grouped = groupPreferences(emptyList())
    assertTrue(grouped[0]["enabled"] as Boolean)
  }

  @Test
  fun groupPreferencesDefaultsEffectiveAggregationToImmediateWhenUnset() {
    val grouped = groupPreferences(emptyList())
    assertEquals("IMMEDIATE", grouped[0]["effectiveAggregation"])
    assertEquals("Immediately", grouped[0]["effectiveAggregationDisplay"])
    assertNull(grouped[0]["aggregationOverride"])
  }

  @Test
  fun groupPreferencesIgnoresNonEmailRows() {
    val grouped =
        groupPreferences(
            listOf(
                mapOf(
                    "topic" to "EVENT_ANNOUNCEMENT",
                    "channel" to "SMS",
                    "enabled" to false,
                    "effectiveAggregation" to "DAILY",
                    "aggregationOverride" to "DAILY",
                )))

    val eventAnnouncement = grouped.first { it["topic"] == "EVENT_ANNOUNCEMENT" }
    // The SMS row must not leak into the EMAIL-only view - defaults apply instead.
    assertTrue(eventAnnouncement["enabled"] as Boolean)
    assertEquals("IMMEDIATE", eventAnnouncement["effectiveAggregation"])
  }

  @Test
  fun groupPreferencesRoundTripsStoredAggregationOverride() {
    val grouped =
        groupPreferences(
            listOf(
                mapOf(
                    "topic" to "ACCOUNT",
                    "channel" to "EMAIL",
                    "enabled" to false,
                    "effectiveAggregation" to "WEEKLY",
                    "aggregationOverride" to "WEEKLY",
                )))

    val account = grouped.first { it["topic"] == "ACCOUNT" }
    assertFalse(account["enabled"] as Boolean)
    assertEquals("WEEKLY", account["effectiveAggregation"])
    assertEquals("Weekly digest", account["effectiveAggregationDisplay"])
    assertEquals("WEEKLY", account["aggregationOverride"])
  }

  @Test
  fun groupPreferencesLeavesOverrideNullWhenUnset() {
    // Guards the hidden-field round-trip contract used by the template: a topic with no stored
    // override must produce a null aggregationOverride, not an empty string or the effective
    // value, since the PUT this drives treats null as "no override" and anything else as "set
    // this override".
    val grouped =
        groupPreferences(
            listOf(
                mapOf(
                    "topic" to "EVENT_ANNOUNCEMENT",
                    "channel" to "EMAIL",
                    "enabled" to true,
                    "effectiveAggregation" to "IMMEDIATE",
                    "aggregationOverride" to null,
                )))

    assertNull(grouped.first { it["topic"] == "EVENT_ANNOUNCEMENT" }["aggregationOverride"])
  }

  @Test
  fun canViewNotificationsForAllowsSelf() {
    assertTrue(canViewNotificationsFor("jdoe", "jdoe", canManageMembers = false))
  }

  @Test
  fun canViewNotificationsForAllowsMemberManager() {
    assertTrue(canViewNotificationsFor("jdoe", "asmith", canManageMembers = true))
  }

  @Test
  fun canViewNotificationsForDeniesOtherMember() {
    assertFalse(canViewNotificationsFor("jdoe", "asmith", canManageMembers = false))
  }

  @Test
  fun canViewNotificationsForDeniesNullCurrentUser() {
    assertFalse(canViewNotificationsFor("jdoe", null, canManageMembers = false))
  }
}
