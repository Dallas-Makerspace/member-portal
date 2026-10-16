package org.dallasmakerspace.workflows

import io.ktor.http.encodeURLParameter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.activedirectory.AmbiguousGroupException
import org.dallasmakerspace.activedirectory.GroupNotFoundException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.models.DirectoryOus
import org.slf4j.LoggerFactory

/**
 * [WorkflowAuthorization.GROUP_ADMIN]: archiving must be enabled for the actor (the
 * `groups.archive.enabled` flag), the group named by the input's "groupName" must be the only group
 * of that name and sit inside the main group OU, and the actor must be one of its listed
 * administrators (which includes Domain Admins, as AD lists them) or an Infrastructure member.
 * Every denial carries the same generic reason, so a caller cannot learn whether a group exists.
 */
@Singleton
class GroupAdminAuthorizer
internal constructor(
    private val activeDirectory: IActiveDirectoryService,
    private val isFeatureEnabled: suspend (String) -> Boolean,
    private val isInfrastructure: suspend (String) -> Boolean,
    private val isGroupAdmin: suspend (String, String) -> Boolean,
    private val directoryDispatcher: CoroutineDispatcher,
) : WorkflowStartAuthorizer {

  @Inject
  constructor(
      activeDirectory: IActiveDirectoryService,
      memberService: MemberService,
      featureFlagService: FeatureFlagService,
  ) : this(
      activeDirectory,
      { username ->
        featureFlagService.isEnabledForUsername(ConfigRegistry.GROUPS_ARCHIVE_ENABLED, username)
      },
      { username -> withContext(Dispatchers.IO) { memberService.isUserInInfraGroup(username) } },
      { username, slug -> memberService.isGroupAdmin(username, slug) },
      Dispatchers.IO,
  )

  private val log = LoggerFactory.getLogger(GroupAdminAuthorizer::class.java)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun authorize(
      workflowId: String,
      input: JsonObject,
      actor: String,
  ): AuthorizationDecision {
    val groupName = (input["groupName"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    if (groupName.isNullOrEmpty() || actor.isBlank()) return DENIED
    return try {
      if (isAllowed(groupName, actor.trim())) AuthorizationDecision.Allowed else DENIED
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.warn("Group admin check for {} failed ({}); denying", workflowId, e::class.simpleName)
      DENIED
    }
  }

  private suspend fun isAllowed(groupName: String, actor: String): Boolean {
    if (!isFeatureEnabled(actor)) return false
    val group =
        try {
          withContext(directoryDispatcher) { activeDirectory.getGroupStrict(groupName) }
        } catch (e: GroupNotFoundException) {
          return false
        } catch (e: AmbiguousGroupException) {
          return false
        }
    if (!DirectoryOus.isInMainGroupOu(group.distinguishedName)) return false
    return isInfrastructure(actor) || isGroupAdmin(actor, group.cn.encodeURLParameter())
  }

  private companion object {
    val DENIED =
        AuthorizationDecision.Denied("You are not allowed to run this workflow for that group")
  }
}
