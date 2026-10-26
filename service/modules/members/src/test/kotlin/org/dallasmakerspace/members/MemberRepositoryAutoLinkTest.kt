package org.dallasmakerspace.members

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MemberRepositoryAutoLinkTest {
  private lateinit var repository: MemberRepository

  @Before
  fun setUp() {
    MembersProfileTestDb.reset()
    val loggerFactory: LoggerFactory = mock()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock())
    repository = MemberRepository(loggerFactory)
  }

  @Test
  fun `claim sets only the Talk username and clears the stale avatar`() = runBlocking<Unit> {
    MembersProfileTestDb.insert("jdoe", discordUserId = "1234", discourseAvatarUrl = "old")

    assertTrue(repository.claimDiscourseUsernameIfUnlinked("jdoe", "talk-jdoe"))

    assertEquals("talk-jdoe", MembersProfileTestDb.discourseUsername("jdoe"))
    assertNull(MembersProfileTestDb.discourseAvatarUrl("jdoe"))
    assertEquals("1234", MembersProfileTestDb.discordUserId("jdoe"))
  }

  @Test
  fun `claim does not overwrite an existing link`() = runBlocking<Unit> {
    MembersProfileTestDb.insert("jdoe", discourseUsername = "talk-first")

    assertFalse(repository.claimDiscourseUsernameIfUnlinked("jdoe", "talk-second"))

    assertEquals("talk-first", MembersProfileTestDb.discourseUsername("jdoe"))
  }

  @Test
  fun `claim returns false instead of throwing when another profile holds the Talk username`() =
      runBlocking<Unit> {
        MembersProfileTestDb.insert("asmith", discourseUsername = "talk-shared")
        MembersProfileTestDb.insert("jdoe")

        assertFalse(repository.claimDiscourseUsernameIfUnlinked("jdoe", "talk-shared"))

        assertNull(MembersProfileTestDb.discourseUsername("jdoe"))
        assertEquals("talk-shared", MembersProfileTestDb.discourseUsername("asmith"))
      }

  @Test
  fun `claim returns false for an unknown member`() = runBlocking<Unit> {
    assertFalse(repository.claimDiscourseUsernameIfUnlinked("nobody", "talk-nobody"))
  }

  @Test
  fun `release clears a matching link and leaves a different one`() = runBlocking<Unit> {
    MembersProfileTestDb.insert("jdoe", discourseUsername = "talk-jdoe", discordUserId = "1234")

    assertFalse(repository.releaseDiscourseUsername("jdoe", "talk-other"))
    assertEquals("talk-jdoe", MembersProfileTestDb.discourseUsername("jdoe"))

    assertTrue(repository.releaseDiscourseUsername("jdoe", "talk-jdoe"))
    assertNull(MembersProfileTestDb.discourseUsername("jdoe"))
    assertEquals("1234", MembersProfileTestDb.discordUserId("jdoe"))
  }
}
