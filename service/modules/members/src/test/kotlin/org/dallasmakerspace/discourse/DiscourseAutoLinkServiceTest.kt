package org.dallasmakerspace.discourse

import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.db.master.IMakerManagerDataService
import org.dallasmakerspace.db.master.MakerManagerEmailMatch
import org.dallasmakerspace.members.AutoLinkOutcome
import org.dallasmakerspace.members.IMemberRepository
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DMSMember
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class DiscourseAutoLinkServiceTest {
  private lateinit var config: ConfigOverrideService
  private lateinit var talk: IDiscourseApiClient
  private lateinit var makerManager: IMakerManagerDataService
  private lateinit var repository: IMemberRepository
  private lateinit var members: MemberService
  private lateinit var directory: IActiveDirectoryService
  private lateinit var service: DiscourseAutoLinkService
  private val pauses = mutableListOf<Long>()

  @Before
  fun setUp() = runBlocking<Unit> {
    val loggerFactory: LoggerFactory = mock()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock())
    config = mock()
    talk = mock()
    makerManager = mock()
    repository = mock()
    members = mock()
    directory = mock()
    service =
        DiscourseAutoLinkService(
            loggerFactory, config, talk, makerManager, repository, members, directory)
    service.pause = { pauses.add(it) }
    whenever(config.getFresh(ConfigRegistry.DISCOURSE_AUTO_LINK_ENABLED)).thenReturn(true)
    whenever(config.getFresh(ConfigRegistry.DISCOURSE_AUTO_LINK_NIGHTLY_CAP)).thenReturn(20)
    whenever(repository.getMembersWithDiscourseUsernames()).thenReturn(emptyList())
    whenever(repository.getMembersByDiscourseUsernames(any())).thenReturn(emptyMap())
  }

  private fun mm(id: Int, username: String) =
      MakerManagerEmailMatch(id, username, "$username@example.invalid", LocalDateTime.of(2020, 1, id, 0, 0))

  private fun talkUser(id: Int, username: String, active: Boolean = true) =
      DiscourseAdminUser(id, username, "${username.removePrefix("talk-")}@example.invalid", active)

  private fun adUser(username: String, enabled: Boolean = true) =
      ADUser(
          sAMAccountName = username,
          mail = "$username@example.invalid",
          givenName = "A",
          sn = "B",
          telephoneNumber = null,
          employeeID = null,
          displayName = "A B",
          objectGuid = "guid",
          whenCreated = null,
          enabled = enabled,
          groups = emptyList())

  private suspend fun stubNightly(
      members: List<MakerManagerEmailMatch>,
      talkUsers: List<DiscourseAdminUser>,
      disabledInAd: Set<String> = emptySet(),
  ) {
    whenever(makerManager.getActiveUsersWithEmail()).thenReturn(members)
    whenever(talk.listUsersPage(1)).thenReturn(talkUsers)
    whenever(directory.getMembersByUsernameList(any()))
        .thenAnswer { call ->
          @Suppress("UNCHECKED_CAST")
          (call.arguments[0] as List<String>).associateWith { adUser(it, it !in disabledInAd) }
        }
  }

  // webhook path

  @Test
  fun `webhook does nothing when the gate is off`() = runBlocking<Unit> {
    whenever(config.getFresh(ConfigRegistry.DISCOURSE_AUTO_LINK_ENABLED)).thenReturn(false)

    service.linkFromTalkEvent(10, "talk-jdoe", "jdoe@example.invalid")

    verifyNoInteractions(talk, makerManager, members)
  }

  @Test
  fun `a gate read failure counts as off`() = runBlocking<Unit> {
    whenever(config.getFresh(ConfigRegistry.DISCOURSE_AUTO_LINK_ENABLED))
        .doThrow(IllegalStateException("db down"))

    assertFalse(service.isEnabled())
  }

  @Test
  fun `webhook makes no outside calls when the Talk account is already linked`() = runBlocking<Unit> {
    whenever(repository.getMembersByDiscourseUsernames(listOf("talk-jdoe")))
        .thenReturn(mapOf("talk-jdoe" to DMSMember(id = 1, username = "jdoe", enabled = true)))

    service.linkFromTalkEvent(10, "talk-jdoe", "jdoe@example.invalid")

    verifyNoInteractions(talk, makerManager, members)
  }

  @Test
  fun `webhook skips an inactive Talk account before touching MakerManager`() = runBlocking<Unit> {
    whenever(talk.findUsersByEmail("jdoe@example.invalid"))
        .thenReturn(listOf(talkUser(10, "talk-jdoe", active = false)))

    service.linkFromTalkEvent(10, "talk-jdoe", "jdoe@example.invalid")

    verifyNoInteractions(makerManager, members)
  }

  @Test
  fun `webhook skips when the lookup returns a different Talk id`() = runBlocking<Unit> {
    whenever(talk.findUsersByEmail("jdoe@example.invalid")).thenReturn(listOf(talkUser(99, "talk-jdoe")))

    service.linkFromTalkEvent(10, "talk-jdoe", "jdoe@example.invalid")

    verifyNoInteractions(makerManager, members)
  }

  @Test
  fun `webhook skips when zero or several active members share the email`() = runBlocking<Unit> {
    whenever(talk.findUsersByEmail("jdoe@example.invalid")).thenReturn(listOf(talkUser(10, "talk-jdoe")))
    whenever(makerManager.findActiveUsersByEmail("jdoe@example.invalid")).thenReturn(emptyList())
    service.linkFromTalkEvent(10, "talk-jdoe", "jdoe@example.invalid")

    whenever(makerManager.findActiveUsersByEmail("jdoe@example.invalid"))
        .thenReturn(listOf(mm(1, "jdoe"), mm(2, "jdoe2")))
    service.linkFromTalkEvent(10, "talk-jdoe", "jdoe@example.invalid")

    verifyNoInteractions(members)
  }

  @Test
  fun `webhook links the single match with the webhook trigger`() = runBlocking<Unit> {
    whenever(talk.findUsersByEmail("jdoe@example.invalid")).thenReturn(listOf(talkUser(10, "talk-jdoe")))
    whenever(makerManager.findActiveUsersByEmail("jdoe@example.invalid")).thenReturn(listOf(mm(1, "jdoe")))
    whenever(members.autoLinkDiscourse("jdoe", "talk-jdoe", "webhook")).thenReturn(AutoLinkOutcome.LINKED)

    val result = service.linkFromTalkEvent(10, "talk-jdoe", "  JDoe@Example.Invalid ")

    assertEquals("LINKED", result)
    verify(members).autoLinkDiscourse("jdoe", "talk-jdoe", "webhook")
  }

  @Test
  fun `a Talk failure propagates without an email in the message`() = runBlocking<Unit> {
    whenever(talk.findUsersByEmail("jdoe@example.invalid"))
        .doThrow(IllegalStateException("Talk admin user lookup failed: 500"))

    val error =
        assertFailsWith<IllegalStateException> {
          service.linkFromTalkEvent(10, "talk-jdoe", "jdoe@example.invalid")
        }

    assertFalse(error.message.orEmpty().contains("example.invalid"))
  }

  // nightly path

  @Test
  fun `a real run is refused while the gate is off, but a dry run still previews`() = runBlocking<Unit> {
    whenever(config.getFresh(ConfigRegistry.DISCOURSE_AUTO_LINK_ENABLED)).thenReturn(false)
    stubNightly(listOf(mm(1, "jdoe")), listOf(talkUser(10, "talk-jdoe")))

    val refused = service.runNightly(dryRun = false)
    assertTrue(refused.contains("REFUSED"))
    verifyNoInteractions(talk, makerManager)

    val preview = service.runNightly(dryRun = true)
    assertTrue(preview.contains("would link: jdoe -> talk-jdoe"))
    verify(members, never()).autoLinkDiscourse(any(), any(), any())
  }

  @Test
  fun `a cap of zero pauses the job with no outside calls`() = runBlocking<Unit> {
    whenever(config.getFresh(ConfigRegistry.DISCOURSE_AUTO_LINK_NIGHTLY_CAP)).thenReturn(0)

    assertTrue(service.runNightly(dryRun = false).contains("PAUSED"))

    verifyNoInteractions(talk, makerManager)
  }

  @Test
  fun `no candidates means no Talk calls`() = runBlocking<Unit> {
    whenever(makerManager.getActiveUsersWithEmail()).thenReturn(listOf(mm(1, "jdoe")))
    whenever(repository.getMembersWithDiscourseUsernames())
        .thenReturn(listOf(DMSMember(id = 1, username = "jdoe", enabled = true, discourseUsername = "x")))

    val report = service.runNightly(dryRun = false)

    assertTrue(report.contains("nothing to link"))
    verifyNoInteractions(talk)
  }

  @Test
  fun `a real run links oldest first up to the cap and skips AD-disabled members`() = runBlocking<Unit> {
    whenever(config.getFresh(ConfigRegistry.DISCOURSE_AUTO_LINK_NIGHTLY_CAP)).thenReturn(2)
    stubNightly(
        members = listOf(mm(1, "old-disabled"), mm(2, "first"), mm(3, "second"), mm(4, "third")),
        talkUsers =
            listOf(
                talkUser(10, "talk-third"),
                talkUser(11, "talk-second"),
                talkUser(12, "talk-first"),
                talkUser(13, "talk-old-disabled")),
        disabledInAd = setOf("old-disabled"))
    whenever(members.autoLinkDiscourse(any(), any(), any())).thenReturn(AutoLinkOutcome.LINKED)

    val report = service.runNightly(dryRun = false)

    verify(members).autoLinkDiscourse("first", "talk-first", "cron")
    verify(members).autoLinkDiscourse("second", "talk-second", "cron")
    verify(members, never()).autoLinkDiscourse("third", "talk-third", "cron")
    verify(members, never()).autoLinkDiscourse("old-disabled", "talk-old-disabled", "cron")
    assertTrue(report.contains("disabled in AD: 1"))
    assertFalse(report.contains("example.invalid"))
  }

  @Test
  fun `one failed link does not stop the rest`() = runBlocking<Unit> {
    stubNightly(
        listOf(mm(1, "first"), mm(2, "second")),
        listOf(talkUser(10, "talk-first"), talkUser(11, "talk-second")))
    whenever(members.autoLinkDiscourse("first", "talk-first", "cron"))
        .doThrow(IllegalStateException("boom"))
    whenever(members.autoLinkDiscourse("second", "talk-second", "cron"))
        .thenReturn(AutoLinkOutcome.LINKED)

    val report = service.runNightly(dryRun = false)

    assertTrue(report.contains("failed=1"))
    assertTrue(report.contains("linked=1"))
  }

  @Test
  fun `a page that keeps failing aborts the run with no writes`() = runBlocking<Unit> {
    whenever(makerManager.getActiveUsersWithEmail()).thenReturn(listOf(mm(1, "jdoe")))
    whenever(talk.listUsersPage(1))
        .thenAnswer { throw DiscourseApiException("Talk admin user lookup failed: 500") }

    val report = service.runNightly(dryRun = false)

    assertTrue(report.contains("INCOMPLETE"))
    verify(members, never()).autoLinkDiscourse(any(), any(), any())
  }

  @Test
  fun `a rate limit is waited out and retried`() = runBlocking<Unit> {
    whenever(makerManager.getActiveUsersWithEmail()).thenReturn(listOf(mm(1, "jdoe")))
    whenever(directory.getMembersByUsernameList(any())).thenReturn(mapOf("jdoe" to adUser("jdoe")))
    whenever(talk.listUsersPage(1))
        .thenAnswer { throw DiscourseRateLimitException(7) }
        .thenReturn(listOf(talkUser(10, "talk-jdoe")))
    whenever(members.autoLinkDiscourse("jdoe", "talk-jdoe", "cron")).thenReturn(AutoLinkOutcome.LINKED)

    val report = service.runNightly(dryRun = false)

    assertTrue(pauses.contains(8_000L))
    assertTrue(report.contains("linked=1"))
  }

  @Test
  fun `full pages are followed until a short page`() = runBlocking<Unit> {
    whenever(makerManager.getActiveUsersWithEmail()).thenReturn(listOf(mm(1, "jdoe")))
    whenever(directory.getMembersByUsernameList(any())).thenReturn(mapOf("jdoe" to adUser("jdoe")))
    val fullPage = (1..100).map { DiscourseAdminUser(it, "other-$it", "other$it@example.invalid", true) }
    whenever(talk.listUsersPage(1)).thenReturn(fullPage)
    whenever(talk.listUsersPage(2)).thenReturn(listOf(talkUser(500, "talk-jdoe")))
    whenever(members.autoLinkDiscourse("jdoe", "talk-jdoe", "cron")).thenReturn(AutoLinkOutcome.LINKED)

    val report = service.runNightly(dryRun = false)

    verify(talk).listUsersPage(2)
    assertTrue(report.contains("talk pages read: 2"))
  }
}
