package org.dallasmakerspace.server.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import io.ktor.server.thymeleaf.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toJavaInstant
import org.dallasmakerspace.models.CommunicationPreference
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.models.slug
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.plugins.UserSession
import org.dallasmakerspace.server.subscriptions.subscriptionButtonModel
import org.dallasmakerspace.server.voterregistration.VoterRegistrationManager

class ProfileHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
    private val voterRegistrationManager: VoterRegistrationManager,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  companion object {
    private val CHICAGO_ZONE = ZoneId.of("America/Chicago")
    private val EVENT_INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val EVENT_OUTPUT_FORMATTER = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' h:mm a")
    private const val FOLLOW_FEATURE_FLAG_KEY = "notifications.follow-feature-enabled"
    private const val PROFILE_PHOTO_FEATURE_FLAG_KEY = "profile-photo.enabled"
    private const val FACEPILE_LIMIT = 5
    private const val FACEPILE_AVATAR_SIZE = 90
    private const val PROFILE_PIC_FALLBACK = "/static/images/profile-pic-not-set.svg"
  }

  override suspend fun handleAuthenticated(call: ApplicationCall) = coroutineScope {
    // Get username requested from path /profile/@{preferred_username}
    val requestedUsername =
        call.parameters["preferred_username"]
            ?: throw AuthException("No username found in url path")

    // Compute flags before any service calls (userInfo already populated by base class)
    val currentUsername = userInfo["preferred_username"] as String?
    val isSelf = requestedUsername == currentUsername
    val canManageMembers = authz.can(Permission.MANAGE_MEMBERS.name)
    val isSelfOrAdmin = isSelf || canManageMembers

    // Launch all calls in parallel immediately
    val memberDeferred = async { memberService.getMember(requestedUsername, session.sessionId) }
    val eventsDeferred = async {
      try {
        memberService.getEventsOrganizedByMember(requestedUsername, 5, session.sessionId)
      } catch (e: Exception) {
        log.warn("Failed to fetch events for member: $requestedUsername", e)
        emptyList()
      }
    }
    val electionsUrlDeferred = async {
      memberServiceClient.getConfigValue("voting.elections-url", session.sessionId)
    }
    val settingsDeferred: Deferred<Map<String, String>?> = async {
      try {
        memberService.getMemberSettings(requestedUsername, session.sessionId)
      } catch (e: Exception) {
        log.warn("Failed to fetch settings for member: $requestedUsername", e)
        null
      }
    }
    val followingDeferred = async {
      memberServiceClient.getFollowing(requestedUsername, session.sessionId)
    }
    val followersDeferred = async {
      memberServiceClient.getFollowers(requestedUsername, session.sessionId)
    }
    val followFeatureEnabledDeferred = async {
      currentUsername?.let {
        memberServiceClient.isFeatureEnabled(FOLLOW_FEATURE_FLAG_KEY, it, session.sessionId)
      } ?: false
    }
    val followSubscriptionDeferred = async {
      if (currentUsername == null || isSelf) null
      else
          memberServiceClient.getSubscriptionStatus(
              TopicKeys.FOLLOWING,
              mapOf("username" to requestedUsername),
              currentUsername,
              session.sessionId)
    }
    val profilePhotoEnabledDeferred = async {
      currentUsername?.let {
        memberServiceClient.isFeatureEnabled(PROFILE_PHOTO_FEATURE_FLAG_KEY, it, session.sessionId)
      } ?: false
    }

    // Await critical result; events still running concurrently
    val requestedMember = memberDeferred.await()

    val avatarUrl = normalizeAvatarUrl(requestedMember.displayAvatarUrl())
    val now = Instant.now()
    val memberSince = requestedMember.memberSince?.toJavaInstant()
    val memberSinceString = getMemberSinceString(memberSince)
    val memberDurationString = getMemberDurationString(memberSince, now)
    val jsonMap =
        mutableMapOf(
            "name" to requestedMember.displayName as Any,
            "preferred_username" to requestedMember.username,
            "member_since" to memberSinceString,
            "membership_duration" to memberDurationString,
            "enabled" to requestedMember.enabled.toString(),
            "avatar_url" to avatarUrl,
        )
    val displayedGroups = profileDisplayGroups(requestedMember.groups)
    if (displayedGroups.isNotEmpty())
        jsonMap["groups"] =
            displayedGroups
                .map { group ->
                  mapOf(
                      "name" to group.name,
                      "slug" to group.slug,
                      "description" to group.description,
                  )
                }
    if (requestedMember.discourseUsername.isNullOrEmpty().not()) {
      jsonMap["discourse_username"] = requestedMember.discourseUsername as Any
    }
    if (requestedMember.discordUserId.isNullOrEmpty().not()) {
      jsonMap["discord_userid"] = requestedMember.discordUserId as Any
      requestedMember.discordUsername?.let { jsonMap["discord_username"] = it }
      requestedMember.discordAvatarUrl?.let { jsonMap["discord_avatar_url"] = it }
    }
    requestedMember.groups
        .firstOrNull { group -> group.name == voterRegistrationManager.getVotingMembersGroupName() }
        ?.apply { jsonMap["is_voting_member"] = "true" }
    jsonMap["is_self"] = isSelf.toString()
    jsonMap["profile_photo_enabled"] = profilePhotoEnabledDeferred.await()
    jsonMap["has_uploaded_photo"] = requestedMember.profilePhotoUrl != null
    jsonMap["can_moderate_photo"] = canManageMembers && !isSelf
    val settings = settingsDeferred.await()
    val communicationPreference = settings?.get("communication_preference") ?: ""
    jsonMap["communication_preference"] = communicationPreference
    jsonMap["social_links"] = SocialLinks.displayLinks(settings.orEmpty())
    // No dialog without settings: it would prefill empty and clear the member's links on save.
    if (isSelf && settings != null) jsonMap["social_link_fields"] = SocialLinks.formFields(settings)
    if (isSelfOrAdmin || communicationPreference == CommunicationPreference.EMAIL.name) {
      requestedMember.personalEmail?.apply { jsonMap["personal_email"] = this as Any }
    }
    if (isSelfOrAdmin || communicationPreference == CommunicationPreference.SMS.name) {
      requestedMember.phoneNumber?.apply { jsonMap["phone_number"] = this as Any }
    }
    jsonMap["communication_preferences"] =
        CommunicationPreference.entries.map { pref ->
          mapOf("value" to pref.name, "label" to pref.label)
        } as Any
    jsonMap["authz"] = authz
    jsonMap["is_voter_registration_test_mode_enabled"] =
        VoterRegistrationManager.IS_VOTER_REGISTRATION_TEST_MODE_ENABLED
    requestedMember.accountInfo?.apply {
      jsonMap["is_primary_account"] = this.isPrimaryAccount
      jsonMap["was_active_past_90_days"] = this.wasActivePast90Days ?: true
      this.lastInactiveDate?.let {
        jsonMap["last_inactive_date"] =
            it.format(LocalDate.Format { byUnicodePattern("M/d") }) as Any
        jsonMap["days_since_last_inactive"] =
            voterRegistrationManager.getDaysSinceLastInactiveDateString(it)
      }
      jsonMap["addon_accounts"] = this.addonAccounts
      this.primaryAccount?.apply {
        jsonMap["primary_account"] = this
        jsonMap["whmcs_id"] = this.whmcsId
      }
    }

    // Await elections URL and events — both have been running throughout all synchronous processing
    jsonMap["elections_url"] = electionsUrlDeferred.await() ?: ""
    val events = eventsDeferred.await()
    if (events.isNotEmpty()) {
      val formattedEvents =
          events.map { event ->
            mapOf(
                "id" to event.id,
                "name" to event.name,
                "eventStart" to formatEventDate(event.eventStart),
                "eventStartRaw" to event.eventStart,
                "status" to event.status,
                "isUpcoming" to isUpcomingEvent(event.eventStart),
                "url" to "https://calendar.dallasmakerspace.org/events/view/${event.id}",
            )
          }
      jsonMap["events_organized"] = formattedEvents
    }

    val followFeatureEnabled = followFeatureEnabledDeferred.await()
    jsonMap["follow_feature_enabled"] = followFeatureEnabled
    if (followFeatureEnabled) {
      val following = followingDeferred.await()
      val followers = followersDeferred.await()
      jsonMap["following_count"] = following.size
      jsonMap["followers_count"] = followers.size
      jsonMap["following_overflow"] = (following.size - FACEPILE_LIMIT).coerceAtLeast(0)
      jsonMap["followers_overflow"] = (followers.size - FACEPILE_LIMIT).coerceAtLeast(0)
      // Only the <=5 avatars actually rendered per row are fetched (<=10 usernames total) - the
      // facepile isn't meant to represent the full lists, just a preview of them.
      // The viewer is included so the follow button can add their avatar to the facepile in place.
      val viewer = currentUsername?.takeIf { !isSelf }
      val shown =
          (following.take(FACEPILE_LIMIT) + followers.take(FACEPILE_LIMIT) + listOfNotNull(viewer))
              .distinct()
      val members = memberServiceClient.getMembersByUsernames(shown, session.sessionId)
      jsonMap["following_facepile"] = buildFacepile(following, members)
      jsonMap["followers_facepile"] = buildFacepile(followers, members)
      followSubscriptionDeferred.await()?.let {
        jsonMap["follow_subscription"] = subscriptionButtonModel(it)
      }
      if (viewer != null) {
        jsonMap["viewer_username"] = viewer
        jsonMap["viewer_facepile_item"] = buildFacepile(listOf(viewer), members).single()
      }
    }

    setToastMessage(call, jsonMap, requestedMember, session, isSelf)
    call.respond(ThymeleafContent("profile", jsonMap))
  }

  private fun setToastMessage(
      call: ApplicationCall,
      jsonMap: MutableMap<String, Any>,
      requestedMember: DMSMember,
      session: UserSession,
      isSelf: Boolean,
  ) {
    if (session.isDiscourseLinkSuccess) {
      log.info("Setting toast message for ${requestedMember.username} successful discourse link")
      call.sessions.set(session.copy(isDiscourseLinkSuccess = false))
      jsonMap["toast_message"] =
          "Successfully linked @${requestedMember.discourseUsername} to your profile."
    } else if (session.isDiscordLinkSuccess) {
      log.info("Setting toast message for ${requestedMember.username} successful discord link")
      call.sessions.set(session.copy(isDiscordLinkSuccess = false))
      jsonMap["toast_message"] =
          "Successfully linked Discord @${requestedMember.discordUsername} to your profile."
    } else if (session.isVoterRegistrationSuccess) {
      log.info(
          "Setting toast message for ${requestedMember.username} successful voter registration")
      call.sessions.set(session.copy(isVoterRegistrationSuccess = false))
      jsonMap["toast_message"] = "Successfully registered to vote. May take few min to take effect."
    } else if (session.isSettingsUpdateSuccess) {
      log.info("Setting toast message for ${requestedMember.username} successful settings update")
      call.sessions.set(session.copy(isSettingsUpdateSuccess = false))
      jsonMap["toast_message"] = "Contact preference updated."
    } else if (isSelf && call.request.queryParameters["updated"] == SocialLinks.FORM_NAME) {
      jsonMap["toast_message"] = "Social links updated."
    }
  }

  /**
   * Build the facepile rows for a Following/Followers section: up to [FACEPILE_LIMIT] entries, each
   * with a display name and a normalized avatar URL (falling back to the placeholder SVG).
   * [members] is the batch lookup result for the union of usernames actually being rendered, so a
   * lookup miss (e.g. a stale/deleted account) still degrades to a usable row instead of a crash.
   */
  private fun buildFacepile(
      usernames: List<String>,
      members: Map<String, DMSMember>,
  ): List<Map<String, String>> =
      usernames.take(FACEPILE_LIMIT).map { username ->
        val member = members[username]
        mapOf(
            "username" to username,
            "display_name" to (member?.displayName?.takeIf { it.isNotBlank() } ?: "@$username"),
            "avatar_url" to
                normalizeAvatarUrl(member?.displayAvatarUrl(), FACEPILE_AVATAR_SIZE).ifEmpty {
                  PROFILE_PIC_FALLBACK
                },
        )
      }

  /**
   * Calculate the duration of time since the member joined the space.
   *
   * @param memberSince The date the member joined the space. e.g. - 2022-09-10T16:36:20Z
   * @param now The current instant (shared to avoid multiple Instant.now() calls).
   * @return A human-readable string like - "2 years, 3 months".
   */
  @Suppress("MagicNumber")
  private fun getMemberDurationString(
      memberSince: Instant?,
      now: Instant = Instant.now(),
  ): String {
    if (memberSince == null || now.isBefore(memberSince)) {
      return "Unknown"
    }
    val duration = now.epochSecond - memberSince.epochSecond
    val years = duration / (60 * 60 * 24 * 365)
    val months = (duration % (60 * 60 * 24 * 365)) / (60 * 60 * 24 * 30)
    val days = (duration % (60 * 60 * 24 * 30)) / (60 * 60 * 24)
    val hours = (duration % (60 * 60 * 24)) / (60 * 60)
    // Only show years if greater than 0, if less than 1 month show only days, if less than a day
    // show hours
    return when {
      years > 1 -> "$years years, $months months"
      years > 0 -> "$years year, $months months"
      months > 1 -> "$months months"
      months > 0 -> "$months month"
      days > 1 -> "$days days"
      days > 0 -> "$days day"
      hours > 1 -> "$hours hours"
      else -> "$hours hour"
    }
  }

  /**
   * Format the [DMSMember.memberSince] date into a human-readable string.
   *
   * @param memberSince The date the member joined the space. e.g. - 2022-09-10T16:36:20Z
   * @return A human-readable string like - "Sept 2022".
   */
  private fun getMemberSinceString(memberSince: Instant?): String {
    if (memberSince == null) {
      return "Unknown"
    }
    // Convert Instant to LocalDate CDT
    val localDate = memberSince.atZone(CHICAGO_ZONE).toLocalDate()
    // Get short version of month with first letter capitalized
    val month = localDate.month.toString().lowercase().replaceFirstChar { it.uppercase() }
    val year = localDate.year.toString()
    return "$month $year"
  }

  /**
   * Format an event date into a human-readable string.
   *
   * @param eventStart The event start date string (e.g., "2024-03-15 14:30:00" or "2024-03-15
   *   14:30:00.0")
   * @return A human-readable string like "Fri, Mar 15 at 2:30 PM"
   */
  private fun formatEventDate(eventStart: String): String {
    return try {
      // Remove milliseconds if present (e.g., "2024-03-15 14:30:00.0" -> "2024-03-15 14:30:00")
      val cleanedEventStart = eventStart.substringBefore(".")
      val dateTime = LocalDateTime.parse(cleanedEventStart, EVENT_INPUT_FORMATTER)
      val zonedDateTime = dateTime.atZone(CHICAGO_ZONE)
      zonedDateTime.format(EVENT_OUTPUT_FORMATTER)
    } catch (e: Exception) {
      log.error("Failed to parse event date: $eventStart", e)
      eventStart
    }
  }

  /**
   * Check if an event is upcoming (in the future).
   *
   * @param eventStart The event start date string
   * @return true if the event is in the future, false otherwise
   */
  private fun isUpcomingEvent(eventStart: String): Boolean {
    return try {
      val cleanedEventStart = eventStart.substringBefore(".")
      val dateTime = LocalDateTime.parse(cleanedEventStart, EVENT_INPUT_FORMATTER)
      val zonedDateTime = dateTime.atZone(CHICAGO_ZONE)
      zonedDateTime.toInstant().isAfter(Instant.now())
    } catch (e: Exception) {
      false
    }
  }
}
