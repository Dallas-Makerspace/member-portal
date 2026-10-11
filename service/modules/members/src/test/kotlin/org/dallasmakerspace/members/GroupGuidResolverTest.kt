package org.dallasmakerspace.members

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADException
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GroupGuidResolverTest {
  private val guid = "33221100-5544-7766-8899-aabbccddeeff"
  private val dn = "CN=Team A,OU=Test,DC=example,DC=test"
  private var now = 0L
  private val ad: IActiveDirectoryService = mock()

  private fun adGroup(objectGuid: String?) =
      ADGroup("Team A", null, dn, objectGuid, emptyList(), false)

  private fun resolver(timeoutMs: Long = 2_000) =
      GroupGuidResolver(
          ad, timeoutMs, 60_000, 30_000, { now }, CoroutineScope(SupervisorJob() + Dispatchers.IO))

  @Test
  fun `resolves guid and dn and normalizes the guid`() = runBlocking {
    whenever(ad.getGroup(any())).thenReturn(adGroup(guid.uppercase()))
    assertEquals(ResolvedGroup(guid, dn), resolver().resolve("Team A"))
  }

  @Test
  fun `positive answers are cached until the ttl expires`() = runBlocking {
    val calls = AtomicInteger()
    whenever(ad.getGroup(any())).thenAnswer {
      calls.incrementAndGet()
      adGroup(guid)
    }
    val r = resolver()
    r.resolve("Team A")
    r.resolve("team a")
    assertEquals(1, calls.get())
    now += 61_000
    r.resolve("Team A")
    assertEquals(2, calls.get())
  }

  @Test
  fun `not found is negatively cached`() = runBlocking {
    val calls = AtomicInteger()
    whenever(ad.getGroup(any())).thenAnswer {
      calls.incrementAndGet()
      throw ADException("not found")
    }
    val r = resolver()
    assertNull(r.resolve("Team A"))
    assertNull(r.resolve("Team A"))
    assertEquals(1, calls.get())
    now += 31_000
    assertNull(r.resolve("Team A"))
    assertEquals(2, calls.get())
  }

  @Test
  fun `malformed guid resolves to null`() = runBlocking {
    whenever(ad.getGroup(any())).thenReturn(adGroup("mock-guid"))
    assertNull(resolver().resolve("Team A"))
  }

  @Test
  fun `slow lookup times out, then the late answer is served from cache`() = runBlocking {
    val calls = AtomicInteger()
    whenever(ad.getGroup(any())).thenAnswer {
      calls.incrementAndGet()
      Thread.sleep(400)
      adGroup(guid)
    }
    val r = resolver(timeoutMs = 50)
    val started = System.currentTimeMillis()
    assertNull(r.resolve("Team A"))
    assertTrue(System.currentTimeMillis() - started < 300)
    Thread.sleep(700)
    assertEquals(ResolvedGroup(guid, dn), r.resolve("Team A"))
    assertEquals(1, calls.get())
  }

  @Test
  fun `invalidate forces a fresh lookup`() = runBlocking {
    val calls = AtomicInteger()
    whenever(ad.getGroup(any())).thenAnswer {
      calls.incrementAndGet()
      adGroup(guid)
    }
    val r = resolver()
    r.resolve("Team A")
    r.invalidate("Team A")
    r.resolve("Team A")
    assertEquals(2, calls.get())
  }
}
