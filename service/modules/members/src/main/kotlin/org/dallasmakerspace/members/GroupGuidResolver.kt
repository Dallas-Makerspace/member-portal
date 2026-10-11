package org.dallasmakerspace.members

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.dallasmakerspace.activedirectory.AdGuid
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.slf4j.LoggerFactory

data class ResolvedGroup(val guid: String, val dn: String)

private const val LOOKUP_TIMEOUT_MS = 1_500L
private const val POSITIVE_TTL_MS = 60_000L
private const val NEGATIVE_TTL_MS = 30_000L

/**
 * Best-effort name to objectGUID lookup in AD for the webhook path. Never throws; a slow, failing
 * or empty lookup resolves to null. A lookup that outlives the timeout keeps running and fills the
 * cache for the next call.
 */
@Singleton
class GroupGuidResolver
internal constructor(
    private val activeDirectoryService: IActiveDirectoryService,
    private val timeoutMs: Long,
    private val positiveTtlMs: Long,
    private val negativeTtlMs: Long,
    private val nowMs: () -> Long,
    private val scope: CoroutineScope,
) {
  @Inject
  constructor(
      activeDirectoryService: IActiveDirectoryService
  ) : this(
      activeDirectoryService,
      LOOKUP_TIMEOUT_MS,
      POSITIVE_TTL_MS,
      NEGATIVE_TTL_MS,
      System::currentTimeMillis,
      CoroutineScope(SupervisorJob() + Dispatchers.IO))

  private class Entry(val value: ResolvedGroup?, val expiresAt: Long)

  private val logger = LoggerFactory.getLogger(GroupGuidResolver::class.java)
  private val cache = ConcurrentHashMap<String, Entry>()
  private val inFlight = ConcurrentHashMap<String, Deferred<ResolvedGroup?>>()

  suspend fun resolve(name: String): ResolvedGroup? {
    val key = name.trim().lowercase()
    cache[key]?.let { if (it.expiresAt > nowMs()) return it.value }
    val flight =
        inFlight.computeIfAbsent(key) {
          scope.async(start = CoroutineStart.LAZY) { lookup(key, name) }
        }
    flight.start()
    return withTimeoutOrNull(timeoutMs) { flight.await() }
  }

  /** Drops the cached answer for [name], e.g. right after a workflow renamed or deleted it. */
  fun invalidate(name: String) {
    cache.remove(name.trim().lowercase())
  }

  @Suppress("TooGenericExceptionCaught")
  private fun lookup(key: String, name: String): ResolvedGroup? {
    var result: ResolvedGroup? = null
    try {
      val group = activeDirectoryService.getGroup(name)
      val guid = AdGuid.normalize(group.objectGuid)
      if (guid != null) result = ResolvedGroup(guid, group.distinguishedName)
    } catch (e: Exception) {
      logger.debug("AD lookup for group GUID failed: {}", e.javaClass.simpleName)
    }
    val ttl = if (result != null) positiveTtlMs else negativeTtlMs
    cache[key] = Entry(result, nowMs() + ttl)
    inFlight.remove(key)
    return result
  }
}
