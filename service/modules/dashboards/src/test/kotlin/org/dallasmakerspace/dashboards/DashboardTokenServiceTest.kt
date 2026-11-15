package org.dallasmakerspace.dashboards

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardTokenServiceTest {
  private val start = Instant.parse("2026-01-01T00:00:00Z")
  private val secret = "test-secret-0123456789"

  private fun service(secretValue: String = secret, clock: Clock = Clock.fixed(start, ZoneOffset.UTC)) =
      DashboardTokenService({ secretValue }, clock)

  @Test
  fun `a token it issued verifies and carries its claims`() {
    val svc = service()
    val token = svc.issue(targetId = 3, committeeId = 9, generation = 2)

    val claims = assertNotNull(svc.verify(token.token))
    assertEquals(DashboardTokenClaims(3, 9, 2, token.expiresAtMs), claims)
    assertEquals(start.toEpochMilli() + DashboardTokenService.TOKEN_LIFETIME_MS, token.expiresAtMs)
  }

  @Test
  fun `a token is rejected once it expires`() {
    val token = service().issue(1, 1, 1)
    val later = Clock.fixed(start.plus(Duration.ofHours(25)), ZoneOffset.UTC)

    assertNull(service(clock = later).verify(token.token))
  }

  @Test
  fun `a token signed with another secret is rejected`() {
    val token = service().issue(1, 1, 1)

    assertNull(service(secretValue = "another-secret-0123456789").verify(token.token))
  }

  @Test
  fun `a tampered payload is rejected`() {
    val token = service().issue(1, 1, 1).token
    val forged = service().issue(1, 2, 1).token.substringBefore('.') + "." + token.substringAfter('.')

    assertNull(service().verify(forged))
  }

  @Test
  fun `garbage is rejected`() {
    val svc = service()
    listOf(null, "", "abc", "a.b", "a.b.c", "....").forEach { assertNull(svc.verify(it), "for $it") }
  }

  @Test
  fun `without a secret nothing verifies and issuing fails`() {
    val svc = service(secretValue = "short")

    assertFalse(svc.isConfigured)
    assertNull(svc.verify("a.b"))
    runCatching { svc.issue(1, 1, 1) }.let { assertTrue(it.isFailure) }
  }
}
