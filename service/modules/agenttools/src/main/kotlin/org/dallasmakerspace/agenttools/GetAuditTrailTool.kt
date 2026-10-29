package org.dallasmakerspace.agenttools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.agent.AgentTool
import org.dallasmakerspace.agent.MemberAccess
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.models.ActivityLogEvent
import org.dallasmakerspace.models.ActivityLogSource
import org.dallasmakerspace.models.workflows.WorkflowRunDto
import org.dallasmakerspace.models.workflows.WorkflowSubject
import org.dallasmakerspace.workflows.RunFilter
import org.dallasmakerspace.workflows.WorkflowService

private const val DEFAULT_LIMIT = 30
private const val MAX_LIMIT = 100
private const val DEFAULT_DAYS = 90
private const val MAX_DAYS = 365
private const val ERROR_CHARS = 120
private const val NAME_CHARS = 80
private val USERNAME = Regex("[A-Za-z0-9._@-]{1,100}")
private val KINDS = setOf("groups", "activity", "workflows")

/** One line of the merged timeline. */
private data class AuditTimelineEntry(val at: Instant, val line: String)

/** A source that failed or was skipped, so the answer can say it is incomplete. */
private data class AuditFetched(val entries: List<AuditTimelineEntry>, val note: String? = null)

/**
 * Staff-only audit trail: group membership changes, activity log and workflow runs for a member,
 * or the membership history of a group, newest first.
 */
@Singleton
class GetAuditTrailTool
internal constructor(
    private val queries: AuditTrailQueries,
    private val workflows: WorkflowService,
    private val clock: Clock,
) : AgentTool {
  @Inject constructor(queries: AuditTrailQueries, workflows: WorkflowService) : this(queries, workflows, Clock.systemUTC())

  override val requiredMemberAccess = MemberAccess.ANY_MEMBER
  override val touchesPersonalInfo = true
  override val maxResultChars = 10_000

  override val descriptor =
      ToolDescriptor(
          "get_audit_trail",
          "Staff audit trail, newest first, times in Central. For a member (username): who added or " +
              "removed them from which groups, their activity log (links, badge fixes, group " +
              "events) and workflow runs they started. For a group: who was added or removed, and " +
              "by whom. Use it to answer who changed what and when. Contact details are never shown " +
              "and badge numbers are masked.",
          requiredParameters = emptyList(),
          optionalParameters =
              listOf(
                  ToolParameterDescriptor("username", "Member to look up (required unless group is given)", ToolParameterType.String),
                  ToolParameterDescriptor("group", "Group name; shows that group's membership history instead (add username to narrow it to one member)", ToolParameterType.String),
                  ToolParameterDescriptor("kind", "Only one source: groups, activity or workflows", ToolParameterType.String),
                  ToolParameterDescriptor("days", "How far back, default $DEFAULT_DAYS, at most $MAX_DAYS", ToolParameterType.Integer),
                  ToolParameterDescriptor("limit", "Entries to return, default $DEFAULT_LIMIT, at most $MAX_LIMIT", ToolParameterType.Integer)))

  @Suppress("ReturnCount", "LongMethod")
  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    if (context.memberAccess != MemberAccess.ANY_MEMBER) {
      return ToolResult("The audit trail is only available to staff assistants.", isError = true)
    }
    val usernameArg = args.string("username")?.trim()?.ifEmpty { null }
    val group = args.string("group")?.trim()?.ifEmpty { null }
    if (usernameArg == null && group == null) {
      return ToolResult("Give a username, or a group name.", isError = true)
    }
    val member =
        if (usernameArg == null) null
        else context.subjectFor(usernameArg) ?: return ToolResult("That member cannot be looked up.", isError = true)
    if (member != null && !USERNAME.matches(member)) return ToolResult("Invalid username.", isError = true)
    if (group != null && (group.length > MAX_GROUP_CHARS || group.any { it.isISOControl() })) {
      return ToolResult("Invalid group name.", isError = true)
    }
    val kind = args.string("kind")?.trim()?.lowercase()?.ifEmpty { null }
    if (kind != null && kind !in KINDS) return ToolResult("kind must be groups, activity or workflows.", isError = true)
    val days = (args.int("days") ?: DEFAULT_DAYS).coerceIn(1, MAX_DAYS)
    val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    val now = clock.instant()
    val since = now.minus(Duration.ofDays(days.toLong()))
    val sinceUtc = LocalDateTime.ofInstant(since, ZoneOffset.UTC)
    val groupMode = group != null

    val (parts, known) =
        coroutineScope {
          val groups =
              async { if (kind in listOf(null, "groups")) fetch("group changes") { groupLines(member, group, sinceUtc, limit) } else null }
          val activity =
              async {
                if (!groupMode && kind in listOf(null, "activity")) fetch("activity log") { activityLines(member!!, sinceUtc, limit) } else null
              }
          val runs =
              async {
                if (kind in listOf(null, "workflows")) fetch("workflow runs") { runLines(member, group, since, limit) } else null
              }
          val profile = async { if (!groupMode && member != null) runCatching { queries.hasProfile(member) }.getOrNull() else null }
          listOf(groups.await(), activity.await(), runs.await()) to profile.await()
        }

    val fetched = parts.filterNotNull()
    val all = fetched.flatMap { it.entries }.sortedByDescending { it.at }
    val shown = all.take(limit)
    val subject = if (groupMode) "group \"${AuditTextSanitizer.oneLine(group!!, NAME_CHARS)}\"" + (member?.let { " (member $it)" } ?: "") else member
    val out = StringBuilder("Audit trail for $subject, last $days days, newest first")
    out.append(if (all.size > shown.size) " (showing ${shown.size} of ${all.size}):\n" else " (${shown.size} entries):\n")
    if (shown.isEmpty()) {
      out.append(
          if (known == false) "No portal profile exists for this username (never used the portal, or misspelled) and nothing was recorded.\n"
          else "Nothing was recorded in this period.\n")
    }
    shown.forEach { out.append("- ").append(it.line).append('\n') }
    fetched.mapNotNull { it.note }.forEach { out.append("Note: ").append(it).append('\n') }
    return ToolResult(out.toString().trimEnd())
  }

  private suspend fun groupLines(member: String?, group: String?, sinceUtc: LocalDateTime, limit: Int) =
      queries.groupChanges(member, group, sinceUtc, limit).map { row ->
        val verb = if (row.added) "ADDED" else "REMOVED"
        val by = if (row.actor.equals(row.member, ignoreCase = true)) "self-service" else "by ${row.actor}"
        val text =
            if (group != null) "[group] ${AuditTextSanitizer.central(row.atUtc)} ${row.member} $verb ($by)"
            else "[group] ${AuditTextSanitizer.central(row.atUtc)} $verb ${if (row.added) "to" else "from"} \"${AuditTextSanitizer.oneLine(row.group, NAME_CHARS)}\" ($by)"
        AuditTimelineEntry(row.atUtc.toInstant(ZoneOffset.UTC), text)
      }

  private suspend fun activityLines(member: String, sinceUtc: LocalDateTime, limit: Int) =
      queries.activity(member, sinceUtc, limit).mapNotNull { row ->
        val at = row.atUtc ?: return@mapNotNull null
        val event = ActivityLogEvent.entries.firstOrNull { it.value == row.event }?.name ?: "EVENT_${row.event}"
        val source = ActivityLogSource.entries.firstOrNull { it.value == row.source }?.name ?: "UNKNOWN"
        val by = row.actor?.let { if (it.equals(member, ignoreCase = true)) "self" else "by $it" } ?: "system"
        val attrs = AuditTextSanitizer.attributes(row.attributes)?.let { " {$it}" }.orEmpty()
        AuditTimelineEntry(at.toInstant(ZoneOffset.UTC), "[activity] ${AuditTextSanitizer.central(at)} $event ($by, $source)$attrs")
      }

  private suspend fun runLines(member: String?, group: String?, since: Instant, limit: Int) =
      workflows
          .listRuns(
              if (group != null) RunFilter(subject = WorkflowSubject("group", group), limit = limit)
              else RunFilter(actor = member, limit = limit))
          .mapNotNull { run -> runEntry(run, since) }

  private fun runEntry(run: WorkflowRunDto, since: Instant): AuditTimelineEntry? {
    val created = runCatching { Instant.parse(run.createdAt) }.getOrNull() ?: return null
    if (created.isBefore(since)) return null
    val finished = run.finishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val step = if (run.stepCount > 0) ", step ${(run.currentStepIndex + 1).coerceAtMost(run.stepCount)} of ${run.stepCount}" else ""
    val end = finished?.let { ", finished ${AuditTextSanitizer.central(it)}" }.orEmpty()
    val error = run.errorSummary?.takeIf { it.isNotBlank() }?.let { "; error: ${AuditTextSanitizer.scrub(it, ERROR_CHARS)}" }.orEmpty()
    val subjects = run.subjects.joinToString(", ") { "${AuditTextSanitizer.oneLine(it.type, NAME_CHARS)}:${AuditTextSanitizer.oneLine(it.key, NAME_CHARS)}" }
    val line =
        "[workflow] ${AuditTextSanitizer.central(created)} run #${run.id} ${AuditTextSanitizer.oneLine(run.workflowId, NAME_CHARS)} " +
            "${run.status} (started by ${run.actor}$step$end)" +
            (if (subjects.isNotEmpty()) " on $subjects" else "") + error
    return AuditTimelineEntry(created, line)
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun fetch(label: String, block: suspend () -> List<AuditTimelineEntry>): AuditFetched =
      try {
        AuditFetched(block())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        AuditFetched(emptyList(), "$label could not be read, so this trail is incomplete.")
      }

  private companion object {
    const val MAX_GROUP_CHARS = 255
  }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int? = string(name)?.trim()?.toIntOrNull()
