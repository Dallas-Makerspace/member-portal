package org.dallasmakerspace.webhook.models

import kotlinx.serialization.Serializable

/**
 * What the UI's public relay forwards after verifying Moodle's signature: [event] is why the plugin
 * sent it (`course_change` from an observer, `manual` from its CLI) and [payload] the raw JSON it
 * sent. Only a hint: the announcer re-reads the whole catalog.
 */
@Serializable data class MoodleWebhookEnvelope(val event: String = "", val payload: String = "")
