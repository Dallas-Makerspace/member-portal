package org.dallasmakerspace.server.memberservice

import javax.inject.Inject
import org.dallasmakerspace.models.ArchivedGroup
import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.server.common.HttpException
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.models.EventSummary
import org.dallasmakerspace.server.models.SearchPreloadResponse

class MemberService
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val memberServiceClient: MemberServiceClient,
) {
  private val log = loggerFactory.create(javaClass)

  suspend fun linkDiscourseAccount(
      username: String,
      discourseUsername: String,
      discourseAvatarUrl: String?,
      sessionId: String?,
  ) {
    // Get member object
    val member = memberServiceClient.getMember(username, sessionId)
    // Update discourseUserName
    member.discourseUsername = discourseUsername
    member.discourseAvatarUrl = discourseAvatarUrl
    try { // Patch object
      memberServiceClient.patchMember(username, member, sessionId)
    } catch (e: HttpException) {
      log.error("Failed to patch member: $username; member object: $member", e)
      throw MemberServiceException(e.message ?: "Failed to patch member: $username", e)
    }
  }

  suspend fun getMember(username: String, sessionId: String?): DMSMember {
    return memberServiceClient.getMember(username, sessionId)
  }

  suspend fun getMemberSettings(username: String, sessionId: String?): Map<String, String> {
    return memberServiceClient.getMemberSettings(username, sessionId)
  }

  suspend fun getMemberSettingsHistory(
      username: String,
      sessionId: String?
  ): List<Map<String, String?>> {
    return memberServiceClient.getMemberSettingsHistory(username, sessionId)
  }

  suspend fun updateMemberSettings(
      username: String,
      actorUsername: String?,
      communicationPreference: String?,
      sessionId: String?,
  ) {
    updateMemberSettings(
        username,
        actorUsername,
        mapOf("communication_preference" to communicationPreference.orEmpty()),
        sessionId)
  }

  /** Values are stored as given; "" clears a setting. */
  suspend fun updateMemberSettings(
      username: String,
      actorUsername: String?,
      updates: Map<String, String>,
      sessionId: String?,
  ) {
    memberServiceClient.updateMemberSettings(username, actorUsername, updates, sessionId)
  }

  suspend fun unlinkDiscourseAccount(username: String, sessionId: String?) {
    // Get member object
    val member = memberServiceClient.getMember(username, sessionId)
    // Update discourseUserName
    member.discourseUsername = null
    member.discourseAvatarUrl = null
    try { // Patch object
      memberServiceClient.patchMember(username, member, sessionId)
    } catch (e: HttpException) {
      log.error("Failed to patch member: $username; member object: $member", e)
      throw MemberServiceException(e.message ?: "Failed to patch member: $username", e)
    }
  }

  suspend fun linkDiscordAccount(
      username: String,
      discordUserId: String,
      discordUsername: String,
      discordAvatarUrl: String?,
      sessionId: String?,
  ) {
    // Get member object
    val member = memberServiceClient.getMember(username, sessionId)
    // Update Discord fields
    member.discordUserId = discordUserId
    member.discordUsername = discordUsername
    member.discordAvatarUrl = discordAvatarUrl
    try { // Patch object
      memberServiceClient.patchMember(username, member, sessionId)
    } catch (e: HttpException) {
      log.error("Failed to patch member: $username; member object: $member", e)
      throw MemberServiceException(e.message ?: "Failed to patch member: $username", e)
    }
  }

  suspend fun unlinkDiscordAccount(username: String, sessionId: String?) {
    // Get member object
    val member = memberServiceClient.getMember(username, sessionId)
    // Clear Discord fields
    member.discordUserId = null
    member.discordUsername = null
    member.discordAvatarUrl = null
    try { // Patch object
      memberServiceClient.patchMember(username, member, sessionId)
    } catch (e: HttpException) {
      log.error("Failed to patch member: $username; member object: $member", e)
      throw MemberServiceException(e.message ?: "Failed to patch member: $username", e)
    }
  }

  suspend fun getGroup(groupName: String, sessionId: String?): DMSGroup {
    return memberServiceClient.getGroup(groupName, sessionId)
  }

  suspend fun getGroupSettings(groupSlug: String, sessionId: String?): Map<String, String> {
    return memberServiceClient.getGroupSettings(groupSlug, sessionId)
  }

  suspend fun getGroupSettingsHistory(
      groupSlug: String,
      sessionId: String?
  ): List<Map<String, String?>> {
    return memberServiceClient.getGroupSettingsHistory(groupSlug, sessionId)
  }

  suspend fun updateGroupSettings(
      groupSlug: String,
      actorUsername: String?,
      updates: Map<String, String?>,
      sessionId: String?,
  ) {
    memberServiceClient.updateGroupSettings(groupSlug, actorUsername, updates, sessionId)
  }

  suspend fun getAllGroups(sessionId: String?): List<DMSGroup> {
    return memberServiceClient.getAllGroups(sessionId)
  }

  suspend fun getArchivedGroups(prefixes: List<String>, sessionId: String?): List<ArchivedGroup> {
    return memberServiceClient.getArchivedGroups(prefixes, sessionId)
  }

  suspend fun getMultipleGroups(groupSlugs: List<String>, sessionId: String?): List<DMSGroup> {
    return memberServiceClient.getMultipleGroups(groupSlugs, sessionId)
  }

  suspend fun getSearchPreload(sessionId: String?): SearchPreloadResponse {
    return memberServiceClient.getSearchPreloads(sessionId)
  }

  suspend fun registerVoting(sessionId: String?, username: String) {
    memberServiceClient.registerVoting(sessionId, username)
  }

  suspend fun unregisterVoting(sessionId: String?, username: String) {
    memberServiceClient.unregisterVoting(sessionId, username)
  }

  suspend fun addToGroup(
      sessionId: String?,
      username: String,
      groupSlug: String,
      actorUsername: String? = null,
  ) {
    memberServiceClient.addToGroup(sessionId, username, groupSlug, actorUsername)
  }

  suspend fun removeFromGroup(
      sessionId: String?,
      username: String,
      groupSlug: String,
      actorUsername: String? = null,
  ) {
    memberServiceClient.removeFromGroup(sessionId, username, groupSlug, actorUsername)
  }

  suspend fun getFeatureFlag(key: String, sessionId: String?): Boolean {
    return memberServiceClient.getFeatureFlag(key, sessionId)
  }

  suspend fun callBackendApi(path: String, sessionId: String?, username: String? = null) =
      memberServiceClient.callBackendApi(path, sessionId, username)

  suspend fun callBackendApiPost(path: String, sessionId: String?, username: String?, body: Any) =
      memberServiceClient.callBackendApiPost(path, sessionId, username, body)

  suspend fun callBackendApiPatch(path: String, sessionId: String?, username: String?, body: Any) =
      memberServiceClient.callBackendApiPatch(path, sessionId, username, body)

  suspend fun callBackendApiDelete(path: String, sessionId: String?, username: String?) =
      memberServiceClient.callBackendApiDelete(path, sessionId, username)

  suspend fun getEventsOrganizedByMember(
      username: String,
      limit: Int,
      sessionId: String?,
  ): List<EventSummary> {
    return memberServiceClient.getEventsOrganizedByMember(username, limit, sessionId)
  }

  suspend fun hasPrerequisiteClasses(groupSlug: String, sessionId: String?): Boolean {
    return memberServiceClient.hasPrerequisiteClasses(groupSlug, sessionId)
  }

  suspend fun isGroupAdmin(username: String, groupSlug: String, sessionId: String?): Boolean {
    return memberServiceClient.isGroupAdmin(username, groupSlug, sessionId)
  }

  suspend fun getUpcomingPrerequisiteEvents(
      groupNames: List<String>,
      sessionId: String?,
  ): List<EventSummary> {
    return memberServiceClient.getUpcomingPrerequisiteEvents(groupNames, sessionId)
  }

  suspend fun askAi(
      sessionId: String?,
      question: String,
      username: String?,
      forceRefresh: Boolean = false,
  ): Map<String, Any?> {
    return memberServiceClient.askAi(sessionId, question, username, forceRefresh)
  }

  suspend fun getAskAiTopQuestions(sessionId: String?, limit: Int = 10): List<Map<String, Any?>> {
    return memberServiceClient.getAskAiTopQuestions(sessionId, limit)
  }

  suspend fun getAskAiBySlug(sessionId: String?, slug: String): Map<String, Any?>? {
    return memberServiceClient.getAskAiBySlug(sessionId, slug)
  }

  suspend fun submitAskAiFeedback(sessionId: String?, cacheId: Int, isHelpful: Boolean): Boolean {
    return memberServiceClient.submitAskAiFeedback(sessionId, cacheId, isHelpful)
  }

  suspend fun streamAskAi(
      sessionId: String?,
      username: String?,
      question: String,
      forceRefresh: Boolean,
      onChunk: suspend (String) -> Unit,
  ) = memberServiceClient.streamAskAi(sessionId, username, question, forceRefresh, onChunk)

  suspend fun getAttendedOrganizers(username: String, sessionId: String?): List<String> {
    return memberServiceClient.getAttendedOrganizers(username, sessionId)
  }

  suspend fun getUpcomingEventsByOrganizers(
      organizers: List<String>,
      sessionId: String?,
  ): List<EventSummary> {
    return memberServiceClient.getUpcomingEventsByOrganizers(organizers, sessionId)
  }

  suspend fun getAttendedEventNames(username: String, sessionId: String?): List<String> {
    return memberServiceClient.getAttendedEventNames(username, sessionId)
  }

  suspend fun getUpcomingEventsByKeywords(
      keywords: List<String>,
      sessionId: String?,
  ): List<EventSummary> {
    return memberServiceClient.getUpcomingEventsByKeywords(keywords, sessionId)
  }

  suspend fun getBadgeFromMakerManager(username: String, sessionId: String?): String? {
    return memberServiceClient.getBadgeFromMakerManager(username, sessionId)
  }

  suspend fun getBadgeFromActiveDirectory(username: String, sessionId: String?): String? {
    return memberServiceClient.getBadgeFromActiveDirectory(username, sessionId)
  }

  suspend fun getTotalActiveDays(username: String, sessionId: String?): Int? {
    return memberServiceClient.getTotalActiveDays(username, sessionId)
  }

  suspend fun getDebugInfo(
      username: String,
      sessionId: String?,
  ): org.dallasmakerspace.models.MemberDebugInfo? {
    return memberServiceClient.getDebugInfo(username, sessionId)
  }

  suspend fun getRecentBadgeSwipes(
      username: String,
      sessionId: String?,
  ): List<org.dallasmakerspace.models.BadgeSwipeInfo> {
    return memberServiceClient.getRecentBadgeSwipes(username, sessionId)
  }

  suspend fun getKioskWaiverPhotos(
      username: String,
      sessionId: String?,
  ): List<org.dallasmakerspace.models.MemberWaiverPhotos> {
    return memberServiceClient.getKioskWaiverPhotos(username, sessionId)
  }
}
