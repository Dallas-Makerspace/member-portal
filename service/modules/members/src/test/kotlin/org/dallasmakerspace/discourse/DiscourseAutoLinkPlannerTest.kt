package org.dallasmakerspace.discourse

import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.dallasmakerspace.db.master.MakerManagerEmailMatch

class DiscourseAutoLinkPlannerTest {
  private val now = Instant.parse("2026-01-01T00:00:00Z")

  private fun member(id: Int, username: String, email: String?) =
      MakerManagerEmailMatch(id, username, email ?: "", LocalDateTime.of(2020, 1, id, 0, 0))

  private fun talk(id: Int, username: String, email: String?, active: Boolean = true) =
      DiscourseAdminUser(id = id, username = username, email = email, active = active)

  private fun candidates(vararg members: MakerManagerEmailMatch, linked: Set<String> = emptySet()) =
      selectCandidates(members.toList(), linked)

  @Test
  fun `emails are trimmed and lowercased, and junk is rejected`() {
    assertEquals("a@example.invalid", normalizeEmail("  A@Example.Invalid "))
    assertNull(normalizeEmail(null))
    assertNull(normalizeEmail("   "))
    assertNull(normalizeEmail("null"))
    assertNull(normalizeEmail("no-at-sign"))
  }

  @Test
  fun `a member with a unique email is a candidate`() {
    val set = candidates(member(1, "jdoe", " JDoe@Example.Invalid"))

    assertEquals(listOf("jdoe@example.invalid"), set.byEmail.keys.toList())
    assertEquals("jdoe", set.byEmail.getValue("jdoe@example.invalid").username)
  }

  @Test
  fun `an email shared by two active users skips both`() {
    val set =
        candidates(
            member(1, "jdoe", "family@example.invalid"),
            member(2, "jdoe2", "FAMILY@example.invalid"),
            member(3, "asmith", "asmith@example.invalid"))

    assertEquals(listOf("asmith@example.invalid"), set.byEmail.keys.toList())
    assertEquals(2, set.skipped[SkipReason.DUPLICATE_MAKERMANAGER_EMAIL])
  }

  @Test
  fun `a member who already has a Talk link is skipped, ignoring username case`() {
    val set = candidates(member(1, "JDoe", "jdoe@example.invalid"), linked = setOf("jdoe"))

    assertEquals(emptyList(), set.byEmail.keys.toList())
    assertEquals(1, set.skipped[SkipReason.ALREADY_LINKED])
  }

  @Test
  fun `members without a usable email or username are ignored`() {
    val set = candidates(member(1, "jdoe", null), member(2, " ", "x@example.invalid"))

    assertEquals(emptyList(), set.byEmail.keys.toList())
  }

  @Test
  fun `candidates keep the order they were given, oldest first`() {
    val set =
        candidates(
            member(1, "first", "first@example.invalid"),
            member(2, "second", "second@example.invalid"),
            member(3, "third", "third@example.invalid"))

    assertEquals(listOf("first", "second", "third"), set.byEmail.values.map { it.username })
  }

  @Test
  fun `a candidate is paired with the Talk account that has the same email`() {
    val set = candidates(member(1, "jdoe", "jdoe@example.invalid"))

    val matches =
        matchTalkUsers(set, listOf(talk(10, "talk-jdoe", "JDoe@example.invalid")), emptySet(), now)

    assertEquals(listOf(PlannedLink("jdoe", "talk-jdoe", 10)), matches.links)
    assertEquals(emptyMap(), matches.skipped)
  }

  @Test
  fun `no Talk account, duplicate Talk emails, ineligible and taken accounts are skipped`() {
    val set =
        candidates(
            member(1, "none", "none@example.invalid"),
            member(2, "dup", "dup@example.invalid"),
            member(3, "inactive", "inactive@example.invalid"),
            member(4, "taken", "taken@example.invalid"),
            member(5, "good", "good@example.invalid"))
    val talkUsers =
        listOf(
            talk(10, "talk-dup1", "dup@example.invalid"),
            talk(11, "talk-dup2", "dup@example.invalid"),
            talk(12, "talk-inactive", "inactive@example.invalid", active = false),
            talk(13, "Talk-Taken", "taken@example.invalid"),
            talk(14, "talk-good", "good@example.invalid"))

    val matches = matchTalkUsers(set, talkUsers, setOf("talk-taken"), now)

    assertEquals(listOf("good"), matches.links.map { it.username })
    assertEquals(1, matches.skipped[SkipReason.NO_TALK_ACCOUNT])
    assertEquals(1, matches.skipped[SkipReason.DUPLICATE_TALK_EMAIL])
    assertEquals(1, matches.skipped[SkipReason.TALK_NOT_ELIGIBLE])
    assertEquals(1, matches.skipped[SkipReason.TALK_ACCOUNT_TAKEN])
  }

  @Test
  fun `links come out in candidate order, not Talk order`() {
    val set =
        candidates(
            member(1, "older", "older@example.invalid"),
            member(2, "newer", "newer@example.invalid"))
    val talkUsers =
        listOf(
            talk(20, "talk-newer", "newer@example.invalid"),
            talk(21, "talk-older", "older@example.invalid"))

    val matches = matchTalkUsers(set, talkUsers, emptySet(), now)

    assertEquals(listOf("older", "newer"), matches.links.map { it.username })
  }
}
