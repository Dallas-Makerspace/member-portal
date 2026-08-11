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
  fun groupDigestsLeavesLoneImmediateSendUngrouped() {
    val rows =
        listOf(
            mapOf(
                "id" to 1L,
                "aggregation" to "IMMEDIATE",
                "status" to "SENT",
                "providerId" to "mock-1",
                "sentAt" to "2026-08-10T09:00:00"))

    val grouped = groupDigests(rows)

    assertEquals(1, grouped.size)
    assertFalse(grouped[0]["isDigest"] as Boolean)
  }

  @Test
  fun groupDigestsLeavesUnsentRowsUngrouped() {
    // Still queued for the next digest run - nothing has actually been delivered yet, so there's
    // no "one email" to collapse into.
    val rows =
        listOf(
            mapOf(
                "id" to 1L,
                "aggregation" to "DAILY",
                "status" to "IN_DIGEST",
                "providerId" to null,
                "sentAt" to null))

    val grouped = groupDigests(rows)

    assertEquals(1, grouped.size)
    assertFalse(grouped[0]["isDigest"] as Boolean)
  }

  @Test
  fun groupDigestsCollapsesRowsSentTogetherIntoOneParentRow() {
    val rows =
        listOf(
            mapOf(
                "id" to 1L,
                "subject" to "First",
                "topic" to "EVENT_ANNOUNCEMENT",
                "aggregation" to "DAILY",
                "status" to "SENT",
                "providerId" to "mock-digest-jdoe",
                "sentAt" to "2026-08-10T09:00:00"),
            mapOf(
                "id" to 2L,
                "subject" to "Second",
                "topic" to "EVENT_ANNOUNCEMENT",
                "aggregation" to "DAILY",
                "status" to "SENT",
                "providerId" to "mock-digest-jdoe",
                "sentAt" to "2026-08-10T09:00:00"))

    val grouped = groupDigests(rows)

    assertEquals(1, grouped.size)
    val group = grouped[0]
    assertTrue(group["isDigest"] as Boolean)
    assertEquals("Dallas Makerspace digest: 2 updates", group["subject"])
    assertEquals("Event announcements", group["topic_display"])
    assertEquals("2026-08-10T09:00:00", group["sentAt"])
    @Suppress("UNCHECKED_CAST") val children = group["children"] as List<Map<String, Any?>>
    assertEquals(listOf("First", "Second"), children.map { it["subject"] })
  }

  @Test
  fun groupDigestsGroupsImmediateRowsDeferredToDigestByProviderId() {
    // NotificationWorker.markInDigest never rewrites the `aggregation` column, so a row deferred
    // to a digest by the per-member daily cap still reads aggregation=IMMEDIATE even once
    // DigestProcessor delivers it. Grouping must key off providerId/sentAt, not aggregation.
    val rows =
        listOf(
            mapOf(
                "id" to 1L,
                "aggregation" to "IMMEDIATE",
                "status" to "SENT",
                "providerId" to "mock-digest-jdoe",
                "sentAt" to "2026-08-10T09:00:00"),
            mapOf(
                "id" to 2L,
                "aggregation" to "IMMEDIATE",
                "status" to "SENT",
                "providerId" to "mock-digest-jdoe",
                "sentAt" to "2026-08-10T09:00:00"))

    val grouped = groupDigests(rows)

    assertEquals(1, grouped.size)
    assertTrue(grouped[0]["isDigest"] as Boolean)
  }

  @Test
  fun groupDigestsLabelsMultipleTopicsWhenChildrenDiffer() {
    val rows =
        listOf(
            mapOf(
                "id" to 1L,
                "topic" to "EVENT_ANNOUNCEMENT",
                "aggregation" to "WEEKLY",
                "status" to "SENT",
                "providerId" to "mock-digest-jdoe",
                "sentAt" to "2026-08-10T09:00:00"),
            mapOf(
                "id" to 2L,
                "topic" to "ACCOUNT",
                "aggregation" to "WEEKLY",
                "status" to "SENT",
                "providerId" to "mock-digest-jdoe",
                "sentAt" to "2026-08-10T09:00:00"))

    val grouped = groupDigests(rows)

    assertEquals("Multiple topics", grouped[0]["topic_display"])
  }

  @Test
  fun groupDigestsKeepsSeparateDigestBatchesAsSeparateGroupsEvenWithSameProviderId() {
    // A mock/test notifier can return a providerId that's stable per member rather than per send
    // (e.g. "mock-digest-jdoe" every time) - sentAt must still split these into two groups.
    fun row(id: Long, sentAt: String) =
        mapOf(
            "id" to id,
            "aggregation" to "DAILY",
            "status" to "SENT",
            "providerId" to "mock-digest-jdoe",
            "sentAt" to sentAt)
    val rows =
        listOf(
            row(1L, "2026-08-10T09:00:00"),
            row(2L, "2026-08-10T09:00:00"),
            row(3L, "2026-08-03T09:00:00"),
            row(4L, "2026-08-03T09:00:00"))

    val grouped = groupDigests(rows)

    assertEquals(2, grouped.size)
    assertTrue(grouped.all { it["isDigest"] as Boolean })
    assertEquals(
        setOf("2026-08-10T09:00:00", "2026-08-03T09:00:00"), grouped.map { it["sentAt"] }.toSet())
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
