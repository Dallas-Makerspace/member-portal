package org.dallasmakerspace.webhook

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.FanOutResult
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.dallasmakerspace.notifications.topics.FollowingTopic
import org.dallasmakerspace.webhook.handlers.CalendarEventWebhook
import org.dallasmakerspace.webhook.handlers.CommitteeClassAnnouncer
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CalendarEventWebhookTest {

  private val mockSubscriptions = mock<ProfileSubscriptionService>()
  private val mockNotifications = mock<NotificationService>()
  private val mockAd = mock<IActiveDirectoryService>()
  private val mockAnnouncer = mock<CommitteeClassAnnouncer>()
  private val handler =
      CalendarEventWebhook(mockSubscriptions, mockNotifications, mockAd, mockAnnouncer)

  init {
    runBlocking {
      whenever(mockAnnouncer.announce(any(), any(), any()))
          .thenReturn("Committee followers: none to notify")
    }
  }

  private fun adUser(username: String, displayName: String?) =
      ADUser(
          sAMAccountName = username,
          mail = "$username@example.com",
          givenName = null,
          sn = null,
          telephoneNumber = null,
          employeeID = null,
          displayName = displayName,
          objectGuid = "guid",
          whenCreated = null,
          enabled = true,
          groups = emptyList())

  private fun requestBody(
      eventId: Long = 12345,
      eventType: String = "event.published",
      eventStart: String = "2026-08-10T19:00:00-05:00",
      eventStartTimezone: String? = null,
  ) =
      """
      {
        "event_type": "$eventType",
        "occurred_at": "2026-08-01T00:00:00Z",
        "data": {
          "id": $eventId,
          "organizer_username": "jdoe",
          "name": "Intro to Laser Cutting",
          "event_start": "$eventStart",
          ${eventStartTimezone?.let { "\"event_start_timezone\": \"$it\"," } ?: ""}
          "url": "https://calendar.dallasmakerspace.org/events/view/$eventId"
        }
      }
      """
          .trimIndent()

  private val payload = argumentCaptor<String>()
  private val dedupe = argumentCaptor<(String) -> String?>()

  private suspend fun givenFanOut(result: FanOutResult) {
    whenever(
            mockNotifications.createForMembers(any(), any(), any(), anyOrNull(), any(), any()))
        .thenReturn(result)
  }

  private suspend fun verifyFanOut(followers: List<String>) {
    verify(mockNotifications)
        .createForMembers(
            eq(followers),
            eq(FollowingTopic),
            eq("event-announcement"),
            payload.capture(),
            eq(Channel.EMAIL),
            dedupe.capture())
  }

  @Test
  fun `handleWebhook should fan out to every follower with the resolved organizer name`() =
      runBlocking {
        whenever(mockSubscriptions.getFollowers("jdoe")).thenReturn(listOf("alice", "bob"))
        whenever(mockAd.getMembersByUsernameList(listOf("jdoe")))
            .thenReturn(mapOf("jdoe" to adUser("jdoe", "Jane Doe")))
        givenFanOut(FanOutResult(created = 2, duplicates = 0, failed = 0))

        val result = handler.handleWebhook(requestBody())

        assertTrue(result.success)
        assertTrue(result.message.contains("Notified 2 of 2"))
        verifyFanOut(listOf("alice", "bob"))
        assertTrue(payload.lastValue.contains("\"organizerName\":\"Jane Doe\""))
        assertTrue(payload.lastValue.contains("\"organizerUsername\":\"jdoe\""))
        assertTrue(payload.lastValue.contains("Mon Aug 10"))
        assertEquals("event-announcement-12345-alice", dedupe.lastValue("alice"))
      }

  @Test
  fun `handleWebhook should fall back to username when AD has no display name`() = runBlocking {
    whenever(mockSubscriptions.getFollowers("jdoe")).thenReturn(listOf("alice"))
    whenever(mockAd.getMembersByUsernameList(listOf("jdoe"))).thenReturn(mapOf("jdoe" to null))
    givenFanOut(FanOutResult(created = 1, duplicates = 0, failed = 0))

    handler.handleWebhook(requestBody())

    verifyFanOut(listOf("alice"))
    assertTrue(payload.lastValue.contains("\"organizerName\":\"jdoe\""))
  }

  @Test
  fun `handleWebhook should skip and report when the organizer has no followers`() = runBlocking {
    whenever(mockSubscriptions.getFollowers("jdoe")).thenReturn(emptyList())

    val result = handler.handleWebhook(requestBody())

    assertTrue(result.success)
    assertTrue(result.message.contains("No followers"))
    verify(mockNotifications, never())
        .createForMembers(any(), any(), any(), anyOrNull(), any(), any())
    Unit
  }

  @Test
  fun `handleWebhook should report duplicates and failures from the fan-out`() = runBlocking {
    whenever(mockSubscriptions.getFollowers("jdoe")).thenReturn(listOf("alice", "bob", "carol"))
    whenever(mockAd.getMembersByUsernameList(listOf("jdoe")))
        .thenReturn(mapOf("jdoe" to adUser("jdoe", "Jane Doe")))
    givenFanOut(FanOutResult(created = 1, duplicates = 1, failed = 1))

    val result = handler.handleWebhook(requestBody())

    assertTrue(result.success)
    assertTrue(result.message.contains("Notified 1 of 3"))
    assertTrue(result.message.contains("duplicates=1, failed=1"))
  }

  @Test
  fun `handleWebhook should reject malformed json`() = runBlocking {
    val result = handler.handleWebhook("not json")

    assertEquals(false, result.success)
  }

  @Test
  fun `handleWebhook should ignore non-published event types`() = runBlocking {
    val result = handler.handleWebhook(requestBody(eventType = "event.updated"))

    assertTrue(result.success)
    assertTrue(result.message.contains("Ignoring event_type=event.updated"))
    verify(mockSubscriptions, never()).getFollowers(any())
    verify(mockNotifications, never())
        .createForMembers(any(), any(), any(), anyOrNull(), any(), any())
    Unit
  }

  @Test
  fun `handleWebhook should parse a local eventStart using the provided timezone`() = runBlocking {
    whenever(mockSubscriptions.getFollowers("jdoe")).thenReturn(listOf("alice"))
    whenever(mockAd.getMembersByUsernameList(listOf("jdoe")))
        .thenReturn(mapOf("jdoe" to adUser("jdoe", "Jane Doe")))
    givenFanOut(FanOutResult(created = 1, duplicates = 0, failed = 0))

    handler.handleWebhook(
        requestBody(eventStart = "2026-08-11T00:00:00", eventStartTimezone = "UTC"))

    // 2026-08-11T00:00:00 UTC is 2026-08-10 19:00 Central (UTC-5 in August).
    verifyFanOut(listOf("alice"))
    assertTrue(payload.lastValue.contains("Mon Aug 10"))
  }

  @Test
  fun `handleWebhook should still announce to committee followers when the organizer has none`() =
      runBlocking {
        whenever(mockSubscriptions.getFollowers("jdoe")).thenReturn(emptyList())

        val result = handler.handleWebhook(requestBody())

        assertTrue(result.success)
        assertTrue(result.message.contains("No followers for jdoe"))
        assertTrue(result.message.contains("Committee followers: none to notify"))
        verify(mockAnnouncer).announce(any(), eq("Mon Aug 10 7:00 PM"), eq(emptySet()))
        Unit
      }

  @Test
  fun `handleWebhook should tell the announcer who the organizer fan-out already notified`() =
      runBlocking {
        whenever(mockSubscriptions.getFollowers("jdoe")).thenReturn(listOf("alice", "bob"))
        whenever(mockAd.getMembersByUsernameList(listOf("jdoe")))
            .thenReturn(mapOf("jdoe" to adUser("jdoe", "Jane Doe")))
        givenFanOut(FanOutResult(created = 2, duplicates = 0, failed = 0))

        handler.handleWebhook(requestBody())

        verify(mockAnnouncer)
            .announce(
                argThat { id == 12345L && organizerUsername == "jdoe" },
                any(),
                eq(setOf("alice", "bob")))
        Unit
      }

  @Test
  fun `handleWebhook should succeed when the committee announcement fails`() = runBlocking {
    whenever(mockSubscriptions.getFollowers("jdoe")).thenReturn(listOf("alice"))
    whenever(mockAd.getMembersByUsernameList(listOf("jdoe")))
        .thenReturn(mapOf("jdoe" to adUser("jdoe", "Jane Doe")))
    givenFanOut(FanOutResult(created = 1, duplicates = 0, failed = 0))
    whenever(mockAnnouncer.announce(any(), any(), any()))
        .thenThrow(IllegalStateException("committee_subscriptions doesn't exist"))

    val result = handler.handleWebhook(requestBody())

    assertTrue(result.success)
    assertTrue(result.message.contains("Notified 1 of 1"))
    assertTrue(result.message.contains("Committee followers: failed"))
    verifyFanOut(listOf("alice"))
  }

  @Test
  fun `handleWebhook should not announce to committees for other event types`() = runBlocking {
    handler.handleWebhook(requestBody(eventType = "event.updated"))

    verify(mockAnnouncer, never()).announce(any(), any(), any())
    Unit
  }

  @Test
  fun `getName should be calendar-event`() {
    assertEquals("calendar-event", handler.getName())
  }
}
