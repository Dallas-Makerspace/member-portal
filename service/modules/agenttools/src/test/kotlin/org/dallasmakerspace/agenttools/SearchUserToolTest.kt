package org.dallasmakerspace.agenttools

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.ADUserMatch
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.agent.MemberAccess
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.members.IMemberRepository
import org.dallasmakerspace.models.DMSMember
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SearchUserToolTest {
  private val directory: IActiveDirectoryService = mock()
  private val members: IMemberRepository = mock()
  private val tool = SearchUserTool(directory, members)
  private val staff = ToolContext("staff1", "c1", "it-assistant", MemberAccess.ANY_MEMBER)
  private val self = ToolContext("jdoe", "c1", "ask-ai", MemberAccess.SELF_ONLY)

  private fun args(query: String?): JsonObject = buildJsonObject { query?.let { put("query", it) } }

  private fun profile(username: String, talk: String? = null, enabled: Boolean = true) =
      DMSMember(id = username.hashCode(), username = username, discourseUsername = talk, enabled = enabled)

  private fun adUser(username: String, name: String, enabled: Boolean = true) =
      ADUser(username, "m@example.test", null, null, null, null, name, "g-$username", null, enabled, emptyList())

  private fun givenDirectory(vararg hits: ADUserMatch) {
    whenever(directory.searchUsers(any(), any())).thenReturn(hits.toList())
  }

  private fun givenProfiles(vararg hits: DMSMember) = runBlocking {
    whenever(members.searchProfiles(any(), any())).thenReturn(hits.toList())
  }

  @Test
  fun `a name match is joined with its Talk username`() = runBlocking<Unit> {
    givenDirectory(ADUserMatch("jdoe", "Jane Doe", true))
    givenProfiles()
    whenever(members.getMembersByUsernames(listOf("jdoe"))).thenReturn(mapOf("jdoe" to profile("jdoe", "janed")))

    val result = tool.run(args("jane"), staff)

    assertFalse(result.isError)
    assertTrue(result.output.contains("1 match for \"jane\""))
    assertTrue(result.output.contains("- jdoe | Jane Doe | Talk: janed | active"))
    assertEquals("/profile/@jdoe", result.sources.single().url)
  }

  @Test
  fun `a Talk username match gets its display name from the directory`() = runBlocking<Unit> {
    givenDirectory()
    givenProfiles(profile("asmith", "maker_amy", enabled = false))
    whenever(directory.getMembersByUsernameList(listOf("asmith"))).thenReturn(mapOf("asmith" to adUser("asmith", "Amy Smith", true)))

    val out = tool.run(args("maker_amy"), staff).output

    assertTrue(out.contains("- asmith | Amy Smith | Talk: maker_amy | active"))
    verify(members, never()).getMembersByUsernames(any())
  }

  @Test
  fun `a profile with no directory entry is reported as such`() = runBlocking<Unit> {
    givenDirectory()
    givenProfiles(profile("gone1"))
    whenever(directory.getMembersByUsernameList(listOf("gone1"))).thenReturn(mapOf("gone1" to null))

    assertTrue(tool.run(args("gone"), staff).output.contains("- gone1 | (name unknown) | Talk: none | not found in the directory"))
  }

  @Test
  fun `a name found by both sources is listed once and costs no extra reads`() = runBlocking<Unit> {
    givenDirectory(ADUserMatch("jdoe", "Jane Doe", false))
    givenProfiles(profile("jdoe", "janed"))

    val out = tool.run(args("jdoe"), staff).output

    assertTrue(out.contains("1 match"))
    assertTrue(out.contains("- jdoe | Jane Doe | Talk: janed | not active"))
    verify(members, never()).getMembersByUsernames(any())
    verify(directory, never()).getMembersByUsernameList(any())
  }

  @Test
  fun `no match says so`() = runBlocking<Unit> {
    givenDirectory()
    givenProfiles()

    val result = tool.run(args("zzzz"), staff)

    assertFalse(result.isError)
    assertEquals("No members matched \"zzzz\".", result.output)
  }

  @Test
  fun `results are capped at ten, exact matches first, and the total is reported`() = runBlocking<Unit> {
    val many = (1..15).map { ADUserMatch("user%02d".format(it), "Maker $it", true) } + ADUserMatch("maker", "Exact Person", true)
    givenDirectory(*many.toTypedArray())
    givenProfiles()
    whenever(members.getMembersByUsernames(any())).thenReturn(emptyMap())

    val result = tool.run(args("maker"), staff)
    val rows = result.output.lines().filter { it.startsWith("- ") }

    assertEquals(10, rows.size)
    assertEquals(10, result.sources.size)
    assertTrue(rows.first().startsWith("- maker |"))
    assertTrue(result.output.contains("Showing 10 of at least 16 matches"))
  }

  @Test
  fun `only the final rows trigger the fill-in reads`() = runBlocking<Unit> {
    givenDirectory(*(1..15).map { ADUserMatch("user%02d".format(it), "Maker $it", true) }.toTypedArray())
    givenProfiles()
    whenever(members.getMembersByUsernames(any())).thenReturn(emptyMap())

    tool.run(args("user"), staff)

    verify(members, times(1)).getMembersByUsernames(
        org.mockito.kotlin.argThat { size == 10 })
  }

  @Test
  fun `filter and wildcard characters are rejected before any lookup`() = runBlocking<Unit> {
    for (bad in listOf("*", "jdoe)(uid=*", "a*b", "x)(|(cn=*", "%", "a;b", "jdoe\\00", "\"x\"", "a,b")) {
      val result = tool.run(args(bad), staff)
      assertTrue(result.isError, "should reject $bad")
    }
    verify(directory, never()).searchUsers(any(), any())
    verify(members, never()).searchProfiles(any(), any())
  }

  @Test
  fun `plain names with apostrophes, dots, hyphens and accents are searched as typed`() = runBlocking<Unit> {
    givenDirectory()
    givenProfiles()

    tool.run(args("  Mary-Ann   O'Neil  "), staff)
    tool.run(args("Zoë"), staff)

    verify(directory).searchUsers("Mary-Ann O'Neil", 15)
    verify(members).searchProfiles("Mary-Ann O'Neil", 15)
    verify(directory).searchUsers("Zoë", 15)
  }

  @Test
  fun `queries that are too short or too long are rejected`() = runBlocking<Unit> {
    assertTrue(tool.run(args("a"), staff).isError)
    assertTrue(tool.run(args(" "), staff).isError)
    assertTrue(tool.run(args(null), staff).isError)
    assertTrue(tool.run(args("a".repeat(65)), staff).isError)
    verify(directory, never()).searchUsers(any(), any())
  }

  @Test
  fun `a self-only context is refused and never reaches the directory`() = runBlocking<Unit> {
    val result = tool.run(args("jane"), self)

    assertTrue(result.isError)
    assertTrue(result.output.contains("only look up themselves"))
    verify(directory, never()).searchUsers(any(), any())
    verify(members, never()).searchProfiles(any(), any())
  }

  @Test
  fun `one source failing still returns the other, both failing is an error`() = runBlocking<Unit> {
    whenever(directory.searchUsers(any(), any())).thenThrow(IllegalStateException("down"))
    givenProfiles(profile("jdoe", "janed"))
    whenever(directory.getMembersByUsernameList(any())).thenReturn(mapOf("jdoe" to adUser("jdoe", "Jane Doe")))

    assertTrue(tool.run(args("jdoe"), staff).output.contains("- jdoe | Jane Doe"))

    whenever(members.searchProfiles(any(), any())).thenThrow(IllegalStateException("db down"))
    val result = tool.run(args("jdoe"), staff)
    assertTrue(result.isError)
    assertFalse(result.output.contains("down\n"))
  }

  @Test
  fun `names from the directory are flattened to one line`() = runBlocking<Unit> {
    givenDirectory(ADUserMatch("jdoe", "Jane\n- injected | row", true))
    givenProfiles()
    whenever(members.getMembersByUsernames(any())).thenReturn(emptyMap())

    val rows = tool.run(args("jdoe"), staff).output.lines().filter { it.startsWith("- ") }
    assertEquals(1, rows.size)
  }

  @Test
  fun `the tool is staff only and marks the chat private`() {
    assertEquals(MemberAccess.ANY_MEMBER, tool.requiredMemberAccess)
    assertTrue(tool.touchesPersonalInfo)
    assertEquals(listOf("query"), tool.descriptorFor(MemberAccess.ANY_MEMBER).requiredParameters.map { it.name })
  }
}
