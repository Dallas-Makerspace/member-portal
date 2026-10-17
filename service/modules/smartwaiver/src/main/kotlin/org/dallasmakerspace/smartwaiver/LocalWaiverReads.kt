package org.dallasmakerspace.smartwaiver

import java.time.LocalDate
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.MemberWaiverPhotos
import org.dallasmakerspace.models.WaiverPhoto
import org.dallasmakerspace.storage.IObjectStore
import org.dallasmakerspace.storage.NamespacedObjectStore
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.StorageNamespace

const val MAX_LOCAL_WAIVERS_FOR_PHOTOS = 15

/**
 * Reads SmartWaiver data from the local mirror instead of the live API. Callers ask [useLocal]
 * first: it follows `smartwaiver.local-reads.enabled`, and a request can force a side with
 * `source=local` or `source=api` to compare the two.
 */
@Singleton
class LocalWaiverReads
@Inject
constructor(
    private val repository: SmartWaiverMirrorRepository,
    private val configOverrideService: ConfigOverrideService,
    @Named(SMARTWAIVER_STORE) objectStore: IObjectStore,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val store = NamespacedObjectStore(objectStore, StorageNamespace.SMARTWAIVER)

  suspend fun useLocal(source: String? = null): Boolean =
      when (source?.lowercase()) {
        "local" -> true
        "api" -> false
        else -> configOverrideService.get(ConfigRegistry.SMARTWAIVER_LOCAL_READS_ENABLED)
      }

  /** Signing dates for the weekly counts; rows with an unreadable date are skipped. */
  suspend fun signingData(from: LocalDate, to: LocalDate): List<WaiverSigningData> =
      repository.waiversBetween(from, to).mapNotNull {
        val date = parseApiDateTime(it.createdOn) ?: return@mapNotNull null
        WaiverSigningData(date = date, dayOfWeek = date.dayOfWeek.value, waiverId = it.waiverId)
      }

  suspend fun summaries(from: LocalDate, to: LocalDate): List<SmartwaiverSummary> =
      repository.waiversBetween(from, to).map {
        SmartwaiverSummary(
            waiverId = it.waiverId,
            templateId = it.templateId.orEmpty(),
            title = it.title.orEmpty(),
            createdOn = it.createdOn,
            firstName = it.firstName,
            lastName = it.lastName,
            email = it.email,
        )
      }

  /**
   * Kiosk photos for a member's waivers, newest first, read from the mirror and S3. Same shape the
   * live path returns, so the UI doesn't change.
   */
  @Suppress("TooGenericExceptionCaught")
  suspend fun kioskPhotos(firstName: String, lastName: String, memberEmail: String): List<MemberWaiverPhotos> {
    val waivers = repository.waiversByName(firstName, lastName, MAX_LOCAL_WAIVERS_FOR_PHOTOS)
    return coroutineScope {
      waivers
          .map { waiver ->
            async {
              val photos =
                  repository.photosFor(waiver.waiverId).mapNotNull { row ->
                    try {
                      val content = store.get(ObjectKey(row.s3Key)) ?: return@mapNotNull null
                      WaiverPhoto(
                          photoId = row.photoId,
                          tag = row.tag,
                          fileType = row.fileType,
                          dataBase64 = Base64.getEncoder().encodeToString(content.bytes),
                      )
                    } catch (e: Exception) {
                      log.warn("Could not read mirrored photo ${row.photoId} of ${waiver.waiverId}: ${e.message}")
                      null
                    }
                  }
              MemberWaiverPhotos(
                  waiverId = waiver.waiverId,
                  firstName = waiver.firstName,
                  lastName = waiver.lastName,
                  dateCompleted = waiver.createdOn,
                  photos = photos,
                  emailMatches = waiver.email?.equals(memberEmail, ignoreCase = true) == true,
              )
            }
          }
          .awaitAll()
    }
  }
}
