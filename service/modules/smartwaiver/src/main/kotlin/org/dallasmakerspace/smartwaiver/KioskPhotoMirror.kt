package org.dallasmakerspace.smartwaiver

import io.ktor.util.logging.Logger
import java.util.Base64
import org.dallasmakerspace.storage.NamespacedObjectStore
import org.dallasmakerspace.storage.ObjectKey

private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")
private val SAFE_FILE_TYPE = Regex("[a-z0-9]{1,10}")

/** What copying one waiver's kiosk photos produced. [created] is what to undo if the DB write fails. */
data class PhotoMirrorResult(
    val photos: List<MirroredPhoto>,
    val status: String,
    val created: List<ObjectKey>,
)

/**
 * Copies a waiver's kiosk photos from SmartWaiver into object storage. Keys are relative to the
 * store's namespace: `{waiverId}/photos/{photoId}.{ext}`. Objects that already exist are left alone,
 * so a re-run costs a HEAD per photo and no uploads.
 */
class KioskPhotoMirror(
    private val client: ISmartwaiverApiClient,
    private val store: NamespacedObjectStore,
    private val log: Logger,
) {
  @Suppress("TooGenericExceptionCaught")
  suspend fun mirror(waiver: SmartwaiverFullWaiver): PhotoMirrorResult {
    // The waiver says how many photos it has; skip the call when it says none.
    if (waiver.photos == 0) return PhotoMirrorResult(emptyList(), SmartWaiverMirrorRepository.PHOTOS_NONE, emptyList())
    val entries = client.getWaiverPhotos(waiver.waiverId)
    if (entries.isEmpty()) return PhotoMirrorResult(emptyList(), SmartWaiverMirrorRepository.PHOTOS_NONE, emptyList())

    val photos = mutableListOf<MirroredPhoto>()
    val created = mutableListOf<ObjectKey>()
    var skipped = 0
    for (entry in entries) {
      val photoId = entry.photoId
      val data = entry.photo
      val fileType = (entry.fileType ?: "jpg").lowercase()
      if (photoId == null || data.isNullOrBlank() || !SAFE_ID.matches(photoId) || !SAFE_FILE_TYPE.matches(fileType)) {
        log.warn("Skipping unusable photo entry on waiver ${waiver.waiverId}")
        skipped++
        continue
      }
      val bytes =
          try {
            Base64.getMimeDecoder().decode(data)
          } catch (e: IllegalArgumentException) {
            log.warn("Photo $photoId on waiver ${waiver.waiverId} is not valid base64: ${e.message}")
            skipped++
            continue
          }
      val key = ObjectKey("${waiver.waiverId}/photos/$photoId.$fileType")
      if (store.head(key) == null) {
        store.put(key, bytes, contentTypeFor(fileType), mapOf("photo-id" to photoId))
        created += key
      }
      photos +=
          MirroredPhoto(
              photoId = photoId,
              type = entry.type,
              tag = entry.tag,
              fileType = fileType,
              takenAt = entry.date,
              s3Key = key.value,
              sizeBytes = bytes.size,
          )
    }
    val status =
        if (skipped == 0) SmartWaiverMirrorRepository.PHOTOS_COMPLETE
        else SmartWaiverMirrorRepository.PHOTOS_PARTIAL
    return PhotoMirrorResult(photos, status, created)
  }

  private fun contentTypeFor(fileType: String) =
      when (fileType) {
        "jpg",
        "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
      }
}
