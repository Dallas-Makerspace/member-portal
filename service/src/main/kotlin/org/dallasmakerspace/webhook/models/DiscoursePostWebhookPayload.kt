package org.dallasmakerspace.webhook.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the UI's public Discourse proxy forwards to the service: the `X-Discourse-Event` header
 * value plus Discourse's raw JSON body as a string (kept raw so this envelope stays stable if
 * Discourse adds fields).
 */
@Serializable data class DiscoursePostWebhookEnvelope(val event: String, val payload: String)

/** Discourse's post-event body: `{"post": {...}}`. Only the fields we use. */
@Serializable data class DiscoursePostEventBody(val post: DiscoursePostEvent)

@Serializable
data class DiscoursePostEvent(
    val id: Int,
    val username: String,
    @SerialName("topic_id") val topicId: Int,
    @SerialName("topic_title") val topicTitle: String = "",
    @SerialName("topic_slug") val topicSlug: String = "",
    @SerialName("post_number") val postNumber: Int,
    /** 1 = regular post; whispers, moderator actions and small actions use other values. */
    @SerialName("post_type") val postType: Int = 1,
    @SerialName("category_id") val categoryId: Int? = null,
    val cooked: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val hidden: Boolean = false,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("user_deleted") val userDeleted: Boolean = false,
)
