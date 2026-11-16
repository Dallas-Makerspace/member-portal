package org.dallasmakerspace.dashboards

import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.MemberService
import org.dallasmakerspace.members.ProfilePhotoService
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.models.normalizeAvatarUrl

/** Who appears on a member-list card: picking from a group, looking people up, and their photos. */
@Singleton
class DashboardMembers
@Inject
constructor(
    private val activeDirectoryService: IActiveDirectoryService,
    private val memberService: MemberService,
    private val profilePhotos: ProfilePhotoService,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val rows = TtlCache<String, List<MemberRow>>(ROW_TTL)

  /** The enabled members of one of [committee]'s groups; refuses a group too big for a card. */
  suspend fun groupMembers(committee: Committee, group: String): List<String> {
    if (!committee.ownsGroup(group)) {
      throw DashboardException("'$group' is not one of ${committee.name}'s groups")
    }
    val members =
        withContext(Dispatchers.IO) { activeDirectoryService.getGroup(group).members }
            .filter { it.enabled }
            .map { it.sAMAccountName }
    if (members.size > MAX_GROUP_MEMBERS_TO_ADD) {
      throw DashboardException(
          "'$group' has ${members.size} members. We cannot add too many members to the card " +
              "(groups of up to $MAX_GROUP_MEMBERS_TO_ADD only); add people one by one instead.")
    }
    if (members.isEmpty()) throw DashboardException("'$group' has no members to add")
    return members
  }

  /** The usernames that are real, enabled members, in the order given; used to check a pick. */
  suspend fun existing(usernames: List<String>): List<String> {
    val found = memberService.getMembersByUsernameList(usernames)
    return usernames.filter { found[it]?.enabled == true }
  }

  /** What the TV shows for [usernames]; people who no longer exist or are disabled are left out. */
  @Suppress("TooGenericExceptionCaught")
  suspend fun rows(usernames: List<String>): List<MemberRow> =
      try {
        rows.getOrLoad(usernames.joinToString(",")) {
          val found = memberService.getMembersByUsernameList(usernames)
          usernames.mapNotNull { username ->
            found[username]?.takeIf { it.enabled }?.let {
              MemberRow(
                  username = username,
                  displayName = it.displayName?.takeIf { name -> name.isNotBlank() } ?: username,
                  photoUrl =
                      if (it.profilePhotoUrl == null) {
                        normalizeAvatarUrl(it.discourseAvatarUrl, PHOTO_SIZE).ifEmpty { null }
                      } else {
                        null
                      },
                  hasPhoto = it.profilePhotoUrl != null)
            }
          }
        }
      } catch (e: Exception) {
        log.warn("Could not load the members for a dashboard list", e)
        emptyList()
      }

  /** An uploaded photo, only for someone who is on one of the committee's member lists. */
  suspend fun photo(allowed: Set<String>, username: String): Pair<ByteArray, String>? =
      if (username in allowed) profilePhotos.get(username) else null

  private companion object {
    val ROW_TTL: Duration = Duration.ofMinutes(30)
    const val PHOTO_SIZE = 240
  }
}
