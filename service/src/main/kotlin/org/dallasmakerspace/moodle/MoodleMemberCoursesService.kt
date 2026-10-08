package org.dallasmakerspace.moodle

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.dallasmakerspace.core.LoggerFactory

private const val MAX_CACHED_MEMBERS = 2_000

/** A member's own DMS Learn enrolments with progress/completion; empty on any failure. */
@Singleton
class MoodleMemberCoursesService
@Inject
constructor(
    private val moodleApiClient: IMoodleApiClient,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private data class Entry<T>(val value: T, val expiresAt: Instant)

  private val userIds = ConcurrentHashMap<String, Entry<Int?>>()
  private val courses = ConcurrentHashMap<String, Entry<List<LearnMemberCourse>>>()

  @Suppress("TooGenericExceptionCaught")
  suspend fun getMemberCourses(username: String): List<LearnMemberCourse> {
    val key = username.lowercase()
    val now = Clock.System.now()
    courses[key]?.takeIf { it.expiresAt > now }?.let { return it.value }

    return try {
      val userId = resolveUserId(key, now) ?: return emptyList()
      val result =
          moodleApiClient.getUserCourses(userId).map {
            LearnMemberCourse(
                courseId = it.id,
                progress = it.progress?.roundToInt(),
                completed = it.completed == true,
            )
          }
      put(courses, key, result, now + 10.minutes)
      result
    } catch (e: Exception) {
      log.warn("Failed to load DMS Learn courses for $username", e)
      emptyList()
    }
  }

  private suspend fun resolveUserId(key: String, now: Instant): Int? {
    userIds[key]?.takeIf { it.expiresAt > now }?.let { return it.value }
    val id = moodleApiClient.getUserId(key)
    // Not found = never logged into DMS Learn; check again sooner than for a known id.
    put(userIds, key, id, now + if (id == null) 1.hours else 24.hours)
    return id
  }

  private fun <T> put(
      map: ConcurrentHashMap<String, Entry<T>>,
      key: String,
      value: T,
      expiresAt: Instant,
  ) {
    if (map.size >= MAX_CACHED_MEMBERS) map.clear()
    map[key] = Entry(value, expiresAt)
  }
}
