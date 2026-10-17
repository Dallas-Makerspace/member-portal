package org.dallasmakerspace.smartwaiver

import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.storage.IObjectStore
import org.dallasmakerspace.storage.NamespacedObjectStore
import org.dallasmakerspace.storage.StorageNamespace

data class MirrorOutcome(val waiverId: String, val photos: Int, val photosStatus: String)

/**
 * The one way a waiver gets mirrored: the webhook queue, the backfill and repairs all call
 * [mirrorWaiver]. It is idempotent, so any of them can run it again for the same waiver.
 */
@Singleton
class SmartWaiverMirrorService
@Inject
constructor(
    private val client: ISmartwaiverApiClient,
    private val repository: SmartWaiverMirrorRepository,
    @Named(SMARTWAIVER_STORE) objectStore: IObjectStore,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val store = NamespacedObjectStore(objectStore, StorageNamespace.SMARTWAIVER)
  private val photoMirror = KioskPhotoMirror(client, store, log)

  /** Fetches the waiver and its photos, stores the photos, then records everything in one transaction. */
  @Suppress("TooGenericExceptionCaught")
  suspend fun mirrorWaiver(waiverId: String): MirrorOutcome {
    val waiver = client.getWaiver(waiverId)
    val photos = photoMirror.mirror(waiver)
    try {
      repository.saveMirror(waiver, photos.photos, photos.status)
    } catch (e: Exception) {
      // Only undo what this call uploaded; objects from an earlier run belong to a recorded mirror.
      photos.created.forEach { key ->
        try {
          store.delete(key)
        } catch (cleanup: Exception) {
          log.warn("Could not remove orphaned object $key: ${cleanup.message}")
        }
      }
      throw e
    }
    return MirrorOutcome(waiver.waiverId, photos.photos.size, photos.status)
  }
}
