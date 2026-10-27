package org.dallasmakerspace.itbot.tools

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class RepoAccessTest {
  @Test
  fun `paths are normalized and secrets, parents and options are refused`() {
    assertEquals("src/main/App.kt", RepoAccess.cleanPath("/src/main/App.kt/"))
    assertEquals("", RepoAccess.cleanPath(""))
    assertEquals("", RepoAccess.cleanPath(null))
    listOf(
            "../etc/passwd", "a/../b", "-rf", ".git/config", "app/.env", ".env.local",
            "keys/server.pem", "id_rsa", "a\\b", "a;b")
        .forEach { assertNull(RepoAccess.cleanPath(it), it) }
  }

  @Test
  fun `revisions cannot be options`() {
    assertEquals("a1b2c3d", RepoAccess.cleanRev("a1b2c3d"))
    assertEquals("HEAD~3", RepoAccess.cleanRev("HEAD~3"))
    assertEquals("origin/main", RepoAccess.cleanRev("origin/main"))
    listOf("--output=/tmp/x", "-1", "a b", "a;b", "").forEach { assertNull(RepoAccess.cleanRev(it), it) }
  }

  @Test
  fun `globs allow wildcards but not options or parents`() {
    assertEquals("*.kt", RepoAccess.cleanPathspec("*.kt"))
    listOf("-x", "../x", "a b", ":(top)x", "").forEach { assertNull(RepoAccess.cleanPathspec(it), it) }
  }
}
