package org.dallasmakerspace.server.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.thymeleaf.*
import javax.inject.Inject
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.models.getSlugFromName

class CommitteesHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val committees = Committees.ALL.filter { it.isActive }

    // Get unique chair group slugs
    val uniqueChairGroupSlugs =
        committees.mapNotNull { it.chairGroupName }.distinct().map { getSlugFromName(it) }

    // Fetch all chair groups with members in a single AD call
    val chairGroups =
        try {
          memberService.getMultipleGroups(uniqueChairGroupSlugs, session.sessionId)
        } catch (e: Exception) {
          log.warn("Failed to fetch chair groups: {}", e.message)
          emptyList()
        }

    // Create a map of group slug -> group for quick lookup
    val chairGroupsMap = chairGroups.associateBy { getSlugFromName(it.name) }

    // Build committee data using the cached groups
    val committeeData = committees.map { committee -> buildCommitteeMap(committee, chairGroupsMap) }

    val jsonMap: MutableMap<String, Any> = mutableMapOf("committees" to committeeData)

    call.respond(ThymeleafContent("committees", jsonMap))
  }

  private fun buildCommitteeMap(
      committee: Committee,
      chairGroupsMap: Map<String, DMSGroup>
  ): Map<String, Any?> {
    val chairpersons = getChairMembersFromCache(committee, chairGroupsMap)

    val teacherGroupLinks =
        committee.teacherGroups.map { groupName ->
          mapOf("name" to groupName, "slug" to getSlugFromName(groupName))
        }

    return mutableMapOf(
        "id" to committee.id,
        "name" to committee.name,
        "description" to committee.description,
        "color" to committee.color,
        "chairpersons" to chairpersons,
        "teacherGroups" to teacherGroupLinks,
    )
  }

  private fun getChairMembersFromCache(
      committee: Committee,
      chairGroupsMap: Map<String, DMSGroup>
  ): List<Map<String, Any?>> {
    val chairGroupName = committee.chairGroupName ?: return emptyList()
    val chairGroupSlug = getSlugFromName(chairGroupName)
    val group = chairGroupsMap[chairGroupSlug] ?: return emptyList()

    return group.members.map { member ->
      val avatarUrl = normalizeAvatarUrl(member.displayAvatarUrl(), size = 144)

      mapOf(
          "username" to member.username,
          "displayName" to member.displayName,
          "avatarUrl" to avatarUrl,
      )
    }
  }
}
