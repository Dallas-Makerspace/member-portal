package org.dallasmakerspace.discourse

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory

/** Mock implementation of the DiscourseApiClient for testing purposes. */
@Singleton
class DiscourseApiClientMock @Inject constructor(loggerFactory: LoggerFactory) :
    IDiscourseApiClient {
  private val log = loggerFactory.create(javaClass)

  override suspend fun addUsersToGroup(
      memberUsernamesList: List<String>,
      groupId: DiscourseService.GroupId
  ) {
    log.info("Mocked adding member(s) $memberUsernamesList to group ${groupId.name}")
    // throw DiscourseApiException("DiscourseApiClientMock: Failed to add member")
    return
  }

  override suspend fun removeMembersFromGroup(
      memberUsernamesList: List<String>,
      groupId: DiscourseService.GroupId
  ) {
    log.info("Mocked removing member(s) $memberUsernamesList to group ${groupId.name}")
    // throw DiscourseApiException("DiscourseApiClientMock: Failed to remove member")
    return
  }

  override suspend fun getUserProfile(username: String): DiscourseUserProfile {
    log.info("Mocked fetching user profile for username: $username")
    return DiscourseUserProfile(
        user =
            DiscourseUser(
                id = 12345,
                username = username,
                name = "Mock User",
                avatarTemplate =
                    "/user_avatar/talk.dallasmakerspace.org/$username/{size}/123_2.png",
                title = "Mock Title",
                admin = false,
                moderator = false,
                trustLevel = 1))
  }

  override suspend fun createPost(
      title: String,
      raw: String,
      categoryId: Int
  ): DiscoursePostResponse {
    log.info("Mocked creating post: $title in category $categoryId")
    return DiscoursePostResponse(
        id = kotlin.random.Random.nextInt(1000, 9999),
        topicId = kotlin.random.Random.nextInt(1000, 9999),
        topicSlug = title.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]"), ""))
  }

  override suspend fun updateTopicStatus(topicId: Int, status: String, enabled: Boolean) {
    log.info("Mocked updating topic $topicId status: $status = $enabled")
  }

  override suspend fun pinTopic(topicId: Int, pinned: Boolean, pinGlobally: Boolean) {
    val globalText = if (pinGlobally) " globally" else ""
    log.info("Mocked ${if (pinned) "pinning" else "unpinning"} topic $topicId$globalText")
  }

  override suspend fun searchTopics(query: String, categoryId: Int?): DiscourseSearchResponse {
    log.info("Mocked searching topics with query: $query in category: $categoryId")
    return DiscourseSearchResponse(topics = emptyList(), posts = emptyList())
  }

  override suspend fun getCategoryTopics(
      categorySlug: String,
      categoryId: Int,
      page: Int
  ): DiscourseCategoryResponse {
    log.info("Mocked fetching category topics for: $categorySlug ($categoryId), page $page")
    return DiscourseCategoryResponse()
  }

  override suspend fun getTopicPosts(topicId: Int): DiscourseTopicDetails {
    log.info("Mocked fetching topic posts for topic: $topicId")
    return DiscourseTopicDetails()
  }

  override suspend fun getTopicPostsByIds(topicId: Int, postIds: List<Int>): DiscourseTopicDetails {
    log.info("Mocked fetching posts by IDs for topic: $topicId, postIds: $postIds")
    return DiscourseTopicDetails()
  }

  override suspend fun getUserActions(
      username: String,
      filter: Int,
      offset: Int
  ): DiscourseUserActionsResponse {
    log.info("Mocked fetching user actions for $username (filter=$filter, offset=$offset)")
    return DiscourseUserActionsResponse()
  }
}
