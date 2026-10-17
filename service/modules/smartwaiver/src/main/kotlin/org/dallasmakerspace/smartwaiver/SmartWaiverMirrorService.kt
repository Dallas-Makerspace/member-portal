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

  /**
   * Fetches the waiver and its photos, stores the photos, then records everything in one
   * transaction. If recording fails the uploaded photos are left in place rather than deleted: their
   * keys are fixed, so the next attempt finds and reuses them, whereas deleting could remove a photo
   * that another writer (the webhook, or a second container) has just recorded.
   */
  suspend fun mirrorWaiver(waiverId: String): MirrorOutcome {
    val waiver = client.getWaiver(waiverId)
    val photos = photoMirror.mirror(waiver)
    repository.saveMirror(waiver, photos.photos, photos.status)
    return MirrorOutcome(waiver.waiverId, photos.photos.size, photos.status)
  }
}
