package org.dallasmakerspace.discourse

import java.time.Instant
import org.dallasmakerspace.db.master.MakerManagerEmailMatch

enum class SkipReason {
  DUPLICATE_MAKERMANAGER_EMAIL,
  ALREADY_LINKED,
  NO_TALK_ACCOUNT,
  DUPLICATE_TALK_EMAIL,
  TALK_NOT_ELIGIBLE,
  TALK_ACCOUNT_TAKEN,
}

data class PlannedLink(val username: String, val talkUsername: String, val talkId: Int)

/** Members that could be linked, keyed by normalized email, oldest account first. */
class CandidateSet(
    val byEmail: Map<String, MakerManagerEmailMatch>,
    val skipped: Map<SkipReason, Int>,
)

class TalkMatches(val links: List<PlannedLink>, val skipped: Map<SkipReason, Int>)

/** Trimmed and lowercased; null for blank, "null" (AD's missing-mail string) or no "@". */
fun normalizeEmail(email: String?): String? =
    email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "null" && '@' in it }

/**
 * Members worth looking up on Talk: an email shared by more than one active user is skipped, as is a
 * member who already has a Talk link.
 */
fun selectCandidates(
    makerManagerUsers: List<MakerManagerEmailMatch>,
    linkedUsernames: Set<String>,
): CandidateSet {
  val skipped = mutableMapOf<SkipReason, Int>()
  val usable =
      makerManagerUsers.mapNotNull { user ->
        val email = normalizeEmail(user.email)
        if (email == null || user.username.isBlank()) null else email to user
      }
  val byEmail = linkedMapOf<String, MakerManagerEmailMatch>()
  usable.groupBy({ it.first }, { it.second }).forEach { (email, users) ->
    when {
      users.distinctBy { it.username.lowercase() }.size > 1 ->
          skipped.merge(SkipReason.DUPLICATE_MAKERMANAGER_EMAIL, users.size, Int::plus)
      users.first().username.lowercase() in linkedUsernames ->
          skipped.merge(SkipReason.ALREADY_LINKED, 1, Int::plus)
      else -> byEmail[email] = users.first()
    }
  }
  return CandidateSet(byEmail, skipped)
}

/** Pairs each candidate with the one eligible, unlinked Talk account that has the same email. */
fun matchTalkUsers(
    candidates: CandidateSet,
    talkUsers: List<DiscourseAdminUser>,
    linkedTalkUsernames: Set<String>,
    now: Instant = Instant.now(),
): TalkMatches {
  val skipped = mutableMapOf<SkipReason, Int>()
  val talkByEmail =
      talkUsers
          .mapNotNull { user -> normalizeEmail(user.email)?.let { it to user } }
          .groupBy({ it.first }, { it.second })
  val links = mutableListOf<PlannedLink>()
  candidates.byEmail.forEach { (email, member) ->
    val accounts = talkByEmail[email].orEmpty()
    val reason =
        when {
          accounts.isEmpty() -> SkipReason.NO_TALK_ACCOUNT
          accounts.size > 1 -> SkipReason.DUPLICATE_TALK_EMAIL
          !accounts.single().isEligible(now) -> SkipReason.TALK_NOT_ELIGIBLE
          accounts.single().username.lowercase() in linkedTalkUsernames ->
              SkipReason.TALK_ACCOUNT_TAKEN
          else -> null
        }
    if (reason == null) {
      val account = accounts.single()
      links.add(PlannedLink(member.username, account.username, account.id))
    } else {
      skipped.merge(reason, 1, Int::plus)
    }
  }
  return TalkMatches(links, skipped)
}
