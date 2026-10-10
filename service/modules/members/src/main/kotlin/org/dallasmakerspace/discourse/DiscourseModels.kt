package org.dallasmakerspace.discourse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the response when creating a new post/topic in Discourse. Returned from POST
 * /posts.json endpoint.
 */
@Serializable
data class DiscoursePostResponse(
    @SerialName("id") val id: Int,
    @SerialName("topic_id") val topicId: Int,
    @SerialName("topic_slug") val topicSlug: String
)

/** Represents a single topic in search results. */
@Serializable
data class DiscourseTopicSearchResult(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String,
    @SerialName("slug") val slug: String,
    @SerialName("pinned_globally") val pinnedGlobally: Boolean = false,
    @SerialName("pinned") val pinned: Boolean = false,
    @SerialName("closed") val closed: Boolean = false,
    @SerialName("category_id") val categoryId: Int? = null
)

/** Represents a single user action (e.g. a reply) from GET /user_actions.json. */
@Serializable
data class DiscourseUserAction(
    @SerialName("action_type") val actionType: Int = 0,
    @SerialName("topic_id") val topicId: Int = 0,
    @SerialName("post_id") val postId: Int = 0,
    @SerialName("post_number") val postNumber: Int = 0,
    @SerialName("category_id") val categoryId: Int? = null,
    @SerialName("title") val title: String = "",
    @SerialName("slug") val slug: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class DiscourseUserActionsResponse(
    @SerialName("user_actions") val userActions: List<DiscourseUserAction> = emptyList()
)

/** Represents the search response from Discourse GET /search.json endpoint. */
@Serializable
data class DiscourseSearchResponse(
    @SerialName("topics") val topics: List<DiscourseTopicSearchResult> = emptyList(),
    @SerialName("posts") val posts: List<DiscoursePostSearchResult> = emptyList()
)

/** Represents a single post in search results. */
@Serializable
data class DiscoursePostSearchResult(
    @SerialName("id") val id: Int,
    @SerialName("topic_id") val topicId: Int,
    @SerialName("blurb") val blurb: String? = null
)

/** Represents a single topic in a category listing. */
@Serializable
data class DiscourseCategoryTopic(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String,
    @SerialName("slug") val slug: String,
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class DiscourseTopicList(
    @SerialName("topics") val topics: List<DiscourseCategoryTopic> = emptyList(),
    @SerialName("more_topics_url") val moreTopicsUrl: String? = null
)

@Serializable
data class DiscourseCategoryResponse(
    @SerialName("topic_list") val topicList: DiscourseTopicList = DiscourseTopicList()
)

/** A single entry in a post's actions_summary array (e.g. id=2 is "like"). */
@Serializable
data class DiscoursePostActionSummary(
    @SerialName("id") val id: Int,
    @SerialName("count") val count: Int = 0
)

/** Represents a single post in a topic. */
@Serializable
data class DiscourseTopicPost(
    @SerialName("id") val id: Int,
    @SerialName("username") val username: String,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("actions_summary")
    val actionsSummary: List<DiscoursePostActionSummary> = emptyList(),
    @SerialName("cooked") val cooked: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("post_number") val postNumber: Int = 1
) {
  /** Discourse action id 2 = like. Prefer actions_summary count over the top-level like_count. */
  val resolvedLikeCount: Int
    get() = actionsSummary.firstOrNull { it.id == DISCOURSE_LIKE_ACTION_ID }?.count ?: likeCount

  companion object {
    private const val DISCOURSE_LIKE_ACTION_ID = 2
  }
}

@Serializable
data class DiscoursePostStream(
    @SerialName("posts") val posts: List<DiscourseTopicPost> = emptyList(),
    @SerialName("stream") val stream: List<Int> = emptyList(),
)

@Serializable
data class DiscourseTopicDetails(
    @SerialName("post_stream") val postStream: DiscoursePostStream = DiscoursePostStream(),
    @SerialName("slug") val slug: String = "",
    @SerialName("title") val title: String = ""
)
