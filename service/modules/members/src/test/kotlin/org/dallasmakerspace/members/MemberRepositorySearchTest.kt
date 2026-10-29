package org.dallasmakerspace.members

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MemberRepositorySearchTest {
  private lateinit var repository: MemberRepository

  @Before
  fun setUp() {
    MembersProfileTestDb.reset()
    val loggerFactory: LoggerFactory = mock()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock())
    repository = MemberRepository(loggerFactory)
    MembersProfileTestDb.insert("jdoe", discourseUsername = "janed")
    MembersProfileTestDb.insert("asmith", discourseUsername = "maker_amy")
    MembersProfileTestDb.insert("bsmith")
  }

  private fun names(term: String, limit: Int = 10) =
      runBlocking { repository.searchProfiles(term, limit) }.map { it.username }.sorted()

  @Test
  fun `matches a username or a Talk username by substring, ignoring case`() {
    assertEquals(listOf("jdoe"), names("JDO"))
    assertEquals(listOf("jdoe"), names("aned"))
    assertEquals(listOf("asmith", "bsmith"), names("smith"))
  }

  @Test
  fun `wildcard and quote characters match literally`() {
    assertTrue(names("%").isEmpty())
    assertTrue(names("_").let { it == listOf("asmith") })
    assertTrue(names("j_oe").isEmpty())
    assertTrue(names("' OR '1'='1").isEmpty())
    assertTrue(names("!").isEmpty())
  }

  @Test
  fun `blank terms match nothing and the limit applies`() {
    assertTrue(names("  ").isEmpty())
    assertEquals(1, names("smith", limit = 1).size)
  }
}
