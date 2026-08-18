package org.dallasmakerspace.cron

import dagger.Reusable
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.ActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.logging.AppInMemoryLogger
import org.dallasmakerspace.discourse.DiscourseAvatarService
import org.dallasmakerspace.members.MemberComparator
import org.dallasmakerspace.members.MemberRepository
import org.dallasmakerspace.members.MemberService

/**
 * This class is responsible for refreshing the member list from Active Directory. It is intended to
 * be run on a regular schedule. It will query Active Directory for the list of members in the DB
 * and compare the two lists. It will then notify observers of any changes.
 *
 * This job also handles periodic refresh of Discourse avatar URLs for members.
 */
@Reusable
class MemberRefreshCronJob
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val memberService: MemberService,
    private val memberComparator: MemberComparator,
    private val activeDirectoryService: ActiveDirectoryService,
    private val discourseAvatarService: DiscourseAvatarService,
    private val memberRepository: MemberRepository
) : CronJob<MemberRefreshCronJobParams>(MemberRefreshCronJobParams::class, loggerFactory) {

  @Suppress("TooGenericExceptionCaught")
  override suspend fun run(params: MemberRefreshCronJobParams): String {
    log.info(
        "**************************************************************************************")
    log.info("Running Cron Job to refresh DB users; params: $params")
    log.info(
        "**************************************************************************************")
    val startTime = System.currentTimeMillis()

    val dbMembers = memberService.getAllMembersWithProfiles()
    val adMembers =
        dbMembers
            .map { it.username }
            .chunked(AD_READ_BATCH_SIZE)
            .map { chunk ->
              withContext(Dispatchers.IO) { activeDirectoryService.getMembersByUsernameList(chunk) }
            }
            .fold(mutableMapOf<String, ADUser?>()) { acc, map ->
              acc.putAll(map)
              acc
            }

    log.info("Found members in DB: ${dbMembers.size}; Found users in AD: ${adMembers.size}")

    // Build list of Pair<> of dbMembers to adMembers
    val memberPairs =
        dbMembers
            .map { dbMember ->
              val adMember = adMembers[dbMember.username]
              Pair(dbMember, adMember)
            }
            .toMutableList()

    var membersDisabled = 0
    var membersEnabled = 0
    var avatarsRefreshed = 0
    var avatarRefreshFailed = 0

    // Get members who need avatar refresh if avatar refresh is enabled
    val membersNeedingAvatarRefresh =
        if (params.refreshAvatars) {
          memberRepository
              .getMembersWithDiscourseUsernames()
              .filter { it.discourseUsername != null }
              .shuffled() // Randomize order to distribute load
              .take(AVATAR_REFRESH_DAILY_LIMIT) // Limit to a reasonable number per run
        } else {
          emptyList()
        }

    if (params.refreshAvatars) {
      log.info(
          "Avatar refresh enabled. Found ${membersNeedingAvatarRefresh.size} members with discourse usernames")
    }

    while (memberPairs.isNotEmpty()) {
      val batchSize = minOf(MAX_MEMBER_PROCESS_BATCH_SIZE, memberPairs.size)
      val batch = List(batchSize) { memberPairs.removeAt(Random.nextInt(memberPairs.size)) }
      try {
        // Randomly pick a batch to send to memberComparator
        val result = memberComparator.process(batch, log, params.isRunningInShadowMode)
        membersDisabled += result.first
        membersEnabled += result.second
      } catch (e: Exception) {
        log.error("Error processing batch: ${e.message}", e)
      }
      // Sleep for a bit between batches
      delay(DELAY_BETWEEN_BATCHES_MILLIS)
    }

    // Process avatar refreshes if enabled
    if (params.refreshAvatars && membersNeedingAvatarRefresh.isNotEmpty()) {
      log.info("Starting avatar refresh for ${membersNeedingAvatarRefresh.size} members")

      val avatarBatches = membersNeedingAvatarRefresh.chunked(AVATAR_REFRESH_BATCH_SIZE)
      for ((batchIndex, avatarBatch) in avatarBatches.withIndex()) {
        try {
          val batchResults = processAvatarBatch(avatarBatch, params.isRunningInShadowMode)
          avatarsRefreshed += batchResults.first
          avatarRefreshFailed += batchResults.second

          val modeText = if (params.isRunningInShadowMode) " (shadow mode)" else ""
          log.debug(
              "Avatar batch ${batchIndex + 1}/${avatarBatches.size} completed$modeText:" +
                  " ${batchResults.first} successful, ${batchResults.second} failed")
        } catch (e: Exception) {
          log.error("Error processing avatar batch ${batchIndex + 1}: ${e.message}", e)
          avatarRefreshFailed += avatarBatch.size
        }

        // Rate limiting between avatar batches
        if (batchIndex < avatarBatches.size - 1) {
          delay(AVATAR_DELAY_BETWEEN_BATCHES_MILLIS)
        }
      }
    }

    val timeTaken = System.currentTimeMillis() - startTime
    val avatarSummary =
        if (params.refreshAvatars) {
          "; Avatars refreshed: $avatarsRefreshed; Avatar refresh failed: $avatarRefreshFailed"
        } else {
          "; Avatar refresh disabled"
        }

    log.info(
        "Finished running MemberRefreshCronJob in $timeTaken ms; Members processed: ${dbMembers.size};\n" +
            "Members disabled: $membersDisabled; Members enabled: $membersEnabled$avatarSummary")
    val logBuffer = (log as? AppInMemoryLogger)?.getLog() ?: ""
    (log as? AppInMemoryLogger)?.clear()
    return logBuffer
  }

  /**
   * Processes a batch of members for avatar refresh.
   *
   * @param memberBatch List of members to refresh avatars for
   * @param isRunningInShadowMode If true, skip database updates but still process API calls
   * @return Pair of (successful refreshes, failed refreshes)
   */
  private suspend fun processAvatarBatch(
      memberBatch: List<org.dallasmakerspace.models.DMSMember>,
      isRunningInShadowMode: Boolean
  ): Pair<Int, Int> {
    var successful = 0
    var failed = 0

    for (member in memberBatch) {
      val discourseUsername = member.discourseUsername
      if (discourseUsername != null) {
        try {
          val avatarUrl = discourseAvatarService.refreshAvatarUrl(discourseUsername)
          if (avatarUrl != null) {
            if (!isRunningInShadowMode) {
              // Only update database when not in shadow mode
              val updateSuccess =
                  memberRepository.updateDiscourseAvatarUrl(member.username, avatarUrl)
              if (updateSuccess) {
                successful++
                log.debug("Successfully refreshed avatar for ${member.username} -> $avatarUrl")
              } else {
                failed++
                log.warn("Failed to update avatar URL in database for ${member.username}")
              }
            } else {
              // In shadow mode, simulate success without database update
              successful++
              log.debug("Shadow mode: Would update avatar for ${member.username} -> $avatarUrl")
            }
          } else {
            failed++
            log.debug("Avatar refresh returned null for ${member.username}")
          }
        } catch (e: Exception) {
          failed++
          log.warn("Avatar refresh failed for ${member.username}: ${e.message}")
        }

        // Rate limiting between individual avatar calls
        delay(AVATAR_DELAY_BETWEEN_CALLS_MILLIS)
      } else {
        failed++
        log.warn("Member ${member.username} has null discourse username")
      }
    }

    return Pair(successful, failed)
  }

  private companion object {
    const val AD_READ_BATCH_SIZE = 100

    const val MAX_MEMBER_PROCESS_BATCH_SIZE = 10
    const val DELAY_BETWEEN_BATCHES_MILLIS = 50L

    // Avatar refresh constants
    const val AVATAR_REFRESH_BATCH_SIZE = 5
    const val AVATAR_REFRESH_DAILY_LIMIT = 60
    const val AVATAR_DELAY_BETWEEN_BATCHES_MILLIS = 200L
    const val AVATAR_DELAY_BETWEEN_CALLS_MILLIS = 100L
  }
}
