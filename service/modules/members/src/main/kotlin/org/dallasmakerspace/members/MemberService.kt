package org.dallasmakerspace.members

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toKotlinLocalDate
import org.dallasmakerspace.activedirectory.GroupNotFoundException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.activedirectory.isGroupDN
import org.dallasmakerspace.activedirectory.parseCNFromDN
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.api.Groups
import org.dallasmakerspace.db.master.IMakerManagerDataService
import org.dallasmakerspace.db.master.IWhmcsDataService
import org.dallasmakerspace.discourse.DiscourseAvatarService
import org.dallasmakerspace.discourse.DiscourseService
import org.dallasmakerspace.doorcontroller.DoorEventsRepository
import org.dallasmakerspace.models.ActivityLogEvent
import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.voterregistration.VoterRegistrationManager

@Suppress("LongParameterList")
class MemberService
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val discourseService: DiscourseService,
    private val discourseAvatarService: DiscourseAvatarService,
    private val memberRepository: IMemberRepository,
    private val whmcsDataService: IWhmcsDataService,
    private val activityLogService: ActivityLogService,
    private val makerManagerDataService: IMakerManagerDataService,
    private val activeDirectoryService: IActiveDirectoryService,
    private val voterRegistrationManager: VoterRegistrationManager,
    private val groupHistoryRepository: IGroupHistoryRepository,
    private val doorEventsRepository: DoorEventsRepository,
) {
  private val log = loggerFactory.create(javaClass)

  // Fallback cache with unlimited TTL - used only when API calls fail
  private val groupsFallbackCache = java.util.concurrent.ConcurrentHashMap<String, DMSGroup>()

  // Cache of whmcsId + isPrimaryAccount per username; populated at startup via
  // populateWhmcsAccountCache().
  // Reads are unsynchronized (safe once stable); writes are synchronized.
  private val whmcsAccountCache = HashMap<String, WhmcsAccountCacheEntry>()

  /** Pre-populates the WHMCS account cache from MakerManager at startup. */
  suspend fun populateWhmcsAccountCache() {
    try {
      val users = makerManagerDataService.getAllUsers()
      val entries =
          users.associate { user ->
            user.username to WhmcsAccountCacheEntry(user.whmcsUserId, user.isPrimaryAccount)
          }
      synchronized(whmcsAccountCache) { whmcsAccountCache.putAll(entries) }
      log.info("Populated WHMCS ID cache with ${entries.size} entries")
    } catch (e: Exception) {
      log.error("Failed to populate WHMCS ID cache: ${e.message}", e)
    }
  }

  suspend fun getMembersLoggedInDays(days: Int): List<DMSMember> {
    val adMembers = activeDirectoryService.getMembersByLoggedInDays(days)
    val dbMembers = memberRepository.getAllMembers()
    log.debug("Found ${adMembers.size} members logged in the last $days days")
    return adMembers.map { adMember ->
      DMSMember(
          id = -1,
          username = adMember.sAMAccountName,
          displayName = adMember.displayName,
          enabled = adMember.enabled,
          personalEmail = adMember.mail,
          phoneNumber = adMember.telephoneNumber,
          badgeNumber = adMember.employeeID,
          discourseUsername =
              dbMembers.find { it.username == adMember.sAMAccountName }?.discourseUsername,
          memberSince = calculateMemberSince(adMember.whenCreated),
          groups = emptyList(),
      )
    }
  }

  suspend fun getMemberByUsername(username: String, refreshAvatar: Boolean = false): DMSMember =
      coroutineScope {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000

        val cached = whmcsAccountCache[username]

        // Phase 1: launch all I/O at t=0.
        // Each lambda records its own start time so we can see if it actually ran concurrently.
        var adStartMs = -1L
        val adDeferred =
            async(Dispatchers.IO) {
              adStartMs = ms()
              activeDirectoryService.getMemberByUsername(username)
            }

        var mmStartMs = -1L
        val mmDeferred = async {
          mmStartMs = ms()
          makerManagerDataService.getAccountInfoMap(listOf(username))
        }

        var whmcsStatusStartMs = -1L
        val whmcsStatusDeferred =
            cached?.let { c ->
              async {
                whmcsStatusStartMs = ms()
                whmcsDataService.getAccountInfoMap(listOf(c.whmcsId))[c.whmcsId]
              }
            }

        // Pre-fetch regdate for all cached accounts regardless of isPrimaryAccount — the
        // isPrimaryAccount flag in the cache may disagree with what MakerManager returns
        // (different derivation), and the query is a cheap SELECT MIN. The result is only
        // used if accountInfo.isPrimaryAccount turns out to be true.
        var whmcsRegdateStartMs = -1L
        val whmcsRegdateDeferred =
            cached?.let { c ->
              async {
                whmcsRegdateStartMs = ms()
                whmcsDataService.getAccountRegdate(c.whmcsId)?.toKotlinLocalDate()
              }
            }

        // AD is fast (~35ms) — start memberRepository ASAP once it finishes
        val adMember = adDeferred.await()
        val adDoneMs = ms()

        var dbStartMs = -1L
        val dbDeferred = async {
          dbStartMs = ms()
          memberRepository.getMemberOrInsert(username, adMember.enabled)
        }

        // Await MakerManager (still needed for the full AccountInfo payload)
        val relatedAccounts = mmDeferred.await()
        val mmDoneMs = ms()
        val accountInfo = relatedAccounts[username]

        var whmcsStatusDoneMs = -1L
        var whmcsRegdateDoneMs = -1L
        if (accountInfo != null) {
          val account =
              if (accountInfo.isPrimaryAccount) accountInfo.primaryAccount
              else accountInfo.addonAccounts.find { it.username == username }

          account?.let {
            val whmcsId = it.whmcsId

            // Back-fill cache if it was a miss (e.g. new member added after startup)
            if (cached == null) {
              synchronized(whmcsAccountCache) {
                whmcsAccountCache[username] =
                    WhmcsAccountCacheEntry(whmcsId, accountInfo.isPrimaryAccount)
              }
            }

            // Resolve WHMCS status: use already-running deferred if available, else fresh call
            val accountStatus =
                if (whmcsStatusDeferred != null) whmcsStatusDeferred.await()
                else whmcsDataService.getAccountInfoMap(listOf(whmcsId))[whmcsId]
            whmcsStatusDoneMs = ms()

            if (accountStatus != null) {
              accountInfo.wasActivePast90Days = accountStatus.wasActiveInRange
              accountInfo.lastInactiveDate = accountStatus.lastInactiveDate?.toKotlinLocalDate()
              accountInfo.regDate =
                  if (accountInfo.isPrimaryAccount)
                  // Use already-running deferred if available, else fresh call
                  if (whmcsRegdateDeferred != null) whmcsRegdateDeferred.await()
                      else whmcsDataService.getAccountRegdate(whmcsId)?.toKotlinLocalDate()
                  else
                      calculateMemberSince(adMember.whenCreated)?.let { createdInstant ->
                        java.time.LocalDate.ofEpochDay(createdInstant.epochSeconds / 86_400)
                            .toKotlinLocalDate()
                      }
              whmcsRegdateDoneMs = ms()
            }
          }
        }

        val dbMember = dbDeferred.await()
        val dbDoneMs = ms()
        // TODO: compare DB and AD members and notify observers of any changes.
        dbMember.firstName = adMember.givenName
        dbMember.lastName = adMember.sn
        dbMember.displayName = adMember.displayName
        dbMember.personalEmail = adMember.mail
        dbMember.phoneNumber =
            getNormalizedPhoneNumber(adMember.telephoneNumber, adMember.sAMAccountName)
        dbMember.badgeNumber = adMember.employeeID
        dbMember.enabled = adMember.enabled
        dbMember.memberSince = calculateMemberSince(accountInfo?.regDate)
        dbMember.groups =
            adMember.groups.map { group ->
              DMSGroup(
                  name = group.cn,
                  description = null,
                  distinguishedName = group.distinguishedName,
                  objectGuid = group.objectGuid,
                  membersListIncomplete = false,
                  members = emptyList(),
              )
            }
        dbMember.accountInfo = relatedAccounts[username]

        // Refresh discourse avatar URL if needed
        var avatarMs = -1L
        if (refreshAvatar) {
          val hasExistingAvatar = !dbMember.discourseAvatarUrl.isNullOrBlank()
          if (hasExistingAvatar) {
            // URL already present — fire-and-forget so response is not delayed
            launch { refreshDiscourseAvatar(dbMember) }
          } else {
            // No URL yet — refresh synchronously so first response includes it
            log.debug("Discourse avatar URL missing for ${dbMember.username}, refreshing...")
            val avatarStartMs = ms()
            dbMember.discourseAvatarUrl = refreshDiscourseAvatar(dbMember)
            avatarMs = ms() - avatarStartMs
          }
        }

        val totalMs = ms()
        log.debug(
            "getMemberByUsername($username) timings: total=${totalMs}ms, cacheHit=${cached != null} | " +
                "ad=${adStartMs}→${adDoneMs}ms " +
                "mm=${mmStartMs}→${mmDoneMs}ms " +
                "db=${dbStartMs}→${dbDoneMs}ms " +
                "whmcsStatus=${whmcsStatusStartMs}→${whmcsStatusDoneMs}ms " +
                "whmcsRegdate=${whmcsRegdateStartMs}→${whmcsRegdateDoneMs}ms" +
                if (avatarMs >= 0) " avatar=${avatarMs}ms" else "")

        dbMember
      }

  /**
   * Fetches multiple members by their usernames in a single batch operation. This is optimized for
   * performance when you need basic member data for multiple users.
   *
   * @param usernames List of usernames to fetch
   * @return Map of username to DMSMember (only includes members that were found)
   */
  suspend fun getMembersByUsernameList(usernames: List<String>): Map<String, DMSMember> {
    if (usernames.isEmpty()) return emptyMap()

    // Batch fetch from Active Directory
    val adMembers = withContext(Dispatchers.IO) { activeDirectoryService.getMembersByUsernameList(usernames) }

    // Batch fetch from local DB - only fetch the specific usernames we need
    val dbMembers = memberRepository.getMembersByUsernames(usernames)

    // Build enriched member objects - filter out null AD members
    return adMembers
        .filterValues { it != null }
        .mapValues { (username, adMember) ->
          // adMember is guaranteed non-null after filterValues
          val adUser = adMember!!
          val dbMember =
              dbMembers[username] ?: memberRepository.getMemberOrInsert(username, adUser.enabled)

          // Populate member data
          dbMember.firstName = adUser.givenName
          dbMember.lastName = adUser.sn
          dbMember.displayName = adUser.displayName
          dbMember.personalEmail = adUser.mail
          dbMember.phoneNumber =
              getNormalizedPhoneNumber(adUser.telephoneNumber, adUser.sAMAccountName)
          dbMember.badgeNumber = adUser.employeeID
          dbMember.enabled = adUser.enabled
          dbMember.groups =
              adUser.groups.map { group ->
                DMSGroup(
                    name = group.cn,
                    description = null,
                    distinguishedName = group.distinguishedName,
                    objectGuid = group.objectGuid,
                    membersListIncomplete = false,
                    members = emptyList(),
                )
              }

          dbMember
        }
  }

  /**
   * Refreshes the discourse avatar URL for a member if they have a discourse username. Implements
   * smart caching and fallback mechanisms.
   *
   * @param member The member to refresh avatar for
   * @return The updated avatar URL, or the existing one if refresh fails/is skipped
   */
  private suspend fun refreshDiscourseAvatar(member: DMSMember): String? {
    val discourseUsername = member.discourseUsername

    // Return existing avatar URL if no discourse username
    if (discourseUsername.isNullOrBlank()) {
      log.debug("Member ${member.username} has no discourse username, skipping avatar refresh")
      return member.discourseAvatarUrl
    }

    try {
      // Attempt to refresh avatar URL using the avatar service
      // The service has its own caching to prevent excessive API calls
      val refreshedAvatarUrl = discourseAvatarService.refreshAvatarUrl(discourseUsername)

      if (refreshedAvatarUrl != null) {
        // Successfully refreshed - update database if URL changed
        if (refreshedAvatarUrl != member.discourseAvatarUrl) {
          val updateSuccess =
              memberRepository.updateDiscourseAvatarUrl(member.username, refreshedAvatarUrl)
          if (updateSuccess) {
            log.debug(
                "Successfully updated discourse avatar URL for ${member.username}: $refreshedAvatarUrl")
            return refreshedAvatarUrl
          } else {
            log.warn(
                "Failed to update discourse avatar URL in database for ${member.username}, using existing URL")
            return member.discourseAvatarUrl // Fallback to existing URL
          }
        } else {
          log.debug("Discourse avatar URL unchanged for ${member.username}")
          return member.discourseAvatarUrl
        }
      } else {
        // Avatar service returned null (likely due to caching or API failure)
        log.debug("Avatar refresh returned null for ${member.username}, using existing URL")
        return member.discourseAvatarUrl // Fallback to existing URL
      }
    } catch (e: Exception) {
      // Error during avatar refresh - fallback to existing URL
      log.warn("Failed to refresh discourse avatar for ${member.username}: ${e.message}")
      return member.discourseAvatarUrl // Fallback to existing URL
    }
  }

  suspend fun getMemberByBadgeNumber(badgeNumber: String): DMSMember {
    // Fetch member from active directory.
    val adMember = activeDirectoryService.getMemberByBadgeNumber(badgeNumber)

    val dbMember = memberRepository.getMemberOrInsert(adMember.sAMAccountName, adMember.enabled)
    // TODO: compare DB and AD members and notify observers of any changes.
    dbMember.firstName = adMember.givenName
    dbMember.lastName = adMember.sn
    dbMember.displayName = adMember.displayName
    dbMember.personalEmail = adMember.mail
    dbMember.phoneNumber =
        getNormalizedPhoneNumber(adMember.telephoneNumber, adMember.sAMAccountName)
    dbMember.badgeNumber = adMember.employeeID
    dbMember.enabled = adMember.enabled
    dbMember.memberSince = calculateMemberSince(adMember.whenCreated)
    dbMember.groups =
        adMember.groups.map { group ->
          DMSGroup(
              name = group.cn,
              description = null,
              distinguishedName = group.distinguishedName,
              objectGuid = group.objectGuid,
              membersListIncomplete = false,
              members = emptyList(),
          )
        }
    return dbMember
  }

  private fun getNormalizedPhoneNumber(
      telephoneNumber: String?,
      usernameForLogging: String,
  ): String? {
    if (telephoneNumber.isNullOrBlank() || telephoneNumber == "null") {
      return null
    }
    val pnE164: String? =
        try {
          val pnu: PhoneNumberUtil = PhoneNumberUtil.getInstance()
          val pn: Phonenumber.PhoneNumber = pnu.parse(telephoneNumber, "US")
          pnu.format(pn, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
        } catch (e: NumberParseException) {
          log.warn("Failed to parse phone number: $telephoneNumber for user: $usernameForLogging")
          null
        }
    return pnE164
  }

  /** Calculates the memberSince date from the whenCreated string. */
  @Deprecated(
      "Use org.dallasmakerspace.members.MemberService.calculateMemberSince(java.time.LocalDate) instead")
  @Suppress("MagicNumber")
  private fun calculateMemberSince(whenCreated: String?): Instant? {
    if (whenCreated == null) {
      return null
    }
    // Parse the whenCreated string from a format like "20220910163620.0Z" to an Instant. Instant
    // expects this format "2022-09-10T16:36:20Z"
    val instant =
        Instant.parse(
            whenCreated.substring(0, 4) +
                "-" +
                whenCreated.substring(4, 6) +
                "-" +
                whenCreated.substring(6, 8) +
                "T" +
                whenCreated.substring(8, 10) +
                ":" +
                whenCreated.substring(10, 12) +
                ":" +
                whenCreated.substring(12, 14) +
                "Z")
    return instant
  }

  /** Calculates the memberSince date from a LocalDate. */
  private fun calculateMemberSince(localDate: LocalDate?): Instant? {
    if (localDate == null) {
      return null
    }
    // Convert LocalDate to Instant at the start of the day in UTC
    return localDate.atStartOfDayIn(kotlinx.datetime.TimeZone.UTC)
  }

  suspend fun updateMember(username: String, memberFromApi: DMSMember) {
    // Fetch member from active directory.
    val adMember = withContext(Dispatchers.IO) { activeDirectoryService.getMemberByUsername(username) }
    // If member is not active in AD then throw an exception.
    check(adMember.enabled) {
      "Member is not active in AD: $username, we should not update this record!"
    }
    val dbMember = memberRepository.getMemberOrInsert(username, adMember.enabled)

    // Check both before any side effects below, so a rejected link can't half-apply another.
    if (memberFromApi.discourseUsername != null &&
        dbMember.discourseUsername != memberFromApi.discourseUsername) {
      reassignDiscourseLinkIfClaimedElsewhere(username, memberFromApi.discourseUsername!!)
    }
    if (memberFromApi.discordUserId != null &&
        dbMember.discordUserId != memberFromApi.discordUserId) {
      reassignDiscordLinkIfClaimedElsewhere(username, memberFromApi.discordUserId!!)
    }

    var propertiesUpdated = false
    // Figure out which properties have been updated by comparing dbMember and memberFromApi.
    if (dbMember.discourseUsername != memberFromApi.discourseUsername) {
      log.info(
          "Discourse username updated for $username; Old username: ${dbMember.discourseUsername}; " +
              "New username: ${memberFromApi.discourseUsername}")
      propertiesUpdated = true
      if (memberFromApi.discourseUsername == null) {
        unlinkDiscourse(dbMember)
      } else {
        linkDiscourse(memberFromApi)
      }
    }
    if (dbMember.discordUserId != memberFromApi.discordUserId) {
      log.info(
          "Discord user ID updated for $username; Old ID: ${dbMember.discordUserId}; " +
              "New ID: ${memberFromApi.discordUserId}")
      propertiesUpdated = true
      if (memberFromApi.discordUserId == null) {
        unlinkDiscord(dbMember)
      } else {
        linkDiscord(memberFromApi)
      }
    }
    if (dbMember.discordUsername != memberFromApi.discordUsername) {
      log.info(
          "Discord username updated for $username; Old: ${dbMember.discordUsername}; " +
              "New: ${memberFromApi.discordUsername}")
      propertiesUpdated = true
    }
    if (dbMember.discordAvatarUrl != memberFromApi.discordAvatarUrl) {
      log.info("Discord avatar URL updated for $username")
      propertiesUpdated = true
    }
    if (dbMember.discordWebhookId != memberFromApi.discordWebhookId) {
      log.info(
          "Discord webhook ID updated for $username; Old: ${dbMember.discordWebhookId}; " +
              "New: ${memberFromApi.discordWebhookId}")
      propertiesUpdated = true
    }
    if (propertiesUpdated) {
      // Update the member in the database.
      log.info("Updating member in the database: $username")
      memberRepository.updateMember(username, memberFromApi)
    }
  }

  private suspend fun linkDiscourse(memberFromApi: DMSMember) {
    // Add the member to the discourse group.
    discourseService.addUserToDmsMembersV2Group(
        listOf(requireNotNull(memberFromApi.discourseUsername)))
    activityLogService.insertActivityLogEntry(
        subjectUsername = memberFromApi.username,
        event = ActivityLogEvent.LINK_DISCOURSE,
    )
  }

  private suspend fun unlinkDiscourse(dbMember: DMSMember) {
    // Remove the member from the discourse group.
    discourseService.removeUserFromDmsMembersV2Group(
        listOf(requireNotNull(dbMember.discourseUsername)))
    activityLogService.insertActivityLogEntry(
        subjectUsername = dbMember.username,
        event = ActivityLogEvent.UNLINK_DISCOURSE,
    )
  }

  private suspend fun linkDiscord(memberFromApi: DMSMember) {
    activityLogService.insertActivityLogEntry(
        subjectUsername = memberFromApi.username,
        event = ActivityLogEvent.LINK_DISCORD,
    )
  }

  private suspend fun unlinkDiscord(dbMember: DMSMember) {
    activityLogService.insertActivityLogEntry(
        subjectUsername = dbMember.username,
        event = ActivityLogEvent.UNLINK_DISCORD,
    )
  }

  private suspend fun reassignDiscourseLinkIfClaimedElsewhere(
      newUsername: String,
      discourseUsername: String,
  ) {
    val conflictingMember =
        memberRepository.getMembersByDiscourseUsernames(listOf(discourseUsername))[
            discourseUsername]
    if (conflictingMember == null || conflictingMember.username == newUsername) return

    if (conflictingMember.enabled) {
      throw ExternalAccountAlreadyLinkedException(
          "This Talk account is already linked to another active member profile. Please " +
              "contact staff to resolve.")
    }

    log.warn(
        "Talk account $discourseUsername is linked to disabled member " +
            "${conflictingMember.username}; reassigning it to $newUsername")
    unlinkDiscourse(conflictingMember)
    conflictingMember.discourseUsername = null
    conflictingMember.discourseAvatarUrl = null
    memberRepository.updateMember(conflictingMember.username, conflictingMember)
  }

  /** Discord counterpart of [reassignDiscourseLinkIfClaimedElsewhere]. */
  private suspend fun reassignDiscordLinkIfClaimedElsewhere(
      newUsername: String,
      discordUserId: String,
  ) {
    val conflictingMember =
        memberRepository.getMembersByDiscordUserIds(listOf(discordUserId))[discordUserId]
    if (conflictingMember == null || conflictingMember.username == newUsername) return

    if (conflictingMember.enabled) {
      throw ExternalAccountAlreadyLinkedException(
          "This Discord account is already linked to another active member profile. Please " +
              "contact staff to resolve.")
    }

    log.warn(
        "Discord account $discordUserId is linked to disabled member " +
            "${conflictingMember.username}; reassigning it to $newUsername")
    unlinkDiscord(conflictingMember)
    conflictingMember.discordUserId = null
    conflictingMember.discordUsername = null
    conflictingMember.discordAvatarUrl = null
    memberRepository.updateMember(conflictingMember.username, conflictingMember)
  }

  suspend fun getAllMembersWithProfiles(): List<DMSMember> {
    return memberRepository.getAllMembers()
  }

  suspend fun getAllMembers(): List<DMSMember> {
    val totalStartNs = System.nanoTime()

    // Get all users from MakerManager
    val mmStartNs = System.nanoTime()
    val makerManagerUsers = makerManagerDataService.getAllUsers()
    val mmMs = (System.nanoTime() - mmStartNs) / 1_000_000

    // Get all members from the repository to get discourse usernames
    val dbStartNs = System.nanoTime()
    val dbMembers = memberRepository.getAllMembers()
    val dbMs = (System.nanoTime() - dbStartNs) / 1_000_000
    val usernameAvatarMap =
        dbMembers
            .filter { it.discourseAvatarUrl?.isNotEmpty() == true }
            .associateBy({ it.username }, { it.discourseAvatarUrl?.replace("{size}", "144") })

    // Create a map of usernames to discourse usernames for quick lookup and combine data
    val combineStartNs = System.nanoTime()
    val discourseUsernameMap = dbMembers.associateBy({ it.username }, { it.discourseUsername })
    val result =
        makerManagerUsers.map { mmUser ->
          DMSMember(
              id = mmUser.makerManagerId,
              username = mmUser.username,
              firstName = mmUser.firstName,
              lastName = mmUser.lastName,
              displayName =
                  "${mmUser.firstName ?: ""} ${mmUser.lastName ?: ""}".trim().takeIf {
                    it.isNotEmpty()
                  },
              personalEmail = mmUser.email,
              phoneNumber = mmUser.phone,
              badgeNumber = mmUser.badgeNumber,
              enabled = mmUser.adActive,
              discourseUsername = discourseUsernameMap[mmUser.username],
              discourseAvatarUrl = usernameAvatarMap[mmUser.username],
              memberSince = null, // Not available in MakerManager query
              groups = emptyList(),
              accountInfo = null,
          )
        }
    val combineMs = (System.nanoTime() - combineStartNs) / 1_000_000

    val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000
    log.info(
        "getAllMembers timings: total=${totalMs}ms, makerManager=${mmMs}ms, " +
            "memberRepository=${dbMs}ms, combine=${combineMs}ms")
    return result
  }

  suspend fun getGroup(groupslug: String): DMSGroup = coroutineScope {
    val groupname = Groups.getNameFromSlug(groupslug)

    // Run AD query, DB members query, and group history query in parallel
    // AD call is blocking, so run on IO dispatcher to avoid blocking coroutine threads
    val adGroupDeferred = async {
      withContext(Dispatchers.IO) { activeDirectoryService.getGroup(groupname) }
    }
    val dbMembersDeferred = async { memberRepository.getAllMembers() }
    val groupHistoryDeferred = async { groupHistoryRepository.getGroupHistoryByName(groupname) }

    // Await all results
    val adGroup = adGroupDeferred.await()
    val dbMembers = dbMembersDeferred.await()
    val groupHistory = groupHistoryDeferred.await()

    val currentMemberDisplayNames =
        adGroup.members.associate { it.sAMAccountName to it.displayName }
    val enrichedHistory = enrichHistoryWithDisplayNames(groupHistory, currentMemberDisplayNames)

    DMSGroup(
        name = adGroup.cn,
        description = adGroup.description,
        distinguishedName = adGroup.distinguishedName,
        objectGuid = adGroup.objectGuid,
        members =
            adGroup.members.map {
              // Find corresponding DB member
              val dbMember = dbMembers.find { db -> db.username == it.sAMAccountName }
              DMSMember(
                  -1,
                  it.sAMAccountName,
                  firstName = it.givenName,
                  lastName = it.sn,
                  displayName = it.displayName,
                  discourseUsername = dbMember?.discourseUsername,
                  discourseAvatarUrl = dbMember?.discourseAvatarUrl,
                  profilePhotoUrl = dbMember?.profilePhotoUrl,
                  personalEmail = it.mail,
                  phoneNumber = getNormalizedPhoneNumber(it.telephoneNumber, it.sAMAccountName),
                  badgeNumber = it.employeeID,
                  enabled = it.enabled,
                  memberSince = calculateMemberSince(it.whenCreated),
                  groups =
                      it.groups.map { group ->
                        DMSGroup(
                            name = group.cn,
                            description = null,
                            distinguishedName = group.distinguishedName,
                            objectGuid = group.objectGuid,
                            membersListIncomplete = false,
                            members = emptyList(),
                        )
                      },
              )
            },
        membersListIncomplete = adGroup.membersListIncomplete,
        administrators = adGroup.administrators,
        history = enrichedHistory,
        nestedGroups = adGroup.nestedGroups,
    )
  }

  suspend fun getMultipleGroups(groupslugs: List<String>): List<DMSGroup> = coroutineScope {
    val groupnames = groupslugs.map { Groups.getNameFromSlug(it) }

    try {
      // Run AD query and DB members query in parallel
      val adGroupsDeferred = async {
        withContext(Dispatchers.IO) { activeDirectoryService.getMultipleGroups(groupnames) }
      }
      val dbMembersDeferred = async { memberRepository.getAllMembers() }

      // Await all results
      val adGroups = adGroupsDeferred.await()
      val dbMembers = dbMembersDeferred.await()

      // Enrich each group with DB member data
      val enrichedGroups =
          adGroups.map { adGroup ->
            DMSGroup(
                name = adGroup.cn,
                description = adGroup.description,
                distinguishedName = adGroup.distinguishedName,
                objectGuid = adGroup.objectGuid,
                members =
                    adGroup.members.map {
                      // Find corresponding DB member
                      val dbMember = dbMembers.find { db -> db.username == it.sAMAccountName }
                      DMSMember(
                          -1,
                          it.sAMAccountName,
                          firstName = it.givenName,
                          lastName = it.sn,
                          displayName = it.displayName,
                          discourseAvatarUrl = dbMember?.discourseAvatarUrl,
                          profilePhotoUrl = dbMember?.profilePhotoUrl,
                          discourseUsername = dbMember?.discourseUsername,
                          personalEmail = it.mail,
                          phoneNumber =
                              getNormalizedPhoneNumber(it.telephoneNumber, it.sAMAccountName),
                          badgeNumber = it.employeeID,
                          enabled = it.enabled,
                          memberSince = calculateMemberSince(it.whenCreated),
                          groups =
                              it.groups.map { group ->
                                DMSGroup(
                                    name = group.cn,
                                    description = null,
                                    distinguishedName = group.distinguishedName,
                                    objectGuid = group.objectGuid,
                                    membersListIncomplete = false,
                                    members = emptyList(),
                                )
                              },
                      )
                    },
                membersListIncomplete = adGroup.membersListIncomplete,
                administrators = adGroup.administrators,
                nestedGroups = adGroup.nestedGroups,
            )
          }

      // Update fallback cache on success
      enrichedGroups.forEach { group -> groupsFallbackCache[group.name] = group }

      enrichedGroups
    } catch (e: Exception) {
      log.warn("Failed to fetch groups from AD/DB, attempting fallback cache: ${e.message}")

      // Try to return cached groups
      val cachedGroups =
          groupnames.mapNotNull { name ->
            groupsFallbackCache[name]?.also { log.info("Returning cached group for name: $name") }
          }

      if (cachedGroups.isNotEmpty()) {
        log.info("Returned ${cachedGroups.size} groups from fallback cache")
        cachedGroups
      } else {
        log.error("No cached groups available, re-throwing exception")
        throw e
      }
    }
  }

  suspend fun addMembersToGroup(
      memberUsernames: List<String>,
      groupslug: String,
      actorUsername: String? = null,
  ) {
    val groupname = Groups.getNameFromSlug(groupslug)
    requireGroupInDirectory(groupname)
    val groupId = groupHistoryRepository.getOrCreateGroup(groupname)
    val now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)

    val actorProfile = actorUsername?.let { runCatching { getMemberByUsername(it) }.getOrNull() }
    if (actorUsername != null && actorProfile == null) {
      log.warn(
          "Could not resolve actor profile for '$actorUsername'; group history will be skipped")
    }

    memberUsernames.forEach { username ->
      val memberProfile = runCatching { getMemberByUsername(username) }.getOrNull()
      if (memberProfile != null && actorProfile != null) {
        groupHistoryRepository.insertGroupHistory(
            actorId = actorProfile.id,
            memberId = memberProfile.id,
            groupId = groupId,
            actionType = org.dallasmakerspace.models.ActionType.ADD_USER,
            eventTimestamp = now,
            source = "PORTAL",
        )
      }
    }

    try {
      activeDirectoryService.addUsersToGroup(memberUsernames, groupname)
    } catch (e: Exception) {
      log.error(
          "AD call failed after PORTAL history records were written for group '$groupname'. " +
              "Members $memberUsernames were not actually added. Manual reconciliation may be needed.",
          e,
      )
      throw e
    }

    memberUsernames.forEach { username ->
      if (actorUsername != null) {
        activityLogService.insertActivityLogEntry(
            actorUsername = actorUsername,
            subjectUsername = username,
            event = ActivityLogEvent.GROUP_MEMBER_ADDED,
            attributes = """{"groupName":"$groupname"}""",
        )
      } else {
        activityLogService.insertActivityLogEntry(
            subjectUsername = username,
            event = ActivityLogEvent.GROUP_MEMBER_ADDED,
        )
      }
      if (groupname == voterRegistrationManager.getVotingMembersGroupName()) {
        activityLogService.insertActivityLogEntry(
            subjectUsername = username,
            event = ActivityLogEvent.ADD_TO_VOTING_MEMBERS_GROUP,
        )
      }
    }
  }

  suspend fun removeMembersToGroup(
      memberUsernames: List<String>,
      groupslug: String,
      actorUsername: String? = null,
  ) {
    val groupname = Groups.getNameFromSlug(groupslug)
    requireGroupInDirectory(groupname)
    val groupId = groupHistoryRepository.getOrCreateGroup(groupname)
    val now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)

    val actorProfile = actorUsername?.let { runCatching { getMemberByUsername(it) }.getOrNull() }
    if (actorUsername != null && actorProfile == null) {
      log.warn(
          "Could not resolve actor profile for '$actorUsername'; group history will be skipped")
    }

    memberUsernames.forEach { username ->
      val memberProfile = runCatching { getMemberByUsername(username) }.getOrNull()
      if (memberProfile != null && actorProfile != null) {
        groupHistoryRepository.insertGroupHistory(
            actorId = actorProfile.id,
            memberId = memberProfile.id,
            groupId = groupId,
            actionType = org.dallasmakerspace.models.ActionType.REMOVE_USER,
            eventTimestamp = now,
            source = "PORTAL",
        )
      }
    }

    try {
      activeDirectoryService.removeUsersFromGroup(memberUsernames, groupname)
    } catch (e: Exception) {
      log.error(
          "AD call failed after PORTAL history records were written for group '$groupname'. " +
              "Members $memberUsernames were not actually removed. Manual reconciliation may be needed.",
          e,
      )
      throw e
    }

    memberUsernames.forEach { username ->
      if (actorUsername != null) {
        activityLogService.insertActivityLogEntry(
            actorUsername = actorUsername,
            subjectUsername = username,
            event = ActivityLogEvent.GROUP_MEMBER_REMOVED,
            attributes = """{"groupName":"$groupname"}""",
        )
      } else {
        activityLogService.insertActivityLogEntry(
            subjectUsername = username,
            event = ActivityLogEvent.GROUP_MEMBER_REMOVED,
        )
      }
      if (groupname == voterRegistrationManager.getVotingMembersGroupName()) {
        activityLogService.insertActivityLogEntry(
            subjectUsername = username,
            event = ActivityLogEvent.REMOVE_FROM_VOTING_MEMBERS_GROUP,
        )
      }
    }
  }

  /** Enriches group history records with display names for actors and members. */
  private suspend fun enrichHistoryWithDisplayNames(
      history: List<org.dallasmakerspace.models.GroupHistory>,
      currentMemberDisplayNames: Map<String, String?>,
  ): List<org.dallasmakerspace.models.GroupHistory> {
    if (history.isEmpty()) return history

    // Find usernames in history that are NOT in current group members
    val historyUsernames =
        (history.map { it.actorUsername } + history.map { it.memberUsername })
            .filter { it.isNotBlank() && !currentMemberDisplayNames.containsKey(it) }
            .toSet()

    // Batch fetch display names for users not in current members
    val additionalMembers =
        if (historyUsernames.isNotEmpty()) {
          getMembersByUsernameList(historyUsernames.toList())
        } else {
          emptyMap()
        }

    return history.map { record ->
      val actorDisplayName =
          currentMemberDisplayNames[record.actorUsername]
              ?: additionalMembers[record.actorUsername]?.displayName
      val memberDisplayName =
          currentMemberDisplayNames[record.memberUsername]
              ?: additionalMembers[record.memberUsername]?.displayName
      record.copy(actorDisplayName = actorDisplayName, memberDisplayName = memberDisplayName)
    }
  }

  /**
   * Updates the badge number (employeeID) in Active Directory.
   *
   * @param username The username of the member
   * @param badgeNumber The new badge number to set
   */
  fun updateBadgeInAD(username: String, badgeNumber: String) {
    activeDirectoryService.updateBadgeNumber(username, badgeNumber)
  }

  /**
   * Gets the badge number from MakerManager database.
   *
   * @param username The username of the member
   * @return The badge number or null if not found
   */
  suspend fun getBadgeFromMakerManager(username: String): String? {
    return try {
      val users = makerManagerDataService.getAllUsers()
      users.find { it.username == username }?.badgeNumber
    } catch (e: Exception) {
      log.error("Failed to get badge from MakerManager for $username", e)
      null
    }
  }

  /**
   * Gets the badge number from Active Directory.
   *
   * @param username The username of the member
   * @return The badge number or null if not found
   */
  fun getBadgeFromActiveDirectory(username: String): String? {
    return try {
      val adUser = activeDirectoryService.getMemberByUsername(username)
      adUser.employeeID
    } catch (e: Exception) {
      log.error("Failed to get badge from Active Directory for $username", e)
      null
    }
  }

  /** DN of the one Infrastructure group (in the main group OU), resolved once from AD. */
  @Volatile private var infraGroupDn: String? = null

  /**
   * True if the user is a member of the Infrastructure group: the actual group in the main group
   * OU, matched by DN, not any group that merely has the same name elsewhere. Fails closed.
   */
  fun isUserInInfraGroup(username: String): Boolean {
    return try {
      val infraDn = infraGroupDn ?: resolveInfraGroupDn()
      activeDirectoryService.getMemberByUsername(username).groups.any {
        DirectoryOus.sameDn(it.distinguishedName, infraDn)
      }
    } catch (e: Exception) {
      log.warn("Could not check infra group membership for '$username', defaulting to false", e)
      false
    }
  }

  private fun resolveInfraGroupDn(): String {
    val group =
        activeDirectoryService.getGroupStrict(org.dallasmakerspace.groups.Constants.INFRASTRUCTURE)
    check(DirectoryOus.isInMainGroupOu(group.distinguishedName)) {
      "The Infrastructure group is not in the main group OU"
    }
    return group.distinguishedName.also { infraGroupDn = it }
  }

  /** Throws [GroupNotFoundException] before any portal row is written for a missing AD group. */
  private suspend fun requireGroupInDirectory(groupname: String) {
    if (!withContext(Dispatchers.IO) { activeDirectoryService.groupExists(groupname) }) {
      throw GroupNotFoundException(groupname)
    }
  }

  /** Direct (memberOf) membership only; throws if AD can't be reached. */
  suspend fun isDirectMemberOfGroup(username: String, groupslug: String): Boolean {
    val groupName = Groups.getNameFromSlug(groupslug)
    return withContext(Dispatchers.IO) {
      activeDirectoryService.getMemberByUsername(username).groups.any {
        it.cn.equals(groupName, ignoreCase = true)
      }
    }
  }

  private fun isUserInGroup(username: String, groupCn: String): Boolean {
    return try {
      activeDirectoryService.getMemberByUsername(username).groups.any { it.cn == groupCn }
    } catch (e: Exception) {
      log.warn(
          "Could not check group '$groupCn' membership for '$username', defaulting to false", e)
      false
    }
  }

  /**
   * Whether [username] administers [groupslug]: directly — either as the individual
   * `managedBy` person, or as a member of a `managedBy` group — or one hop removed (member of
   * a group that is itself a direct member of a group-type `administrators` entry). Capped at
   * [MAX_GROUP_ADMIN_DEPTH] hops as a guardrail — no AD-side recursive/chain query is used, so
   * this is a plain bounded frontier expansion over already-fetched group data. Only group-type
   * `administrators` entries are walked further; a person can't have nested members, so a
   * person-type entry is only ever matched directly against the actor's own account.
   *
   * DN comparisons are done case-insensitively — AD's distinguishedNameMatch rule is
   * case-insensitive, and the same DN can come back with different casing depending on which
   * attribute (`memberOf` vs. `member`/`managedBy`) it was read from.
   *
   * AD calls are blocking, so run on IO dispatcher to avoid blocking coroutine threads.
   */
  suspend fun isGroupAdmin(username: String, groupslug: String): Boolean {
    return try {
      val groupName = Groups.getNameFromSlug(groupslug)
      val administrators =
          withContext(Dispatchers.IO) { activeDirectoryService.getGroup(groupName) }.administrators
      if (administrators.isEmpty()) return false

      val actor =
          withContext(Dispatchers.IO) { activeDirectoryService.getMemberByUsername(username) }
      // "Who the actor is, directly": their own account DN (matches a person-type managedBy
      // entry) plus every AD group they're a direct member of (matches a group-type entry).
      val actorIdentityDNs = actor.groups.map { it.distinguishedName } + actor.distinguishedName
      if (actorIdentityDNs.matchesAnyDn(administrators)) return true

      // One hop further, via group-type administrators entries only — a person entry has no
      // members to walk, and was already checked above.
      var frontier = administrators.filter { isGroupDN(it) }
      repeat(MAX_GROUP_ADMIN_DEPTH - 1) {
        frontier =
            frontier.flatMap { dn ->
              try {
                withContext(Dispatchers.IO) {
                  activeDirectoryService.getGroup(parseCNFromDN(dn)).nestedGroups
                }
              } catch (e: Exception) {
                log.warn(
                    "Could not resolve nested groups for admin group DN '$dn' while " +
                        "checking '$username' on '$groupslug', skipping this branch",
                    e)
                emptyList()
              }
            }
        if (actorIdentityDNs.matchesAnyDn(frontier)) return true
      }
      false
    } catch (e: Exception) {
      log.warn(
          "Could not check group admin status for '$username' on group '$groupslug', " +
              "defaulting to false",
          e)
      false
    }
  }

  private suspend fun computeWhmcsStatus(username: String): WhmcsStatusSummary {
    val relatedAccounts = makerManagerDataService.getAccountInfoMap(listOf(username))
    val accountInfo =
        relatedAccounts[username] ?: return WhmcsStatusSummary(null, false, null, emptyList())
    val account =
        if (accountInfo.isPrimaryAccount) accountInfo.primaryAccount
        else accountInfo.addonAccounts.find { it.username == username }
    account ?: return WhmcsStatusSummary(null, false, null, emptyList())

    val whmcsId = account.whmcsId
    val regDate =
        accountInfo.regDate?.let { date ->
          java.time.LocalDate.of(date.year, date.monthNumber, date.dayOfMonth)
        } ?: whmcsDataService.getAccountRegdate(whmcsId)
    regDate ?: return WhmcsStatusSummary(null, false, null, emptyList())

    val accountStatus =
        whmcsDataService.getAccountInfoMap(listOf(whmcsId), regDate)[whmcsId]
            ?: return WhmcsStatusSummary(null, false, null, emptyList())

    val timeline =
        accountStatus.timeline.map { entry ->
          org.dallasmakerspace.models.TimelineEntryInfo(
              type =
                  when (entry.type) {
                    org.dallasmakerspace.db.master.TimelineEntryType.ACTIVE ->
                        org.dallasmakerspace.models.TimelineEntryType.ACTIVE
                    org.dallasmakerspace.db.master.TimelineEntryType.GAP ->
                        org.dallasmakerspace.models.TimelineEntryType.GAP
                  },
              startDate = entry.startDate.toKotlinLocalDate(),
              endDate = entry.endDate?.toKotlinLocalDate(),
              durationDays = entry.durationDays,
          )
        }
    val whmcsActive =
        timeline.lastOrNull()?.let {
          it.type == org.dallasmakerspace.models.TimelineEntryType.ACTIVE && it.endDate == null
        } ?: false

    return WhmcsStatusSummary(accountStatus.daysInCurrentStatus, whmcsActive, regDate, timeline)
  }

  suspend fun getScannerStatus(
      username: String,
      actorUsername: String?,
  ): ScannerStatusResponse? {
    val mmUsers = makerManagerDataService.getAllUsers()
    val mmUser = mmUsers.find { it.username.equals(username, ignoreCase = true) } ?: return null
    val canonicalUsername = mmUser.username

    val whmcsStatus = computeWhmcsStatus(canonicalUsername)
    val dbMember = memberRepository.getMembersByUsernames(listOf(canonicalUsername))[canonicalUsername]

    val isPrivileged =
        actorUsername != null &&
            withContext(Dispatchers.IO) {
              isUserInInfraGroup(actorUsername) ||
                  isUserInGroup(actorUsername, LOGISTICS_CHAIR_GROUP_CN)
            }

    return ScannerStatusResponse(
        username = canonicalUsername,
        firstName = mmUser.firstName,
        lastName = mmUser.lastName,
        isActive = mmUser.adActive,
        daysInCurrentStatus = whmcsStatus.daysInCurrentStatus,
        regDate = whmcsStatus.regDate?.toString(),
        phone = if (isPrivileged) mmUser.phone else null,
        email = if (isPrivileged) mmUser.email else null,
        discourseUsername = dbMember?.discourseUsername,
        discordUserId = dbMember?.discordUserId,
    )
  }

  /**
   * Gets debug information for a member, including AD status, MM status, and WHMCS status.
   *
   * @param username The username of the member
   * @return MemberDebugInfo with status from all three systems
   */
  suspend fun getTotalActiveDays(username: String): Int? {
    val relatedAccounts = makerManagerDataService.getAccountInfoMap(listOf(username))
    val accountInfo = relatedAccounts[username] ?: return null
    val account =
        if (accountInfo.isPrimaryAccount) accountInfo.primaryAccount
        else accountInfo.addonAccounts.find { it.username == username }
    account ?: return null
    val whmcsId = account.whmcsId
    val regDate =
        accountInfo.regDate?.let { date ->
          java.time.LocalDate.of(date.year, date.monthNumber, date.dayOfMonth)
        } ?: whmcsDataService.getAccountRegdate(whmcsId) ?: return null
    val accountStatus =
        whmcsDataService.getAccountInfoMap(listOf(whmcsId), regDate)[whmcsId] ?: return null
    return accountStatus.timeline
        .filter { it.type == org.dallasmakerspace.db.master.TimelineEntryType.ACTIVE }
        .sumOf { it.durationDays }
        .takeIf { it > 0 }
  }

  suspend fun getDebugInfo(username: String): org.dallasmakerspace.models.MemberDebugInfo {
    val adAccountEnabled =
        try {
          withContext(Dispatchers.IO) { activeDirectoryService.getMemberByUsername(username).enabled }
        } catch (e: Exception) {
          log.error("Failed to get AD account status for $username", e)
          false
        }

    val mmAdActive =
        try {
          makerManagerDataService.getAllUsers().find { it.username == username }?.adActive ?: false
        } catch (e: Exception) {
          log.error("Failed to get MM AD active status for $username", e)
          false
        }

    val whmcsStatus = computeWhmcsStatus(username)

    return org.dallasmakerspace.models.MemberDebugInfo(
        adAccountEnabled = adAccountEnabled,
        mmAdActive = mmAdActive,
        whmcsActive = whmcsStatus.whmcsActive,
        daysInCurrentWhmcsStatus = whmcsStatus.daysInCurrentStatus,
        totalActiveDays =
            if (whmcsStatus.regDate != null)
                whmcsStatus.timeline
                    .filter { it.type == org.dallasmakerspace.models.TimelineEntryType.ACTIVE }
                    .sumOf { it.durationDays }
            else null,
        timeline = whmcsStatus.timeline,
    )
  }

  suspend fun getRecentBadgeSwipes(
      username: String
  ): List<org.dallasmakerspace.models.BadgeSwipeInfo> {
    return try {
      doorEventsRepository.getRecentSwipesForUser(username)
    } catch (e: Exception) {
      log.error("Failed to get recent badge swipes for $username", e)
      emptyList()
    }
  }
}

private const val LOGISTICS_CHAIR_GROUP_CN = "Logistics Committee Chair"

// Guardrail for MemberService.isGroupAdmin: bounds the AD group-nesting walk so a misconfigured
// or deeply-nested group tree can't turn it into an unbounded traversal.
private const val MAX_GROUP_ADMIN_DEPTH = 2

// AD's distinguishedNameMatch equality rule is case-insensitive, and the same DN can come back
// with different casing depending on which attribute (memberOf vs. member/managedBy) it was read
// from — so DN comparisons must not rely on exact String/Set equality.
private fun List<String>.matchesAnyDn(other: List<String>): Boolean =
    any { dn -> other.any { it.equals(dn, ignoreCase = true) } }

/** Cached WHMCS account metadata for a username; populated at startup from MakerManager. */
private data class WhmcsAccountCacheEntry(val whmcsId: Int, val isPrimaryAccount: Boolean)

private data class WhmcsStatusSummary(
    val daysInCurrentStatus: Int?,
    val whmcsActive: Boolean,
    val regDate: java.time.LocalDate?,
    val timeline: List<org.dallasmakerspace.models.TimelineEntryInfo>,
)
