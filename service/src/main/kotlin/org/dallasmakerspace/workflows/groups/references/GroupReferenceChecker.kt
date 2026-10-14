package org.dallasmakerspace.workflows.groups.references

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val FINDER_TIMEOUT = 30.seconds

private val STANDING_NOTES =
    listOf(
        "Access that Active Directory group membership grants to systems outside the portal " +
            "(door controllers, file shares, other applications) is not detected.",
    )

/**
 * Read-only: reports where the portal still depends on a group. A finder that fails or times out
 * is reported as a blocking UNKNOWN reference, so a failed lookup can never green-light a delete.
 */
@Singleton
class GroupReferenceChecker
internal constructor(
    private val finders: Set<GroupReferenceFinder>,
    private val timeout: Duration,
) {
  @Inject
  constructor(
      finders: Set<@JvmSuppressWildcards GroupReferenceFinder>
  ) : this(finders, FINDER_TIMEOUT)

  private val log = LoggerFactory.getLogger(GroupReferenceChecker::class.java)

  suspend fun check(groupName: String): GroupReferences {
    require(groupName.isNotBlank()) { "groupName is required" }
    val name = groupName.trim()
    val ordered = finders.sortedBy { it.system }
    val results = coroutineScope { ordered.map { async { runFinder(it, name) } }.awaitAll() }
    return GroupReferences(
        blocking = results.flatten().sortedWith(compareBy({ it.system }, { it.kind })),
        notes = (STANDING_NOTES + ordered.flatMap { it.notes }).distinct(),
    )
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun runFinder(finder: GroupReferenceFinder, name: String): List<GroupReference> {
    val outcome =
        try {
          withTimeoutOrNull(timeout) { finder.find(name) }?.let { Result.success(it) }
              ?: Result.failure(TimeoutException())
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
    return outcome.getOrElse { error ->
      log.warn("Group reference finder '{}' failed: {}", finder.system, error::class.simpleName)
      val reason = error::class.simpleName ?: "error"
      listOf(
          GroupReference(
              system = finder.system,
              kind = GroupReferenceKinds.UNKNOWN,
              count = 1,
              detail = "could not check ${finder.system}: $reason"))
    }
  }

  private class TimeoutException : RuntimeException()
}
