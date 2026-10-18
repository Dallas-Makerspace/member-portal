package org.dallasmakerspace.webhook.handlers

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.slf4j.LoggerFactory

/** Drops committee followers who already hold the AD group a class or course grants. */
class CommitteeFollowerFilter @Inject constructor(private val activeDirectoryService: IActiveDirectoryService) {
  private val logger = LoggerFactory.getLogger(CommitteeFollowerFilter::class.java)

  /**
   * [members] minus those already in AD group [adGroup], by direct membership (how calendar and
   * Moodle assign it). If AD can't answer for a batch its members are kept: a repeat notice is
   * better than a missed one. Looks up 100 at a time, off the request dispatcher (LDAP blocks).
   */
  @Suppress("TooGenericExceptionCaught")
  suspend fun withoutGroup(members: List<String>, adGroup: String): List<String> =
      withContext(Dispatchers.IO) {
        members.chunked(AD_BATCH_SIZE).flatMap { batch ->
          try {
            val users = activeDirectoryService.getMembersByUsernameList(batch)
            batch.filter { name ->
              users[name]?.groups?.none { it.cn.equals(adGroup, ignoreCase = true) } ?: true
            }
          } catch (e: Exception) {
            logger.warn("AD lookup failed for ${batch.size} committee followers, keeping them", e)
            batch
          }
        }
      }

  private companion object {
    const val AD_BATCH_SIZE = 100
  }
}
