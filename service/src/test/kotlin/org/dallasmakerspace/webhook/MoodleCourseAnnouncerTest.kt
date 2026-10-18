package org.dallasmakerspace.webhook

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.keyvalue.KeyValueStore
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.moodle.LearnCourse
import org.dallasmakerspace.moodle.MoodleCatalogService
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.FanOutResult
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.db.CommitteeSubscriptionsRepository
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic
import org.dallasmakerspace.webhook.handlers.CommitteeFollowerFilter
import org.dallasmakerspace.webhook.handlers.MoodleCourseAnnouncer
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MoodleCourseAnnouncerTest {

  private class InMemoryKeyValueStore : KeyValueStore {
    val data = mutableMapOf<Pair<String, String>, String>()

    override suspend fun get(namespace: String, key: String) = data[namespace to key]

    override suspend fun put(namespace: String, key: String, value: String) {
      data[namespace to key] = value
    }

    override suspend fun putIfAbsent(namespace: String, key: String, value: String) =
        data.putIfAbsent(namespace to key, value) == null

    override suspend fun putAllIfAbsent(namespace: String, entries: Map<String, String>) =
        entries.count { (k, v) -> putIfAbsent(namespace, k, v) }

    override suspend fun contains(namespace: String, key: String) = (namespace to key) in data

    override suspend fun delete(namespace: String, key: String) = data.remove(namespace to key) != null

    override suspend fun keys(namespace: String, prefix: String) =
        data.keys.filter { it.first == namespace && it.second.startsWith(prefix) }.map { it.second }.toSet()
  }

  private val catalog = mock<MoodleCatalogService>()
  private val store = InMemoryKeyValueStore()
  private val subscriptions = mock<CommitteeSubscriptionsRepository>()
  private val notifications = mock<NotificationService>()
  private val ad = mock<IActiveDirectoryService>()
  private val announcer =
      MoodleCourseAnnouncer(catalog, store, subscriptions, notifications, CommitteeFollowerFilter(ad))

  private val laser = Committees.findByName("Laser")!!
  private val woodshop = Committees.findByName("Woodshop")!!
  private val ns = MoodleCourseAnnouncer.NAMESPACE

  private fun course(
      id: Int,
      committee: String? = "laser",
      visible: Boolean = true,
      selfEnrol: Boolean = true,
      adGroup: String? = null,
  ) =
      LearnCourse(
          id = id,
          shortname = "EX-$id",
          name = "Example Course $id",
          summary = "",
          url = "https://learn.example.org/course/view.php?id=$id",
          visible = visible,
          categoryName = "Example",
          committeeSlug = committee,
          committeeName = committee,
          selfEnrol = selfEnrol,
          adGroup = adGroup)

  private suspend fun givenCatalog(vararg courses: LearnCourse) {
    whenever(catalog.refreshNow()).thenReturn(courses.toList())
  }

  private suspend fun givenFollowers(committeeId: Int, vararg names: String) {
    whenever(subscriptions.getSubscribers(listOf(committeeId))).thenReturn(names.toList())
  }

  private suspend fun givenFanOut(result: FanOutResult = FanOutResult(1, 0, 0)) {
    whenever(notifications.createForMembers(any(), any(), any(), anyOrNull(), any(), any()))
        .thenReturn(result)
  }

  private suspend fun verifyNothingSent() =
      verify(notifications, never()).createForMembers(any(), any(), any(), anyOrNull(), any(), any())

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

  private val payload = argumentCaptor<String>()
  private val dedupe = argumentCaptor<(String) -> String?>()

  /** The first pass: everything visible becomes the baseline. */
  private suspend fun seed(vararg courses: LearnCourse) {
    givenCatalog(*courses)
    announcer.announce()
  }

  @Test
  fun `the first pass seeds every visible course and notifies nobody`() = runBlocking<Unit> {
    givenCatalog(course(1), course(2, visible = false), course(3, committee = null))
    givenFollowers(laser.id, "alice")

    val result = announcer.announce()

    assertTrue(result.success)
    assertTrue(result.message.contains("seeded 2"), result.message)
    assertEquals(setOf("1", "3", "_baseline"), store.keys(ns))
    verifyNothingSent()
  }

  @Test
  fun `after seeding, an old course that is edited is not announced`() = runBlocking<Unit> {
    seed(course(1), course(2))
    givenFollowers(laser.id, "alice")

    val result = announcer.announce()

    assertEquals("Moodle courses: nothing new", result.message)
    verifyNothingSent()
  }

  @Test
  fun `a newly visible course is announced to the committee's followers, then marked`() =
      runBlocking<Unit> {
        seed(course(1), course(2, visible = false))
        givenCatalog(course(1), course(2))
        givenFollowers(laser.id, "alice", "bob")
        givenFanOut(FanOutResult(2, 0, 0))

        val result = announcer.announce()

        assertTrue(result.success)
        assertTrue(result.message.contains("announced 1 of 1"), result.message)
        assertTrue(result.message.contains("2 notification(s)"), result.message)
        verify(notifications)
            .createForMembers(
                eq(listOf("alice", "bob")),
                eq(CommitteeUpdatesTopic),
                eq("committee-course"),
                payload.capture(),
                eq(Channel.EMAIL),
                dedupe.capture())
        assertTrue(payload.lastValue.contains("\"courseName\":\"Example Course 2\""))
        assertTrue(payload.lastValue.contains("\"committeeName\":\"Laser\""))
        assertTrue(payload.lastValue.contains("course/view.php?id=2"))
        assertEquals("committee-course-2-alice", dedupe.lastValue("alice"))
        assertTrue(store.contains(ns, "2"))
      }

  @Test
  fun `a course is announced only once even if the pass runs again or it is hidden and shown`() =
      runBlocking<Unit> {
        seed(course(1))
        givenCatalog(course(1), course(2))
        givenFollowers(laser.id, "alice")
        givenFanOut()
        announcer.announce()

        announcer.announce()
        givenCatalog(course(1), course(2, visible = false))
        announcer.announce()
        givenCatalog(course(1), course(2))
        announcer.announce()

        verify(notifications, org.mockito.kotlin.times(1))
            .createForMembers(any(), any(), any(), anyOrNull(), any(), any())
      }

  @Test
  fun `hidden, unmapped and non-self-enrol courses are not announced`() = runBlocking<Unit> {
    seed(course(1))
    givenCatalog(
        course(1),
        course(2, visible = false),
        course(3, committee = null),
        course(4, selfEnrol = false))
    givenFollowers(laser.id, "alice")

    val result = announcer.announce()

    assertEquals("Moodle courses: nothing new", result.message)
    verifyNothingSent()
  }

  @Test
  fun `a course that only becomes joinable later is announced then`() = runBlocking<Unit> {
    seed(course(1))
    givenCatalog(course(1), course(2, selfEnrol = false))
    announcer.announce()
    verifyNothingSent()

    givenCatalog(course(1), course(2, selfEnrol = true))
    givenFollowers(laser.id, "alice")
    givenFanOut()
    announcer.announce()

    verify(notifications)
        .createForMembers(eq(listOf("alice")), any(), any(), anyOrNull(), any(), any())
  }

  @Test
  fun `followers already in the course's AD group are skipped`() = runBlocking<Unit> {
    seed(course(1))
    givenCatalog(course(1), course(2, adGroup = "Laser Basics"))
    givenFollowers(laser.id, "alice", "bob")
    whenever(ad.getMembersByUsernameList(listOf("alice", "bob")))
        .thenReturn(mapOf("alice" to adUser("alice", "Laser Basics"), "bob" to adUser("bob")))
    givenFanOut()

    announcer.announce()

    verify(notifications)
        .createForMembers(eq(listOf("bob")), any(), any(), anyOrNull(), any(), any())
  }

  @Test
  fun `with no AD group every follower is notified and AD is not asked`() = runBlocking<Unit> {
    seed(course(1))
    givenCatalog(course(1), course(2, adGroup = null))
    givenFollowers(laser.id, "alice", "bob")
    givenFanOut()

    announcer.announce()

    verify(notifications)
        .createForMembers(eq(listOf("alice", "bob")), any(), any(), anyOrNull(), any(), any())
    verify(ad, never()).getMembersByUsernameList(any())
  }

  @Test
  fun `each course goes to its own committee's followers`() = runBlocking<Unit> {
    seed(course(1))
    givenCatalog(course(1), course(2, committee = "laser"), course(3, committee = "woodshop"))
    givenFollowers(laser.id, "alice")
    givenFollowers(woodshop.id, "bob")
    givenFanOut()

    announcer.announce()

    verify(notifications)
        .createForMembers(eq(listOf("alice")), any(), any(), anyOrNull(), any(), any())
    verify(notifications)
        .createForMembers(eq(listOf("bob")), any(), any(), anyOrNull(), any(), any())
    assertTrue(store.contains(ns, "2") && store.contains(ns, "3"))
  }

  @Test
  fun `a course nobody follows is still marked so later followers aren't told about it`() =
      runBlocking<Unit> {
        seed(course(1))
        givenCatalog(course(1), course(2))
        givenFollowers(laser.id)

        val result = announcer.announce()

        assertTrue(result.success)
        assertTrue(store.contains(ns, "2"))
        verifyNothingSent()
      }

  @Test
  fun `a failed fan-out leaves the course unmarked and reports failure so Moodle retries`() =
      runBlocking<Unit> {
        seed(course(1))
        givenCatalog(course(1), course(2))
        givenFollowers(laser.id, "alice", "bob")
        givenFanOut(FanOutResult(created = 1, duplicates = 0, failed = 1))

        val result = announcer.announce()

        assertFalse(result.success)
        assertTrue(result.message.contains("1 to retry"), result.message)
        assertFalse(store.contains(ns, "2"))

        // The retry succeeds, and the per-follower dedupe keys absorb the one already created.
        givenFanOut(FanOutResult(created = 1, duplicates = 1, failed = 0))
        assertTrue(announcer.announce().success)
        assertTrue(store.contains(ns, "2"))
      }

  @Test
  fun `an unreachable Moodle throws and changes nothing`() = runBlocking<Unit> {
    whenever(catalog.refreshNow()).thenThrow(RuntimeException("moodle down"))

    val failure = runCatching { announcer.announce() }.exceptionOrNull()

    assertEquals("moodle down", failure?.message)
    assertTrue(store.keys(ns).isEmpty())
    verifyNothingSent()
  }
}
