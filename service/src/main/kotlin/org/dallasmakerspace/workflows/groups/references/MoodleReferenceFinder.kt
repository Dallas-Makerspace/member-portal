package org.dallasmakerspace.workflows.groups.references

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.moodle.IMoodleApiClient
import org.dallasmakerspace.moodle.adGroupName

/**
 * Moodle courses name the group they grant on completion in the course custom field `ad_group`
 * (local/addad plugin). That field comes back from the read-only course listing. Calls the client
 * directly: the cached catalog turns a failure into an empty list.
 */
@Singleton
class MoodleReferenceFinder @Inject constructor(private val moodle: IMoodleApiClient) :
    GroupReferenceFinder {
  override val system = "Moodle"

  override val notes =
      listOf(
          "Moodle chair-group mappings (local/addad plugin settings) cannot be read through the " +
              "Moodle web service. The committee chair and course-editor groups they use are " +
              "covered by the protected-group list instead.")

  override suspend fun find(groupName: String): List<GroupReference> {
    val courses = moodle.getCourses().filter { sameGroup(it.adGroupName(), groupName) }
    if (courses.isEmpty()) return emptyList()
    val names = courses.map { it.shortname.ifBlank { "course ${it.id}" } }
    val detail = "Course completion adds members to this group: ${summarize(names)}"
    return listOf(GroupReference(system, GroupReferenceKinds.COURSE, courses.size, detail))
  }
}
