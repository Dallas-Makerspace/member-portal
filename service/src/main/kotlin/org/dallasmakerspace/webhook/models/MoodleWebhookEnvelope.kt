package org.dallasmakerspace.webhook.models

import kotlinx.serialization.Serializable

/**
 * What the UI's public relay forwards after verifying Moodle's signature: [event] is what changed
 * in Moodle (course_created, course_updated, course_category_updated, manual) and [payload] the
 * raw JSON the plugin sent. Only a hint: the announcer re-reads the whole catalog.
 */
@Serializable data class MoodleWebhookEnvelope(val event: String = "", val payload: String = "")
