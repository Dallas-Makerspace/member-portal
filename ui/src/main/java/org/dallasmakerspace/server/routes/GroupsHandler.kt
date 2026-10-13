package org.dallasmakerspace.server.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.thymeleaf.*
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.models.ActorDisplayResolver
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.models.getNameFromSlug
import org.dallasmakerspace.server.models.getSlugFromName
import org.dallasmakerspace.server.models.isGroupDN
import org.dallasmakerspace.server.models.parseCNFromDN
import org.dallasmakerspace.server.moodle.MOODLE_FEATURE_FLAG
import org.dallasmakerspace.server.moodle.MoodleCourses
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.workflows.GroupWorkflowLaunch

class GroupsHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) = coroutineScope {
    // Get group slug requested from path /groups/{group_slug}
    val requestedGroupSlug =
        call.parameters["group_slug"] ?: throw AuthException("No group slug found in url path")
    // External links (e.g. Moodle's AD Group field) use %20; canonicalize to the "+" slug.
    if (requestedGroupSlug.contains(' ')) {
      call.respondRedirect("/groups/${getSlugFromName(requestedGroupSlug)}")
      return@coroutineScope
    }
    val requestedGroupName = getNameFromSlug(requestedGroupSlug)
    val actorUsername = userInfo["preferred_username"] as? String

    // Fetch group data and prerequisite classes check in parallel
    val groupDeferred = async { memberService.getGroup(requestedGroupSlug, session.sessionId) }
    val hasPrerequisiteClassesDeferred = async {
      memberService.hasPrerequisiteClasses(requestedGroupSlug, session.sessionId)
    }
    val featureEnabledDeferred = async {
      val isInfraUser = authz.can(Permission.MANAGE_CONFIG.name)
      memberServiceClient.getFeatureFlag(
          "groups.member-management-enabled", session.sessionId, isInfraUser)
    }
    val groupSettingsFeatureEnabledDeferred = async {
      // groups.settings-enabled is a FeatureFlagType (can be piloted per admin/AD group), so this
      // needs the per-username check rather than the plain current-value lookup used above.
      actorUsername?.let {
        memberServiceClient.isFeatureEnabled("groups.settings-enabled", it, session.sessionId)
      } ?: false
    }
    // Lets a group's own AD-designated admin (e.g. a "managedBy" group like "CA Teachers") manage
    // membership even without the site-wide MANAGE_GROUPS permission — see GroupAuthz.kt.
    val isGroupAdminDeferred = async {
      actorUsername?.let {
        memberServiceClient.isGroupAdmin(it, requestedGroupSlug, session.sessionId)
      } ?: false
    }
    val selfMembershipDeferred = async {
      actorUsername?.let {
        memberServiceClient.getSelfMembershipStatus(requestedGroupSlug, it, session.sessionId)
      }
    }

    val onlineCoursesDeferred = async {
      val enabled =
          actorUsername?.let {
            memberServiceClient.isFeatureEnabled(MOODLE_FEATURE_FLAG, it, session.sessionId)
          } ?: false
      if (!enabled) emptyList()
      else
          MoodleCourses.forGroup(
              MoodleCourses.parse(memberServiceClient.getMoodleCatalog(session.sessionId)),
              requestedGroupName)
    }

    val requestedGroup = groupDeferred.await()
    val hasPrerequisiteClasses = hasPrerequisiteClassesDeferred.await()
    val featureEnabled = featureEnabledDeferred.await()
    val groupSettingsFeatureEnabled = groupSettingsFeatureEnabledDeferred.await()
    val isGroupAdmin = isGroupAdminDeferred.await()
    val selfMembership = selfMembershipDeferred.await()

    // Check feature flag and user role
    val canManageMembers =
        featureEnabled && canManageGroup(authz.can(Permission.MANAGE_GROUPS.name), isGroupAdmin)
    // Group settings are a separate admin action from member management, gated by its own
    // "groups.settings-enabled" rollout flag rather than the member-management one.
    val canManageGroupSettings = groupSettingsFeatureEnabled && authz.can(Permission.MANAGE_GROUPS.name)

    log.debug("Group: {}", requestedGroup)
    val jsonMap: MutableMap<String, Any> =
        mutableMapOf(
            "name" to requestedGroupName,
            "slug" to requestedGroupSlug,
            "hasPrerequisiteClasses" to hasPrerequisiteClasses,
            "canManageGroupSettings" to canManageGroupSettings,
            "canManageMembers" to canManageMembers,
            "actorUsername" to (actorUsername ?: ""),
            "selfMembershipAction" to
                when {
                  selfMembership == null || !selfMembership.allowed -> ""
                  selfMembership.isMember -> "leave"
                  else -> "join"
                },
            "memberUsernamesJson" to "[]",
            "workflowSubject" to GroupWorkflowLaunch.subject(requestedGroupName),
            "exportMembersInput" to GroupWorkflowLaunch.exportMembersInput(requestedGroupName),
        )
    if (requestedGroup.members.isNotEmpty()) {
      // Create a map of username to most recent timestamp from history
      val memberTimestamps =
          requestedGroup.history
              .groupBy { it.memberUsername }
              .mapValues { (_, events) -> events.maxOf { it.eventTimestamp } }

      fun processMember(member: org.dallasmakerspace.models.DMSMember): MutableMap<String, Any?> {
        val avatarUrl = normalizeAvatarUrl(member.displayAvatarUrl(), size = 144)
        return mutableMapOf(
            "username" to member.username,
            "displayName" to member.displayName,
            "avatarUrl" to avatarUrl,
            "discourseUsername" to member.discourseUsername,
            "discourseAvatarUrl" to member.discourseAvatarUrl,
            "discordUserId" to member.discordUserId,
        )
      }

      val sortKey: (org.dallasmakerspace.models.DMSMember) -> kotlinx.datetime.LocalDateTime =
          { m ->
            memberTimestamps[m.username] ?: kotlinx.datetime.LocalDateTime(1970, 1, 1, 0, 0, 0)
          }

      val activeMembers =
          requestedGroup.members
              .filter { it.enabled }
              .sortedByDescending(sortKey)
              .map { processMember(it) }

      val inactiveMembers =
          requestedGroup.members
              .filter { !it.enabled }
              .sortedByDescending(sortKey)
              .map { processMember(it) }

      // JSON for the member picker excluded list — include all members regardless of enabled status
      val memberUsernamesJson =
          requestedGroup.members.joinToString(",", "[", "]") { "\"${it.username}\"" }

      if (activeMembers.isNotEmpty()) jsonMap["members"] = activeMembers
      if (inactiveMembers.isNotEmpty()) jsonMap["inactiveMembers"] = inactiveMembers
      jsonMap["memberlist_incomplete"] = requestedGroup.membersListIncomplete
      jsonMap["memberUsernamesJson"] = memberUsernamesJson
    }

    // Process administrators
    if (requestedGroup.administrators.isNotEmpty()) {
      val processedAdmins =
          requestedGroup.administrators
              .filter { it.contains("Domain Admins").not() }
              .map { dn ->
                val name = parseCNFromDN(dn)
                val isGroup = isGroupDN(dn)
                val slug = getSlugFromName(name)
                mutableMapOf<String, Any?>(
                    "name" to name,
                    "isGroup" to isGroup,
                    "slug" to slug,
                    "link" to if (isGroup) "/groups/$slug" else "/profile/@$name",
                )
              }
      if (processedAdmins.isNotEmpty()) {
        jsonMap["administrators"] = processedAdmins
      }
    }

    // Process nested groups
    log.debug("Nested groups from service: {}", requestedGroup.nestedGroups)
    if (requestedGroup.nestedGroups.isNotEmpty()) {
      val processedNestedGroups =
          requestedGroup.nestedGroups
              .filter { it.contains("Domain Admins").not() }
              .map { dn ->
                val name = parseCNFromDN(dn)
                val slug = getSlugFromName(name)
                mutableMapOf<String, Any?>(
                    "name" to name,
                    "slug" to slug,
                )
              }
      log.debug("Processed nested groups: {}", processedNestedGroups)
      if (processedNestedGroups.isNotEmpty()) {
        jsonMap["nestedGroups"] = processedNestedGroups
      }
    }

    // Process history - limit to last 5 events
    if (requestedGroup.history.isNotEmpty()) {
      val processedHistory =
          requestedGroup.history.take(5).map { event ->
            // Handle special service accounts for actor
            val actorInfo =
                ActorDisplayResolver.resolve(
                    actorUsername = event.actorUsername,
                    groupName = requestedGroupName,
                    fallbackDisplayName = event.actorDisplayName,
                )

            mutableMapOf<String, Any?>(
                "actorDisplayName" to actorInfo.displayName,
                "actorLink" to actorInfo.link,
                "actorIsExternal" to actorInfo.isExternal,
                "memberUsername" to event.memberUsername,
                "memberDisplayName" to (event.memberDisplayName ?: event.memberUsername),
                "timestamp" to event.eventTimestamp.toString(),
                "actionType" to event.actionType.name,
            )
          }
      jsonMap["history"] = processedHistory
    }

    requestedGroup.description?.let { jsonMap["description"] = it }
    onlineCoursesDeferred.await().takeIf { it.isNotEmpty() }?.let { jsonMap["onlineCourses"] = it }
    call.respond(ThymeleafContent("group", jsonMap))
  }
}
