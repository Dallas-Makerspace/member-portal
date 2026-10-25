package org.dallasmakerspace.smartwaiver

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

private val UTC_ZONE = ZoneId.of("UTC")
private val CHICAGO_ZONE = ZoneId.of("America/Chicago")
private val DATE_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

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

  private suspend fun waiversInChicagoDays(from: LocalDate, to: LocalDate): List<LocalWaiver> =
      repository.waiversBetween(
          from.atStartOfDay(CHICAGO_ZONE).withZoneSameInstant(UTC_ZONE).toLocalDateTime(),
          to.plusDays(1).atStartOfDay(CHICAGO_ZONE).withZoneSameInstant(UTC_ZONE).toLocalDateTime(),
      )

  /** A stored UTC time as Chicago time in the same text format; unreadable text is left alone. */
  private fun chicagoText(utcText: String): String =
      parseApiDateTime(utcText)
          ?.atZone(UTC_ZONE)
          ?.withZoneSameInstant(CHICAGO_ZONE)
          ?.format(DATE_TEXT) ?: utcText

  suspend fun useLocal(source: String? = null): Boolean =
      when (source?.lowercase()) {
        "local" -> true
        "api" -> false
        else -> configOverrideService.get(ConfigRegistry.SMARTWAIVER_LOCAL_READS_ENABLED)
      }

  /**
   * Signing times (Chicago) for the weekly counts, for the Chicago days [from] through [to]; rows
   * with an unreadable date are skipped.
   */
  suspend fun signingData(from: LocalDate, to: LocalDate): List<WaiverSigningData> =
      waiversInChicagoDays(from, to).mapNotNull {
        val utc = parseApiDateTime(it.createdOn) ?: return@mapNotNull null
        val date = utc.atZone(UTC_ZONE).withZoneSameInstant(CHICAGO_ZONE).toLocalDateTime()
        WaiverSigningData(date = date, dayOfWeek = date.dayOfWeek.value, waiverId = it.waiverId)
      }

  /** Summaries keep `createdOn` in UTC, as the API returns it; the report converts for display. */
  suspend fun summaries(from: LocalDate, to: LocalDate): List<SmartwaiverSummary> =
      waiversInChicagoDays(from, to).map {
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
                  dateCompleted = chicagoText(waiver.createdOn),
                  photos = photos,
                  emailMatches = waiver.email?.equals(memberEmail, ignoreCase = true) == true,
              )
            }
          }
          .awaitAll()
    }
  }
}
