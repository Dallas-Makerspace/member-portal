package org.dallasmakerspace.webhook.handlers

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.dallasmakerspace.keyvalue.KeyValueStore
import org.dallasmakerspace.keyvalue.namespace
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.moodle.LearnCourse
import org.dallasmakerspace.moodle.MoodleCatalogService
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.db.CommitteeSubscriptionsRepository
import org.dallasmakerspace.notifications.templates.CommitteeCourseData
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic

/** Outcome of one announcement pass; [success] false makes Moodle retry the webhook. */
data class AnnounceResult(val success: Boolean, val message: String)

/**
 * Tells members who follow a committee that it has published an online course. Moodle only sends a
 * "something changed" signal, so this reads the whole catalog fresh and announces every course
 * that is visible, belongs to a committee, is joinable (self-enrol) and has not been announced.
 *
 * "Announced" is a marker per course in the key/value store, so an edit to a long-published course
 * (Moodle fires an update event for any edit) never announces it again, and a course that is hidden
 * and shown again isn't announced twice to people who followed in between. The first pass ever
 * writes a baseline of every course visible at that moment and notifies nobody, so nothing that
 * already existed is treated as new.
 *
 * A course is marked only after its notifications were created; a failed pass is retried by
 * Moodle, and the per-follower dedupe key makes the repeat harmless.
 */
@Singleton
class MoodleCourseAnnouncer
@Inject
constructor(
    private val catalog: MoodleCatalogService,
    keyValueStore: KeyValueStore,
    private val subscriptions: CommitteeSubscriptionsRepository,
    private val notificationService: NotificationService,
    private val followerFilter: CommitteeFollowerFilter,
) {
  private val announced = keyValueStore.namespace(NAMESPACE)
  private val json = Json { ignoreUnknownKeys = true }

  // Moodle can send several signals at once; one pass at a time keeps the baseline race-free.
  private val lock = Mutex()

  /** Throws if Moodle can't be read, so the caller reports a failure and Moodle retries. */
  suspend fun announce(): AnnounceResult = lock.withLock { announceLocked() }

  private suspend fun announceLocked(): AnnounceResult {
    val visible = catalog.refreshNow().filter { it.visible }

    if (!announced.contains(BASELINE_KEY)) {
      val added = announced.putAllIfAbsent(visible.associate { it.id.toString() to now() })
      announced.putIfAbsent(BASELINE_KEY, now())
      return AnnounceResult(true, "Moodle courses: seeded $added visible course(s), nobody notified")
    }

    val alreadyAnnounced = announced.keys()
    val candidates =
        visible.filter {
          it.id.toString() !in alreadyAnnounced && it.committeeSlug != null && it.selfEnrol
        }
    if (candidates.isEmpty()) return AnnounceResult(true, "Moodle courses: nothing new")

    var notified = 0
    var failedCourses = 0
    candidates.forEach { course ->
      val outcome = announceCourse(course)
      notified += outcome.created
      if (outcome.complete) announced.putIfAbsent(course.id.toString(), now()) else failedCourses++
    }
    return AnnounceResult(
        success = failedCourses == 0,
        message =
            "Moodle courses: announced ${candidates.size - failedCourses} of ${candidates.size} " +
                "new course(s), $notified notification(s) created, $failedCourses to retry")
  }

  private class CourseOutcome(val created: Int, val complete: Boolean)

  private suspend fun announceCourse(course: LearnCourse): CourseOutcome {
    val committee =
        course.committeeSlug?.let { Committees.findBySlug(it) } ?: return CourseOutcome(0, true)
    val followers = subscriptions.getSubscribers(listOf(committee.id))
    // With no AD group we can't tell who has finished the course, so every follower is told.
    val eligible = course.adGroup?.let { followerFilter.withoutGroup(followers, it) } ?: followers
    if (eligible.isEmpty()) return CourseOutcome(0, true)

    val result =
        notificationService.createForMembers(
            memberUsernames = eligible,
            topic = CommitteeUpdatesTopic,
            templateName = "committee-course",
            payload =
                json.encodeToString(
                    CommitteeCourseData.serializer(),
                    CommitteeCourseData(
                        courseName = course.name,
                        committeeName = committee.name,
                        courseUrl = course.url)),
            channel = Channel.EMAIL,
            dedupeKey = { member -> "committee-course-${course.id}-$member" })
    return CourseOutcome(result.created, complete = result.failed == 0)
  }

  private fun now(): String = Instant.now().toString()

  companion object {
    const val NAMESPACE = "moodle-course-announced"
    const val BASELINE_KEY = "_baseline"
  }
}
