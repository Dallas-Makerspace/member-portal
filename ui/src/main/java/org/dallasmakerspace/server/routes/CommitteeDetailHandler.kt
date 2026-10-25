package org.dallasmakerspace.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.thymeleaf.*
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.committees.CommitteePageSectionProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.models.getSlugFromName
import org.dallasmakerspace.server.plugins.AuthException
import org.dallasmakerspace.server.reports.ArchivedGroupsReport
import org.dallasmakerspace.server.subscriptions.subscriptionButtonModel

class CommitteeDetailHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
    private val sectionProviders: Set<@JvmSuppressWildcards CommitteePageSectionProvider>,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  override suspend fun handleAuthenticated(call: ApplicationCall) = coroutineScope {
    // Get committee slug from path
    val committeeSlug =
        call.parameters["committee_slug"]
            ?: throw AuthException("No committee slug found in url path")

    // Find committee by slug
    val committee = Committees.findBySlug(committeeSlug)
    if (committee == null) {
      log.warn("Committee not found for slug: $committeeSlug")
      call.respond(HttpStatusCode.NotFound, "Committee not found")
      return@coroutineScope
    }

    // Ask every registered section provider whether it has content for this committee - this is
    // the only place that touches sectionProviders, so a new committee-specific feature (e.g. the
    // Bambu status summary) never requires editing this handler or committee-detail.html again.
    // Launched first (alongside the group fetches below), not after them - a contributed section
    // has no dependency on this handler's own group/event data, and launching it late left its own
    // network round-trip (e.g. Bambuddy's ~600-700ms summary call) purely additive to every
    // /committees/{slug} page load instead of overlapping with everything else.
    val currentUsername = userInfo["preferred_username"] as? String
    val sectionsDeferred = async {
      sectionProviders
          .filter { it.appliesTo(committee) }
          .mapNotNull { provider ->
            try {
              provider.renderSection(committee, session.sessionId, currentUsername)
            } catch (e: Exception) {
              log.warn("Committee page section provider failed: {}", e.message)
              null
            }
          }
    }

    // Only committees with group prefixes can be followed (a class is tied to a committee through
    // its prerequisite group); null when the follow feature is off for the viewer.
    val subscriptionDeferred = async {
      if (currentUsername == null || committee.groupPrefixes.isEmpty()) {
        null
      } else {
        memberService.getSubscriptionStatus(
            TopicKeys.COMMITTEE_UPDATES,
            mapOf("committee" to committee.id.toString()),
            currentUsername,
            session.sessionId)
      }
    }

    // Follower facepile, shown whenever the follow button is (same committee and pilot gating).
    val followersDeferred = async {
      if (subscriptionDeferred.await() == null) {
        null
      } else {
        val page =
            memberService.getCommitteeFollowers(
                committee.id, FACEPILE_LIMIT, 0, session.sessionId)
        val members = memberService.getMembersByUsernames(page.usernames, session.sessionId)
        mapOf(
            "count" to page.total,
            "overflow" to (page.total - page.usernames.size).coerceAtLeast(0),
            "facepile" to FacepileEntries.build(page.usernames, members),
            "url" to "/committees/${committee.slug}/followers",
        )
      }
    }

    // Fetch chair groups, teacher groups, and all groups in parallel
    val chairGroupsDeferred = async {
      val uniqueChairGroupSlugs =
          listOfNotNull(committee.chairGroupName).map { getSlugFromName(it) }
      if (uniqueChairGroupSlugs.isEmpty()) {
        emptyList()
      } else {
        try {
          memberService.getMultipleGroups(uniqueChairGroupSlugs, session.sessionId)
        } catch (e: Exception) {
          log.warn("Failed to fetch chair groups: {}", e.message)
          emptyList()
        }
      }
    }

    val teacherGroupsDeferred = async {
      val teacherGroupSlugs = committee.teacherGroups.map { getSlugFromName(it) }
      if (teacherGroupSlugs.isEmpty()) {
        emptyList()
      } else {
        try {
          memberService.getMultipleGroups(teacherGroupSlugs, session.sessionId)
        } catch (e: Exception) {
          log.warn("Failed to fetch teacher groups: {}", e.message)
          emptyList()
        }
      }
    }

    val allGroupsDeferred = async {
      try {
        memberService.getAllGroups(session.sessionId)
      } catch (e: Exception) {
        log.warn("Failed to fetch all groups: {}", e.message)
        emptyList()
      }
    }

    val archivedCountDeferred = async {
      ArchivedGroupsReport.committeeCount(
          committee,
          fetch = { memberService.getArchivedGroups(it, session.sessionId) },
          onFailure = { log.warn("Failed to fetch archived groups: {}", it) })
    }

    val chairGroups = chairGroupsDeferred.await()
    val teacherGroups = teacherGroupsDeferred.await()
    val allGroups = allGroupsDeferred.await()

    // Build committee data
    val chairGroupsMap = chairGroups.associateBy { getSlugFromName(it.name) }
    val chairpersons = getChairMembersFromCache(committee, chairGroupsMap)

    // Build teacher group data with members
    val teacherGroupsMap = teacherGroups.associateBy { getSlugFromName(it.name) }
    val teacherGroupsWithMembers =
        committee.teacherGroups.mapNotNull { groupName ->
          val slug = getSlugFromName(groupName)
          val group = teacherGroupsMap[slug]
          if (group != null) {
            val members = processMembersList(group.members)
            mapOf("name" to groupName, "slug" to slug, "members" to members)
          } else {
            null
          }
        }

    // Filter committee groups by committee prefixes
    val committeeGroups =
        if (committee.groupPrefixes.isNotEmpty()) {
          allGroups
              .filter { group ->
                committee.groupPrefixes.any { prefix ->
                  group.name.startsWith(prefix, ignoreCase = true)
                }
              }
              .sortedBy { it.name }
              .map { group -> mapOf("name" to group.name, "slug" to getSlugFromName(group.name)) }
        } else {
          emptyList()
        }

    // Collect all related group names for event fetching
    val relatedGroupNames =
        if (committee.groupPrefixes.isNotEmpty()) {
          allGroups
              .filter { group ->
                committee.groupPrefixes.any { prefix ->
                  group.name.startsWith(prefix, ignoreCase = true)
                }
              }
              .map { it.name }
        } else {
          emptyList()
        }

    // Fetch upcoming prerequisite events
    val upcomingEventsRaw =
        async {
              if (relatedGroupNames.isNotEmpty()) {
                try {
                  memberService.getUpcomingPrerequisiteEvents(relatedGroupNames, session.sessionId)
                } catch (e: Exception) {
                  log.warn("Failed to fetch upcoming events: {}", e.message)
                  emptyList()
                }
              } else {
                emptyList()
              }
            }
            .await()

    // Collect unique organizer usernames
    val organizerUsernames = upcomingEventsRaw.mapNotNull { it.organizerUsername }.distinct()

    // Fetch organizer member details
    val organizerMembers =
        async {
              if (organizerUsernames.isNotEmpty()) {
                try {
                  organizerUsernames
                      .mapNotNull { username ->
                        try {
                          username to memberService.getMember(username, session.sessionId)
                        } catch (e: Exception) {
                          log.warn("Failed to fetch organizer member: $username", e)
                          null
                        }
                      }
                      .toMap()
                } catch (e: Exception) {
                  log.warn("Failed to fetch organizer members: {}", e.message)
                  emptyMap()
                }
              } else {
                emptyMap()
              }
            }
            .await()

    // Enrich events with organizer details
    val upcomingEvents =
        upcomingEventsRaw.map { event ->
          val organizer = event.organizerUsername?.let { organizerMembers[it] }
          val avatarUrl = normalizeAvatarUrl(organizer?.displayAvatarUrl(), size = 144)

          mapOf(
              "id" to event.id,
              "name" to event.name,
              "eventStart" to event.eventStart,
              "status" to event.status,
              "organizer" to
                  mapOf(
                      "username" to (event.organizerUsername ?: ""),
                      "displayName" to (organizer?.displayName ?: event.organizerUsername ?: ""),
                      "avatarUrl" to avatarUrl,
                  ))
        }

    val jsonMap: MutableMap<String, Any?> =
        mutableMapOf(
            "committee" to
                mapOf(
                    "id" to committee.id,
                    "name" to committee.name,
                    "slug" to committee.slug,
                    "description" to committee.description,
                    "color" to committee.color,
                    "homepageUrl" to committee.homepageUrl,
                ),
            "chairpersons" to chairpersons,
            "teacherGroups" to teacherGroupsWithMembers,
            "committeeGroups" to committeeGroups,
            "upcomingEvents" to upcomingEvents,
            "sections" to sectionsDeferred.await(),
        )

    subscriptionDeferred.await()?.let {
      jsonMap["committee_subscription"] = subscriptionButtonModel(it)
    }
    followersDeferred.await()?.let { jsonMap["committee_followers"] = it }

    val archivedCount = archivedCountDeferred.await()
    if (archivedCount > 0) {
      jsonMap["archivedGroupsCount"] = archivedCount
      jsonMap["archivedGroupsUrl"] = ArchivedGroupsReport.committeeUrl(committee)
    }

    call.respond(ThymeleafContent("committee-detail", jsonMap))
  }

  private companion object {
    const val FACEPILE_LIMIT = 7
  }

  private fun processMembersList(
      members: List<org.dallasmakerspace.models.DMSMember>
  ): List<Map<String, Any?>> {
    return members.map { member ->
      val avatarUrl = normalizeAvatarUrl(member.displayAvatarUrl(), size = 144)

      mapOf(
          "username" to member.username,
          "displayName" to member.displayName,
          "avatarUrl" to avatarUrl,
      )
    }
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
