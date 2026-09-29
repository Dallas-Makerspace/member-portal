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
  fun enrichNotificationUsesMemberFriendlyLabelsForFailures() {
    val failed = enrichNotification(mapOf("status" to "FAILED"))
    assertEquals("Retrying", failed["status_display"])
    assertEquals("warning", failed["status_class"])

    val deadLetter = enrichNotification(mapOf("status" to "DEAD_LETTER"))
    assertEquals("Not delivered", deadLetter["status_display"])
    assertEquals("danger", deadLetter["status_class"])
    assertEquals("We couldn't deliver this email.", deadLetter["status_reason"])
  }

  @Test
  fun enrichNotificationHidesRawErrorsFromMembers() {
    val row = mapOf("status" to "DEAD_LETTER", "lastError" to "SparkPost 400: {\"errors\":[]}")

    assertNull(enrichNotification(row)["error_detail"])
    assertEquals("SparkPost 400: {\"errors\":[]}", enrichNotification(row, showErrors = true)["error_detail"])
  }

  @Test
  fun enrichNotificationExplainsSkippedAndDigestRows() {
    val skipped =
        enrichNotification(
            mapOf("status" to "SKIPPED", "lastError" to "Disabled in notification preferences"))
    assertEquals(
        "Not sent because this topic is turned off in your preferences.", skipped["status_reason"])

    // 13:00 UTC in August is 8:00 AM Central.
    val inDigest =
        enrichNotification(mapOf("status" to "IN_DIGEST", "nextAttemptAt" to "2026-08-10T13:00"))
    assertEquals(
        "Will be included in your digest on Mon, Aug 10 at 8:00 AM.", inDigest["status_reason"])
  }

  @Test
  fun enrichNotificationConvertsTimestampsToCentral() {
    val enriched = enrichNotification(mapOf("status" to "SENT", "sentAt" to "2026-08-10T03:30:00"))
    assertEquals("2026-08-09T22:30", enriched["when"])
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
                    "digestSupported" to true,
                    "visibleInPreferences" to true,
                )))

    val eventAnnouncement = grouped.first { it["topic"] == "EVENT_ANNOUNCEMENT" }
    assertFalse(eventAnnouncement["enabled"] as Boolean)
    assertEquals("WEEKLY", eventAnnouncement["effectiveAggregation"])
    assertEquals("Weekly digest", eventAnnouncement["effectiveAggregationDisplay"])
    assertTrue(eventAnnouncement["digestSupported"] as Boolean)
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
    assertEquals("Digest email (2 updates)", group["subject"])
    assertEquals("Event announcements", group["topic_display"])
    assertEquals("2026-08-10T04:00", group["when"])
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
        setOf("2026-08-10T04:00", "2026-08-03T04:00"), grouped.map { it["when"] }.toSet())
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

  private val immediateTopic =
      listOf(
          mapOf(
              "topic" to "EVENT_ANNOUNCEMENT",
              "enabled" to true,
              "digestSupported" to true,
              "effectiveAggregation" to "IMMEDIATE"))

  private fun sentEmails(count: Int, sentAt: String, sharedProvider: Boolean = false) =
      (1..count).map { i ->
        mapOf(
            "id" to i.toLong(),
            "status" to "SENT",
            "aggregation" to "IMMEDIATE",
            "providerId" to if (sharedProvider) "txn" else "txn-$i",
            "sentAt" to sentAt)
      }

  @Test
  fun suggestsDailyDigestAfterManyImmediateEmailsThisWeek() {
    val now = java.time.LocalDateTime.of(2026, 9, 29, 12, 0)

    assertTrue(shouldSuggestDailyDigest(sentEmails(10, "2026-09-27T12:00:00"), immediateTopic, now))
    assertFalse(shouldSuggestDailyDigest(sentEmails(9, "2026-09-27T12:00:00"), immediateTopic, now))
  }

  @Test
  fun digestSuggestionCountsEmailsNotRowsAndIgnoresOldOnes() {
    val now = java.time.LocalDateTime.of(2026, 9, 29, 12, 0)

    assertFalse(
        shouldSuggestDailyDigest(
            sentEmails(12, "2026-09-27T12:00:00", sharedProvider = true), immediateTopic, now))
    assertFalse(shouldSuggestDailyDigest(sentEmails(12, "2026-09-01T12:00:00"), immediateTopic, now))
  }

  @Test
  fun noDigestSuggestionWhenAlreadyOnDigest() {
    val now = java.time.LocalDateTime.of(2026, 9, 29, 12, 0)
    val onDaily = listOf(immediateTopic[0] + ("effectiveAggregation" to "DAILY"))

    assertFalse(shouldSuggestDailyDigest(sentEmails(20, "2026-09-27T12:00:00"), onDaily, now))
  }

  @Test
  fun explainsRowsMovedToDigestByTheDailyLimit() {
    val enriched =
        enrichNotification(
            mapOf(
                "status" to "IN_DIGEST",
                "lastError" to "Daily email limit reached; moved to the next daily digest (15)",
                "nextAttemptAt" to "2026-08-11T13:00"))

    assertEquals(
        "You reached today's email limit, so this will be in your digest on Tue, Aug 11 at 8:00 AM.",
        enriched["status_reason"])
  }

  @Test
  fun labelsHoldWindowGroupsAsCombinedEmail() {
    val rows = sentEmails(2, "2026-08-10T09:00:00", sharedProvider = true)

    assertEquals("Combined email (2 updates)", groupDigests(rows).single()["subject"])
  }
}
