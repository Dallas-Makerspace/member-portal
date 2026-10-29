package org.dallasmakerspace.agenttools

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.GroupService

private const val TTL_MS = 60 * 60 * 1000L
private const val RETRY_AFTER_FAILURE_MS = 60 * 1000L

/** AD group names, cached for an hour: [GroupService.getAllGroups] is a full LDAP search. */
@Singleton
class AuthorizationGroupDirectory
@Inject
constructor(private val groups: GroupService, loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  internal var nowMs: () -> Long = System::currentTimeMillis

  private val lock = Mutex()
  @Volatile private var names: List<String>? = null
  @Volatile private var loadedAt = 0L
  @Volatile private var failedAt = 0L

  /** Group names, or null when AD has never been readable. A failed refresh keeps the last list. */
  @Suppress("TooGenericExceptionCaught")
  suspend fun groupNames(): List<String>? {
    if (fresh()) return names
    return lock.withLock {
      val now = nowMs()
      if (fresh()) {
        names
      } else if (names == null && failedAt != 0L && now - failedAt < RETRY_AFTER_FAILURE_MS) {
        null
      } else {
        try {
          names = groups.getAllGroups().map { it.name }.distinct()
          loadedAt = now
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          failedAt = now
          log.warn("Could not read AD group names for the authorization tool", e)
        }
        names
      }
    }
  }

  private fun fresh() = names != null && nowMs() - loadedAt < TTL_MS
}
