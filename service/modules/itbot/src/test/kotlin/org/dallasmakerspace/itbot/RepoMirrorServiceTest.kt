package org.dallasmakerspace.itbot

import java.time.Instant
import kotlin.test.assertEquals
import org.junit.Test

class RepoMirrorServiceTest {
  @Test
  fun `only name=github https lines are accepted and duplicates are dropped`() {
    val repos =
        RepoMirrorService.parseRepos(
            listOf(
                    "# comment",
                    "demo=https://github.com/example-org/demo.git",
                    "demo=https://github.com/example-org/other.git",
                    "other=https://github.com/example-org/other",
                    "",
                    "bad name=https://github.com/example-org/x.git",
                    "ssh=git@github.com:example-org/x.git",
                    "http=http://github.com/example-org/x.git",
                    "local=file:///tmp/x",
                    "host=https://evil.example.com/example-org/x.git",
                )
                .joinToString("\n"))

    assertEquals(listOf("demo", "other"), repos.map { it.name })
    assertEquals("https://github.com/example-org/demo", repos.first().webUrl)
  }

  @Test
  fun `the next sync is at 4am Central`() {
    // 09:00 UTC in January is 03:00 Central, one hour before the sync
    assertEquals(HOUR, RepoMirrorService.millisUntilNextSync(Instant.parse("2026-01-15T09:00:00Z")))
    // 05:00 Central: the next one is tomorrow
    assertEquals(23 * HOUR, RepoMirrorService.millisUntilNextSync(Instant.parse("2026-01-15T11:00:00Z")))
  }

  private companion object {
    const val HOUR = 60 * 60 * 1000L
  }
}
