package org.dallasmakerspace.discourse

import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.db.master.IMakerManagerDataService
import org.dallasmakerspace.members.IMemberRepository
import org.dallasmakerspace.members.MemberService

private const val TALK_PAGE_SIZE = 100
private const val MAX_TALK_PAGES = 400
private const val MAX_CAP = 200
private const val AD_BATCH_SIZE = 100
private const val PACE_MS = 1_100L
private const val FETCH_ATTEMPTS = 3
private const val FAILED_FETCH_WAIT_MS = 5_000L
private const val MAX_RATE_LIMIT_WAIT_SECONDS = 120L

/**
 * Links Talk accounts to members by email. Only ever reads emails; nothing here logs or returns
 * one.
 */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
class DiscourseAutoLinkService
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val configOverrideService: ConfigOverrideService,
    private val discourseApiClient: IDiscourseApiClient,
    private val makerManagerDataService: IMakerManagerDataService,
    private val memberRepository: IMemberRepository,
    private val memberService: MemberService,
    private val activeDirectoryService: IActiveDirectoryService,
) {
  private val log = loggerFactory.create(javaClass)

  /** Replaced in tests so nothing actually waits. */
  internal var pause: suspend (Long) -> Unit = { delay(it) }

  suspend fun isEnabled(): Boolean =
      try {
        configOverrideService.getFresh(ConfigRegistry.DISCOURSE_AUTO_LINK_ENABLED)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.warn("Could not read ${ConfigRegistry.DISCOURSE_AUTO_LINK_ENABLED.key}; treating as off")
        false
      }

  suspend fun nightlyCap(): Int =
      try {
        configOverrideService.getFresh(ConfigRegistry.DISCOURSE_AUTO_LINK_NIGHTLY_CAP)
            .coerceIn(0, MAX_CAP)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.warn("Could not read ${ConfigRegistry.DISCOURSE_AUTO_LINK_NIGHTLY_CAP.key}; using 0")
        0
      }

  /**
   * Webhook path: links the Talk account that just confirmed [email] if it belongs to exactly one
   * active member. Returns a short outcome with no email in it; throws if Talk, MakerManager or AD
   * can't be reached.
   */
  suspend fun linkFromTalkEvent(talkId: Int, talkUsername: String, email: String): String {
    if (!isEnabled()) return "auto-link is off"
    val normalized = normalizeEmail(email) ?: return "no usable email"
    if (memberRepository.getMembersByDiscourseUsernames(listOf(talkUsername)).isNotEmpty()) {
      return "Talk account already linked"
    }

    val talkUser =
        discourseApiClient.findUsersByEmail(normalized).singleOrNull()
            ?: return "no single Talk account for the email"
    if (talkUser.id != talkId || !talkUser.isEligible()) return "Talk account not eligible"

    val member =
        makerManagerDataService
            .findActiveUsersByEmail(normalized)
            .distinctBy { it.username.lowercase() }
            .singleOrNull() ?: return "no single active member for the email"

    return memberService.autoLinkDiscourse(member.username, talkUser.username, "webhook").name
  }

  /** Cron path. Returns a plain-text report; a dry run changes nothing. */
  suspend fun runNightly(dryRun: Boolean): String {
    val gateOn = isEnabled()
    val cap = nightlyCap()
    val report = StringBuilder("mode=${if (dryRun) "DRY_RUN" else "REAL_RUN"} gate=${onOff(gateOn)} cap=$cap\n")
    if (!dryRun && !gateOn) {
      return report.append("REFUSED: ${ConfigRegistry.DISCOURSE_AUTO_LINK_ENABLED.key} is off\n").toString()
    }
    if (cap == 0) return report.append("PAUSED: cap is 0\n").toString()

    val makerManagerUsers = makerManagerDataService.getActiveUsersWithEmail()
    val linkedProfiles = memberRepository.getMembersWithDiscourseUsernames()
    val candidates =
        selectCandidates(makerManagerUsers, linkedProfiles.map { it.username.lowercase() }.toSet())
    report.append("active members with email: ${makerManagerUsers.size}\n")
    report.append("candidates: ${candidates.byEmail.size}${skips(candidates.skipped)}\n")
    if (candidates.byEmail.isEmpty()) return report.append("RESULT: OK, nothing to link\n").toString()

    val scan = scanTalk(candidates.byEmail.keys)
    report.append("talk pages read: ${scan.pages}\n")
    if (!scan.complete) {
      return report.append("RESULT: INCOMPLETE, no changes made (${scan.problem})\n").toString()
    }

    val matches =
        matchTalkUsers(
            candidates,
            scan.users,
            linkedProfiles.mapNotNull { it.discourseUsername?.lowercase() }.toSet())
    report.append("matched on Talk: ${matches.links.size}${skips(matches.skipped)}\n")

    val enabledInAd = enabledUsernames(matches.links.map { it.username })
    val eligible = matches.links.filter { it.username.lowercase() in enabledInAd }
    val selected = eligible.take(cap)
    report.append("disabled in AD: ${matches.links.size - eligible.size}\n")
    report.append("eligible: ${eligible.size}, selected: ${selected.size}\n")

    if (dryRun) {
      selected.forEach { report.append("would link: ${it.username} -> ${it.talkUsername}\n") }
      return report.append("RESULT: OK, dry run\n").toString()
    }
    val outcomes = linkAll(selected, report)
    return report.append("RESULT: OK, ${outcomes}\n").toString()
  }

  private suspend fun linkAll(links: List<PlannedLink>, report: StringBuilder): String {
    val counts = sortedMapOf<String, Int>()
    links.forEachIndexed { index, link ->
      if (index > 0) pause(PACE_MS)
      val outcome =
          try {
            memberService.autoLinkDiscourse(link.username, link.talkUsername, "cron").name
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            log.warn("Auto-link failed for ${link.username}: ${e.javaClass.simpleName}")
            "FAILED"
          }
      counts.merge(outcome, 1, Int::plus)
      report.append("${link.username} -> ${link.talkUsername}: $outcome\n")
    }
    return counts.entries.joinToString(", ") { "${it.key.lowercase()}=${it.value}" }
  }

  private suspend fun enabledUsernames(usernames: List<String>): Set<String> {
    val enabled = mutableSetOf<String>()
    usernames.chunked(AD_BATCH_SIZE).forEach { chunk ->
      val found = withContext(Dispatchers.IO) { activeDirectoryService.getMembersByUsernameList(chunk) }
      found.forEach { (name, user) -> if (user?.enabled == true) enabled.add(name.lowercase()) }
    }
    return enabled
  }

  private class TalkScan(
      val users: List<DiscourseAdminUser>,
      val pages: Int,
      val complete: Boolean,
      val problem: String = "",
  )

  /** Reads every Talk page once, keeping only accounts whose email is in [wantedEmails]. */
  private suspend fun scanTalk(wantedEmails: Set<String>): TalkScan {
    val kept = linkedMapOf<Int, DiscourseAdminUser>()
    for (page in 1..MAX_TALK_PAGES) {
      val users =
          fetchPage(page) ?: return TalkScan(emptyList(), page - 1, false, "page $page failed")
      users.filter { normalizeEmail(it.email) in wantedEmails }.forEach { kept[it.id] = it }
      if (users.size < TALK_PAGE_SIZE) return TalkScan(kept.values.toList(), page, true)
      pause(PACE_MS)
    }
    return TalkScan(emptyList(), MAX_TALK_PAGES, false, "more than $MAX_TALK_PAGES pages")
  }

  private suspend fun fetchPage(page: Int): List<DiscourseAdminUser>? {
    repeat(FETCH_ATTEMPTS) { attempt ->
      try {
        return discourseApiClient.listUsersPage(page)
      } catch (e: CancellationException) {
        throw e
      } catch (e: DiscourseRateLimitException) {
        log.warn("Talk rate limit on page $page")
        if (attempt < FETCH_ATTEMPTS - 1) {
          pause((minOf(e.waitSeconds + 1, MAX_RATE_LIMIT_WAIT_SECONDS)) * 1_000)
        }
      } catch (e: DiscourseApiException) {
        log.warn("Talk page $page failed: ${e.message}")
        if (attempt < FETCH_ATTEMPTS - 1) pause(FAILED_FETCH_WAIT_MS)
      }
    }
    return null
  }

  private fun skips(skipped: Map<SkipReason, Int>): String =
      if (skipped.isEmpty()) {
        ""
      } else {
        skipped.entries
            .sortedBy { it.key }
            .joinToString(prefix = " (skipped: ", postfix = ")") {
              "${it.key.name.lowercase().replace('_', '-')}=${it.value}"
            }
      }

  private fun onOff(value: Boolean) = if (value) "on" else "off"
}
