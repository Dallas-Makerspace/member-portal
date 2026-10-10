package org.dallasmakerspace.discourse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a Discourse user profile response from the /u/<username>.json endpoint. This is the
 * top-level response object containing the user data.
 */
@Serializable data class DiscourseUserProfile(@SerialName("user") val user: DiscourseUser)

/**
 * Represents a Discourse user object containing user profile information. This contains the
 * avatar_template field that we need for avatar URL generation.
 */
@Serializable
data class DiscourseUser(
    @SerialName("id") val id: Int,
    @SerialName("username") val username: String,
    @SerialName("name") val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String,
    @SerialName("title") val title: String? = null,
    @SerialName("admin") val admin: Boolean = false,
    @SerialName("moderator") val moderator: Boolean = false,
    @SerialName("trust_level") val trustLevel: Int = 0
)
