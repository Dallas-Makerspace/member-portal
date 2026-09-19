package org.dallasmakerspace.adreplication

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.dallasmakerspace.core.LoggerFactory
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class AdReplicationHealthEsRepositoryTest {

  private fun newRepository(): AdReplicationHealthEsRepository {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(AdReplicationHealthEsRepository::class.java)).thenReturn(mock<Logger>())
    return AdReplicationHealthEsRepository(loggerFactory)
  }

  private val validDoc =
      mapOf(
          "@timestamp" to "2026-09-19T17:55:13.645149200Z",
          "server" to "DC1.dms.local",
          "partner" to "DC3",
          "partition" to "CN=Configuration,DC=dms,DC=local",
          "pairKey" to "DC1.dms.local->DC3",
          "lastReplicationSuccess" to "2026-09-19T17:41:18Z",
          "consecutiveReplicationFailures" to 0,
          "lastReplicationResult" to 0,
          "latencyMinutes" to 13L,
      )

  @Test
  fun `parseDoc extracts all fields from a well-formed ES source map`() {
    val sample = newRepository().parseDoc(validDoc)

    assertEquals(Instant.parse("2026-09-19T17:55:13.645149200Z"), sample?.timestamp)
    assertEquals("DC1.dms.local", sample?.server)
    assertEquals("DC3", sample?.partner)
    assertEquals("CN=Configuration,DC=dms,DC=local", sample?.partition)
    assertEquals("DC1.dms.local->DC3", sample?.pairKey)
    assertEquals(Instant.parse("2026-09-19T17:41:18Z"), sample?.lastReplicationSuccess)
    assertEquals(0, sample?.consecutiveReplicationFailures)
    assertEquals(0, sample?.lastReplicationResult)
    assertEquals(13L, sample?.latencyMinutes)
  }

  @Test
  fun `parseDoc returns null when @timestamp is missing`() {
    assertNull(newRepository().parseDoc(validDoc - "@timestamp"))
  }

  @Test
  fun `parseDoc returns null when server is missing`() {
    assertNull(newRepository().parseDoc(validDoc - "server"))
  }

  @Test
  fun `parseDoc returns null when partner is missing`() {
    assertNull(newRepository().parseDoc(validDoc - "partner"))
  }

  @Test
  fun `parseDoc returns null rather than throwing on an unparsable timestamp`() {
    assertNull(newRepository().parseDoc(validDoc + ("@timestamp" to "not a timestamp")))
  }

  @Test
  fun `parseDoc falls back to server-partner when pairKey is absent (older docs)`() {
    val sample = newRepository().parseDoc(validDoc - "pairKey")
    assertEquals("DC1.dms.local->DC3", sample?.pairKey)
  }

  @Test
  fun `parseDoc treats a never-synced link (null lastReplicationSuccess, latencyMinutes) as null`() {
    val sample =
        newRepository()
            .parseDoc(validDoc + ("lastReplicationSuccess" to null) + ("latencyMinutes" to null))

    assertNull(sample?.lastReplicationSuccess)
    assertNull(sample?.latencyMinutes)
    // The row itself still parses successfully even though these two fields are absent.
    assertEquals("DC1.dms.local", sample?.server)
  }

  @Test
  fun `parseDoc defaults missing consecutiveReplicationFailures and lastReplicationResult to 0`() {
    val sample = newRepository().parseDoc(validDoc - "consecutiveReplicationFailures" - "lastReplicationResult")
    assertEquals(0, sample?.consecutiveReplicationFailures)
    assertEquals(0, sample?.lastReplicationResult)
  }
}
