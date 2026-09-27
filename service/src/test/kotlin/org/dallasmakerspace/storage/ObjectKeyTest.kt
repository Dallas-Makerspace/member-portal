package org.dallasmakerspace.storage

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class ObjectKeyTest {

  @Test
  fun `accepts a normal key`() {
    assertEquals("foo/bar.jpg", ObjectKey("foo/bar.jpg").value)
  }

  @Test
  fun `rejects blank`() {
    assertFailsWith<IllegalArgumentException> { ObjectKey("") }
  }

  @Test
  fun `rejects path traversal`() {
    assertFailsWith<IllegalArgumentException> { ObjectKey("foo/../../etc/passwd") }
  }

  @Test
  fun `rejects a leading slash`() {
    assertFailsWith<IllegalArgumentException> { ObjectKey("/foo/bar") }
  }

  @Test
  fun `rejects control characters`() {
    assertFailsWith<IllegalArgumentException> { ObjectKey("foo\nbar") }
  }

  @Test
  fun `rejects an overlong key`() {
    assertFailsWith<IllegalArgumentException> { ObjectKey("a".repeat(901)) }
  }

  @Test
  fun `plus joins with a slash`() {
    assertEquals("profile-photos/jdoe.jpg", (ObjectKey("profile-photos") + "jdoe.jpg").value)
  }
}
