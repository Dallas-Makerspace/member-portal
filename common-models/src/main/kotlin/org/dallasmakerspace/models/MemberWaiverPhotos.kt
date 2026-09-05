package org.dallasmakerspace.models

import kotlinx.serialization.Serializable

/**
 * A single Smartwaiver waiver signing session found by matching the member's name, with its kiosk
 * photos.
 */
@Serializable
data class MemberWaiverPhotos(
    val waiverId: String,
    val firstName: String?,
    val lastName: String?,
    /** As stored by Smartwaiver, "yyyy-MM-dd HH:mm:ss" */
    val dateCompleted: String?,
    val photos: List<WaiverPhoto>,
    /**
     * Whether this waiver's email matches the member's on-file email. False doesn't necessarily
     * mean a different person signed it — members often sign with an older/different email than
     * what's on their current DMS account — but it's surfaced so an admin can judge for themselves
     * rather than the code silently deciding.
     */
    val emailMatches: Boolean,
)

/** A single kiosk-captured photo for a waiver signing session. */
@Serializable
data class WaiverPhoto(
    val photoId: String?,
    val tag: String?,
    val fileType: String?,
    /** Base64-encoded image bytes, no data-URI prefix. */
    val dataBase64: String?,
)
