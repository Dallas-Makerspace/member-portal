package org.dallasmakerspace.webhook

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.calendar.CalendarService
import org.dallasmakerspace.calendar.EventPrerequisite
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.FanOutResult
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.db.CommitteeSubscriptionsRepository
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic
import org.dallasmakerspace.webhook.handlers.CommitteeClassAnnouncer
import org.dallasmakerspace.webhook.handlers.CommitteeFollowerFilter
import org.dallasmakerspace.webhook.models.CalendarEventWebhookData
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CommitteeClassAnnouncerTest {

  private val calendar = mock<CalendarService>()
  private val subscriptions = mock<CommitteeSubscriptionsRepository>()
  private val notifications = mock<NotificationService>()
  private val ad = mock<IActiveDirectoryService>()
  private val announcer = CommitteeClassAnnouncer(calendar, subscriptions, notifications, CommitteeFollowerFilter(ad))

  private val laser = Committees.findByName("Laser")!!
  private val event =
      CalendarEventWebhookData(
          id = 12345,
          organizerUsername = "jdoe",
          name = "Intro to Laser Cutting",
          eventStart = "2026-08-10T19:00:00-05:00",
          url = "https://calendar.example.org/events/view/12345")

  private fun adUser(username: String, vararg groups: String) =
      ADUser(
          sAMAccountName = username,
          mail = "$username@example.com",
          givenName = null,
          sn = null,
          telephoneNumber = null,
          employeeID = null,
          displayName = username,
          objectGuid = "guid-$username",
          whenCreated = null,
          enabled = true,
          groups =
              groups.map {
                ADGroup(
                    cn = it,
                    description = null,
                    distinguishedName = "CN=$it,OU=Groups,DC=example,DC=internal",
                    objectGuid = null,
                    members = emptyList(),
                    membersListIncomplete = false)
              })

  private suspend fun givenEvent(prerequisite: EventPrerequisite?) {
    whenever(calendar.getEventPrerequisite(12345)).thenReturn(prerequisite)
  }

  private suspend fun givenSubscribers(vararg names: String) {
    whenever(subscriptions.getSubscribers(any())).thenReturn(names.toList())
  }

  private suspend fun givenFanOut() {
    whenever(notifications.createForMembers(any(), any(), any(), anyOrNull(), any(), any()))
        .thenReturn(FanOutResult(created = 1, duplicates = 0, failed = 0))
  }

  private suspend fun verifyNothingSent() {
    verify(notifications, never()).createForMembers(any(), any(), any(), anyOrNull(), any(), any())
  }

  private val payload = argumentCaptor<String>()
  private val dedupe = argumentCaptor<(String) -> String?>()

  @Test
  fun `notifies followers of the owning committee who do not already have the group`() =
      runBlocking {
        givenEvent(EventPrerequisite("Laser Basics", null))
        givenSubscribers("alice", "bob")
        whenever(ad.getMembersByUsernameList(listOf("alice", "bob")))
            .thenReturn(
                mapOf(
                    "alice" to adUser("alice", "Laser Basics", "Other"),
                    "bob" to adUser("bob", "Other")))
        givenFanOut()

        val summary = announcer.announce(event, "Mon Aug 10 7:00 PM", emptySet(), "")

        verify(subscriptions).getSubscribers(listOf(laser.id))
        verify(notifications)
            .createForMembers(
                eq(listOf("bob")),
                eq(CommitteeUpdatesTopic),
                eq("committee-class"),
                payload.capture(),
                eq(Channel.EMAIL),
                dedupe.capture())
        assertTrue(payload.lastValue.contains("\"committeeName\":\"Laser\""))
        assertTrue(payload.lastValue.contains("\"eventName\":\"Intro to Laser Cutting\""))
        assertTrue(payload.lastValue.contains("Mon Aug 10 7:00 PM"))
        assertEquals("committee-class-12345-bob", dedupe.lastValue("bob"))
        assertTrue(summary.contains("notified 1 of 1"), summary)
        assertTrue(summary.contains("already have it=1"), summary)
      }

  @Test
  fun `the event image goes into the class notification`() = runBlocking {
    givenEvent(EventPrerequisite("Laser Basics", null))
    givenSubscribers("bob")
    whenever(ad.getMembersByUsernameList(listOf("bob"))).thenReturn(mapOf("bob" to adUser("bob")))
    givenFanOut()

    announcer.announce(event, "", emptySet(), "https://calendar.example.org/files/1/a.png")

    verify(notifications)
        .createForMembers(any(), any(), any(), payload.capture(), any(), any())
    assertTrue(
        payload.lastValue.contains("\"imageUrl\":\"https://calendar.example.org/files/1/a.png\""),
        payload.lastValue)
    Unit
  }

  @Test
  fun `group membership is compared ignoring case`() = runBlocking {
    givenEvent(EventPrerequisite("Laser Basics", null))
    givenSubscribers("alice")
    whenever(ad.getMembersByUsernameList(listOf("alice")))
        .thenReturn(mapOf("alice" to adUser("alice", "LASER BASICS")))

    val summary = announcer.announce(event, "", emptySet(), "")

    verifyNothingSent()
    assertTrue(summary.contains("already have it"), summary)
  }

  @Test
  fun `sessions of a multi-part event share one key so members are told once`() = runBlocking {
    givenEvent(EventPrerequisite("Laser Basics", partOfId = 77))
    givenSubscribers("bob")
    whenever(ad.getMembersByUsernameList(listOf("bob"))).thenReturn(mapOf("bob" to adUser("bob")))
    givenFanOut()

    announcer.announce(event, "", emptySet(), "")

    verify(notifications)
        .createForMembers(any(), any(), any(), anyOrNull(), any(), dedupe.capture())
    assertEquals("committee-class-77-bob", dedupe.lastValue("bob"))
  }

  @Test
  fun `skips the organizer and members already told by the organizer fan-out`() = runBlocking {
    givenEvent(EventPrerequisite("Laser Basics", null))
    givenSubscribers("jdoe", "alice", "bob")
    whenever(ad.getMembersByUsernameList(listOf("bob"))).thenReturn(mapOf("bob" to adUser("bob")))
    givenFanOut()

    announcer.announce(event, "", alreadyNotified = setOf("alice"), imageUrl = "")

    verify(notifications)
        .createForMembers(eq(listOf("bob")), any(), any(), anyOrNull(), any(), any())
    Unit
  }

  @Test
  fun `a lookup failure or an event without a group sends nothing`() = runBlocking {
    givenEvent(null)
    assertTrue(announcer.announce(event, "", emptySet(), "").contains("lookup failed"))

    givenEvent(EventPrerequisite(null, null))
    assertTrue(announcer.announce(event, "", emptySet(), "").contains("grants no group"))

    verify(subscriptions, never()).getSubscribers(any())
    verifyNothingSent()
  }

  @Test
  fun `a group no committee owns sends nothing`() = runBlocking {
    givenEvent(EventPrerequisite("Board of Directors", null))

    val summary = announcer.announce(event, "", emptySet(), "")

    assertTrue(summary.contains("no committee owns"), summary)
    verify(subscriptions, never()).getSubscribers(any())
    verifyNothingSent()
  }

  @Test
  fun `no followers sends nothing and skips the AD lookup`() = runBlocking {
    givenEvent(EventPrerequisite("Laser Basics", null))
    givenSubscribers()

    announcer.announce(event, "", emptySet(), "")

    verify(ad, never()).getMembersByUsernameList(any())
    verifyNothingSent()
  }

  @Test
  fun `if AD fails its batch is kept rather than losing the announcement`() = runBlocking {
    givenEvent(EventPrerequisite("Laser Basics", null))
    givenSubscribers("alice", "bob")
    whenever(ad.getMembersByUsernameList(any())).thenThrow(RuntimeException("ldap down"))
    givenFanOut()

    announcer.announce(event, "", emptySet(), "")

    verify(notifications)
        .createForMembers(eq(listOf("alice", "bob")), any(), any(), anyOrNull(), any(), any())
    Unit
  }

  @Test
  fun `followers missing from AD are kept`() = runBlocking {
    givenEvent(EventPrerequisite("Laser Basics", null))
    givenSubscribers("alice", "ghost")
    whenever(ad.getMembersByUsernameList(listOf("alice", "ghost")))
        .thenReturn(mapOf("alice" to adUser("alice"), "ghost" to null))
    givenFanOut()

    announcer.announce(event, "", emptySet(), "")

    verify(notifications)
        .createForMembers(eq(listOf("alice", "ghost")), any(), any(), anyOrNull(), any(), any())
    Unit
  }

  @Test
  fun `large follower lists are looked up in batches of 100`() = runBlocking {
    val names = (1..250).map { "member$it" }
    givenEvent(EventPrerequisite("Laser Basics", null))
    whenever(subscriptions.getSubscribers(any())).thenReturn(names)
    whenever(ad.getMembersByUsernameList(any())).thenAnswer { invocation ->
      @Suppress("UNCHECKED_CAST")
      (invocation.arguments[0] as List<String>).associateWith { adUser(it) }
    }
    givenFanOut()

    announcer.announce(event, "", emptySet(), "")

    verify(ad, times(3)).getMembersByUsernameList(any())
    verify(notifications)
        .createForMembers(eq(names), any(), any(), anyOrNull(), any(), any())
    Unit
  }
}
