package org.dallasmakerspace.bambuddy.notifications.db

import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DeadlockRetryTest {

  private val deadlock = SQLException("Deadlock found when trying to get lock", "40001", 1213)

  @Test
  fun `recognises deadlocks by sqlstate or MariaDB code, including wrapped causes`() {
    assertTrue(deadlock.isDeadlock())
    assertTrue(SQLException("x", "HY000", 1213).isDeadlock())
    assertTrue(SQLException("x", "40001", 0).isDeadlock())
    assertTrue(RuntimeException("wrapped", deadlock).isDeadlock())
    assertFalse(SQLException("dup", "23000", 1062).isDeadlock())
    assertFalse(IllegalStateException("nope").isDeadlock())
  }

  @Test
  fun `a deadlock is retried once and the retry result is returned`() = runBlocking {
    var calls = 0
    val result = retryOnceOnDeadlock {
      calls++
      if (calls == 1) throw RuntimeException("rolled back", deadlock)
      "ok"
    }
    assertEquals("ok", result)
    assertEquals(2, calls)
  }

  @Test
  fun `a second deadlock is rethrown`() = runBlocking {
    var calls = 0
    assertFailsWith<SQLException> {
      retryOnceOnDeadlock<String> {
        calls++
        throw deadlock
      }
    }
    assertEquals(2, calls)
  }

  @Test
  fun `non-deadlock failures are not retried`() = runBlocking {
    var calls = 0
    assertFailsWith<IllegalStateException> {
      retryOnceOnDeadlock<String> {
        calls++
        throw IllegalStateException("boom")
      }
    }
    assertEquals(1, calls)
  }
}
