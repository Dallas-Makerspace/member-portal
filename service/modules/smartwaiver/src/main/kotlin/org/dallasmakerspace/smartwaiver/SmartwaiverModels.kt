package org.dallasmakerspace.smartwaiver

import java.time.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SmartwaiverListResponse(
    val version: Int? = null,
    val id: String? = null,
    val ts: String? = null,
    val type: String? = null,
    val waivers: List<SmartwaiverSummary>? = null,
    val count: Int? = null,
    val pages: Int? = null,
    @SerialName("pageSize") val pageSize: Int? = null
)

@Serializable
data class SmartwaiverSearchResponse(
    val version: Int? = null,
    val id: String? = null,
    val ts: String? = null,
    val type: String? = null,
    val search: SmartwaiverSearchMetadata? = null
)

@Serializable
data class SmartwaiverSearchMetadata(
    val guid: String,
    val count: Int? = null,
    val pages: Int? = null,
    @SerialName("pageSize") val pageSize: Int? = null
)

@Serializable
data class SmartwaiverSearchResultsResponse(
    val version: Int? = null,
    val id: String? = null,
    val ts: String? = null,
    val type: String? = null,
    @SerialName("search_results") val searchResults: List<SmartwaiverSummary>? = null
)

@Serializable
data class SmartwaiverSummary(
    @SerialName("waiverId") val waiverId: String,
    @SerialName("templateId") val templateId: String,
    @SerialName("title") val title: String,
    @SerialName("createdOn") val createdOn: String, // ISO 8601 format
    @SerialName("expirationDate") val expirationDate: String? = null,
    @SerialName("expired") val expired: Boolean? = null,
    @SerialName("verified") val verified: Boolean? = null,
    @SerialName("kiosk") val kiosk: Boolean? = null,
    @SerialName("firstName") val firstName: String? = null,
    @SerialName("middleName") val middleName: String? = null,
    @SerialName("lastName") val lastName: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("dob") val dob: String? = null,
    @SerialName("isMinor") val isMinor: Boolean? = null,
    @SerialName("tags") val tags: List<String>? = null
)

data class WaiverSigningData(
    val date: LocalDateTime,
    val dayOfWeek: Int, // 1=Monday, 7=Sunday
    val waiverId: String
)

open class SmartwaiverApiException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** SmartWaiver answered 404: the waiver no longer exists there. */
class SmartwaiverNotFoundException(message: String) : SmartwaiverApiException(message)

@Serializable
data class SmartwaiverCustomField(
    val value: String = "",
    val displayText: String = "",
)

/**
 * The parts of a v4 waiver we mirror. This is an allowlist: the API also returns driver's license,
 * insurance, client IP, gender, marketing opt-ins and typed signatures, which are deliberately not
 * declared here so they are never deserialized, mapped or stored.
 */
@Serializable
data class SmartwaiverFullWaiver(
    val waiverId: String,
    val title: String? = null,
    val createdOn: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val dob: String? = null,
    val templateId: String? = null,
    val expirationDate: String? = null,
    val verified: Boolean? = null,
    val kiosk: Boolean? = null,
    val isMinor: Boolean? = null,
    val tags: List<String> = emptyList(),
    val participants: List<SmartwaiverParticipant> = emptyList(),
    val guardian: SmartwaiverGuardian? = null,
    val addressLineOne: String? = null,
    val addressLineTwo: String? = null,
    val addressCity: String? = null,
    val addressState: String? = null,
    val addressZip: String? = null,
    val addressCountry: String? = null,
    val emergencyContactName: String? = null,
    val emergencyContactRelation: String? = null,
    val emergencyContactPhone: String? = null,
    /** Number of kiosk photos SmartWaiver holds; null if the API didn't say. */
    val photos: Int? = null,
    val customWaiverFields: Map<String, SmartwaiverCustomField> = emptyMap(),
)

@Serializable
data class SmartwaiverParticipant(
    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val dob: String? = null,
    val isMinor: Boolean? = null,
    val minorExpired: Boolean? = null,
    val phone: String? = null,
)

/** Guardian name, relationship and phone only; the guardian's dob and address are not kept. */
@Serializable
data class SmartwaiverGuardian(
    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val relationship: String? = null,
    val phone: String? = null,
)

@Serializable
data class SmartwaiverCheckin(
    val checkinId: Long,
    val waiverId: String,
    val date: String,
    val dateSigned: String? = null,
    val position: Int? = null,
    val templateId: String? = null,
    val isMinor: Int? = null,
)

@Serializable
data class SmartwaiverCheckinPage(
    val checkins: List<SmartwaiverCheckin> = emptyList(),
    val moreCheckins: Boolean = false,
)

@Serializable
data class SmartwaiverCheckinsResponse(
    val version: Int? = null,
    val id: String? = null,
    val ts: String? = null,
    val type: String? = null,
    val checkins: SmartwaiverCheckinPage? = null,
)

@Serializable
data class SmartwaiverFullWaiverResponse(
    val version: Int? = null,
    val id: String? = null,
    val ts: String? = null,
    val type: String? = null,
    val waiver: SmartwaiverFullWaiver? = null,
)

@Serializable
data class SmartwaiverPhotoEntry(
    val type: String? = null,
    val date: String? = null,
    val tag: String? = null,
    val fileType: String? = null,
    val photoId: String? = null,
    val photo: String? = null, // base64 JPEG, no data-URI prefix
)

@Serializable
data class SmartwaiverWaiverPhotos(
    val waiverId: String? = null,
    val templateId: String? = null,
    val createdOn: String? = null,
    val title: String? = null,
    val photos: List<SmartwaiverPhotoEntry> = emptyList(),
)

@Serializable
data class SmartwaiverPhotosResponse(
    val version: Int? = null,
    val id: String? = null,
    val ts: String? = null,
    val type: String? = null,
    val photos: SmartwaiverWaiverPhotos? = null,
)
