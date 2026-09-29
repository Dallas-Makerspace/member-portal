package org.dallasmakerspace.webhook.handlers

import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.discourse.FeaturedProjectsService
import org.dallasmakerspace.discourse.ShowAndTellRules
import org.dallasmakerspace.members.IMemberRepository
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.dallasmakerspace.notifications.templates.ProjectPostData
import org.dallasmakerspace.notifications.templates.htmlExcerpt
import org.dallasmakerspace.notifications.topics.FollowingTopic
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.WebhookResult
import org.dallasmakerspace.webhook.models.DiscoursePostEvent
import org.dallasmakerspace.webhook.models.DiscoursePostEventBody
import org.dallasmakerspace.webhook.models.DiscoursePostWebhookEnvelope
import org.slf4j.LoggerFactory

/**
 * Webhook handler for Discourse post events. When a member posts (or edits in) an image in a
 * monthly Show & Tell topic - the same posts that appear in the profile's Featured Projects list
 * (see [ShowAndTellRules]) - it notifies everyone following that member.
 *
 * Discourse reaches us through the UI's public proxy (which verifies the webhook signature), so
 * this handler only sees verified traffic. It returns success for everything it deliberately
 * ignores: Discourse disables webhooks that keep failing, so only a malformed payload is an error.
 *
 * `post_edited` is handled as well as `post_created`, since members often add the photo in an edit
 * and Discourse post-processing can arrive as one. Re-deliveries and create-then-edit are made
 * harmless by the per-post, per-follower dedupe key.
 */
class DiscoursePostWebhook
@Inject
constructor(
    private val memberRepository: IMemberRepository,
    private val profileSubscriptionService: ProfileSubscriptionService,
    private val notificationService: NotificationService,
    private val featuredProjectsService: FeaturedProjectsService,
    private val activeDirectoryService: IActiveDirectoryService,
) : WebhookHandler() {

  private val logger = LoggerFactory.getLogger(DiscoursePostWebhook::class.java)
  private val json = Json { ignoreUnknownKeys = true }

  override fun getName() = "discourse-post"

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleWebhook(body: String): WebhookResult {
    val envelope =
        try {
          json.decodeFromString<DiscoursePostWebhookEnvelope>(body)
        } catch (e: Exception) {
          logger.error("Invalid discourse-post webhook envelope", e)
          return WebhookResult(false, "Invalid discourse-post webhook payload: ${e.message}")
        }
    if (envelope.event !in HANDLED_EVENTS) {
      return WebhookResult(true, "Ignoring event ${envelope.event}")
    }
    val post =
        try {
          json.decodeFromString<DiscoursePostEventBody>(envelope.payload).post
        } catch (e: Exception) {
          logger.error("Invalid Discourse post payload", e)
          return WebhookResult(false, "Invalid Discourse post payload: ${e.message}")
        }
    return handlePost(envelope.event, post)
  }

  private suspend fun handlePost(event: String, post: DiscoursePostEvent): WebhookResult {
    val skipReason = ineligibleReason(event, post)
    val imageUrl = ShowAndTellRules.extractLargeImageUrl(post.cooked)
    if (skipReason != null || imageUrl == null) {
      return WebhookResult(true, "Ignoring post ${post.id}: ${skipReason ?: "no qualifying image"}")
    }

    val member =
        memberRepository.getMembersByDiscourseUsernames(listOf(post.username))[post.username]
            ?: return WebhookResult(true, "Ignoring post ${post.id}: poster is not a DMS member")

    // The profile's Featured Projects list is cached; make it show this post promptly.
    featuredProjectsService.invalidateMember(member.username)

    val followers = profileSubscriptionService.getFollowers(member.username)
    if (followers.isEmpty()) {
      return WebhookResult(true, "No followers for ${member.username}, nothing to send")
    }

    val payload =
        json.encodeToString(
            ProjectPostData.serializer(),
            ProjectPostData(
                memberName = resolveDisplayName(member.username),
                memberUsername = member.username,
                topicTitle = post.topicTitle,
                imageUrl = imageUrl,
                excerpt = htmlExcerpt(post.cooked, EXCERPT_LENGTH)))
    val result =
        notificationService.createForMembers(
            memberUsernames = followers,
            topic = FollowingTopic,
            templateName = "project-post",
            payload = payload,
            dedupeKey = { follower -> "project-post-${post.id}-$follower" })
    return WebhookResult(
        true,
        "Notified ${result.created} of ${followers.size} follower(s) of ${member.username}" +
            " (duplicates=${result.duplicates}, failed=${result.failed})")
  }

  /**
   * The poster's full name from AD (the profile table doesn't store it). LDAP is blocking, so this
   * is scoped to [Dispatchers.IO]; falls back to the username on any failure.
   */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun resolveDisplayName(username: String): String =
      withContext(Dispatchers.IO) {
        try {
          activeDirectoryService.getMembersByUsernameList(listOf(username))[username]?.displayName
              ?: username
        } catch (e: Exception) {
          logger.warn("Failed to resolve display name for $username, using username", e)
          username
        }
      }

  /** Why [post] shouldn't be announced, or null if it should. */
  private fun ineligibleReason(event: String, post: DiscoursePostEvent): String? =
      when {
        post.categoryId != ShowAndTellRules.CATEGORY_ID -> "not in the Show & Tell category"
        !ShowAndTellRules.isProjectTopic(post.topicTitle) -> "not a monthly Show and Tell topic"
        !ShowAndTellRules.isProjectPost(post.postNumber) -> "intro post"
        post.postType != POST_TYPE_REGULAR -> "not a regular post"
        post.hidden || post.deletedAt != null || post.userDeleted -> "hidden or deleted"
        event == EVENT_POST_EDITED && !isRecent(post.createdAt) -> "edit of an older post"
        else -> null
      }

  private fun isRecent(createdAt: String): Boolean =
      runCatching { Instant.parse(createdAt) }
          .getOrNull()
          ?.let { Duration.between(it, Instant.now()) <= MAX_EDITED_POST_AGE } == true

  private companion object {
    const val EVENT_POST_CREATED = "post_created"
    const val EVENT_POST_EDITED = "post_edited"
    const val POST_TYPE_REGULAR = 1

    /** Short enough to sit beside the thumbnail without towering over it. */
    const val EXCERPT_LENGTH = 120
    val HANDLED_EVENTS = setOf(EVENT_POST_CREATED, EVENT_POST_EDITED)

    /** An edit only announces a post created within this window, so old posts aren't re-announced. */
    val MAX_EDITED_POST_AGE: Duration = Duration.ofDays(7)
  }
}
