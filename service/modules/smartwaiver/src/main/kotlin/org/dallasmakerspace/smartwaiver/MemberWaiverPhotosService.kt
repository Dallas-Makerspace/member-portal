package org.dallasmakerspace.smartwaiver

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.db.master.GenericRepository
import org.dallasmakerspace.models.MemberWaiverPhotos
import org.dallasmakerspace.models.WaiverPhoto

// Bounds concurrent Smartwaiver API calls for members with a long signing history.
private const val MAX_WAIVERS_FOR_PHOTOS = 15

private data class MemberIdentity(val email: String, val firstName: String, val lastName: String)

@Singleton
class MemberWaiverPhotosService
@Inject
constructor(
    private val genericRepository: GenericRepository,
    private val smartwaiverApiClient: ISmartwaiverApiClient,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  suspend fun getKioskPhotosForMember(username: String): List<MemberWaiverPhotos> {
    val t0 = System.currentTimeMillis()
    log.info("getKioskPhotosForMember starting: resolving identity via DB")
    val identity = resolveIdentity(username)
    log.info(
        "resolveIdentity took ${System.currentTimeMillis() - t0}ms, found=${identity != null}")
    if (identity == null) return emptyList()

    // Search by name rather than email: Smartwaiver's `email` search parameter was empirically
    // found to miss real, existing waivers that `firstName`/`lastName` search finds correctly (see
    // ISmartwaiverApiClient.getWaiversByName kdoc) — so we search by name for full historical
    // coverage. We do NOT filter out results whose email doesn't match the member's on-file
    // email — members frequently sign with an older/different email than what's currently on
    // their DMS account, so an email mismatch alone isn't proof it's a different person. Instead
    // each result is tagged with whether its email matched, so an admin can judge for themselves.
    val t1 = System.currentTimeMillis()
    val waivers =
        try {
          smartwaiverApiClient.getWaiversByName(identity.firstName, identity.lastName)
        } catch (e: SmartwaiverApiException) {
          log.warn("Failed to search waivers by name", e)
          emptyList()
        }
    log.info("getWaiversByName took ${System.currentTimeMillis() - t1}ms, found=${waivers.size}")
    val cappedWaivers = waivers.sortedByDescending { it.createdOn }.take(MAX_WAIVERS_FOR_PHOTOS)

    val t2 = System.currentTimeMillis()
    val result =
        coroutineScope {
          cappedWaivers
              .map { waiver ->
                async {
                  val photos =
                      try {
                        smartwaiverApiClient.getWaiverPhotos(waiver.waiverId)
                      } catch (e: SmartwaiverApiException) {
                        log.warn("Failed to fetch photos for waiver ${waiver.waiverId}", e)
                        emptyList()
                      }
                  MemberWaiverPhotos(
                      waiverId = waiver.waiverId,
                      firstName = waiver.firstName,
                      lastName = waiver.lastName,
                      dateCompleted = waiver.createdOn,
                      photos =
                          photos.map {
                            WaiverPhoto(
                                photoId = it.photoId,
                                tag = it.tag,
                                fileType = it.fileType,
                                dataBase64 = it.photo,
                            )
                          },
                      emailMatches = waiver.email?.equals(identity.email, ignoreCase = true) == true,
                  )
                }
              }
              .awaitAll()
        }
    log.info(
        "getWaiverPhotos (${cappedWaivers.size} waivers, parallel) took " +
            "${System.currentTimeMillis() - t2}ms")
    return result
  }

  private suspend fun resolveIdentity(username: String): MemberIdentity? {
    val escaped = username.replace("'", "''")
    val result =
        genericRepository.getReportData(
            """
            SELECT LOWER(email) AS email, first_name, last_name
            FROM `dms-makermanager`.users
            WHERE LOWER(username) = LOWER('$escaped')
            """
                .trimIndent())
    val row = result?.data?.firstOrNull()
    val email = row?.get("email")?.toString()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
    val firstName =
        row?.get("first_name")?.toString()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
    val lastName =
        row?.get("last_name")?.toString()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
    return if (email != null && firstName != null && lastName != null) {
      MemberIdentity(email, firstName, lastName)
    } else {
      null
    }
  }
}
