package org.dallasmakerspace.discourse

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscourseAdminUserTest {
  private val now = Instant.parse("2026-01-01T00:00:00Z")
  private val active = DiscourseAdminUser(id = 5, username = "talk-user", active = true)

  @Test
  fun `parses a list page and ignores unknown fields`() {
    val body =
        """
        [
          {"id": 5, "username": "talk-user1", "email": "user1@example.invalid", "active": true,
           "staged": false, "silenced": false, "trust_level": 1, "admin": false},
          {"id": 6, "username": "talk-user2", "active": false, "suspended_till": null}
        ]
        """

    val users = parseAdminUsers(body)

    assertEquals(2, users.size)
    assertEquals("user1@example.invalid", users[0].email)
    assertTrue(users[0].active)
    assertNull(users[1].email)
    assertFalse(users[1].active)
  }

  @Test
  fun `an empty page parses to an empty list`() {
    assertTrue(parseAdminUsers("[]").isEmpty())
  }

  @Test
  fun `active unrestricted accounts are eligible`() {
    assertTrue(active.isEligible(now))
  }

  @Test
  fun `system accounts, inactive and staged accounts are not eligible`() {
    assertFalse(active.copy(id = -1).isEligible(now))
    assertFalse(active.copy(id = 0).isEligible(now))
    assertFalse(active.copy(active = false).isEligible(now))
    assertFalse(active.copy(staged = true).isEligible(now))
  }

  @Test
  fun `suspended and silenced accounts are not eligible until the restriction ends`() {
    assertFalse(active.copy(suspendedTill = "2026-06-01T00:00:00.000Z").isEligible(now))
    assertTrue(active.copy(suspendedTill = "2025-06-01T00:00:00.000Z").isEligible(now))
    assertFalse(active.copy(suspendedAt = "2025-12-01T00:00:00.000Z").isEligible(now))
    assertFalse(active.copy(silenced = true).isEligible(now))
    assertFalse(active.copy(silencedTill = "2026-06-01T00:00:00.000Z").isEligible(now))
  }

  @Test
  fun `an unreadable restriction date counts as restricted`() {
    assertFalse(active.copy(suspendedTill = "not-a-date").isEligible(now))
  }

  @Test
  fun `toString leaves the email out`() {
    val user = active.copy(email = "user1@example.invalid")

    assertFalse(user.toString().contains("example.invalid"))
    assertTrue(user.toString().contains("talk-user"))
  }
}
