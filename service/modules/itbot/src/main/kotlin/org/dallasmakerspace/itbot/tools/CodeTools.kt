package org.dallasmakerspace.itbot.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.agent.AgentTool
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.itbot.GitResult
import org.dallasmakerspace.itbot.GitRunner
import org.dallasmakerspace.itbot.RepoMirrorService
import org.dallasmakerspace.models.agent.AgentSourceLink

private const val CODE_SOURCE = "Code"
private const val MAX_MATCH_LINES = 60
private const val MAX_LINE_CHARS = 300
private const val MAX_SOURCES = 5
private const val DEFAULT_READ_LINES = 250
private const val MAX_READ_LINES = 500
private const val MAX_LIST_ENTRIES = 200
private const val DEFAULT_LOG_COMMITS = 15
private const val MAX_LOG_COMMITS = 40
private const val MAX_BLAME_LINES = 120
private const val SYNC_COOLDOWN_MINUTES = 5L

private fun text(name: String, description: String) =
    ToolParameterDescriptor(name, description, ToolParameterType.String)

private fun number(name: String, description: String) =
    ToolParameterDescriptor(name, description, ToolParameterType.Integer)

private fun GitResult.failure(what: String): ToolResult =
    ToolResult(
        if (timedOut) "$what timed out." else "$what failed: ${output.trim().take(200).ifEmpty { "no details" }}",
        isError = true)

private fun String.clip(): String = if (length > MAX_LINE_CHARS) take(MAX_LINE_CHARS) + "…" else this

private const val NO_SUCH_REPO = "Missing or unknown repo."

/** Searches file contents across the downloaded repositories with `git grep`. */
@Singleton
class SearchCodeTool @Inject constructor(private val repos: RepoAccess, private val git: GitRunner) :
    AgentTool {
  override val descriptor =
      ToolDescriptor(
          "search_code",
          "Search file contents in the Dallas Makerspace code repositories (regular expression, " +
              "case-insensitive). Returns file:line:text matches. Use it to find where a message, " +
              "setting, class or function is defined or used.",
          requiredParameters = listOf(text("pattern", "Regular expression or plain text to find")),
          optionalParameters =
              listOf(
                  text("repo", "Limit to one repository; default searches all"),
                  text("files", "Limit to files matching a glob such as *.kt or *.php")))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val pattern = args.string("pattern")?.trim().orEmpty()
    if (pattern.isEmpty() || pattern.length > 200) return ToolResult("Give a pattern of 1-200 characters.", isError = true)
    val files = args.string("files")?.let { RepoAccess.cleanPathspec(it) ?: return ToolResult("Invalid files glob.", isError = true) }
    val targets =
        args.string("repo")?.let { name ->
          val (target, error) = repos.target(name)
          if (target == null) return ToolResult(error ?: NO_SUCH_REPO, isError = true)
          listOf(target)
        } ?: repos.all()
    if (targets.isEmpty()) return ToolResult("No repositories are downloaded yet. Try again in a minute.", isError = true)

    val lines = mutableListOf<String>()
    val sources = linkedMapOf<String, AgentSourceLink>()
    for (target in targets) {
      val pathspec = listOfNotNull(files) + RepoAccess.EXCLUDE_SECRETS
      val result =
          git.run(
              target.dir, "grep", "-n", "-I", "-i", "-E", "--no-color", "-e", pattern, "HEAD", "--",
              *pathspec.toTypedArray())
      if (result.exitCode == 1 && result.output.isBlank()) continue
      if (!result.ok) return result.failure("Search in ${target.spec.name}")
      for (raw in result.output.lineSequence().filter { it.isNotBlank() }) {
        if (lines.size >= MAX_MATCH_LINES) break
        // "HEAD:path:line:text"
        val parts = raw.removePrefix("HEAD:").split(':', limit = 3)
        if (parts.size < 3) continue
        val path = parts[0]
        val lineNo = parts[1].toIntOrNull()
        lines += "${target.spec.name}:$path:${parts[1]}:${parts[2].trim().clip()}"
        if (sources.size < MAX_SOURCES && path !in sources) {
          sources.putIfAbsent(
              "${target.spec.name}/$path",
              AgentSourceLink("${target.spec.name}/$path", target.link(path, lineNo), CODE_SOURCE))
        }
      }
    }
    if (lines.isEmpty()) return ToolResult("No matches for \"$pattern\".")
    val note = if (lines.size >= MAX_MATCH_LINES) "\n(Showing the first $MAX_MATCH_LINES matches; narrow the search.)" else ""
    val links = sources.values.joinToString("\n") { "- ${it.title}: ${it.url}" }
    return ToolResult(lines.joinToString("\n") + note + "\n\nLinks:\n" + links, sources.values.toList())
  }
}

/** Lists one directory of a repository. */
@Singleton
class ListFilesTool @Inject constructor(private val repos: RepoAccess, private val git: GitRunner) :
    AgentTool {
  override val descriptor =
      ToolDescriptor(
          "list_files",
          "List the files and folders in a directory of a repository (folders end with /). Leave " +
              "path empty for the top level.",
          requiredParameters = listOf(text("repo", "Repository name")),
          optionalParameters = listOf(text("path", "Directory inside the repository")))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val (target, error) = repos.target(args.string("repo"))
    if (target == null) return ToolResult(error ?: NO_SUCH_REPO, isError = true)
    val path = RepoAccess.cleanPath(args.string("path")) ?: return ToolResult("Invalid path.", isError = true)
    val spec = if (path.isEmpty()) emptyArray() else arrayOf("--", "$path/")
    val result = git.run(target.dir, "ls-tree", "HEAD", *spec)
    if (!result.ok) return result.failure("Listing")
    val entries =
        result.output.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
          val (meta, name) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
          val isDir = meta.contains(" tree ")
          val shown = name.removePrefix(if (path.isEmpty()) "" else "$path/")
          if (RepoAccess.cleanPath(name) == null) null else if (isDir) "$shown/" else shown
        }.toList()
    if (entries.isEmpty()) return ToolResult("Nothing found at \"$path\".")
    val shown = entries.take(MAX_LIST_ENTRIES)
    val more = if (entries.size > shown.size) "\n(${entries.size - shown.size} more not shown)" else ""
    return ToolResult(shown.joinToString("\n") + more)
  }
}

/** Reads a range of lines from one file. */
@Singleton
class ReadFileTool @Inject constructor(private val repos: RepoAccess, private val git: GitRunner) :
    AgentTool {
  override val descriptor =
      ToolDescriptor(
          "read_file",
          "Read a file from a repository with line numbers. Returns up to $MAX_READ_LINES lines; " +
              "use startLine and endLine to page through long files.",
          requiredParameters = listOf(text("repo", "Repository name"), text("path", "File path")),
          optionalParameters =
              listOf(number("startLine", "First line, default 1"), number("endLine", "Last line")))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val (target, error) = repos.target(args.string("repo"))
    if (target == null) return ToolResult(error ?: NO_SUCH_REPO, isError = true)
    val path = RepoAccess.cleanPath(args.string("path"))?.ifEmpty { null } ?: return ToolResult("Invalid path.", isError = true)
    val start = (args.int("startLine") ?: 1).coerceAtLeast(1)
    val end = (args.int("endLine") ?: (start + DEFAULT_READ_LINES - 1)).coerceIn(start, start + MAX_READ_LINES - 1)
    val result = git.run(target.dir, "show", "HEAD:$path", maxChars = MAX_FILE_CHARS)
    if (!result.ok) return result.failure("Reading $path")
    if (result.output.contains(' ')) return ToolResult("$path is a binary file.", isError = true)
    val all = result.output.lines()
    if (start > all.size) return ToolResult("$path has only ${all.size} lines.", isError = true)
    val slice = all.subList(start - 1, minOf(end, all.size))
    val body = slice.mapIndexed { i, line -> "${start + i}: ${line.clip()}" }.joinToString("\n")
    val footer = if (end < all.size) "\n(File continues; ${all.size} lines in total.)" else ""
    return ToolResult(
        "${target.spec.name}/$path lines $start-${start + slice.size - 1} (link: ${target.link(path, start)})\n$body$footer",
        listOf(AgentSourceLink("${target.spec.name}/$path", target.link(path, start), CODE_SOURCE)))
  }

  private companion object {
    const val MAX_FILE_CHARS = 400_000
  }
}

/** Recent commits, optionally for one path. */
@Singleton
class GitLogTool @Inject constructor(private val repos: RepoAccess, private val git: GitRunner) :
    AgentTool {
  override val descriptor =
      ToolDescriptor(
          "git_log",
          "List recent commits (hash, date, author, subject), newest first, optionally only those " +
              "touching one path or since a date such as 2026-09-01 or \"2 weeks ago\".",
          requiredParameters = listOf(text("repo", "Repository name")),
          optionalParameters =
              listOf(
                  text("path", "Only commits touching this file or directory"),
                  text("since", "Only commits after this date"),
                  number("count", "How many commits, default $DEFAULT_LOG_COMMITS, at most $MAX_LOG_COMMITS")))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val (target, error) = repos.target(args.string("repo"))
    if (target == null) return ToolResult(error ?: NO_SUCH_REPO, isError = true)
    val path = RepoAccess.cleanPath(args.string("path")) ?: return ToolResult("Invalid path.", isError = true)
    val count = (args.int("count") ?: DEFAULT_LOG_COMMITS).coerceIn(1, MAX_LOG_COMMITS)
    val since = args.string("since")?.trim()?.takeIf { it.isNotEmpty() }
    if (since != null && !SINCE.matches(since)) return ToolResult("Invalid since value.", isError = true)
    val command =
        mutableListOf("log", "-n", count.toString(), "--date=short", "--format=%h %ad %an: %s")
    since?.let { command += "--since=$it" }
    command += "--"
    if (path.isNotEmpty()) command += path
    val result = git.run(target.dir, *command.toTypedArray())
    if (!result.ok) return result.failure("Reading history")
    return ToolResult(result.output.trim().ifEmpty { "No commits found." })
  }

  private companion object {
    val SINCE = Regex("""[A-Za-z0-9 :.-]{1,40}""")
  }
}

/** One commit's message and changes. */
@Singleton
class GitShowTool @Inject constructor(private val repos: RepoAccess, private val git: GitRunner) :
    AgentTool {
  override val descriptor =
      ToolDescriptor(
          "git_show",
          "Show one commit: message and diff. Use a hash from git_log. Optionally limit the diff to " +
              "one path.",
          requiredParameters = listOf(text("repo", "Repository name"), text("rev", "Commit hash, tag or branch")),
          optionalParameters = listOf(text("path", "Only show changes to this path")))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val (target, error) = repos.target(args.string("repo"))
    if (target == null) return ToolResult(error ?: NO_SUCH_REPO, isError = true)
    val rev = RepoAccess.cleanRev(args.string("rev")) ?: return ToolResult("Invalid revision.", isError = true)
    val path = RepoAccess.cleanPath(args.string("path")) ?: return ToolResult("Invalid path.", isError = true)
    val command =
        mutableListOf("show", "--no-color", "--date=short", "--format=%h %ad %an%n%s%n%n%b", "--stat", "--patch", rev, "--")
    if (path.isNotEmpty()) command += path else command += RepoAccess.EXCLUDE_SECRETS
    val result = git.run(target.dir, *command.toTypedArray(), maxChars = MAX_DIFF_CHARS)
    if (!result.ok) return result.failure("Showing $rev")
    val note = if (result.truncated) "\n(Diff cut short; pass a path to see one file.)" else ""
    return ToolResult(result.output.trim() + note)
  }

  private companion object {
    const val MAX_DIFF_CHARS = 12_000
  }
}

/** Who last changed each line of a range. */
@Singleton
class GitBlameTool @Inject constructor(private val repos: RepoAccess, private val git: GitRunner) :
    AgentTool {
  override val descriptor =
      ToolDescriptor(
          "git_blame",
          "Show who last changed each line in a range of a file, with the commit and date. Up to " +
              "$MAX_BLAME_LINES lines.",
          requiredParameters =
              listOf(
                  text("repo", "Repository name"),
                  text("path", "File path"),
                  number("startLine", "First line"),
                  number("endLine", "Last line")))

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val (target, error) = repos.target(args.string("repo"))
    if (target == null) return ToolResult(error ?: NO_SUCH_REPO, isError = true)
    val path = RepoAccess.cleanPath(args.string("path"))?.ifEmpty { null } ?: return ToolResult("Invalid path.", isError = true)
    val start = args.int("startLine")?.takeIf { it >= 1 } ?: return ToolResult("Give a startLine.", isError = true)
    val end = (args.int("endLine") ?: start).coerceIn(start, start + MAX_BLAME_LINES - 1)
    val result = git.run(target.dir, "blame", "--date=short", "-L", "$start,$end", "HEAD", "--", path)
    if (!result.ok) return result.failure("Blame of $path")
    return ToolResult(
        result.output.lineSequence().joinToString("\n") { it.clip() }.trim(),
        listOf(AgentSourceLink("${target.spec.name}/$path", target.link(path, start), CODE_SOURCE)))
  }
}

/** Downloads the latest commits of the repositories, at most every few minutes. */
@Singleton
class SyncReposTool @Inject constructor(private val mirror: RepoMirrorService) : AgentTool {
  override val descriptor =
      ToolDescriptor(
          "sync_repos",
          "Download the latest code from GitHub. The repositories already refresh every night; use " +
              "this only when asked for the very latest or when a recent change is missing.",
          requiredParameters = emptyList(),
          optionalParameters = listOf(text("repo", "One repository; default all")))

  @Volatile private var lastSync: Instant = Instant.EPOCH

  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val ago = Duration.between(lastSync, Instant.now())
    if (ago.toMinutes() < SYNC_COOLDOWN_MINUTES) {
      return ToolResult("Code was refreshed ${ago.toMinutes()} minutes ago; that is recent enough.")
    }
    lastSync = Instant.now()
    mirror.awaitReady()
    val name = args.string("repo")?.trim()?.takeIf { it.isNotEmpty() }
    val results = if (name != null) listOf(mirror.sync(name)) else mirror.syncAll()
    return ToolResult(results.joinToString("\n"))
  }
}
