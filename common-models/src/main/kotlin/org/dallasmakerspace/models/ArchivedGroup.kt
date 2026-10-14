package org.dallasmakerspace.models

import kotlinx.serialization.Serializable

/**
 * One archived group, for the Archived Groups report. Wire type of the service route
 * `GET /groups/archived?prefix=<p>&prefix=<q>` (prefix filters group names, case-insensitively; no prefix = all).
 * See docs/features/workflows/archive-group.md.
 */
@Serializable
data class ArchivedGroup(
    val id: Int,
    val name: String,
    /** ISO-8601 UTC instant. */
    val archivedAt: String,
    /** Username of the member who archived it. */
    val archivedBy: String,
    val reason: String,
    /** Workflow run that archived it, if known. */
    val runId: Long? = null,
    /** Step index and artifact name of the member-list snapshot, for the infra-only download. */
    val artifactStepIndex: Int? = null,
    val artifactName: String? = null,
)
