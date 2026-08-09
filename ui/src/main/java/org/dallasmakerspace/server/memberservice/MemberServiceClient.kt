package org.dallasmakerspace.server.memberservice

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.utils.io.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.models.FeaturedProject
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DMSHttpClient
import org.dallasmakerspace.server.common.HttpException
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.models.EventSummary
import org.dallasmakerspace.server.models.SearchPreloadResponse
import org.dallasmakerspace.server.models.dmsGroupFromMap
import org.dallasmakerspace.server.models.dmsMemberFromMap
import org.dallasmakerspace.server.models.dmsMemberToMap

sealed class ShortLinkResult {
  data class Redirect(val location: String, val description: String?) : ShortLinkResult()

  data object NotFound : ShortLinkResult()

  data class Error(val message: String) : ShortLinkResult()
}

/** Preference update for one topic/channel combination. */
data class NotificationPreferenceUpdate(
    val topic: String,
    val channel: String,
    val enabled: Boolean,
    val aggregation: String?,
)

@Suppress("TooGenericExceptionCaught")
@Singleton
class MemberServiceClient
@Inject
constructor(
    loggerFactory: LoggerFactory,
    appConfig: AppConfig,
    private val dmsHttpClient: DMSHttpClient,
) {
  private val log = loggerFactory.create(javaClass)
  private val baseUrl = appConfig.requireStringProperty("app.member-service.url")
  private val authHeaders =
      mapOf(
          "X-Api-Key" to appConfig.requireStringProperty("app.member-service.api-key"),
          "X-Api-Client" to appConfig.requireStringProperty("app.member-service.api-client"),
      )

  suspend fun getMember(username: String, sessionId: String?): DMSMember {
    val userMap =
        try {
          val apiHeaders = getApiHeaders(sessionId)
          dmsHttpClient.get("$baseUrl/members/$username", apiHeaders)
        } catch (ignored: Exception) {
          log.error("Failed to get member: $username", ignored)
          throw MemberServiceException("Failed to get member: $username", ignored)
        }
    val data =
        userMap["data"] as Map<*, *>?
            ?: throw MemberServiceException("data attribute missing required")
    return dmsMemberFromMap(data as Map<String, Any?>)
  }

  suspend fun patchMember(username: String, member: DMSMember, sessionId: String?) {
    val apiHeaders = getApiHeaders(sessionId)
    val userMap = dmsMemberToMap(member)
    dmsHttpClient.patch("$baseUrl/members/$username/update", apiHeaders, userMap)
  }

  suspend fun getMemberSettings(username: String, sessionId: String?): Map<String, String> {
    val apiHeaders = getApiHeaders(sessionId)
    val settingsMap = dmsHttpClient.get("$baseUrl/members/$username/settings", apiHeaders)
    val data = settingsMap["data"] as Map<*, *>? ?: return emptyMap()
    return data.mapKeys { it.key.toString() }.mapValues { it.value?.toString() ?: "" }
  }

  suspend fun getMemberSettingsHistory(
      username: String,
      sessionId: String?
  ): List<Map<String, String?>> {
    val apiHeaders = getApiHeaders(sessionId)
    val historyMap = dmsHttpClient.get("$baseUrl/members/$username/settings/history", apiHeaders)
    val data = historyMap["data"] as List<*>? ?: return emptyList()
    return data.mapNotNull { entry ->
      (entry as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value?.toString() }
    }
  }

  suspend fun updateMemberSettings(
      username: String,
      actorUsername: String?,
      communicationPreference: String?,
      sessionId: String?,
  ) {
    val baseHeaders = getApiHeaders(sessionId)
    val apiHeaders =
        StringValues.build {
          baseHeaders.forEach { key, values -> values.forEach { value -> append(key, value) } }
          actorUsername?.let { append("X-Actor-Username", it) }
        }
    val body = mapOf("communication_preference" to (communicationPreference ?: ""))
    dmsHttpClient.patch("$baseUrl/members/$username/settings", apiHeaders, body)
  }

  suspend fun getGroupSettings(groupSlug: String, sessionId: String?): Map<String, String> {
    val apiHeaders = getApiHeaders(sessionId)
    val settingsMap = dmsHttpClient.get("$baseUrl/groups/$groupSlug/settings", apiHeaders)
    val data = settingsMap["data"] as Map<*, *>? ?: return emptyMap()
    return data.mapKeys { it.key.toString() }.mapValues { it.value?.toString() ?: "" }
  }

  suspend fun getGroupSettingsHistory(
      groupSlug: String,
      sessionId: String?
  ): List<Map<String, String?>> {
    val apiHeaders = getApiHeaders(sessionId)
    val historyMap =
        dmsHttpClient.get("$baseUrl/groups/$groupSlug/settings/history", apiHeaders)
    val data = historyMap["data"] as List<*>? ?: return emptyList()
    return data.mapNotNull { entry ->
      (entry as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value?.toString() }
    }
  }

  suspend fun updateGroupSettings(
      groupSlug: String,
      actorUsername: String?,
      updates: Map<String, String?>,
      sessionId: String?,
  ) {
    val baseHeaders = getApiHeaders(sessionId)
    val apiHeaders =
        StringValues.build {
          baseHeaders.forEach { key, values -> values.forEach { value -> append(key, value) } }
          actorUsername?.let { append("X-Actor-Username", it) }
        }
    dmsHttpClient.patch("$baseUrl/groups/$groupSlug/settings", apiHeaders, updates)
  }

  private fun getApiHeaders(sessionId: String?, username: String? = null) =
      StringValues.build {
        sessionId?.apply { append("X-Request-Id", sessionId) }
        username?.apply { append("X-Username", username) }
        authHeaders.forEach(this::append)
      }

  suspend fun getGroup(groupName: String, sessionId: String?): DMSGroup {
    val groupMap =
        try {
          val apiHeaders = getApiHeaders(sessionId)
          dmsHttpClient.get("$baseUrl/groups/$groupName", apiHeaders)
        } catch (ignored: Exception) {
          log.error("Failed to get group: $groupName", ignored)
          throw MemberServiceException("Failed to get group: $groupName", ignored)
        }
    val data =
        if (groupMap["data"] is Map<*, *>) groupMap["data"] as Map<String, Any?>
        else throw MemberServiceException("data attribute missing required")
    return dmsGroupFromMap(data)
  }

  suspend fun getAllGroups(sessionId: String?): List<DMSGroup> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val groupMap = dmsHttpClient.get("$baseUrl/groups", apiHeaders)
      val data =
          groupMap["data"] as List<*>?
              ?: throw MemberServiceException("data attribute missing required")
      data.map { dmsGroupFromMap(it as Map<String, Any?>) }
    } catch (ex: Exception) {
      log.error("Failed to get all groups", ex)
      throw MemberServiceException("Failed to get all groups", ex)
    }
  }

  suspend fun getMultipleGroups(groupSlugs: List<String>, sessionId: String?): List<DMSGroup> {
    return try {
      withTimeout(10_000L) { // 10 second timeout
        val apiHeaders = getApiHeaders(sessionId)
        val groupMap = dmsHttpClient.post("$baseUrl/groups/batch", apiHeaders, groupSlugs)
        val data =
            groupMap["data"] as List<*>?
                ?: throw MemberServiceException("data attribute missing required")
        data.map { dmsGroupFromMap(it as Map<String, Any?>) }
      }
    } catch (ex: Exception) {
      log.error("Failed to get multiple groups", ex)
      throw MemberServiceException("Failed to get multiple groups", ex)
    }
  }

  suspend fun getSearchPreloads(sessionId: String?): SearchPreloadResponse = coroutineScope {
    val overallTimeoutMs = 15_000L

    withTimeout(overallTimeoutMs) {
      val apiHeaders = getApiHeaders(sessionId)

      // Fetch members then groups with per-request timeouts
      val members =
          try {
            withTimeout(12_000L) {
              val userMap = dmsHttpClient.get("$baseUrl/members", apiHeaders)
              val data =
                  userMap["data"] as List<*>?
                      ?: throw MemberServiceException("data attribute missing required")
              data.map { dmsMemberFromMap(it as Map<String, Any?>) }
            }
          } catch (ex: TimeoutCancellationException) {
            log.error("Timed out getting search preloads - members", ex)
            throw MemberServiceException("Timed out getting search preloads - members", ex)
          } catch (ex: CancellationException) {
            throw ex
          } catch (ex: Exception) {
            log.error("Failed to get search preloads - members", ex)
            throw MemberServiceException("Failed to get search preloads - members", ex)
          }

      val groups =
          try {
            withTimeout(12_000L) {
              val groupMap = dmsHttpClient.get("$baseUrl/groups", apiHeaders)
              val data =
                  groupMap["data"] as List<*>?
                      ?: throw MemberServiceException("data attribute missing required")
              data.map { dmsGroupFromMap(it as Map<String, Any?>) }
            }
          } catch (ex: TimeoutCancellationException) {
            log.error("Timed out getting search preloads - groups", ex)
            throw MemberServiceException("Timed out getting search preloads - groups", ex)
          } catch (ex: CancellationException) {
            throw ex
          } catch (ex: Exception) {
            log.error("Failed to get search preloads - groups", ex)
            throw MemberServiceException("Failed to get search preloads - groups", ex)
          }

      SearchPreloadResponse(members, groups)
    }
  }

  suspend fun addToGroup(
      sessionId: String?,
      username: String,
      groupName: String,
      actorUsername: String? = null,
  ) {
    try {
      val baseHeaders = getApiHeaders(sessionId)
      val apiHeaders =
          StringValues.build {
            baseHeaders.forEach { key, values -> values.forEach { value -> append(key, value) } }
            actorUsername?.let { append("X-Actor-Username", it) }
          }
      dmsHttpClient.patch("$baseUrl/groups/$groupName/add", apiHeaders, username)
    } catch (ex: Exception) {
      log.error("Failed to add $username to group: $groupName", ex)
      throw MemberServiceException("Failed to add $username to group: $groupName", ex)
    }
  }

  suspend fun removeFromGroup(
      sessionId: String?,
      username: String,
      groupName: String,
      actorUsername: String? = null,
  ) {
    try {
      val baseHeaders = getApiHeaders(sessionId)
      val apiHeaders =
          StringValues.build {
            baseHeaders.forEach { key, values -> values.forEach { value -> append(key, value) } }
            actorUsername?.let { append("X-Actor-Username", it) }
          }
      dmsHttpClient.delete("$baseUrl/groups/$groupName/add", apiHeaders, username)
    } catch (ex: Exception) {
      log.error("Failed to remove $username from group: $groupName", ex)
      throw MemberServiceException("Failed to remove $username from group: $groupName", ex)
    }
  }

  suspend fun registerVoting(sessionId: String?, username: String) {
    try {
      val apiHeaders = getApiHeaders(sessionId)
      dmsHttpClient.patch("$baseUrl/voter-registration/$username", apiHeaders, "")
    } catch (ex: Exception) {
      log.error("Failed to register $username for voting", ex)
      throw MemberServiceException("Failed to register $username for voting", ex)
    }
  }

  suspend fun unregisterVoting(sessionId: String?, username: String) {
    try {
      val apiHeaders = getApiHeaders(sessionId)
      dmsHttpClient.delete("$baseUrl/voter-registration/$username", apiHeaders)
    } catch (ex: Exception) {
      log.error("Failed to unregister $username from voting", ex)
      throw MemberServiceException("Failed to unregister $username from voting", ex)
    }
  }

  /**
   * Call the backend API with the given path, sessionId, and username. This is just a proxy for the
   * backend API, it will just pass the parameters through and return the response as a String.
   */
  suspend fun callBackendApi(
      path: String,
      sessionId: String?,
      username: String? = null,
  ): Map<String, Any> {
    try {
      val apiHeaders = getApiHeaders(sessionId, username)
      var result: Map<String, Any>
      withTimeout(120_000L) { result = dmsHttpClient.get("$baseUrl/$path", apiHeaders) }
      return result
    } catch (ex: Exception) {
      log.error("Failed to call backend API: $path ", ex)
      throw MemberServiceException("Failed to call backend API: $path", ex)
    }
  }

  suspend fun callBackendApiPost(
      path: String,
      sessionId: String?,
      username: String?,
      body: Any,
  ): Map<String, Any> {
    try {
      val apiHeaders = getApiHeaders(sessionId, username)
      var result: Map<String, Any>
      withTimeout(120_000L) { result = dmsHttpClient.post("$baseUrl/$path", apiHeaders, body) }
      return result
    } catch (ex: Exception) {
      log.error("Failed to call backend API POST: $path ", ex)
      throw MemberServiceException("Failed to call backend API POST: $path", ex)
    }
  }

  suspend fun callBackendApiPatch(
      path: String,
      sessionId: String?,
      username: String?,
      body: Any,
  ): Map<String, Any> {
    try {
      val apiHeaders = getApiHeaders(sessionId, username)
      var result: Map<String, Any>
      withTimeout(120_000L) { result = dmsHttpClient.patch("$baseUrl/$path", apiHeaders, body) }
      return result
    } catch (ex: Exception) {
      log.error("Failed to call backend API PATCH: $path ", ex)
      throw MemberServiceException("Failed to call backend API PATCH: $path", ex)
    }
  }

  suspend fun callBackendApiDelete(
      path: String,
      sessionId: String?,
      username: String?,
  ): Map<String, Any> {
    try {
      val apiHeaders = getApiHeaders(sessionId, username)
      var result: Map<String, Any>
      withTimeout(120_000L) { result = dmsHttpClient.delete("$baseUrl/$path", apiHeaders) }
      return result
    } catch (ex: Exception) {
      log.error("Failed to call backend API DELETE: $path ", ex)
      throw MemberServiceException("Failed to call backend API DELETE: $path", ex)
    }
  }

  suspend fun callBackendApiPut(
      path: String,
      sessionId: String?,
      username: String?,
      body: Any,
  ): Map<String, Any> {
    try {
      val apiHeaders = getApiHeaders(sessionId, username)
      var result: Map<String, Any>
      withTimeout(DMSHttpClient.DEFAULT_TIMEOUT_MS) {
        result = dmsHttpClient.put("$baseUrl/$path", apiHeaders, body)
      }
      return result
    } catch (ex: Exception) {
      log.error("Failed to call backend API PUT: $path ", ex)
      throw MemberServiceException("Failed to call backend API PUT: $path", ex)
    }
  }

  suspend fun getEventsOrganizedByMember(
      username: String,
      limit: Int,
      sessionId: String?,
  ): List<EventSummary> {
    try {
      val apiHeaders = getApiHeaders(sessionId)
      val result =
          dmsHttpClient.get(
              "$baseUrl/members/$username/events?limit=$limit",
              apiHeaders,
              DMSHttpClient.SHORT_TIMEOUT_MS,
          )
      val data =
          result["data"] as List<*>?
              ?: throw MemberServiceException("data attribute missing required")
      return data.map { eventMap ->
        val event = eventMap as Map<*, *>
        EventSummary(
            id = (event["id"] as Number).toInt(),
            name = event["name"] as String,
            eventStart = event["eventStart"] as String,
            status = event["status"] as String,
        )
      }
    } catch (ex: Exception) {
      log.error("Failed to get events for member: $username", ex)
      throw MemberServiceException("Failed to get events for member: $username", ex)
    }
  }

  /** Usernames [username] follows. */
  suspend fun getFollowing(username: String, sessionId: String?): List<String> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/members/$username/following", apiHeaders)
      (result["data"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    } catch (ex: Exception) {
      log.warn("Failed to get following list for: $username", ex)
      emptyList()
    }
  }

  /** Usernames that follow [username]. */
  suspend fun getFollowers(username: String, sessionId: String?): List<String> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/members/$username/followers", apiHeaders)
      (result["data"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    } catch (ex: Exception) {
      log.warn("Failed to get followers list for: $username", ex)
      emptyList()
    }
  }

  /**
   * Batch member lookup, username -> member. Mirrors `POST /members/batch` as used by
   * dms-member-service.js for `dms-member-card`. Used for decorative data (e.g. facepile avatars),
   * so this fails soft (empty map) rather than throwing, and isolates a single malformed row rather
   * than losing the whole batch.
   */
  suspend fun getMembersByUsernames(
      usernames: List<String>,
      sessionId: String?,
  ): Map<String, DMSMember> {
    if (usernames.isEmpty()) return emptyMap()
    return try {
      withTimeout(BATCH_MEMBERS_TIMEOUT_MS) {
        val apiHeaders = getApiHeaders(sessionId)
        val result =
            dmsHttpClient.post(
                "$baseUrl/members/batch", apiHeaders, mapOf("usernames" to usernames))
        val data = result["data"] as? Map<*, *> ?: return@withTimeout emptyMap()
        data.entries
            .mapNotNull { (key, value) ->
              val username = key as? String ?: return@mapNotNull null
              @Suppress("UNCHECKED_CAST")
              val map = value as? Map<String, Any?> ?: return@mapNotNull null
              runCatching { username to dmsMemberFromMap(map) }.getOrNull()
            }
            .toMap()
      }
    } catch (ex: Exception) {
      log.warn("Failed to batch-get members: $usernames", ex)
      emptyMap()
    }
  }

  suspend fun hasPrerequisiteClasses(groupSlug: String, sessionId: String?): Boolean {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result =
          dmsHttpClient.get("$baseUrl/groups/$groupSlug/has-prerequisite-classes", apiHeaders)
      result["data"] as? Boolean ?: false
    } catch (ex: Exception) {
      log.warn("Failed to check prerequisite classes for group: $groupSlug", ex)
      false
    }
  }

  suspend fun getUpcomingPrerequisiteEvents(
      groupNames: List<String>,
      sessionId: String?,
  ): List<EventSummary> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.post("$baseUrl/calendar/upcoming-events", apiHeaders, groupNames)
      val data =
          result["data"] as List<*>?
              ?: throw MemberServiceException("data attribute missing required")
      data.map { eventMap ->
        val event = eventMap as Map<*, *>
        EventSummary(
            id = (event["id"] as Number).toInt(),
            name = event["name"] as String,
            eventStart = event["eventStart"] as String,
            status = event["status"] as String,
            organizerUsername = event["organizerUsername"] as? String,
        )
      }
    } catch (ex: Exception) {
      log.error("Failed to get upcoming prerequisite events", ex)
      throw MemberServiceException("Failed to get upcoming prerequisite events", ex)
    }
  }

  suspend fun askAi(
      sessionId: String?,
      question: String,
      username: String?,
      forceRefresh: Boolean = false,
  ): Map<String, Any?> {
    try {
      val apiHeaders = getApiHeaders(sessionId, username)
      val body = mapOf("question" to question)
      val url = if (forceRefresh) "$baseUrl/ask-ai?refresh=true" else "$baseUrl/ask-ai"
      var result: Map<String, Any>
      withTimeout(120_000L) { result = dmsHttpClient.post(url, apiHeaders, body) }
      val data =
          result["data"] as? Map<*, *> ?: throw MemberServiceException("data attribute missing")
      @Suppress("UNCHECKED_CAST")
      return data as Map<String, Any?>
    } catch (ex: Exception) {
      log.error("Failed to call Ask AI", ex)
      throw MemberServiceException("Failed to call Ask AI", ex)
    }
  }

  suspend fun getAskAiTopQuestions(sessionId: String?, limit: Int = 10): List<Map<String, Any?>> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/ask-ai/top-questions?limit=$limit", apiHeaders)
      val data = result["data"] as? List<*> ?: emptyList<Map<String, Any?>>()
      @Suppress("UNCHECKED_CAST")
      data as List<Map<String, Any?>>
    } catch (ex: Exception) {
      log.warn("Failed to get Ask AI top questions", ex)
      emptyList()
    }
  }

  suspend fun getAskAiBySlug(sessionId: String?, slug: String): Map<String, Any?>? {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/ask-ai/q/$slug", apiHeaders)
      val data = result["data"] as? Map<*, *>
      @Suppress("UNCHECKED_CAST")
      data as? Map<String, Any?>
    } catch (ex: Exception) {
      log.warn("Failed to get Ask AI by slug: $slug", ex)
      null
    }
  }

  suspend fun getFeaturedProjects(sessionId: String?): List<FeaturedProject> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/featured-projects", apiHeaders)
      parseFeaturedProjectsData(result)
    } catch (ex: Exception) {
      log.warn("Failed to get featured projects", ex)
      emptyList()
    }
  }

  suspend fun getFeaturedProjectsForMember(
      username: String,
      sessionId: String?,
  ): List<FeaturedProject> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/featured-projects?username=$username", apiHeaders)
      parseFeaturedProjectsData(result)
    } catch (ex: Exception) {
      log.warn("Failed to get featured projects for member: $username", ex)
      emptyList()
    }
  }

  suspend fun getFeaturedProjectsForMemberAsync(
      username: String,
      sessionId: String?,
  ): List<FeaturedProject> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/featured-projects/member/$username", apiHeaders)
      parseFeaturedProjectsData(result)
    } catch (ex: Exception) {
      log.warn("Failed to get featured projects (async) for member: $username", ex)
      emptyList()
    }
  }

  private fun parseFeaturedProjectsData(result: Map<String, Any>): List<FeaturedProject> {
    val data = result["data"] as? List<*> ?: return emptyList()
    return data.mapNotNull { item ->
      val map = item as? Map<*, *> ?: return@mapNotNull null
      FeaturedProject(
          topicId = (map["topicId"] as? Number)?.toInt() ?: return@mapNotNull null,
          postId = (map["postId"] as? Number)?.toInt() ?: return@mapNotNull null,
          title = map["title"] as? String ?: return@mapNotNull null,
          imageUrl = map["imageUrl"] as? String ?: return@mapNotNull null,
          memberUsername = map["memberUsername"] as? String ?: return@mapNotNull null,
          memberDisplayName = map["memberDisplayName"] as? String,
          memberAvatarUrl = map["memberAvatarUrl"] as? String,
          likeCount = (map["likeCount"] as? Number)?.toInt() ?: 0,
          discourseTopicUrl = map["discourseTopicUrl"] as? String ?: return@mapNotNull null,
      )
    }
  }

  suspend fun submitAskAiFeedback(sessionId: String?, cacheId: Int, isHelpful: Boolean): Boolean {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val body = mapOf("cacheId" to cacheId, "isHelpful" to isHelpful)
      dmsHttpClient.post("$baseUrl/ask-ai/feedback", apiHeaders, body)
      true
    } catch (ex: Exception) {
      log.warn("Failed to submit Ask AI feedback", ex)
      false
    }
  }

  suspend fun getAttendedOrganizers(username: String, sessionId: String?): List<String> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result =
          dmsHttpClient.get(
              "$baseUrl/members/$username/attended-organizers",
              apiHeaders,
              DMSHttpClient.SHORT_TIMEOUT_MS,
          )
      (result["data"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    } catch (ex: Exception) {
      log.warn("Failed to get attended organizers for: $username", ex)
      emptyList()
    }
  }

  suspend fun getUpcomingEventsByOrganizers(
      organizers: List<String>,
      sessionId: String?,
  ): List<EventSummary> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result =
          dmsHttpClient.post(
              "$baseUrl/calendar/upcoming-events-by-organizers",
              apiHeaders,
              organizers,
              DMSHttpClient.SHORT_TIMEOUT_MS,
          )
      val data = result["data"] as? List<*> ?: return emptyList()
      data.map { eventMap ->
        val event = eventMap as Map<*, *>
        EventSummary(
            id = (event["id"] as Number).toInt(),
            name = event["name"] as String,
            eventStart = event["eventStart"] as String,
            status = event["status"] as String,
            organizerUsername = event["organizerUsername"] as? String,
            fulfillsPrerequisiteGroup = event["fulfillsPrerequisiteGroup"] as? String,
        )
      }
    } catch (ex: Exception) {
      log.warn("Failed to get upcoming events by organizers", ex)
      emptyList()
    }
  }

  suspend fun getAttendedEventNames(username: String, sessionId: String?): List<String> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result =
          dmsHttpClient.get(
              "$baseUrl/members/$username/attended-event-names",
              apiHeaders,
              DMSHttpClient.SHORT_TIMEOUT_MS,
          )
      (result["data"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    } catch (ex: Exception) {
      log.warn("Failed to get attended event names for: $username", ex)
      emptyList()
    }
  }

  suspend fun getUpcomingEventsByKeywords(
      keywords: List<String>,
      sessionId: String?,
  ): List<EventSummary> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result =
          dmsHttpClient.post(
              "$baseUrl/calendar/upcoming-events-by-keywords",
              apiHeaders,
              keywords,
              DMSHttpClient.SHORT_TIMEOUT_MS,
          )
      val data = result["data"] as? List<*> ?: return emptyList()
      data.map { eventMap ->
        val event = eventMap as Map<*, *>
        EventSummary(
            id = (event["id"] as Number).toInt(),
            name = event["name"] as String,
            eventStart = event["eventStart"] as String,
            status = event["status"] as String,
            organizerUsername = event["organizerUsername"] as? String,
            fulfillsPrerequisiteGroup = event["fulfillsPrerequisiteGroup"] as? String,
        )
      }
    } catch (ex: Exception) {
      log.warn("Failed to get upcoming events by keywords", ex)
      emptyList()
    }
  }

  suspend fun getBadgeFromMakerManager(username: String, sessionId: String?): String? {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/members/$username/badge-mm", apiHeaders)
      result["data"] as? String
    } catch (ex: Exception) {
      log.warn("Failed to get badge from MakerManager for $username", ex)
      null
    }
  }

  suspend fun getBadgeFromActiveDirectory(username: String, sessionId: String?): String? {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/members/$username/badge-ad", apiHeaders)
      result["data"] as? String
    } catch (ex: Exception) {
      log.warn("Failed to get badge from Active Directory for $username", ex)
      null
    }
  }

  suspend fun getScannerStatus(
      username: String,
      sessionId: String?,
      actorUsername: String?,
  ): Map<String, Any?>? {
    val baseHeaders = getApiHeaders(sessionId)
    val apiHeaders =
        StringValues.build {
          baseHeaders.forEach { key, values -> values.forEach { value -> append(key, value) } }
          actorUsername?.let { append("X-Actor-Username", it) }
        }
    return try {
      val result = dmsHttpClient.get("$baseUrl/members/$username/scanner-status", apiHeaders)
      @Suppress("UNCHECKED_CAST")
      result["data"] as? Map<String, Any?>
    } catch (ex: HttpException) {
      if (ex.httpStatus == HttpStatusCode.NotFound.value) null else throw ex
    }
  }

  suspend fun getTotalActiveDays(username: String, sessionId: String?): Int? {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/members/$username/total-active-time", apiHeaders)
      (result["data"] as? Number)?.toInt()
    } catch (ex: Exception) {
      log.warn("Failed to get total active days for $username", ex)
      null
    }
  }

  suspend fun getDebugInfo(
      username: String,
      sessionId: String?,
  ): org.dallasmakerspace.models.MemberDebugInfo? {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/members/$username/debug-info", apiHeaders)
      val data = result["data"] as? Map<*, *> ?: return null

      // Parse timeline
      val timeline =
          (data["timeline"] as? List<*>)?.mapNotNull { entryData ->
            val entry = entryData as? Map<*, *> ?: return@mapNotNull null
            val type = entry["type"] as? String ?: return@mapNotNull null
            val startDate = entry["startDate"] as? String ?: return@mapNotNull null
            val endDate = entry["endDate"] as? String
            val durationDays = (entry["durationDays"] as? Number)?.toInt() ?: return@mapNotNull null
            org.dallasmakerspace.models.TimelineEntryInfo(
                type =
                    when (type) {
                      "ACTIVE" -> org.dallasmakerspace.models.TimelineEntryType.ACTIVE
                      "GAP" -> org.dallasmakerspace.models.TimelineEntryType.GAP
                      else -> return@mapNotNull null
                    },
                startDate = kotlinx.datetime.LocalDate.parse(startDate),
                endDate = endDate?.let { kotlinx.datetime.LocalDate.parse(it) },
                durationDays = durationDays,
            )
          } ?: emptyList()

      org.dallasmakerspace.models.MemberDebugInfo(
          adAccountEnabled = data["adAccountEnabled"] as? Boolean ?: false,
          mmAdActive = data["mmAdActive"] as? Boolean ?: false,
          whmcsActive = data["whmcsActive"] as? Boolean ?: false,
          daysInCurrentWhmcsStatus = (data["daysInCurrentWhmcsStatus"] as? Number)?.toInt(),
          totalActiveDays = (data["totalActiveDays"] as? Number)?.toInt(),
          timeline = timeline,
      )
    } catch (ex: Exception) {
      log.warn("Failed to get debug info for $username", ex)
      null
    }
  }

  suspend fun getRecentBadgeSwipes(
      username: String,
      sessionId: String?,
  ): List<org.dallasmakerspace.models.BadgeSwipeInfo> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/members/$username/badge-swipes", apiHeaders)
      @Suppress("UNCHECKED_CAST") val dataList = result["data"] as? List<*> ?: return emptyList()
      dataList.mapNotNull { item ->
        val entry = item as? Map<*, *> ?: return@mapNotNull null
        org.dallasmakerspace.models.BadgeSwipeInfo(
            swipeTime = entry["swipeTime"] as? String ?: return@mapNotNull null,
            doorName = entry["doorName"] as? String ?: "Unknown",
            granted = entry["granted"] as? Boolean ?: false,
        )
      }
    } catch (ex: Exception) {
      log.warn("Failed to get recent badge swipes for $username", ex)
      emptyList()
    }
  }

  /**
   * Whether the [key] feature flag is on for [username] (global toggle or per-user/per-group
   * pilot). Fails closed (returns false) on any error, since this gates optional UI, not something
   * required for the page to render correctly.
   */
  suspend fun isFeatureEnabled(key: String, username: String, sessionId: String?): Boolean {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/feature-flags/$key?username=$username", apiHeaders)
      (result["data"] as? Map<*, *>)?.get("enabled") as? Boolean ?: false
    } catch (ex: Exception) {
      log.warn("Failed to check feature flag '$key' for $username", ex)
      false
    }
  }

  suspend fun getConfigValue(key: String, sessionId: String?): String? {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/config/$key", apiHeaders)
      val data = result["data"] as? Map<*, *> ?: return null
      data["currentValue"] as? String
    } catch (ex: Exception) {
      log.warn("Failed to get config value for key: $key", ex)
      null
    }
  }

  suspend fun getConfigList(sessionId: String?): List<Map<String, Any?>> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/config", apiHeaders)
      val data = result["data"] as? List<*> ?: return emptyList()
      @Suppress("UNCHECKED_CAST")
      data as List<Map<String, Any?>>
    } catch (ex: Exception) {
      log.warn("Failed to get config list", ex)
      emptyList()
    }
  }

  suspend fun getConfigHistory(key: String, sessionId: String?): List<Map<String, Any?>> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/config/$key/history", apiHeaders)
      val data = result["data"] as? List<*> ?: return emptyList()
      @Suppress("UNCHECKED_CAST")
      data as List<Map<String, Any?>>
    } catch (ex: Exception) {
      log.warn("Failed to get config history for key: $key", ex)
      emptyList()
    }
  }

  suspend fun getFeatureFlag(
      key: String,
      sessionId: String?,
      isInfraUser: Boolean = false
  ): Boolean {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val url = "$baseUrl/config/$key" + if (isInfraUser) "?isInfraUser=true" else ""
      val result: Map<String, Any> = dmsHttpClient.get(url, apiHeaders)
      val data = result["data"] as? Map<*, *>
      (data?.get("currentValue") as? String)?.toBoolean() ?: false
    } catch (ex: Exception) {
      log.warn("Failed to get feature flag $key, defaulting to false", ex)
      false
    }
  }

  suspend fun resolveShortLink(
      path: String,
      sessionId: String?,
      username: String?,
      isPreview: Boolean = false,
  ): ShortLinkResult {
    val client = HttpClient(CIO) { followRedirects = false }
    val apiHeaders = getApiHeaders(sessionId, username)
    val url = if (isPreview) "$baseUrl/go/$path?preview=true" else "$baseUrl/go/$path"
    return try {
      val response = client.get(url) { headers { appendAll(apiHeaders) } }
      when (response.status) {
        HttpStatusCode.Found,
        HttpStatusCode.MovedPermanently,
        HttpStatusCode.TemporaryRedirect -> {
          val location = response.headers[HttpHeaders.Location]
          if (location != null) {
            val description =
                response.headers["X-Short-Link-Description"]?.let {
                  java.net.URLDecoder.decode(it, "UTF-8")
                }
            ShortLinkResult.Redirect(location, description)
          } else {
            log.error("Redirect response missing Location header for path: $path")
            ShortLinkResult.Error("Redirect location not found")
          }
        }
        HttpStatusCode.NotFound -> ShortLinkResult.NotFound
        else -> {
          log.error("Unexpected response from service: ${response.status} for path: $path")
          ShortLinkResult.Error("Unexpected response: ${response.status}")
        }
      }
    } catch (ex: Exception) {
      log.error("Error resolving short link: $path", ex)
      ShortLinkResult.Error("Error resolving short link: ${ex.message}")
    } finally {
      client.close()
    }
  }

  suspend fun getRemoteAccessCategories(sessionId: String?): List<Map<String, Any?>> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/remote-access/categories", apiHeaders)
      val data = result["data"] as? List<*> ?: return emptyList()
      @Suppress("UNCHECKED_CAST")
      data as List<Map<String, Any?>>
    } catch (ex: Exception) {
      log.warn("Failed to get remote access categories", ex)
      emptyList()
    }
  }

  suspend fun killActiveConnection(activeConnectionId: String, sessionId: String?): Boolean {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      dmsHttpClient.delete(
          "$baseUrl/remote-access/active-connections/$activeConnectionId", apiHeaders)
      true
    } catch (ex: Exception) {
      log.warn("Failed to kill active connection $activeConnectionId", ex)
      false
    }
  }

  suspend fun streamAskAi(
      sessionId: String?,
      username: String?,
      question: String,
      forceRefresh: Boolean = false,
      onChunk: suspend (String) -> Unit,
  ) {
    val apiHeaders = getApiHeaders(sessionId, username)
    val encodedQuestion = java.net.URLEncoder.encode(question, "UTF-8")
    val url = buildString {
      append("$baseUrl/ask-ai/stream?question=$encodedQuestion")
      if (forceRefresh) append("&refresh=true")
    }
    val client =
        HttpClient(CIO) {
          engine {
            endpoint {
              requestTimeout = SSE_TIMEOUT_MS
              connectTimeout = 10_000
            }
          }
        }
    client.use {
      it.prepareGet(url) { headers { appendAll(apiHeaders) } }
          .execute { response ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (!channel.isClosedForRead) {
              val read = channel.readAvailable(buffer)
              if (read > 0) onChunk(String(buffer, 0, read, Charsets.UTF_8))
            }
          }
    }
  }

  suspend fun getNotifications(
      username: String,
      limit: Int,
      sessionId: String?,
  ): List<Map<String, Any?>> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result =
          dmsHttpClient.get("$baseUrl/notifications?username=$username&limit=$limit", apiHeaders)
      val data = result["data"] as? List<*> ?: return emptyList()
      @Suppress("UNCHECKED_CAST")
      data as List<Map<String, Any?>>
    } catch (ex: Exception) {
      log.warn("Failed to get notifications for: $username", ex)
      emptyList()
    }
  }

  suspend fun getNotificationPreferences(
      username: String,
      sessionId: String?,
  ): List<Map<String, Any?>> {
    return try {
      val apiHeaders = getApiHeaders(sessionId)
      val result = dmsHttpClient.get("$baseUrl/notifications/preferences/$username", apiHeaders)
      val data = result["data"] as? List<*> ?: return emptyList()
      @Suppress("UNCHECKED_CAST")
      data as List<Map<String, Any?>>
    } catch (ex: Exception) {
      log.warn("Failed to get notification preferences for: $username", ex)
      emptyList()
    }
  }

  suspend fun updateNotificationPreference(
      username: String,
      sessionId: String?,
      update: NotificationPreferenceUpdate,
  ) {
    val body =
        mapOf(
            "topic" to update.topic,
            "channel" to update.channel,
            "enabled" to update.enabled,
            "aggregation" to update.aggregation,
        )
    callBackendApiPut("notifications/preferences/$username", sessionId, username, body)
  }

  companion object {
    private const val SSE_TIMEOUT_MS = 300_000L
    private const val BATCH_MEMBERS_TIMEOUT_MS = 10_000L
  }
}
