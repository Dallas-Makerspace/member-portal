package org.dallasmakerspace.server.reports

import io.ktor.http.encodeURLPathPart
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.dallasmakerspace.models.ArchivedGroup
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.Committees

/** One table row of the Archived Groups report. [downloadUrl] is null unless the viewer is infra. */
data class ArchivedGroupRow(
    val name: String,
    val archivedAt: String,
    val archivedBy: String,
    val reason: String,
    val downloadUrl: String?,
)

/** Model for the Archived Groups report and the committee page button. */
object ArchivedGroupsReport {
  const val PARENT_SLUG = "it"
  const val SLUG = "archived-groups"
  const val PATH = "/reports/$PARENT_SLUG/$SLUG"
  const val COMMITTEE_LOOKUP_TIMEOUT_MS = 3_000L

  private val CHICAGO = ZoneId.of("America/Chicago")
  private val DISPLAY = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a z", Locale.US)

  fun committeeUrl(committee: Committee) =
      "$PATH?committee=${URLEncoder.encode(committee.slug, "UTF-8")}"

  /** UTC ISO instant to Central Time for display; unparseable input is shown as given. */
  fun formatArchivedAt(iso: String): String {
    val instant =
        try {
          Instant.parse(iso)
        } catch (_: Exception) {
          try {
            OffsetDateTime.parse(iso).toInstant()
          } catch (_: Exception) {
            return iso
          }
        }
    return DISPLAY.format(instant.atZone(CHICAGO))
  }

  fun downloadUrl(group: ArchivedGroup): String? {
    val runId = group.runId ?: return null
    val step = group.artifactStepIndex ?: return null
    val artifact = group.artifactName ?: return null
    return "/api/workflows/runs/$runId/steps/$step/artifacts/${artifact.encodeURLPathPart()}/download"
  }

  /**
   * Template model: archivedRows, archivedShowDownload, plus archivedCommittee when a known
   * committee filter is applied, archivedCommitteeSlug when an unknown one is, archivedLoadFailed
   * when the service call fails. Only non-null values are added.
   */
  suspend fun reportModel(
      committeeSlug: String?,
      canDownload: Boolean,
      fetch: suspend (List<String>) -> List<ArchivedGroup>,
      onFailure: (Exception) -> Unit = {},
  ): Map<String, Any> {
    val slug = committeeSlug?.takeIf { it.isNotBlank() }
    val model = mutableMapOf<String, Any>("archivedShowDownload" to canDownload)
    var groups = emptyList<ArchivedGroup>()
    if (slug == null) {
      groups = fetchOrFail(emptyList(), fetch, onFailure, model)
    } else {
      val committee = Committees.findBySlug(slug)
      if (committee == null) {
        model["archivedCommitteeSlug"] = slug
      } else {
        model["archivedCommittee"] = committee
        if (committee.groupPrefixes.isNotEmpty()) {
          groups = fetchOrFail(committee.groupPrefixes, fetch, onFailure, model)
        }
      }
    }
    model["archivedRows"] =
        groups.map {
          ArchivedGroupRow(
              name = it.name,
              archivedAt = formatArchivedAt(it.archivedAt),
              archivedBy = it.archivedBy,
              reason = it.reason,
              downloadUrl = if (canDownload) downloadUrl(it) else null,
          )
        }
    return model
  }

  private suspend fun fetchOrFail(
      prefixes: List<String>,
      fetch: suspend (List<String>) -> List<ArchivedGroup>,
      onFailure: (Exception) -> Unit,
      model: MutableMap<String, Any>,
  ): List<ArchivedGroup> =
      try {
        fetch(prefixes)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        onFailure(e)
        model["archivedLoadFailed"] = true
        emptyList()
      }

  /** Archived group count for the committee page button; 0 on no prefixes, failure or timeout. */
  suspend fun committeeCount(
      committee: Committee,
      fetch: suspend (List<String>) -> List<ArchivedGroup>,
      onFailure: (String) -> Unit,
      timeoutMs: Long = COMMITTEE_LOOKUP_TIMEOUT_MS,
  ): Int {
    if (committee.groupPrefixes.isEmpty()) return 0
    return try {
      val groups = withTimeoutOrNull(timeoutMs) { fetch(committee.groupPrefixes) }
      if (groups == null) onFailure("timed out")
      groups?.size ?: 0
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      onFailure(e.message ?: e.javaClass.simpleName)
      0
    }
  }
}
