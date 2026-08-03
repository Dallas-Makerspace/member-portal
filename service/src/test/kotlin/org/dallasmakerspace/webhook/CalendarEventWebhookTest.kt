package org.dallasmakerspace.webhook

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.CreateNotificationRequest
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.NotificationTopic
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.dallasmakerspace.webhook.handlers.CalendarEventWebhook
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CalendarEventWebhookTest {

  private val mockSubscriptions = mock<ProfileSubscriptionService>()
  private val mockNotifications = mock<NotificationService>()
  private val mockAd = mock<IActiveDirectoryService>()
  private val handler = CalendarEventWebhook(mockSubscriptions, mockNotifications, mockAd)

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

  private fun requestBody(eventId: String = "12345") =
      """
      {
        "eventId": "$eventId",
        "organizerUsername": "jphelps",
        "eventName": "Intro to Laser Cutting",
        "eventStart": "2026-08-10T19:00:00-05:00",
        "eventUrl": "https://calendar.dallasmakerspace.org/events/view/$eventId"
      }
      """
          .trimIndent()

  @Test
  fun `handleWebhook should notify every follower with the resolved organizer name`() =
      runBlocking {
        whenever(mockSubscriptions.getFollowers("jphelps")).thenReturn(listOf("alice", "bob"))
        whenever(mockAd.getMembersByUsernameList(listOf("jphelps")))
            .thenReturn(mapOf("jphelps" to adUser("jphelps", "Jeff Phelps")))
        whenever(mockNotifications.create(any())).thenReturn(mock())

        val result = handler.handleWebhook(requestBody())

        assertTrue(result.success)
        assertTrue(result.message.contains("Notified 2 of 2"))
        verify(mockNotifications)
            .create(
                argThat<CreateNotificationRequest> {
                  memberUsername == "alice" &&
                      topic == NotificationTopic.EVENT_ANNOUNCEMENT &&
                      templateName == "event-announcement" &&
                      channel == Channel.EMAIL &&
                      dedupeKey == "event-announcement-12345-alice" &&
                      payload!!.contains("\"organizerName\":\"Jeff Phelps\"") &&
                      payload!!.contains("\"organizerUsername\":\"jphelps\"") &&
                      payload!!.contains("Mon Aug 10")
                })
        verify(mockNotifications)
            .create(
                argThat<CreateNotificationRequest> { dedupeKey == "event-announcement-12345-bob" })
        Unit
      }

  @Test
  fun `handleWebhook should fall back to username when AD has no display name`() = runBlocking {
    whenever(mockSubscriptions.getFollowers("jphelps")).thenReturn(listOf("alice"))
    whenever(mockAd.getMembersByUsernameList(listOf("jphelps")))
        .thenReturn(mapOf("jphelps" to null))
    whenever(mockNotifications.create(any())).thenReturn(mock())

    handler.handleWebhook(requestBody())

    verify(mockNotifications)
        .create(
            argThat<CreateNotificationRequest> {
              payload!!.contains("\"organizerName\":\"jphelps\"")
            })
    Unit
  }

  @Test
  fun `handleWebhook should skip and report when the organizer has no followers`() = runBlocking {
    whenever(mockSubscriptions.getFollowers("jphelps")).thenReturn(emptyList())

    val result = handler.handleWebhook(requestBody())

    assertTrue(result.success)
    assertTrue(result.message.contains("No followers"))
    verify(mockNotifications, never()).create(any())
    Unit
  }

  @Test
  fun `handleWebhook should continue notifying remaining followers when one create fails`() =
      runBlocking {
        whenever(mockSubscriptions.getFollowers("jphelps")).thenReturn(listOf("alice", "bob"))
        whenever(mockAd.getMembersByUsernameList(listOf("jphelps")))
            .thenReturn(mapOf("jphelps" to adUser("jphelps", "Jeff Phelps")))
        whenever(
                mockNotifications.create(
                    argThat<CreateNotificationRequest> { memberUsername == "alice" }))
            .thenThrow(IllegalStateException("Unknown member username: alice"))
        whenever(
                mockNotifications.create(
                    argThat<CreateNotificationRequest> { memberUsername == "bob" }))
            .thenReturn(mock())

        val result = handler.handleWebhook(requestBody())

        assertTrue(result.success)
        assertTrue(result.message.contains("Notified 1 of 2"))
        verify(mockNotifications, times(2)).create(any())
        Unit
      }

  @Test
  fun `handleWebhook should reject malformed json`() = runBlocking {
    val result = handler.handleWebhook("not json")

    assertEquals(false, result.success)
  }

  @Test
  fun `getName should be calendar-event`() {
    assertEquals("calendar-event", handler.getName())
  }
}
