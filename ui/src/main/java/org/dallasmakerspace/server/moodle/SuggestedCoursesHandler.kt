package org.dallasmakerspace.server.moodle

import io.ktor.server.application.*
import io.ktor.server.response.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.AuthenticatedHandler

private const val CACHE_TTL_MS = 5 * 60 * 1000L
private const val MAX_CACHED_MEMBERS = 500

/** `GET /api/suggested-courses` — DMS Learn courses for the home page's suggestions row. */
class SuggestedCoursesHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberServiceClient: MemberServiceClient,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {
  private val log = loggerFactory.create(javaClass)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleAuthenticated(call: ApplicationCall) = coroutineScope {
    val username =
        userInfo["preferred_username"] as? String
            ?: run {
              call.respond(disabled())
              return@coroutineScope
            }
    try {
      cache[username]?.takeIf { it.second > System.currentTimeMillis() }?.let {
        call.respond(it.first)
        return@coroutineScope
      }
      if (!memberServiceClient.isFeatureEnabled(MOODLE_FEATURE_FLAG, username, session.sessionId)) {
        call.respond(disabled())
        return@coroutineScope
      }
      val groups =
          (userInfo["groups"] as? List<*>).orEmpty().filterIsInstance<String>().toSet()
      val memberCoursesDeferred = async {
        memberServiceClient.getMoodleMemberCourses(session.sessionId, username)
      }
      val catalog = memberServiceClient.getMoodleCatalog(session.sessionId)
      val courses = MoodleCourses.parse(catalog, memberCoursesDeferred.await())
      val response =
          mapOf(
              "enabled" to true,
              "learnUrl" to MoodleCourses.learnUrl(catalog),
              "courses" to MoodleCourses.suggest(courses, groups).map { it.toJson() },
          )
      if (cache.size >= MAX_CACHED_MEMBERS) cache.clear()
      cache[username] = response to System.currentTimeMillis() + CACHE_TTL_MS
      call.respond(response)
    } catch (e: Exception) {
      log.warn("Failed to get suggested courses", e)
      call.respond(disabled())
    }
  }

  private fun disabled(): Map<String, Any> = mapOf("enabled" to false, "courses" to emptyList<Any>())

  companion object {
    // Handlers are rebuilt per request, so the cache lives on the companion.
    private val cache = ConcurrentHashMap<String, Pair<Map<String, Any?>, Long>>()
  }
}
