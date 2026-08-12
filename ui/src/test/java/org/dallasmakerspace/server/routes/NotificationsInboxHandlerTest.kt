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
                "topicDisplayName" to "Event announcements",
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
  fun groupPreferencesReturnsNothingWhenFetchYieldsNoRows() {
    // No rows to source display from - an empty result (e.g. a failed preferences fetch) means an
    // empty table rather than a fabricated set of defaults, now that visibility and content both
    // come from the fetched rows rather than a locally-held topic list.
    assertEquals(emptyList<Map<String, Any?>>(), groupPreferences(emptyList()))
  }

  @Test
  fun groupPreferencesHidesRowsTheTopicItselfMarksNotVisible() {
    // ACCOUNT has no producer wired up yet on the service side, so its topic object's own
    // visibleInPreferences is false - the API echoes that per-row, and this must not surface it as
    // a toggle-able row.
    val grouped =
        groupPreferences(
            listOf(
                mapOf(
                    "topic" to "ACCOUNT",
                    "channel" to "EMAIL",
                    "enabled" to true,
                    "effectiveAggregation" to "IMMEDIATE",
                    "visibleInPreferences" to false,
                )))

    assertTrue(grouped.isEmpty())
  }

  @Test
  fun groupPreferencesShowsRowsTheTopicItselfMarksVisible() {
    val grouped =
        groupPreferences(
            listOf(
                mapOf(
                    "topic" to "EVENT_ANNOUNCEMENT",
                    "topicDisplayName" to "Event announcements",
                    "channel" to "EMAIL",
                    "enabled" to true,
                    "effectiveAggregation" to "IMMEDIATE",
                    "visibleInPreferences" to true,
                )))

    assertEquals(listOf("EVENT_ANNOUNCEMENT"), grouped.map { it["topic"] })
    assertEquals("Event announcements", grouped[0]["topicDisplayName"])
    assertFalse(grouped[0].containsKey("rows"))
  }

  @Test
  fun groupPreferencesIgnoresNonEmailRowsEvenWhenVisible() {
    val grouped =
        groupPreferences(
            listOf(
                mapOf(
                    "topic" to "EVENT_ANNOUNCEMENT",
                    "channel" to "SMS",
                    "enabled" to false,
                    "effectiveAggregation" to "DAILY",
                    "aggregationOverride" to "DAILY",
                    "visibleInPreferences" to true,
                )))

    assertTrue(grouped.isEmpty())
  }

  @Test
  fun groupPreferencesRoundTripsStoredAggregationOverride() {
    val grouped =
        groupPreferences(
            listOf(
                mapOf(
                    "topic" to "EVENT_ANNOUNCEMENT",
                    "channel" to "EMAIL",
                    "enabled" to false,
                    "effectiveAggregation" to "WEEKLY",
                    "aggregationOverride" to "WEEKLY",
                    "visibleInPreferences" to true,
                )))

    val eventAnnouncement = grouped.first { it["topic"] == "EVENT_ANNOUNCEMENT" }
    assertFalse(eventAnnouncement["enabled"] as Boolean)
    assertEquals("WEEKLY", eventAnnouncement["effectiveAggregation"])
    assertEquals("Weekly digest", eventAnnouncement["effectiveAggregationDisplay"])
    assertEquals("WEEKLY", eventAnnouncement["aggregationOverride"])
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
                    "visibleInPreferences" to true,
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
                "topicDisplayName" to "Event announcements",
                "aggregation" to "DAILY",
                "status" to "SENT",
                "providerId" to "mock-digest-jdoe",
                "sentAt" to "2026-08-10T09:00:00"),
            mapOf(
                "id" to 2L,
                "subject" to "Second",
                "topic" to "EVENT_ANNOUNCEMENT",
                "topicDisplayName" to "Event announcements",
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
                "topicDisplayName" to "Event announcements",
                "aggregation" to "WEEKLY",
                "status" to "SENT",
                "providerId" to "mock-digest-jdoe",
                "sentAt" to "2026-08-10T09:00:00"),
            mapOf(
                "id" to 2L,
                "topic" to "ACCOUNT",
                "topicDisplayName" to "Account",
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
