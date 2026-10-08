package org.dallasmakerspace.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.util.concurrent.*
import javax.inject.Inject
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.models.SearchPreloadResponse
import org.dallasmakerspace.server.moodle.MOODLE_FEATURE_FLAG
import org.dallasmakerspace.server.moodle.MoodleCourses
import org.dallasmakerspace.server.models.slug

class SearchPreloadHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleAuthenticated(call: ApplicationCall) {

    log.debug("SearchPreloadHandler start")

    val cacheKey = "searchPreload"
    val currentTime = System.currentTimeMillis()

    val jsonMap: MutableMap<String, Any> = mutableMapOf()
    try {
      // Only the backend fetch is cached (shared across users). The response is projected
      // per request below because sensitive fields depend on the requester's permissions.
      val cachedData = cache[cacheKey]
      val preloadResponse =
          if (cachedData != null && currentTime - cachedData.first < cacheDuration) {
            log.debug("Using cached preload data")
            cachedData.second
          } else {
            memberService.getSearchPreload(session.sessionId).also {
              cache[cacheKey] = Pair(currentTime, it)
            }
          }

      // Process members
      val memberData =
          preloadResponse.members.map {
            val avatarUrl = normalizeAvatarUrl(it.displayAvatarUrl())
            val record =
                mutableMapOf(
                    "displayName" to it.displayName,
                    "username" to it.username,
                    "discourseUsername" to it.discourseUsername,
                    "avatarUrl" to avatarUrl,
                    "enabled" to it.enabled,
                    "type" to "member")
            if (authz.can(Permission.MANAGE_MEMBERS.name)) {
              record["badgeNumber"] = it.badgeNumber
              record["personalEmail"] = it.personalEmail
              record["phoneNumber"] = it.phoneNumber
            }
            record
          }

      // Process groups
      val groupData =
          preloadResponse.groups.map {
            mapOf(
                "displayName" to it.name,
                "username" to it.slug,
                "description" to it.description,
                "type" to "group")
          }

      val courseData = moodleCourseRecords(currentTime)

      // Combine members, groups and DMS Learn courses
      val combinedData = memberData + groupData + courseData

      log.debug("SearchPreloadHandler members size: ${preloadResponse.members.size}")
      log.debug("SearchPreloadHandler groups size: ${preloadResponse.groups.size}")
      log.debug("SearchPreloadHandler combined size: ${combinedData.size}")

      jsonMap["status"] = "SUCCESS"
      jsonMap["data"] = combinedData

      call.respond(jsonMap)
    } catch (e: Exception) {
      jsonMap["status"] = "ERROR"
      jsonMap["error"] = "${e.message}: ${e.stackTraceToString()}"
      call.respond(HttpStatusCode.InternalServerError, jsonMap)
    }
  }

  /** DMS Learn courses as search records, if `moodle.enabled` is on for the requester. */
  private suspend fun moodleCourseRecords(currentTime: Long): List<Map<String, Any?>> {
    val username = userInfo["preferred_username"] as? String ?: return emptyList()
    val enabled =
        moodleFlagCache[username]?.takeIf { currentTime - it.first < cacheDuration }?.second
            ?: memberServiceClient
                .isFeatureEnabled(MOODLE_FEATURE_FLAG, username, session.sessionId)
                .also {
                  if (moodleFlagCache.size >= MAX_FLAG_CACHE_SIZE) moodleFlagCache.clear()
                  moodleFlagCache[username] = currentTime to it
                }
    if (!enabled) return emptyList()

    val cached = moodleCourseCache.get()?.takeIf { currentTime - it.first < cacheDuration }
    val courses =
        cached?.second
            ?: MoodleCourses.parse(memberServiceClient.getMoodleCatalog(session.sessionId))
                .filter { it.selfEnrol }
                .map {
                  mapOf(
                      "displayName" to it.name,
                      "username" to it.id.toString(),
                      "description" to
                          listOfNotNull(it.committeeName, it.authorName?.let { a -> "by $a" }, it.summary)
                              .joinToString(" · "),
                      "url" to it.url,
                      "type" to "course")
                }
                .also { if (it.isNotEmpty()) moodleCourseCache.set(currentTime to it) }
    return courses
  }

  companion object {
    private const val MAX_FLAG_CACHE_SIZE = 1_000
    private val cache = ConcurrentHashMap<String, Pair<Long, SearchPreloadResponse>>()
    private val cacheDuration = TimeUnit.MINUTES.toMillis(5)
    private val moodleFlagCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private val moodleCourseCache =
        java.util.concurrent.atomic.AtomicReference<Pair<Long, List<Map<String, Any?>>>?>(null)
  }
}
