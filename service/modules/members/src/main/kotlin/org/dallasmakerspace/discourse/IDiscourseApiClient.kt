package org.dallasmakerspace.discourse

interface IDiscourseApiClient {
  suspend fun addUsersToGroup(memberUsernamesList: List<String>, groupId: DiscourseService.GroupId)

  suspend fun removeMembersFromGroup(
      memberUsernamesList: List<String>,
      groupId: DiscourseService.GroupId
  )

  suspend fun getUserProfile(username: String): DiscourseUserProfile

  /** Talk accounts whose primary email is [email] (exact match); needs an admin API key. */
  suspend fun findUsersByEmail(email: String): List<DiscourseAdminUser>

  /** One 100-user page (1-based, by username, which pages stably) of Talk accounts with emails. */
  suspend fun listUsersPage(page: Int): List<DiscourseAdminUser>

  /**
   * Create a new post/topic in Discourse.
   *
   * @param title The title of the topic
   * @param raw The raw markdown content of the post
   * @param categoryId The category ID where the topic should be created
   * @return DiscoursePostResponse containing the created post/topic details
   */
  suspend fun createPost(title: String, raw: String, categoryId: Int): DiscoursePostResponse

  /**
   * Like [createPost] but authored by [username] (the API key impersonates them), so the topic
   * shows them as its author and is subject to their category permissions.
   */
  suspend fun createTopicAs(
      username: String,
      title: String,
      raw: String,
      categoryId: Int
  ): DiscoursePostResponse

  /** The topic's details; throws [DiscourseTopicNotFoundException] when it can't be read. */
  suspend fun getTopic(topicId: Int): DiscourseTopicDetails

  /**
   * Update the status of a topic (pin, unpin, close, etc.).
   *
   * @param topicId The ID of the topic to update
   * @param status The status type (e.g., "pinned", "pinned_globally", "closed")
   * @param enabled Whether to enable or disable the status
   */
  suspend fun updateTopicStatus(topicId: Int, status: String, enabled: Boolean)

  /**
   * Pin or unpin a topic.
   *
   * @param topicId The ID of the topic to pin/unpin
   * @param pinned Whether to pin or unpin the topic
   * @param pinGlobally Whether to pin globally (true) or just in category (false)
   */
  suspend fun pinTopic(topicId: Int, pinned: Boolean, pinGlobally: Boolean = false)

  /**
   * Search for topics in Discourse.
   *
   * @param query The search query string
   * @param categoryId Optional category ID to filter results
   * @return DiscourseSearchResponse containing matching topics
   */
  suspend fun searchTopics(query: String, categoryId: Int? = null): DiscourseSearchResponse

  suspend fun getCategoryTopics(
      categorySlug: String,
      categoryId: Int,
      page: Int = 0
  ): DiscourseCategoryResponse

  suspend fun getTopicPosts(topicId: Int): DiscourseTopicDetails

  suspend fun getTopicPostsByIds(topicId: Int, postIds: List<Int>): DiscourseTopicDetails

  suspend fun getUserActions(
      username: String,
      filter: Int,
      offset: Int = 0
  ): DiscourseUserActionsResponse
}
