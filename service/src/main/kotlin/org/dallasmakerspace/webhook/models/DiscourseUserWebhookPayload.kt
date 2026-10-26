package org.dallasmakerspace.webhook.models

import kotlinx.serialization.Serializable

/** Discourse's user-event body: `{"user": {...}}`. Only the fields we use. */
@Serializable data class DiscourseUserEventBody(val user: DiscourseUserEvent)

/** [toString] leaves the email out. */
@Serializable
data class DiscourseUserEvent(
    val id: Int,
    val username: String,
    val email: String? = null,
    val staged: Boolean = false,
) {
  override fun toString() = "DiscourseUserEvent(id=$id, username=$username)"
}
