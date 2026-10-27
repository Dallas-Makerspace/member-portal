package org.dallasmakerspace.itbot.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.itbot.GitRunner
import org.dallasmakerspace.itbot.RepoMirrorService
import org.dallasmakerspace.itbot.RepoSpec
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** Runs the tools against a real throwaway git repository. */
class CodeToolsTest {
  private val spec = RepoSpec("demo", "https://github.com/example-org/demo.git")
  private lateinit var dir: Path
  private val git = GitRunner()
  private val context = ToolContext("jdoe", "c1", "it-assistant")

  private fun sh(vararg args: String) {
    val process =
        ProcessBuilder(
                listOf("git", "-c", "user.name=Tess Tester", "-c", "user.email=tess@example.org") + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
    process.inputStream.readBytes()
    check(process.waitFor() == 0) { "git ${args.toList()} failed" }
  }

  @Before
  fun setUp() {
    assumeTrue(
        "git is not installed",
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false))
    dir = Files.createTempDirectory("itbot-demo")
    sh("init", "-q")
    Files.createDirectories(dir.resolve("src"))
    Files.writeString(
        dir.resolve("src/App.kt"), "fun main() {\n  println(\"hello widget\")\n  retryUpload()\n}\n")
    Files.writeString(dir.resolve(".env"), "API_KEY=supersecretwidget\n")
    Files.writeString(dir.resolve("README.md"), "Demo project\n")
    sh("add", "-f", ".")
    sh("commit", "-q", "-m", "Initial commit")
    Files.writeString(
        dir.resolve("src/App.kt"), "fun main() {\n  println(\"hello widget\")\n  retryUpload(3)\n}\n")
    sh("commit", "-q", "-am", "Retry uploads three times")
  }

  private val mirror =
      mock<RepoMirrorService> {
        onBlocking { repos() } doReturn listOf(spec)
        on { dirOf(spec) } doAnswer { dir }
      }
  private val repos = RepoAccess(mirror)

  private fun args(vararg pairs: Pair<String, String>) =
      buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

  @Test
  fun `search_code finds matches with links and never reads secret files`() = runBlocking {
    val tool = SearchCodeTool(repos, git)

    val found = tool.run(args("pattern" to "widget"), context)
    assertFalse(found.isError, found.output)
    assertTrue(found.output.contains("demo:src/App.kt:2:"), found.output)
    assertFalse(found.output.contains("supersecretwidget"))
    assertEquals("https://github.com/example-org/demo/blob/HEAD/src/App.kt#L2", found.sources.single().url)

    assertTrue(tool.run(args("pattern" to "supersecret"), context).output.startsWith("No matches"))
    assertTrue(tool.run(args("pattern" to "nothing-here-xyz"), context).output.startsWith("No matches"))
    assertTrue(tool.run(args("pattern" to "widget", "repo" to "missing"), context).isError)
  }

  @Test
  fun `read_file numbers lines and refuses secrets and parent paths`() = runBlocking {
    val tool = ReadFileTool(repos, git)

    val page =
        tool.run(args("repo" to "demo", "path" to "src/App.kt", "startLine" to "2", "endLine" to "3"), context)
    assertFalse(page.isError, page.output)
    assertTrue(page.output.contains("2:   println(\"hello widget\")"), page.output)
    assertTrue(page.output.contains("3:   retryUpload(3)"))

    assertTrue(tool.run(args("repo" to "demo", "path" to ".env"), context).isError)
    assertTrue(tool.run(args("repo" to "demo", "path" to "../outside"), context).isError)
    assertTrue(tool.run(args("repo" to "demo", "path" to "src/Missing.kt"), context).isError)
  }

  @Test
  fun `list_files shows one directory level`() = runBlocking {
    val tool = ListFilesTool(repos, git)

    val top = tool.run(args("repo" to "demo"), context).output.lines()
    assertTrue("src/" in top, top.toString())
    assertTrue("README.md" in top)
    assertFalse(".env" in top)
    assertEquals(listOf("App.kt"), tool.run(args("repo" to "demo", "path" to "src"), context).output.lines())
  }

  @Test
  fun `git_log, git_show and git_blame explain history`() = runBlocking {
    val log = GitLogTool(repos, git).run(args("repo" to "demo", "path" to "src/App.kt"), context)
    assertTrue(log.output.contains("Retry uploads three times"), log.output)
    assertTrue(log.output.contains("Tess Tester"))
    assertFalse(log.output.contains("example.org"))

    val show = GitShowTool(repos, git).run(args("repo" to "demo", "rev" to "HEAD"), context)
    assertTrue(show.output.contains("+  retryUpload(3)"), show.output)
    assertFalse(show.output.contains("supersecret"))

    val blame =
        GitBlameTool(repos, git)
            .run(args("repo" to "demo", "path" to "src/App.kt", "startLine" to "3", "endLine" to "3"), context)
    assertTrue(blame.output.contains("retryUpload(3)"), blame.output)

    assertTrue(GitShowTool(repos, git).run(args("repo" to "demo", "rev" to "--output=/tmp/x"), context).isError)
  }
}
