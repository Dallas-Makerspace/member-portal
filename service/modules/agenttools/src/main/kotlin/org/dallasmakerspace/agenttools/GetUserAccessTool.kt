package org.dallasmakerspace.agenttools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.activedirectory.ADException
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.agent.AgentTool
import org.dallasmakerspace.agent.MemberAccess
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.models.agent.AgentSourceLink
import org.dallasmakerspace.moodle.LearnCourse
import org.dallasmakerspace.moodle.LearnMemberCourse
import org.dallasmakerspace.moodle.MoodleCatalogService
import org.dallasmakerspace.moodle.MoodleMemberCoursesService

private const val MAX_GROUPS = 60
private const val MAX_COURSES = 30
private const val RESULT_CHARS = 6_000
private const val DATE_LENGTH = 8
private const val LEARN = "DMS Learn"

/**
 * A member's access picture: account state, badge on file, AD groups and DMS Learn completions,
 * flagging completed courses whose group the member lacks. Three concurrent reads, no MakerManager.
 */
@Singleton
class GetUserAccessTool
@Inject
constructor(
    private val directory: IActiveDirectoryService,
    private val memberCourses: MoodleMemberCoursesService,
    private val catalog: MoodleCatalogService,
) : AgentTool {
  private val usernameParam =
      ToolParameterDescriptor(
          "username", "Member username, e.g. jdoe. Omit to look up the person asking.", ToolParameterType.String)

  override val descriptor =
      ToolDescriptor(
          "get_user_access",
          "Show what a member can access: whether their account is active, whether a badge is on " +
              "file (last 4 digits only), when they joined, their Active Directory groups, and their " +
              "DMS Learn course completions with the group each course grants, flagging a completed " +
              "course whose group they are missing.",
          requiredParameters = emptyList(),
          optionalParameters = listOf(usernameParam))

  private val selfOnlyDescriptor =
      ToolDescriptor(
          "get_user_access",
          "Show your own access: whether your account is active, whether a badge is on file, when " +
              "you joined, your Active Directory groups, and your DMS Learn course completions with " +
              "the group each course grants, flagging a completed course whose group you are missing.",
          requiredParameters = emptyList(),
          optionalParameters = emptyList())

  override fun descriptorFor(access: MemberAccess) =
      if (access == MemberAccess.ANY_MEMBER) descriptor else selfOnlyDescriptor

  override val touchesPersonalInfo = true

  override val maxResultChars = RESULT_CHARS

  private class Loaded(val user: ADUser, val courses: List<LearnMemberCourse>, val all: List<LearnCourse>)

  @Suppress("TooGenericExceptionCaught")
  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val requested = args["username"]?.jsonPrimitive?.contentOrNull
    val asked =
        context.subjectFor(requested)
            ?: return ToolResult(UserToolSupport.SELF_ONLY_REFUSAL, isError = true)
    val subject = if (asked.equals(context.username, ignoreCase = true)) context.username else asked
    if (!UserToolSupport.isValidUsername(subject)) {
      return ToolResult("That is not a valid username.", isError = true)
    }
    val loaded =
        try {
          load(subject)
        } catch (e: CancellationException) {
          throw e
        } catch (e: ADException) {
          return ToolResult("No member with that username was found in the directory.", isError = true)
        } catch (e: Exception) {
          return ToolResult("The directory could not be reached right now.", isError = true)
        }
    return render(loaded, context.memberAccess)
  }

  private suspend fun load(subject: String): Loaded = coroutineScope {
    val user = async(Dispatchers.IO) { findUser(subject) }
    val courses = async { memberCourses.getMemberCourses(subject) }
    val all = async { catalog.getAllCourses() }
    val found =
        try {
          user.await()
        } catch (e: Exception) {
          courses.cancel()
          all.cancel()
          throw e
        }
    Loaded(found, courses.await(), all.await())
  }

  /** The directory matches the name case-sensitively when reading results; usernames are lowercase. */
  private fun findUser(name: String): ADUser =
      try {
        directory.getMemberByUsername(name)
      } catch (e: ADException) {
        if (name == name.lowercase()) throw e else directory.getMemberByUsername(name.lowercase())
      }

  private fun render(loaded: Loaded, access: MemberAccess): ToolResult {
    val user = loaded.user
    val username = UserToolSupport.clean(user.sAMAccountName) ?: "unknown"
    val name = UserToolSupport.clean(user.displayName) ?: username
    val lines = mutableListOf("Member: $name ($username)")
    lines += "Account: " + if (user.enabled) "enabled (active)" else "disabled (not active)"
    lines += badgeLine(user.employeeID, access)
    createdDate(user.whenCreated)?.let { lines += "Member since: $it (directory account created)" }

    val groupNames =
        user.groups.mapNotNull { UserToolSupport.clean(it.cn) }.distinct().sortedBy { it.lowercase() }
    lines += groupsLine(groupNames)
    lines += coursesSection(loaded, groupNames)

    val links =
        loaded.courses.filter { it.completed }.mapNotNull { c -> loaded.all.firstOrNull { it.id == c.courseId } }
            .take(MAX_COURSES)
            .map { AgentSourceLink(UserToolSupport.clean(it.name) ?: "Course", it.url, LEARN) }
    return ToolResult(lines.joinToString("\n"), listOf(AgentSourceLink(name, "/profile/@$username", "Member portal")) + links)
  }

  private fun badgeLine(badge: String?, access: MemberAccess): String =
      when {
        !UserToolSupport.hasBadge(badge) -> "Badge: none on file"
        access == MemberAccess.ANY_MEMBER -> "Badge: on file (${UserToolSupport.maskBadge(badge!!.trim())})"
        else -> "Badge: on file"
      }

  private fun createdDate(whenCreated: String?): String? {
    val raw = whenCreated?.trim().orEmpty()
    if (raw.length < DATE_LENGTH || !raw.take(DATE_LENGTH).all { it.isDigit() }) return null
    return "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, DATE_LENGTH)}"
  }

  private fun groupsLine(names: List<String>): String {
    if (names.isEmpty()) return "AD groups: none (direct memberships only)"
    val shown = names.take(MAX_GROUPS).joinToString("; ")
    val more = if (names.size > MAX_GROUPS) "; and ${names.size - MAX_GROUPS} more" else ""
    return "AD groups (${names.size}, direct memberships only): $shown$more"
  }

  private fun coursesSection(loaded: Loaded, groupNames: List<String>): String {
    if (loaded.courses.isEmpty()) {
      return "DMS Learn: no course enrolments found (never signed in to DMS Learn, or it could not be reached)."
    }
    val byId = loaded.all.associateBy { it.id }
    val held = groupNames.map { it.lowercase() }.toSet()
    val done = loaded.courses.count { it.completed }
    val sorted = loaded.courses.sortedWith(compareByDescending<LearnMemberCourse> { it.completed }.thenBy { it.courseId })
    val rows =
        sorted.take(MAX_COURSES).map { c ->
          val course = byId[c.courseId]
          val title = UserToolSupport.clean(course?.name) ?: "Course #${c.courseId}"
          val group = UserToolSupport.clean(course?.adGroup)
          val grant =
              when {
                group == null -> ""
                c.completed && group.lowercase() in held -> " -> grants group \"$group\" (member has it)"
                c.completed -> " -> grants group \"$group\" (MEMBER IS MISSING THIS GROUP)"
                else -> " -> grants group \"$group\" on completion"
              }
          val state = if (c.completed) "completed" else "in progress" + (c.progress?.let { " $it%" } ?: "")
          "- $title: $state$grant"
        }
    val more = if (sorted.size > MAX_COURSES) "\n(${sorted.size - MAX_COURSES} more not shown)" else ""
    return "DMS Learn: ${loaded.courses.size} enrolled, $done completed\n" + rows.joinToString("\n") + more
  }
}
