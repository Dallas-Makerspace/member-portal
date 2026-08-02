package org.dallasmakerspace.members

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class MemberSettingsServiceTest {

  private lateinit var mockLoggerFactory: LoggerFactory
  private lateinit var mockLogger: Logger
  private lateinit var mockRepository: IMemberSettingsRepository
  private lateinit var service: MemberSettingsService

  @Before
  fun setUp() {
    mockLoggerFactory = mock()
    mockLogger = mock()
    mockRepository = mock()

    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)

    service = MemberSettingsService(mockRepository, mockLoggerFactory)
  }

  @Test
  fun `getSettings returns only stored keys`() = runBlocking {
    whenever(mockRepository.getProfileId("user1")).thenReturn(1)
    whenever(mockRepository.getLatest(1))
        .thenReturn(mapOf("communication_preference" to "EMAIL", "unknown_setting" to "x"))

    val result = service.getSettings("user1")

    assertEquals(mapOf("communication_preference" to "EMAIL"), result)
  }

  @Test
  fun `getSettings returns empty map when nothing stored`() = runBlocking {
    whenever(mockRepository.getProfileId("user1")).thenReturn(1)
    whenever(mockRepository.getLatest(1)).thenReturn(emptyMap())

    val result = service.getSettings("user1")

    assertEquals(emptyMap<String, String>(), result)
  }

  @Test
  fun `getSettings returns empty map when member not found`() = runBlocking {
    whenever(mockRepository.getProfileId("ghost")).thenReturn(null)

    val result = service.getSettings("ghost")

    assertEquals(emptyMap<String, String>(), result)
  }

  @Test
  fun `updateSettings appends valid value with actor`() = runBlocking {
    whenever(mockRepository.getProfileId("user1")).thenReturn(1)
    whenever(mockRepository.getProfileId("admin")).thenReturn(2)

    service.updateSettings("user1", mapOf("communication_preference" to "EMAIL"), "admin")

    verify(mockRepository).append(eq(1), eq("communication_preference"), eq("EMAIL"), eq(2))
  }

  @Test
  fun `updateSettings appends clear when value is blank`() = runBlocking {
    whenever(mockRepository.getProfileId("user1")).thenReturn(1)
    whenever(mockRepository.getProfileId("user1")).thenReturn(1)

    service.updateSettings("user1", mapOf("communication_preference" to ""), "user1")

    verify(mockRepository).append(eq(1), eq("communication_preference"), eq(null), eq(1))
  }

  @Test
  fun `updateSettings appends clear when value is null`() = runBlocking {
    whenever(mockRepository.getProfileId("user1")).thenReturn(1)

    service.updateSettings("user1", mapOf("communication_preference" to null), "user1")

    verify(mockRepository).append(eq(1), eq("communication_preference"), eq(null), eq(1))
  }

  @Test
  fun `updateSettings rejects unknown member`(): Unit = runBlocking {
    whenever(mockRepository.getProfileId("ghost")).thenReturn(null)

    assertFailsWith<IllegalArgumentException> {
      service.updateSettings("ghost", mapOf("communication_preference" to "EMAIL"), null)
    }
  }

  @Test
  fun `updateSettings rejects unknown key`(): Unit = runBlocking {
    whenever(mockRepository.getProfileId("user1")).thenReturn(1)

    assertFailsWith<IllegalArgumentException> {
      service.updateSettings("user1", mapOf("bogus_setting" to "value"), null)
    }
  }

  @Test
  fun `updateSettings rejects invalid value`(): Unit = runBlocking {
    whenever(mockRepository.getProfileId("user1")).thenReturn(1)

    assertFailsWith<IllegalArgumentException> {
      service.updateSettings("user1", mapOf("communication_preference" to "CARRIER_PIGEON"), null)
    }
  }
}
