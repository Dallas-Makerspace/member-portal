package org.dallasmakerspace.committees

import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.discourse.DiscourseApiException
import org.dallasmakerspace.discourse.DiscoursePostResponse
import org.dallasmakerspace.discourse.DiscourseTopicDetails
import org.dallasmakerspace.discourse.DiscourseTopicNotFoundException
import org.dallasmakerspace.discourse.DiscourseUserNotFoundException
import org.dallasmakerspace.discourse.DiscourseUserProfile
import org.dallasmakerspace.discourse.IDiscourseApiClient
import org.dallasmakerspace.members.db.ProfileTable
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.RedirectType
import org.dallasmakerspace.models.ShortLink
import org.dallasmakerspace.notifications.FanOutResult
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.db.CommitteeMessagesRepository
import org.dallasmakerspace.notifications.db.CommitteeSubscriptionsRepository
import org.dallasmakerspace.notifications.db.Reservation
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic
import org.dallasmakerspace.shortlinks.ShortLinksService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class CommitteeMessageServiceTest {

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      val db =
          Database.connect(
              "jdbc:h2:mem:committee-message-service;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) {
        SchemaUtils.create(ProfileTable)
        ProfileTable.insert {
          it[username] = "chairperson"
          it[discourseUsername] = "chair_talk"
        }
        ProfileTable.insert { it[username] = "unlinked-chair" }
      }
    }
  }

  private val laser = Committees.findByName("Laser")!!
  private val ad = mock<IActiveDirectoryService>()
  private val flags = mock<FeatureFlagService>()
  private val config = mock<ConfigOverrideService>()
  private val discourse = mock<IDiscourseApiClient>()
  private val shortLinks = mock<ShortLinksService>()
  private val followers = mock<CommitteeSubscriptionsRepository>()
  private val messages = mock<CommitteeMessagesRepository>()
  private val notifications = mock<NotificationService>()
  private lateinit var service: CommitteeMessageService

  @Before
  fun setUp() = runBlocking<Unit> {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    service =
        CommitteeMessageService(
            ad, flags, config, discourse, shortLinks, followers, messages, notifications,
            loggerFactory)

    whenever(flags.isEnabledForUsername(eq(ConfigRegistry.COMMITTEE_BROADCAST_ENABLED), any()))
        .thenReturn(true)
    whenever(config.get(ConfigRegistry.COMMITTEE_TALK_CATEGORIES)).thenReturn("9=10")
    asChair("chairperson", laser.chairGroupName!!)
    asChair("unlinked-chair", laser.chairGroupName!!)
    whenever(discourse.getUserProfile(any())).thenReturn(mock<DiscourseUserProfile>())
    whenever(discourse.getTopic(42))
        .thenReturn(DiscourseTopicDetails(id = 42, slug = "meeting-notes", title = "Meeting notes"))
    whenever(followers.getFollowers(laser.id)).thenReturn(listOf("chairperson", "asmith", "bsmith"))
    whenever(messages.reserve(any(), any(), any(), any(), any())).thenReturn(Reservation.Reserved(7))
    whenever(shortLinks.createShortLink(anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull()))
        .thenReturn(Result.success(shortLink("abc123")))
    whenever(
            notifications.createForMembers(
                any(), any(), any(), anyOrNull(), any(), any(), any()))
        .thenReturn(FanOutResult(created = 2, duplicates = 0, failed = 0))
  }

  private fun asChair(username: String, vararg groups: String) {
    whenever(ad.getMemberByUsername(username))
        .thenReturn(
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
                    }))
  }

  private fun shortLink(slug: String) =
      ShortLink(
          id = 1,
          namespaceId = null,
          slug = slug,
          redirectType = RedirectType.BASIC,
          destinationUrl = "https://example.test",
          description = null,
          creatorId = null,
          updatedBy = null,
          isActive = true,
          createdAt = Clock.System.now(),
          updatedAt = Clock.System.now())

  private fun existingThread(message: String = "Meeting moved to Thursday") =
      CommitteeMessageRequest(
          message = message, talkUrl = "https://talk.dallasmakerspace.org/t/meeting-notes/42/1")

  private fun errorOf(block: suspend () -> Unit): CommitteeMessageException =
      assertFailsWith<CommitteeMessageException> { runBlocking { block() } }

  @Test
  fun `a chair sends to followers other than themselves and gets the short link`() = runBlocking {
    val result = service.send("chairperson", laser.id, existingThread())

    assertEquals(7, result.id)
    assertEquals("https://dallas.ms/abc123", result.shortUrl)
    assertEquals("https://talk.dallasmakerspace.org/t/meeting-notes/42", result.talkUrl)
    assertEquals(2, result.recipients)
    assertEquals(false, result.threadCreated)

    val recipients = argumentCaptor<Collection<String>>()
    val dedupe = argumentCaptor<(String) -> String?>()
    val payload = argumentCaptor<String>()
    verify(notifications)
        .createForMembers(
            recipients.capture(),
            eq(CommitteeUpdatesTopic),
            eq("committee-message"),
            payload.capture(),
            any(),
            dedupe.capture(),
            any())
    assertEquals(listOf("asmith", "bsmith"), recipients.firstValue.toList())
    assertEquals("committee-msg-7-asmith", dedupe.firstValue("asmith"))
    assertTrue("Meeting moved to Thursday" in payload.firstValue)
    assertTrue("https://dallas.ms/abc123" in payload.firstValue)
    verify(messages).markSent(7, "https://dallas.ms/abc123", 2)
  }

  @Test
  fun `a chair can have a thread created in their name`() = runBlocking {
    whenever(discourse.createTopicAs(any(), any(), any(), any()))
        .thenReturn(DiscoursePostResponse(id = 1, topicId = 99, topicSlug = "laser-committee"))

    val result =
        service.send(
            "chairperson", laser.id, CommitteeMessageRequest("Meeting moved", createThread = true))

    assertEquals(true, result.threadCreated)
    assertEquals("https://talk.dallasmakerspace.org/t/laser-committee/99", result.talkUrl)
    verify(discourse).createTopicAs(eq("chair_talk"), any(), any(), eq(10))
    verify(messages).attachThread(7, 99, result.talkUrl, true)
  }

  @Test
  fun `someone who is not a chair of the committee is refused`() {
    asChair("chairperson", "Woodshop Committee Chair")

    val error = errorOf { service.send("chairperson", laser.id, existingThread()) }

    assertEquals(CommitteeMessageError.NOT_CHAIR, error.error)
    runBlocking { verify(notifications, never()).createForMembers(any(), any(), any(), anyOrNull(), any(), any(), any()) }
  }

  @Test
  fun `a directory failure means not a chair`() {
    whenever(ad.getMemberByUsername("chairperson")).thenThrow(RuntimeException("AD down"))

    val error = errorOf { service.send("chairperson", laser.id, existingThread()) }

    assertEquals(CommitteeMessageError.NOT_CHAIR, error.error)
  }

  @Test
  fun `the feature flag gates sending`() {
    runBlocking {
      whenever(flags.isEnabledForUsername(eq(ConfigRegistry.COMMITTEE_BROADCAST_ENABLED), any()))
          .thenReturn(false)
    }

    val error = errorOf { service.send("chairperson", laser.id, existingThread()) }

    assertEquals(CommitteeMessageError.NOT_ENABLED, error.error)
  }

  @Test
  fun `a chair without a linked Talk account is refused up front`() {
    val error = errorOf { service.send("unlinked-chair", laser.id, existingThread()) }

    assertEquals(CommitteeMessageError.TALK_NOT_LINKED, error.error)
    runBlocking { verify(messages, never()).reserve(any(), any(), any(), any(), any()) }
  }

  @Test
  fun `a linked Talk account that no longer exists is treated as unlinked`() {
    runBlocking {
      whenever(discourse.getUserProfile("chair_talk"))
          .thenAnswer { throw DiscourseUserNotFoundException("chair_talk") }
    }

    val error = errorOf { service.send("chairperson", laser.id, existingThread()) }

    assertEquals(CommitteeMessageError.TALK_NOT_LINKED, error.error)
  }

  @Test
  fun `a missing thread is refused before the limit is used`() {
    runBlocking { whenever(discourse.getTopic(42)).thenAnswer { throw DiscourseTopicNotFoundException(42) } }

    val error = errorOf { service.send("chairperson", laser.id, existingThread()) }

    assertEquals(CommitteeMessageError.BAD_THREAD, error.error)
    runBlocking { verify(messages, never()).reserve(any(), any(), any(), any(), any()) }
  }

  @Test
  fun `a private message thread is refused`() {
    runBlocking {
      whenever(discourse.getTopic(42))
          .thenReturn(DiscourseTopicDetails(id = 42, archetype = "private_message"))
    }

    val error = errorOf { service.send("chairperson", laser.id, existingThread()) }

    assertEquals(CommitteeMessageError.BAD_THREAD, error.error)
  }

  @Test
  fun `a thread link must be on the Talk host`() {
    val request = existingThread().copy(talkUrl = "https://talk.dallasmakerspace.org.evil.test/t/x/42")

    val error = errorOf { service.send("chairperson", laser.id, request) }

    assertEquals(CommitteeMessageError.BAD_THREAD, error.error)
  }

  @Test
  fun `the limit is reported with when sending is allowed again`() {
    val next = LocalDateTime.of(2026, 3, 11, 9, 30)
    runBlocking {
      whenever(messages.reserve(any(), any(), any(), any(), any()))
          .thenReturn(Reservation.RateLimited(next))
    }

    val error = errorOf { service.send("chairperson", laser.id, existingThread()) }

    assertEquals(CommitteeMessageError.RATE_LIMITED, error.error)
    assertEquals(next, error.nextAllowedAt)
  }

  @Test
  fun `a failure after the limit is reserved gives it back`() {
    runBlocking {
      whenever(notifications.createForMembers(any(), any(), any(), anyOrNull(), any(), any(), any()))
          .thenThrow(IllegalStateException("db down"))
    }

    assertFailsWith<IllegalStateException> {
      runBlocking { service.send("chairperson", laser.id, existingThread()) }
    }

    runBlocking { verify(messages).markFailed(7) }
  }

  @Test
  fun `a Talk failure creating the thread is reported and gives the limit back`() {
    runBlocking {
      whenever(discourse.createTopicAs(any(), any(), any(), any()))
          .thenAnswer { throw DiscourseApiException("422") }
    }

    val error =
        errorOf {
          service.send("chairperson", laser.id, CommitteeMessageRequest("Hello", createThread = true))
        }

    assertEquals(CommitteeMessageError.UNAVAILABLE, error.error)
    runBlocking { verify(messages).markFailed(7) }
  }

  @Test
  fun `nothing is sent when no one follows the committee`() {
    runBlocking { whenever(followers.getFollowers(laser.id)).thenReturn(listOf("chairperson")) }

    val error = errorOf { service.send("chairperson", laser.id, existingThread()) }

    assertEquals(CommitteeMessageError.INVALID, error.error)
  }

  @Test
  fun `message text rules`() {
    assertEquals("Hello there", CommitteeMessageService.validateMessage("  Hello there \n"))
    listOf(
            "",
            "   ",
            "x".repeat(121),
            "two\nlines",
            "see https://example.test",
            "visit www.example.test now",
            "go to example.com/now",
            "dallas.ms/abc")
        .forEach { bad ->
          assertEquals(
              CommitteeMessageError.INVALID,
              assertFailsWith<CommitteeMessageException>(bad) {
                    CommitteeMessageService.validateMessage(bad)
                  }
                  .error,
              bad)
        }
    assertEquals(120, CommitteeMessageService.validateMessage("x".repeat(120)).length)
    assertEquals("Class at 7 p.m., e.g. Thursday", CommitteeMessageService.validateMessage("Class at 7 p.m., e.g. Thursday"))
  }

  @Test
  fun `thread links parse to a topic id`() {
    assertEquals(42, CommitteeMessageService.parseTalkTopicId("https://talk.dallasmakerspace.org/t/some-slug/42"))
    assertEquals(42, CommitteeMessageService.parseTalkTopicId("https://talk.dallasmakerspace.org/t/some-slug/42/7"))
    assertEquals(42, CommitteeMessageService.parseTalkTopicId(" https://talk.dallasmakerspace.org/t/42 "))
    assertEquals(42, CommitteeMessageService.parseTalkTopicId("https://talk.dallasmakerspace.org/t/42/7"))
    listOf(
            "http://talk.dallasmakerspace.org/t/x/42",
            "https://talk.dallasmakerspace.org/c/laser/10",
            "https://example.test/t/x/42",
            "https://talk.dallasmakerspace.org/t/x/42?next=https://example.test")
        .forEach { bad ->
          assertFailsWith<CommitteeMessageException>(bad) {
            CommitteeMessageService.parseTalkTopicId(bad)
          }
        }
  }

  @Test
  fun `category config parses pairs and skips junk`() {
    assertEquals(mapOf(1 to 7, 9 to 10), CommitteeMessageService.parseCategories("1=7, 9 = 10,x=2,3=,4"))
    assertEquals(emptyMap(), CommitteeMessageService.parseCategories(""))
  }

  @Test
  fun `eligibility is empty for someone who is not a chair`() = runBlocking {
    asChair("chairperson", "Some Other Group")

    assertEquals(CommitteeMessageEligibility(), service.eligibility("chairperson", laser.id))
  }

  @Test
  fun `eligibility tells a chair what they can do and how much they have left`() = runBlocking {
    val now = CommitteeMessagesRepository.nowUtc()
    whenever(messages.recentSends(eq(laser.id), any())).thenReturn(listOf(now.minusHours(1), now.minusDays(2)))

    val eligibility = service.eligibility("chairperson", laser.id)

    assertTrue(eligibility.canMessage)
    assertTrue(eligibility.talkLinked)
    assertTrue(eligibility.canCreateThread)
    assertEquals(2, eligibility.followerCount)
    assertEquals(1, eligibility.sentToday)
    assertEquals(2, eligibility.sentThisWeek)
    assertEquals(null, eligibility.nextAllowedAt)
  }

  @Test
  fun `eligibility says when a limit lifts`() = runBlocking {
    val now = CommitteeMessagesRepository.nowUtc()
    whenever(messages.recentSends(eq(laser.id), any())).thenReturn(listOf(now.minusHours(5), now.minusHours(1)))

    val eligibility = service.eligibility("chairperson", laser.id)

    assertTrue(eligibility.nextAllowedAt != null)
    assertEquals(2, eligibility.sentToday)
  }
}
